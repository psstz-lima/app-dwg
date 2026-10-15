package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongProfileTest {
    @Test
    fun samplesTerrainAndDesignAlongTheAxis() {
        // Terrain rises 0.1 m per metre northwards; design flat at 105 from station 0 to 200.
        val plane = Surface("T", doubleArrayOf(0.0, 200.0, 200.0, 0.0), doubleArrayOf(0.0, 0.0, 200.0, 200.0),
            doubleArrayOf(100.0, 100.0, 120.0, 120.0), intArrayOf(0, 1, 2, 0, 2, 3))
        val axis = Alignment("E", 0.0, listOf(AlignmentElement.Line(Point2(100.0, 0.0), Point2(100.0, 200.0))),
            Profile("G", listOf(Profile.Pvi(0.0, 105.0), Profile.Pvi(200.0, 105.0))))
        val p = LongProfile.of(axis, listOf(plane), samples = 201)
        assertEquals(201, p.stations.size)
        assertEquals(110.0, p.interpolate(p.lines.single().elevations, 100.0), 1e-6)
        assertEquals(105.0, p.interpolate(p.design!!, 150.0), 1e-9)
        assertTrue(p.interpolate(p.design!!, 250.0).isNaN())
        assertEquals(2, p.pvis.size)
    }
}
