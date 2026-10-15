package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContoursTest {
    // Plane rising 1 m per 10 m eastwards: contours every metre are north-south lines.
    private val plane = Surface("T", doubleArrayOf(0.0, 100.0, 100.0, 0.0), doubleArrayOf(0.0, 0.0, 100.0, 100.0),
        doubleArrayOf(0.0, 10.0, 10.0, 0.0), intArrayOf(0, 1, 2, 0, 2, 3))

    @Test
    fun levelsCrossThePlaneAndJoinIntoLines() {
        val levels = Contours.segments(plane, 1.0)!!
        // Levels 0 and 10 touch only the edge vertices; 1 to 9 cross both triangles.
        assertTrue(levels.map { it.elevation }.containsAll((1..9).map { it.toDouble() }))
        val lines = Contours.lines(levels.filter { it.elevation in 1.0..9.0 })
        assertEquals(9, lines.size)
        val five = lines.single { it.elevation == 5.0 }
        assertTrue(five.xs.all { kotlin.math.abs(it - 50.0) < 1e-6 })
        assertEquals(0.0, five.ys.min(), 1e-6)
        assertEquals(100.0, five.ys.max(), 1e-6)
    }

    @Test
    fun regionLimitsTheTriangles() {
        val levels = Contours.segments(plane, 1.0, Bounds(0.0, 0.0, 10.0, 10.0))!!
        assertTrue(levels.isNotEmpty())
        assertEquals(1.0, Contours.autoInterval(40.0))
        assertEquals(0.25, Contours.autoInterval(9.0))
    }
}
