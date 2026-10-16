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
    fun aTubularCulvertGetsAClosedPipeSection() {
        val section = CatalogLoader.bundled().device("BSTC 60")!!.section!!
        val expected = PI * 0.3 * 0.3
        assertTrue(abs(section.areaM2!! - expected) / expected < 0.02)
        assertEquals(0.6, section.points.maxOf { it.y } - section.points.minOf { it.y }, 1e-9)
    }
}
