package com.appdwg.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorridorTest {
    private val pis = listOf(Pi(Point2(0.0, 0.0)), Pi(Point2(500.0, 0.0), radius = 200.0), Pi(Point2(500.0, 500.0)))

    @Test
    fun circularCurveLength() {
        val a = AlignmentBuilder.fromPis("E", pis)
        // 90° curve of 200 m: tangent 200 m, arc π·100 m.
        val expected = (500 - 200) * 2 + PI * 200 / 2
        assertEquals(expected, a.length, 0.01)
        val end = a.pointAt(a.endStation)!!
        assertEquals(500.0, end.x, 1e-6); assertEquals(500.0, end.y, 1e-6)
    }

    @Test
    fun spiralsKeepTheAlignmentSmoothAndClosed() {
        val a = AlignmentBuilder.fromPis("E", listOf(pis[0], pis[1].copy(spiral = 60.0), pis[2]))
        assertEquals(5, a.elements.size)
        val end = a.pointAt(a.endStation)!!
        assertEquals(500.0, end.x, 0.05); assertEquals(500.0, end.y, 0.05)
        // No kinks: consecutive drawn segments turn by small angles only.
        val v = a.vertices
        var maxTurn = 0.0
        for (i in 1 until v.size - 1) {
            val h1 = Math.atan2(v[i].y - v[i - 1].y, v[i].x - v[i - 1].x)
            val h2 = Math.atan2(v[i + 1].y - v[i].y, v[i + 1].x - v[i].x)
            var d = abs(h2 - h1); if (d > PI) d = 2 * PI - d
            maxTurn = maxOf(maxTurn, d)
        }
        assertTrue(maxTurn < Math.toRadians(2.0), "turn ${Math.toDegrees(maxTurn)}")
    }

    @Test
    fun fillCorridorOnFlatGroundMatchesTheTrapezoidVolume() {
        val ground = Surface("T", doubleArrayOf(-100.0, 400.0, 400.0, -100.0), doubleArrayOf(-200.0, -200.0, 200.0, 200.0),
            DoubleArray(4) { 100.0 }, intArrayOf(0, 1, 2, 0, 2, 3))
        val profile = Profile("G", listOf(Profile.Pvi(0.0, 102.5), Profile.Pvi(300.0, 102.5)))
        val axis = Alignment("E", 0.0, listOf(AlignmentElement.Line(Point2(0.0, 0.0), Point2(300.0, 0.0))), profile)
        // Flat 10 m platform (no crossfall), no pavement, 2:1 fill without benches.
        val side = SideTemplate(laneWidth = 5.0, laneSlope = 0.0, shoulderWidth = 0.0, ditchWidth = 0.0)
        val tpl = Template(side, side, SlopeRule(1.0), SlopeRule(2.0), pavement = 0.0)
        val r = Corridor.build(axis, ground, listOf(Region(0.0, 300.0, tpl)))
        assertEquals(0, r.unresolved)
        val sec = r.sections.first { it.station == 100.0 }
        // Toe 2,5 m × 2 = 5 m beyond the edge.
        assertEquals(-10.0, sec.top.first().offset, 0.05)
        assertEquals("pé", sec.top.first().code)
        val area = 10 * 2.5 + 2 * (0.5 * 5 * 2.5)
        assertEquals(area, sec.fillArea, 0.2)
        assertEquals(area * 300, r.totalFill, area * 300 * 0.01)
        assertEquals(-r.totalFill, r.volumes.last().accumulated, 1e-6)
        assertTrue(r.features.keys.containsAll(listOf("bordo E", "bordo D", "pé E", "pé D")))
        assertEquals(102.5, r.top.elevationAt(Point2(150.0, 0.0))!!, 1e-6)
    }

    @Test
    fun superelevationRotatesAboutTheAxis() {
        val ground = Surface("T", doubleArrayOf(-100.0, 400.0, 400.0, -100.0), doubleArrayOf(-200.0, -200.0, 200.0, 200.0),
            DoubleArray(4) { 100.0 }, intArrayOf(0, 1, 2, 0, 2, 3))
        val profile = Profile("G", listOf(Profile.Pvi(0.0, 101.0), Profile.Pvi(300.0, 101.0)))
        val axis = Alignment("E", 0.0, listOf(AlignmentElement.Line(Point2(0.0, 0.0), Point2(300.0, 0.0))), profile)
        val (sec, _) = Corridor.section(axis, profile, ground, Template(pavement = 0.0), 100.0, 6.0)!!
        val edgeL = sec.top.first { it.code == "bordo" && it.offset < 0 }
        val edgeR = sec.top.first { it.code == "bordo" && it.offset > 0 }
        assertEquals(101.0 - 3.5 * 0.06, edgeL.z, 1e-9)
        assertEquals(101.0 + 3.5 * 0.06, edgeR.z, 1e-9)
        assertEquals(30.0, AlignmentBuilder.curvesFromK(listOf(Profile.Pvi(0.0, 0.0), Profile.Pvi(100.0, 2.0), Profile.Pvi(200.0, 1.0)), 10.0)[1].curveLength, 1e-9)
        assertTrue(hypot(sec.right.x, sec.right.y) > 0.999)
    }
}
