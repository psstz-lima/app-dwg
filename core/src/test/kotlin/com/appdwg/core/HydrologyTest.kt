package com.appdwg.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HydrologyTest {
    /** cols × rows grid of [cell] m cells, row 0 to the north, z(x, y) at the cell centres. */
    private fun grid(cols: Int, rows: Int, cell: Double = 1.0, z: (Double, Double) -> Double): ElevationGrid {
        val b = Bounds(0.0, 0.0, cols * cell, rows * cell)
        val v = FloatArray(cols * rows) { i -> val c = i % cols; val r = i / cols; z((c + 0.5) * cell, (rows - r - 0.5) * cell).toFloat() }
        return ElevationGrid(b, cols, rows, v)
    }

    private fun shoelace(p: List<Point2>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return s / 2
    }

    @Test
    fun waterRunsThroughAPitToTheEdge() {
        // Plane falling south with a 2 m deep pit in the middle.
        val g = grid(30, 30) { x, y -> 100 + 0.1 * y - 2 * maxOf(0.0, 1 - kotlin.math.hypot(x - 15, y - 15) / 4) }
        val f = Hydrology.flow(g)!!
        val inPit = 15 * 30 + 15
        // Following the flow from the pit reaches the grid edge.
        var i = inPit; var steps = 0
        while (f.next[i] >= 0 && steps < 1000) { i = f.next[i]; steps++ }
        assertEquals(29, i / 30)
        // Every cell drains somewhere: the outflows at the edge add up to the whole grid.
        assertEquals(900, f.count.indices.filter { f.next[it] < 0 }.sumOf { f.count[it] })
    }

    @Test
    fun basinOfOneBranchOnly() {
        // Two valleys, along x = 21 and x = 61 (2 m cells), falling south, separated by a ridge at x = 41.
        val g = grid(40, 50, 2.0) { x, y -> 100 + minOf(abs(x - 21), abs(x - 61)) * 0.3 + y * 0.05 }
        val f = Hydrology.flow(g)!!
        val outlet = Hydrology.snap(f, Point2(21.0, 1.0), 6.0)!!
        val b = Hydrology.basin(f, outlet)!!
        // The western valley: x 0–42 (the ridge column may go either way), whole height.
        assertTrue(b.area in 0.45 * 80 * 100..0.55 * 80 * 100, "area ${b.area}")
        assertTrue(b.cells.all { (it % 40) * 2.0 + 1 < 42 })
        // The outline encloses exactly the basin cells, counterclockwise.
        assertEquals(b.area, shoelace(b.outline), 1e-6)
        // The main stream runs from the northern end down to the outlet.
        assertEquals(b.outlet, Point2(b.mainStream.last().x, b.mainStream.last().y))
        assertTrue(b.mainStream.first().y > 80, "head ${b.mainStream.first()}")
        assertTrue(b.streamLength > 90)
        assertTrue(b.streamDrop > 4)
    }

    @Test
    fun kirpichAndRational() {
        val b = Basin(Point2(0.0, 0.0), 0, IntArray(0), 1e6, emptyList(), 4000.0, emptyList(), 1000.0, 10.0, 0.0, 10.0, 5.0)
        assertEquals(57 * 0.1.pow(0.385), b.kirpichMinutes, 1e-9)
        val idf = Idf(k = 1000.0, a = 0.0, b = 0.0, c = 0.0) // 1000 mm/h whatever the duration
        val q = Hydrology.rational(b, 0.5, idf, 10.0)
        assertEquals(0.5 * 1000 * 1 / 3.6, q.peak, 1e-9)
        assertEquals(Hydrology.MIN_TC_MINUTES, q.minutes.coerceAtMost(Hydrology.MIN_TC_MINUTES))
        assertEquals(false, q.tooLarge)
    }

    @Test
    fun culvertWhereTheStreamCrossesTheRoad() {
        val g = grid(21, 30) { x, y -> 100 + abs(x - 10.5) * 0.5 + y * 0.05 }
        val f = Hydrology.flow(g)!!
        val road = listOf(Point2(0.0, 15.2), Point2(21.0, 15.2))
        val c = Hydrology.crossings(f, road, 20.0)
        assertEquals(1, c.size)
        assertEquals(10.5, c[0].point.x, 1e-6)
        // The area drains from the north half: about 21 × 15 cells.
        assertTrue(c[0].area in 250.0..350.0, "area ${c[0].area}")

        // The lower half as a work area: water leaves it at the bottom of the valley.
        val area = listOf(Point2(0.0, 0.0), Point2(21.0, 0.0), Point2(21.0, 10.0), Point2(0.0, 10.0))
        val e = Hydrology.exits(f, area)
        assertNotNull(e.firstOrNull())
        assertEquals(10.5, e[0].point.x, 1e-6)
        assertEquals(0.5, e[0].point.y, 1e-6)
        assertEquals(10.5, Hydrology.terrainOutlets(f)[0].point.x, 1e-6)
    }
}
