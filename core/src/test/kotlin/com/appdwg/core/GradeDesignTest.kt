package com.appdwg.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradeDesignTest {
    /** Rolling ground: a 30 m hill in the middle of a 2 km strip, gentle waves elsewhere. */
    private val terrain = TestTerrain.surface(20_000, size = 2200.0, x0 = -100.0, y0 = -1100.0) { x, _ ->
        val u = x - 100
        100 + 30 * kotlin.math.exp(-((u - 1000) / 300) * ((u - 1000) / 300)) + 3 * sin(u / 90)
    }
    private val axis = Alignment("E", 0.0, listOf(AlignmentElement.Line(Point2(0.0, 0.0), Point2(2000.0, 0.0))), null)

    @Test
    fun gradesStayWithinTheLimitAndTieIntoTheGround() {
        val rules = GradeDesign.GradeRules(maxGrade = 4.0, pviSpacing = 100.0, k = 30.0)
        val s = GradeDesign.suggest(axis, terrain, Template(), rules)
        assertEquals(setOf(GradeSuggestion.Kind.LEAST_EARTHWORK, GradeSuggestion.Kind.BALANCED), s.map { it.kind }.toSet())
        for (g in s) {
            assertTrue(g.maxGrade <= 4.0 + 1e-6, "max ${g.maxGrade}")
            assertEquals(terrain.elevationAt(Point2(0.0, 0.0))!!, g.pvis.first().elevation, 0.5)
            assertEquals(terrain.elevationAt(Point2(2000.0, 0.0))!!, g.pvis.last().elevation, 0.5)
            // Over the hill the grade cuts it down: lower than its 130 m top.
            val p = Profile("G", g.pvis)
            assertTrue(p.elevationAt(1000.0)!! < 128, "top ${p.elevationAt(1000.0)}")
            // Simplified: far fewer PVIs than candidates.
            assertTrue(g.pvis.size < 15, "pvis ${g.pvis.size}")
        }
        val least = s.first { it.kind == GradeSuggestion.Kind.LEAST_EARTHWORK }
        val bal = s.first { it.kind == GradeSuggestion.Kind.BALANCED }
        assertTrue(abs(bal.cut - bal.fill) < 0.05 * (bal.cut + bal.fill), "cut ${bal.cut} fill ${bal.fill}")
        assertTrue(least.cut + least.fill <= (bal.cut + bal.fill) * 1.01)

        // The real corridor on the suggested grade agrees with the estimate to within a fifth.
        val r = Corridor.build(Alignment("E", 0.0, axis.elements, Profile("G", bal.pvis)), terrain, listOf(Region(0.0, 1e9, Template())))
        assertTrue(abs(r.totalCut - bal.cut) < 0.2 * bal.cut + 500, "corridor ${r.totalCut} vs ${bal.cut}")
    }

    @Test
    fun douglasPeuckerKeepsTheBends() {
        val x = DoubleArray(11) { it * 10.0 }
        val z = DoubleArray(11) { if (it <= 5) it.toDouble() else 10.0 - it }
        assertEquals(listOf(0, 5, 10), GradeDesign.simplify(x, z, 0.1))
    }
}
