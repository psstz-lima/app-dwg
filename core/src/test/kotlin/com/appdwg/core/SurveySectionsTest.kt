package com.appdwg.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurveySectionsTest {
    private val nan = Double.NaN

    @Test
    fun laterSurveyIsCompletedFromThePrimitivaAndInnerGapsInterpolated() {
        val prim = doubleArrayOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0)
        val med = doubleArrayOf(nan, 8.0, nan, 6.0, nan, nan)
        val c = SurveySections.complete(listOf(prim, med))
        assertEquals(listOf(10.0, 8.0, 7.0, 6.0, 10.0, 10.0), c.elevations[1].toList())
        assertEquals(listOf(true, false, true, false, true, true), c.filled[1].toList())
        assertFalse(c.filled[0].any { it })
        assertTrue(c.anyFilled)
    }

    @Test
    fun areasSplitWhereProfilesCross() {
        val off = doubleArrayOf(0.0, 10.0)
        // b goes from 1 m below a to 1 m above: two triangles of 2,5 m² each.
        val (cut, fill) = SurveySections.areas(off, doubleArrayOf(10.0, 10.0), doubleArrayOf(9.0, 11.0))
        assertEquals(2.5, cut, 1e-9); assertEquals(2.5, fill, 1e-9)
        val (c2, f2) = SurveySections.areas(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(5.0, 5.0, 5.0), doubleArrayOf(4.0, 4.0, nan))
        assertEquals(1.0, c2, 1e-9); assertEquals(0.0, f2, 1e-9)
    }

    @Test
    fun volumesByAverageEndAreas() {
        val rows = SurveySections.volumes(listOf(0.0, 20.0, 40.0), listOf(10.0, 20.0, 0.0), listOf(0.0, 4.0, 4.0))
        assertEquals(0.0, rows[0].cutVolume)
        assertEquals(300.0, rows[1].cutVolume, 1e-9); assertEquals(40.0, rows[1].fillVolume, 1e-9)
        assertEquals(200.0, rows[2].cutVolume, 1e-9); assertEquals(80.0, rows[2].fillVolume, 1e-9)
    }

    @Test
    fun autoAxisRunsAlongTheLongSide() {
        // A 200 × 40 strip turned 30°.
        val a = Math.toRadians(30.0)
        val pts = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 40.0, 0.0 to 40.0, 100.0 to 20.0).map { (x, y) -> x * kotlin.math.cos(a) - y * kotlin.math.sin(a) to x * kotlin.math.sin(a) + y * kotlin.math.cos(a) }
        val s = Surface("S", pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), DoubleArray(5), intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4))
        val axis = assertNotNull(SurveySections.autoAxis(s, "Eixo"))
        assertEquals(200.0, axis.length, 1.0)
        val e = axis.elements.single()
        val heading = Math.toDegrees(kotlin.math.atan2(e.end.y - e.start.y, e.end.x - e.start.x))
        assertTrue(abs(heading - 30.0) < 1.0, "heading $heading")
    }
}
