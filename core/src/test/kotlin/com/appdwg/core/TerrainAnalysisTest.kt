package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainAnalysisTest {

    /** cols × rows grid of 1 m cells, row 0 to the north, z(x, y) at the cell centres. */
    private fun grid(cols: Int, rows: Int, z: (Double, Double) -> Double): ElevationGrid {
        val b = Bounds(0.0, 0.0, cols.toDouble(), rows.toDouble())
        val v = FloatArray(cols * rows) { i -> val c = i % cols; val r = i / cols; z(c + 0.5, rows - r - 0.5).toFloat() }
        return ElevationGrid(b, cols, rows, v)
    }

    @Test
    fun planeSlopeAndClass() {
        // 10 % rise to the east: every cell 10 %, all in "Ondulado" (8–20 %).
        val g = grid(20, 10) { x, _ -> 100 + 0.1 * x }
        val s = TerrainAnalysis.slopes(g)
        assertTrue(s.all { kotlin.math.abs(it - 10f) < 1e-3 })
        val cls = TerrainAnalysis.classes(g, s)
        assertEquals(200.0, cls.single { it.name == "Ondulado" }.area, 1e-9)
    }

    @Test
    fun valleyCollectsTheFlow() {
        // V-shaped valley along x = 10, falling to the south: water ends in the bottom row of the valley.
        val g = grid(21, 30) { x, y -> 100 + kotlin.math.abs(x - 10.5) * 0.5 + y * 0.05 }
        val f = TerrainAnalysis.flow(g)!!
        val outlet = f.count.indices.maxByOrNull { f.count[it] }!!
        assertEquals(10, outlet % 21)
        assertEquals(29, outlet / 21)
        assertEquals(21 * 30, f.count[outlet])
        val channels = f.channels(minArea = 50.0)
        assertTrue(channels.isNotEmpty() && channels.all { kotlin.math.abs(it.first.x - 10.5) < 1e-9 })
    }

    @Test
    fun aspectPointsDownhill() {
        val g = grid(5, 5) { x, _ -> 100 - x } // falls to the east
        assertEquals(0.0, TerrainAnalysis.aspect(g, 2, 2), 1e-9)
    }
}
