package com.appdwg.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectAdvisorTest {
    private val terrain = TestTerrain.surface(2000, size = 1000.0)

    /** Two tangents joined by a 150 m curve of 60°, drawn as 5° chords, like an exploded alignment. */
    private fun exploded(): Polyline {
        val r = 150.0
        val pts = ArrayList<Point2>()
        pts += Point2(100.0, 100.0)
        // Tangent east to the curve start at (400, 100), curve centre at (400, 250), turning left.
        for (k in 0..12) { val a = -PI / 2 + Math.toRadians(5.0 * k); pts += Point2(400 + r * cos(a), 250 + r * sin(a)) }
        val end = pts.last()
        pts += Point2(end.x + 300 * cos(PI / 3), end.y + 300 * sin(PI / 3))
        return Polyline("F1-HZ-EIXO", DoubleArray(pts.size) { pts[it].x }, DoubleArray(pts.size) { pts[it].y }, false)
    }

    @Test
    fun axisFromAnExplodedLineKeepsItsCurve() {
        val pis = ProjectAdvisor.pisFromLine(exploded())
        assertEquals(3, pis.size)
        assertEquals(150.0, pis[1].radius, 1.0)
        // PI where the tangents meet: T = R tan(30°) after the curve start.
        assertEquals(400 + 150 * kotlin.math.tan(PI / 6), pis[1].position.x, 0.5)
        assertEquals(100.0, pis[1].position.y, 0.5)
    }

    @Test
    fun advisesFromWhatTheFilesHold() {
        val pad = Polyline("X7-PLATO", doubleArrayOf(500.0, 600.0, 600.0, 500.0), doubleArrayOf(500.0, 500.0, 600.0, 600.0), true)
        val p = LandXmlProject(null, null, listOf(terrain), emptyList(), emptyList(), emptyList(), polylines = listOf(exploded(), pad))
        val kinds = ProjectAdvisor.advise(p).map { it.kind }
        assertTrue(kinds.containsAll(listOf(Advice.Kind.AXIS_FROM_LINE, Advice.Kind.PLATFORM_FROM_OUTLINE, Advice.Kind.CULVERTS, Advice.Kind.BASINS)), "$kinds")
        assertTrue(Advice.Kind.ROUTE !in kinds)

        val axis = Alignment("E", 0.0, listOf(AlignmentElement.Line(Point2(100.0, 100.0), Point2(900.0, 100.0))), null)
        val q = LandXmlProject(null, null, listOf(terrain), listOf(axis), emptyList(), emptyList())
        assertEquals(Advice.Kind.GRADE, ProjectAdvisor.advise(q).first().kind)
        val empty = LandXmlProject(null, null, listOf(terrain), emptyList(), emptyList(), emptyList())
        assertTrue(Advice.Kind.ROUTE in ProjectAdvisor.advise(empty).map { it.kind })
    }
}
