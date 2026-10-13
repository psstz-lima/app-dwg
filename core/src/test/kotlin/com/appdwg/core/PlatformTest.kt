package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {
    /** Flat terrain at [z] over a 1 km square centred on the origin. */
    private fun flat(z: Double) = Surface("T", doubleArrayOf(-500.0, 500.0, 500.0, -500.0), doubleArrayOf(-500.0, -500.0, 500.0, 500.0),
        DoubleArray(4) { z }, intArrayOf(0, 1, 2, 0, 2, 3))

    private val square = listOf(Point2(-10.0, -10.0), Point2(10.0, -10.0), Point2(10.0, 10.0), Point2(-10.0, 10.0))

    @Test
    fun fillSlopeReachesFlatGroundAtRatioTimesHeight() {
        // Platform 4 m above the ground with 2:1 fill slopes: daylight 8 m outside the edge.
        val d = PlatformDesign(square, 104.0, cut = SlopeRule(1.0), fill = SlopeRule(2.0), step = 5.0)
        val r = Platform.design(d, flat(100.0))
        assertEquals(0, r.unresolved)
        val mid = r.daylight.minByOrNull { kotlin.math.abs(it.x) + kotlin.math.abs(it.y + 18) }!!
        assertEquals(-18.0, mid.y, 0.05)
        assertEquals(100.0, mid.z, 0.05)
        // The surface is the platform on top and the slope down to the ground.
        assertEquals(104.0, r.surface.elevationAt(Point2(0.0, 0.0))!!, 1e-6)
        assertEquals(102.0, r.surface.elevationAt(Point2(0.0, -14.0))!!, 0.05)
        val v = SurfaceCompare.difference(flat(100.0), r.surface, maxCells = 200_000)!!
        // Top 20x20 by 4 m, a triangular prism along each edge, a quarter cone at each corner.
        val expected = 20.0 * 20 * 4 + 4 * (20.0 * 0.5 * 8 * 4) + Math.PI * 8 * 8 * 4 / 3
        assertEquals(expected, v.fillVolume, expected * 0.01)
        assertEquals(0.0, v.cutVolume, 1.0)
    }

    @Test
    fun cutWithBenchesBreaksTheProfile() {
        // Platform 12 m below the ground; 1:1 cut, bench every 5 m, 3 m wide, flat.
        val d = PlatformDesign(square, 88.0, cut = SlopeRule(1.0, benchHeight = 5.0, benchWidth = 3.0), fill = SlopeRule(2.0), step = 5.0)
        val (pts, unresolved) = Platform.profile(Point3(0.0, -10.0, 88.0), Point2(0.0, -1.0), d.cut, d.fill, d.maxReach, flat(100.0))
        assertEquals(false, unresolved)
        // Start, bench 1 (two edges), bench 2 (two edges), daylight.
        assertEquals(6, pts.size)
        assertEquals(93.0, pts[1].z, 1e-9)
        assertEquals(-15.0, pts[1].y, 1e-9)
        assertEquals(-18.0, pts[2].y, 1e-9)
        // 12 m of cut: 10 m of slope (10 m run), 2 benches (6 m), then 2 m more: daylight 18 m out.
        assertEquals(-28.0, pts.last().y, 0.05)
        assertEquals(100.0, pts.last().z, 0.05)
    }

    @Test
    fun earClipHandlesAConcaveOutline() {
        val l = listOf(Point2(0.0, 0.0), Point2(2.0, 0.0), Point2(2.0, 1.0), Point2(1.0, 1.0), Point2(1.0, 2.0), Point2(0.0, 2.0))
        val t = Platform.earClip(l)
        assertEquals(12, t.size)
        var area = 0.0
        for (k in 0 until t.size / 3) area += kotlin.math.abs(Platform.signedArea(listOf(l[t[3 * k]], l[t[3 * k + 1]], l[t[3 * k + 2]])))
        assertEquals(3.0, area, 1e-9)
        assertTrue(Platform.signedArea(l) > 0)
    }

    @Test
    fun eachBenchItsOwnHeight() {
        // Flat ground at 80, platform edge at 100 going east: fill. Benches after 5 m, then every 10 m.
        val ground = Surface("T", doubleArrayOf(-10.0, 500.0, 500.0, -10.0), doubleArrayOf(-10.0, -10.0, 10.0, 10.0), DoubleArray(4) { 80.0 }, intArrayOf(0, 1, 2, 0, 2, 3))
        val rule = SlopeRule(1.0, 8.0, 2.0, 0.0, heights = listOf(5.0, 10.0))
        val (pts, open) = Platform.profile(Point3(0.0, 0.0, 100.0), Point2(1.0, 0.0), rule, rule, 400.0, ground)
        assertEquals(false, open)
        // start, 1st bench (95) in and out, 2nd bench (85) in and out, daylight (80).
        assertEquals(listOf(100.0, 95.0, 95.0, 85.0, 85.0, 80.0), pts.map { Math.round(it.z * 1000) / 1000.0 })
        assertEquals(5.0 + 2 + 10 + 2 + 5, pts.last().x, 1e-6)
    }
}
