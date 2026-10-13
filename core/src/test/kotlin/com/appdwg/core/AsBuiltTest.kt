package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AsBuiltTest {

    private val design = Surface("Projeto", doubleArrayOf(0.0, 100.0, 100.0, 0.0), doubleArrayOf(0.0, 0.0, 100.0, 100.0),
        DoubleArray(4) { 500.0 }, intArrayOf(0, 1, 2, 0, 2, 3))

    @Test
    fun classifiesAgainstTolerance() {
        val pts = listOf(
            CogoPoint("1", "", 10.0, 10.0, 500.02),
            CogoPoint("2", "", 20.0, 20.0, 500.08),
            CogoPoint("3", "", 30.0, 30.0, 499.90),
            CogoPoint("4", "", 200.0, 30.0, 499.90),
            CogoPoint("5", "", 40.0, 40.0, Double.NaN),
        )
        val r = AsBuiltReport.check(pts, design, null, tolerance = 0.05)
        assertEquals(4, r.rows.size)
        assertEquals(listOf(AsBuiltStatus.OK, AsBuiltStatus.ABOVE, AsBuiltStatus.BELOW, AsBuiltStatus.OUTSIDE), r.rows.map { r.status(it) })
        assertEquals(3, r.checked)
        assertEquals(1, r.outside)
        assertEquals(1.0 / 3, r.passRate, 1e-9)
        assertEquals(0.0, r.mean, 1e-9)
        assertEquals(0.08, r.max!!, 1e-9)
        assertEquals(-0.10, r.min!!, 1e-9)
        assertNull(r.rows[3].delta)
    }

    @Test
    fun stationAndOffsetFromTheAlignment() {
        val axis = Alignment("EIXO", 0.0, listOf(AlignmentElement.Line(Point2(0.0, 50.0), Point2(100.0, 50.0))), null)
        val r = AsBuiltReport.check(listOf(CogoPoint("1", "", 40.0, 47.0, 500.0)), design, axis, 0.03)
        val so = r.rows.single().stationOffset!!
        assertEquals(40.0, so.station, 1e-6)
        assertEquals(3.0, so.offset, 1e-6)
    }
}
