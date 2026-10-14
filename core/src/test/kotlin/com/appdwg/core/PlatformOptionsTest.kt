package com.appdwg.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformOptionsTest {
    /** Ground rising 5 % to the north over a 1 km square centred on the origin. */
    private val ground = Surface("T", doubleArrayOf(-500.0, 500.0, 500.0, -500.0), doubleArrayOf(-500.0, -500.0, 500.0, 500.0),
        doubleArrayOf(75.0, 75.0, 125.0, 125.0), intArrayOf(0, 1, 2, 0, 2, 3))
    private val square = listOf(Point2(-20.0, -20.0), Point2(20.0, -20.0), Point2(20.0, 20.0), Point2(-20.0, 20.0))

    @Test
    fun groundFallFromThePlane() {
        val (slope, az) = PlatformOptions.groundFall(square, ground)!!
        assertEquals(5.0, slope, 1e-6)
        assertEquals(0.0, az, 1e-6)
    }

    @Test
    fun balancedAndSlopedShapes() {
        val opts = PlatformOptions.suggest(square, ground, SlopeRule(1.0), SlopeRule(1.5), maxGrade = 2.0)
        val kinds = opts.map { it.kind }
        assertEquals(PlatformOption.Kind.entries.toSet(), kinds.toSet())
        val flat = opts.first { it.kind == PlatformOption.Kind.FLAT_BALANCED }
        assertEquals(0.0, flat.gradePercent)
        // Balanced: cut and fill within 2 % of each other, the plane near the middle height.
        assertTrue(abs(flat.cut - flat.fill) < 0.02 * (flat.cut + flat.fill), "cut ${flat.cut} fill ${flat.fill}")
        assertTrue(flat.baseElevation in 98.0..102.0, "base ${flat.baseElevation}")
        // Following the ground's fall moves less earth than staying flat.
        val sloped = opts.first { it.kind == PlatformOption.Kind.SLOPED_BALANCED }
        assertEquals(2.0, sloped.gradePercent)
        assertEquals(0.0, sloped.gradeAzimuth, 1e-6)
        assertTrue(sloped.cut + sloped.fill < flat.cut + flat.fill)
        assertTrue(sloped.maxSlopeHeight < flat.maxSlopeHeight)
        // The least-earthwork shapes are never worse than the balanced ones with the same grade.
        val least = opts.first { it.kind == PlatformOption.Kind.FLAT_LEAST }
        assertTrue(least.cut + least.fill <= (flat.cut + flat.fill) * 1.01)
    }
}
