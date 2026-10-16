package com.appdwg.core.drainage

import com.appdwg.core.Idf
import com.appdwg.core.OutletSuggestion
import com.appdwg.core.Point2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThalwegTest {
    @Test
    fun aCrossingBecomesACulvertSizedForItsBasin() {
        val crossing = OutletSuggestion(Point2(10.0, 20.0), 0, 1_000_000.0, OutletSuggestion.Kind.CROSSING)
        val suggested = ThalwegCulverts.suggest(listOf(crossing), 0.5, Idf(100.0, 0.0, 0.0, 0.0), 10.0, 15.0, allowable = 4.0)
        val one = suggested.single()
        assertEquals(10.0, one.x, 1e-9)
        assertEquals(20.0, one.y, 1e-9)
        assertEquals(13.8889, one.discharge, 1e-3)
        assertTrue(one.barrel.rise >= 0.8)
        assertFalse(one.head.overtopped)
    }

    @Test
    fun noCrossingSuggestsNothing() {
        assertTrue(ThalwegCulverts.suggest(emptyList(), 0.5, Idf(100.0, 0.0, 0.0, 0.0), 10.0, 10.0).isEmpty())
    }

    @Test
    fun aSuggestedCulvertFollowsTheTerrainAndKeepsATwinCode() {
        val road = listOf(Point2(0.0, 0.0), Point2(100.0, 0.0))
        val barrel = CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 2)
        val crossing = ThalwegCulvert(40.0, 0.0, 1_000.0, 1.0, barrel, CulvertHead(0.4, 0.3, 0.4, 1.0, false))
        val placed = ThalwegCulverts.device(crossing, road, "c1")
        assertEquals("BDTC 100", placed.code)
        assertEquals(GradeMode.HYDRAULIC, placed.grade.mode)
        assertTrue(placed.grade.buried)
        val section = CrossSection("circular_barrels", "m", listOf(circle(1.0)))
        val nodes = Sweep.polyline(placed.xs.toDoubleArray(), placed.ys.toDoubleArray(), null, null)
        val mesh = Sweep.sweep(section, nodes, 0.0, placed.grade, { _, _ -> 120.0 })!!
        assertTrue(mesh.frames.minOf { it.invertZ } > 110.0)
    }

    @Test
    fun theBarrelCrossesTheAlignment() {
        val road = listOf(Point2(0.0, 0.0), Point2(100.0, 0.0))
        val (a, b) = ThalwegCulverts.barrelAcross(road, 40.0, 0.0)
        assertEquals(40.0, a.x, 1e-9)
        assertEquals(40.0, b.x, 1e-9)
        assertEquals(-7.5, a.y, 1e-9)
        assertEquals(7.5, b.y, 1e-9)
    }

    @Test
    fun aTubularCulvertGetsAClosedPipeSection() {
        val section = CatalogLoader.bundled().device("BSTC 60")!!.section!!
        val expected = PI * 0.3 * 0.3
        assertTrue(abs(section.areaM2!! - expected) / expected < 0.02)
        assertEquals(0.6, section.points.maxOf { it.y } - section.points.minOf { it.y }, 1e-9)
    }
}
