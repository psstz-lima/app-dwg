package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VolumesTest {

    /** Regular TIN over [0, size]² with height z(x, y). */
    private fun grid(name: String, size: Double, n: Int, z: (Double, Double) -> Double): Surface {
        val xs = DoubleArray((n + 1) * (n + 1)); val ys = DoubleArray(xs.size); val zs = DoubleArray(xs.size)
        for (i in 0..n) for (j in 0..n) { val k = i * (n + 1) + j; xs[k] = size * j / n; ys[k] = size * i / n; zs[k] = z(xs[k], ys[k]) }
        val t = ArrayList<Int>()
        for (i in 0 until n) for (j in 0 until n) {
            val a = i * (n + 1) + j; val b = a + 1; val c = a + n + 1; val d = c + 1
            t += listOf(a, b, d, a, d, c)
        }
        return Surface(name, xs, ys, zs, t.toIntArray())
    }

    @Test
    fun boxAboveAnElevation() {
        // Flat at 102 over 100 × 100: 2 m above 100 → 20 000 m³; 1 m below 103 → 10 000 m³ of fill.
        val s = grid("S", 100.0, 10) { _, _ -> 102.0 }
        val up = assertNotNull(Volumes.of(s, null, VolumeBase.Elevation(100.0), maxCells = 40_000))
        assertEquals(20_000.0, up.above, 1.0)
        assertEquals(0.0, up.below, 1e-9)
        assertEquals(10_000.0, up.area, 1.0)
        val down = assertNotNull(Volumes.of(s, null, VolumeBase.Elevation(103.0), maxCells = 40_000))
        assertEquals(10_000.0, down.below, 1.0)
    }

    @Test
    fun polygonLimitsTheArea() {
        val s = grid("S", 100.0, 10) { _, _ -> 101.0 }
        val sq = listOf(Point2(20.0, 20.0), Point2(60.0, 20.0), Point2(60.0, 70.0), Point2(20.0, 70.0))
        val v = assertNotNull(Volumes.of(s, sq, VolumeBase.Elevation(100.0), maxCells = 250_000))
        assertEquals(2000.0, v.area, 20.0)
        assertEquals(2000.0, v.above, 20.0)
    }

    @Test
    fun pileOnSlopeAgainstTheEdgePlane() {
        // Ground tilted 5 % in x, plus a pyramid 10 m high at the centre covering 40 × 40: volume = 40² · 10 / 3.
        val pile = grid("P", 100.0, 100) { x, y ->
            val ground = 100 + 0.05 * x
            val h = 10 * (1 - maxOf(kotlin.math.abs(x - 50), kotlin.math.abs(y - 50)) / 20).coerceAtLeast(0.0)
            ground + h
        }
        val sq = listOf(Point2(25.0, 25.0), Point2(75.0, 25.0), Point2(75.0, 75.0), Point2(25.0, 75.0))
        val v = assertNotNull(Volumes.of(pile, sq, VolumeBase.PlaneEdge, maxCells = 250_000))
        assertEquals(40.0 * 40 * 10 / 3, v.above, 60.0)
        assertEquals(0.0, v.below, 5.0)
    }

    @Test
    fun rimOfASquareTin() {
        val s = grid("S", 10.0, 2) { _, _ -> 0.0 }
        assertEquals(8, Volumes.rim(s).size)
    }
}
