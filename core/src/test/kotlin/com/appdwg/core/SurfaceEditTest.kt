package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SurfaceEditTest {

    /** n × n squares of 10 m, z = x / 10. */
    private fun grid(n: Int = 10): Surface {
        val xs = DoubleArray((n + 1) * (n + 1)); val ys = DoubleArray(xs.size); val zs = DoubleArray(xs.size)
        for (i in 0..n) for (j in 0..n) { val k = i * (n + 1) + j; xs[k] = 10.0 * j; ys[k] = 10.0 * i; zs[k] = xs[k] / 10 }
        val t = ArrayList<Int>()
        for (i in 0 until n) for (j in 0 until n) { val a = i * (n + 1) + j; t += listOf(a, a + 1, a + n + 2, a, a + n + 2, a + n + 1) }
        return Surface("S", xs, ys, zs, t.toIntArray())
    }

    private val square = listOf(Point2(20.0, 20.0), Point2(60.0, 20.0), Point2(60.0, 60.0), Point2(20.0, 60.0))

    @Test
    fun clipKeepsTheInside() {
        val s = SurfaceEdit.clip(grid(), square, keepInside = true)
        assertEquals(32, s.triangleCount)
        assertEquals(25, s.vertexCount)
        val hole = SurfaceEdit.clip(grid(), square, keepInside = false)
        assertEquals(200 - 32, hole.triangleCount)
        assertNull(hole.elevationAt(Point2(40.0, 40.0)))
    }

    @Test
    fun raiseMovesOnlyTheInside() {
        val s = SurfaceEdit.raise(grid(), square, dz = 2.0)
        assertEquals(4.0 + 2.0, s.elevationAt(Point2(40.0, 40.0))!!, 1e-9)
        assertEquals(9.0, s.elevationAt(Point2(90.0, 90.0))!!, 1e-9)
    }

    @Test
    fun deletingPointsFillsTheHole() {
        val s = SurfaceEdit.deletePoints(grid(), listOf(Point2(35.0, 35.0), Point2(45.0, 35.0), Point2(45.0, 45.0), Point2(35.0, 45.0)))
        assertEquals(120, s.vertexCount)
        // Still a whole surface, and z = x / 10 is a plane, so it stays exact.
        assertEquals(4.0, s.elevationAt(Point2(40.0, 40.0))!!, 1e-9)
        assertEquals(200 - 2, s.triangleCount)
    }

    @Test
    fun addingAPointBumpsTheSurface() {
        val s = assertNotNull(SurfaceEdit.addPoint(grid(), Point3(43.0, 47.0, 10.0)))
        assertEquals(122, s.vertexCount)
        assertEquals(10.0, s.elevationAt(Point2(43.0, 47.0))!!, 1e-6)
        assertEquals(9.0, s.elevationAt(Point2(90.0, 90.0))!!, 1e-9)
        assertNull(SurfaceEdit.addPoint(grid(), Point3(500.0, 500.0, 0.0)))
    }

    @Test
    fun flipSwapsTheDiagonal() {
        val g = grid(1) // one square, diagonal 0–3
        val f = assertNotNull(SurfaceEdit.flipEdge(g, Point2(5.0, 5.0)))
        val tri = f.triangles.toList()
        // The new diagonal joins 1 and 2.
        assertEquals(true, (0 until 2).all { k -> 1 in tri.subList(3 * k, 3 * k + 3) && 2 in tri.subList(3 * k, 3 * k + 3) })
    }
}
