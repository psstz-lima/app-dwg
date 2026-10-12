package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossSectionTest {

    /** Plane z = 100 + 0.1 * x over a 200 m square, as two triangles. */
    private val plane = Surface(
        "TERRENO",
        doubleArrayOf(0.0, 200.0, 200.0, 0.0),
        doubleArrayOf(0.0, 0.0, 200.0, 200.0),
        doubleArrayOf(100.0, 120.0, 120.0, 100.0),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )

    /** North-going axis along x = 100 with a flat 105 m grade. */
    private val axis = Alignment(
        "EIXO", 0.0,
        listOf(AlignmentElement.Line(Point2(100.0, 0.0), Point2(100.0, 200.0))),
        Profile("GREIDE", listOf(Profile.Pvi(0.0, 105.0), Profile.Pvi(200.0, 105.0))),
    )

    @Test
    fun sectionRunsSquareToTheAxisWithRightPositive() {
        val s = CrossSection.at(axis, 50.0, listOf(plane), 20.0, samples = 41)!!
        assertEquals(Point2(100.0, 50.0), s.center)
        // Going north, the right side is east, where the plane rises.
        assertEquals(1.0, s.right.x, 1e-9)
        val z = s.lines.single().elevations
        assertEquals(108.0, z.first(), 1e-6) // 20 m left: x = 80
        assertEquals(112.0, z.last(), 1e-6) // 20 m right: x = 120
        assertEquals(110.0, s.terrainOnAxis!!, 1e-6)
        assertEquals(105.0, s.design!!, 1e-9)
        assertEquals(-5.0, s.cutFill!!, 1e-6)
        assertEquals(111.0, s.interpolate(z, 10.0), 1e-6)
    }

    @Test
    fun offTheSurfaceIsNaN() {
        val s = CrossSection.at(axis, 50.0, listOf(plane), 150.0, samples = 31)!!
        val z = s.lines.single().elevations
        assertTrue(z.first().isNaN())
        assertTrue(z.last().isNaN())
        assertNull(CrossSection.at(axis, 50.0, emptyList(), 10.0)!!.cutFill)
    }

    @Test
    fun parsesStations() {
        assertEquals(245.4, StationFormat.parse("12 + 5,40")!!, 1e-9)
        assertEquals(245.4, StationFormat.parse("12+5.40")!!, 1e-9)
        assertEquals(240.0, StationFormat.parse("12+")!!, 1e-9)
        assertEquals(245.4, StationFormat.parse("245,4")!!, 1e-9)
        assertNull(StationFormat.parse("abc"))
    }
}
