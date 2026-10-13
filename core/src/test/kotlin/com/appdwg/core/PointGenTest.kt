package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointGenTest {
    private val line = PointGen.Path(doubleArrayOf(0.0, 100.0, 100.0), doubleArrayOf(0.0, 0.0, 50.0), doubleArrayOf(10.0, 20.0, 20.0), false)

    @Test
    fun pointsAlongALineWithOffsetsAndElevation() {
        val pts = PointGen.alongLine(line, 20.0, listOf(-3.5, 0.0, 3.5), includeVertices = true)
        // Distances 0..140 every 20 plus the end at 150 and the vertex at 100 (a multiple already).
        val distances = pts.map { it.distance }.distinct()
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 150.0), distances)
        // Going east, right is south: offset +3,5 lies at y = −3,5.
        val p = pts.single { it.distance == 40.0 && it.offset == 3.5 }
        assertEquals(40.0, p.position.x, 1e-9)
        assertEquals(-3.5, p.position.y, 1e-9)
        assertEquals(14.0, p.lineZ!!, 1e-9)
    }

    @Test
    fun rangeLimitsTheStations() {
        val axis = PointGen.Path(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 0.0), null, false, start = 1000.0)
        val pts = PointGen.alongLine(axis, 20.0, listOf(0.0), includeVertices = false, from = 1030.0, to = 1070.0)
        assertEquals(listOf(1030.0, 1040.0, 1060.0, 1070.0), pts.map { it.distance })
        assertNull(pts.first().lineZ)
    }

    @Test
    fun gridKeepsPointsInsideAndRotates() {
        val square = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val g = PointGen.grid(square, 2.5, 2.5, origin = Point2(1.25, 1.25))
        assertEquals(16, g.size)
        val r = PointGen.grid(square, 5.0, 5.0, rotationDeg = 45.0, origin = Point2(5.0, 5.0))
        assertTrue(r.contains(Point2(5.0, 5.0)))
        assertTrue(r.all { PointGen.inside(square, it.x, it.y) })
    }

    @Test
    fun picksTheNearestLine() {
        val p = LandXmlProject(null, null, emptyList(), emptyList(), emptyList(), emptyList(),
            polylines = listOf(Polyline("A", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), false), Polyline("B", doubleArrayOf(0.0, 10.0), doubleArrayOf(5.0, 5.0), false)))
        assertEquals(LineRef.Drawing(1), PointGen.nearestLine(p, Point2(5.0, 4.0), 2.0))
        assertNull(PointGen.nearestLine(p, Point2(5.0, 2.5), 1.0))
        assertEquals(listOf(-3.5, 0.0, 3.5), PointGen.parseOffsets("-3,5; 0; 3,5"))
    }
}
