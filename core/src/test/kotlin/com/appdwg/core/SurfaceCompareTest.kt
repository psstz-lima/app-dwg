package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfaceCompareTest {

    private fun square(name: String, x0: Double, size: Double, z: (Double, Double) -> Double): Surface {
        val xs = doubleArrayOf(x0, x0 + size, x0 + size, x0)
        val ys = doubleArrayOf(0.0, 0.0, size, size)
        return Surface(name, xs, ys, DoubleArray(4) { z(xs[it], ys[it]) }, intArrayOf(0, 1, 2, 0, 2, 3))
    }

    @Test
    fun flatRaiseIsAllFill() {
        val a = square("A", 0.0, 100.0) { _, _ -> 100.0 }
        val b = square("B", 0.0, 100.0) { _, _ -> 102.0 }
        val d = SurfaceCompare.difference(a, b, maxCells = 10_000)!!
        assertEquals(20_000.0, d.fillVolume, 1.0)
        assertEquals(0.0, d.cutVolume, 1e-9)
        assertEquals(10_000.0, d.fillArea, 1.0)
        assertEquals(2.0, d.maxDifference, 1e-4)
    }

    @Test
    fun tiltedPlaneBalancesCutAndFill() {
        // B rises from −1 m to +1 m against A across x: half cut, half fill, 2500 m³ each.
        val a = square("A", 0.0, 100.0) { _, _ -> 50.0 }
        val b = square("B", 0.0, 100.0) { x, _ -> 50.0 + (x - 50.0) / 50.0 }
        val d = SurfaceCompare.difference(a, b, maxCells = 40_000)!!
        assertEquals(2500.0, d.cutVolume, 10.0)
        assertEquals(2500.0, d.fillVolume, 10.0)
        assertEquals(0.0, d.netVolume, 1.0)
        assertEquals(-1.0, d.minDifference, 0.02)
    }

    @Test
    fun onlyTheOverlapCounts() {
        val a = square("A", 0.0, 100.0) { _, _ -> 0.0 }
        val b = square("B", 50.0, 100.0) { _, _ -> 1.0 }
        val d = SurfaceCompare.difference(a, b, maxCells = 10_000)!!
        assertEquals(5000.0, d.fillVolume, 60.0)
        assertNull(SurfaceCompare.difference(a, square("C", 500.0, 10.0) { _, _ -> 0.0 }))
    }

    @Test
    fun invertedSwapsCutAndFill() {
        val a = square("A", 0.0, 100.0) { _, _ -> 100.0 }
        val b = square("B", 0.0, 100.0) { _, _ -> 102.0 }
        val d = SurfaceCompare.difference(a, b, maxCells = 10_000)!!.inverted()
        assertEquals("B", d.baseName)
        assertEquals(20_000.0, d.cutVolume, 1.0)
        assertEquals(-2.0, d.minDifference, 1e-4)
    }

    @Test
    fun stagesTrackProgressToTheDesign() {
        // Ground at 100, survey at 101, design at 104: a quarter of the 4 m fill is done.
        val ground = square("T", 0.0, 100.0) { _, _ -> 100.0 }
        val survey = square("L1", 0.0, 100.0) { _, _ -> 101.0 }
        val design = square("P", 0.0, 100.0) { _, _ -> 104.0 }
        val p = SurfaceCompare.stages(listOf(ground, survey, design), maxCells = 10_000)!!
        assertEquals(1, p.periods.size)
        assertEquals(10_000.0, p.periods[0].fill, 1.0)
        assertEquals(30_000.0, p.remainingFill, 1.0)
        assertEquals(40_000.0, p.totalFill, 1.0)
        assertEquals(0.25, p.done, 1e-6)
    }
}
