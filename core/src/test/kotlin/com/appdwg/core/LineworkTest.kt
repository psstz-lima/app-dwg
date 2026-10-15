package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineworkTest {
    @Test
    fun descriptionsReadAsCodeAndLine() {
        val c = PointCodes.defaults
        assertEquals("PE" to "", PointCodes.match("pe", c)?.let { it.first.code to it.second })
        assertEquals("PE" to "2", PointCodes.match("PE2", c)?.let { it.first.code to it.second })
        assertEquals("PE" to "3", PointCodes.match("Pé talude 3", c)?.let { it.first.code to it.second })
        assertEquals("CR" to "", PointCodes.match("CRISTA", c)?.let { it.first.code to it.second })
        assertNull(PointCodes.match("XYZ", c))
    }

    @Test
    fun shuffledToePointsAreJoinedInOrderAlongTheLine() {
        // A toe along a gentle curve, collected out of order, with a crest line beside it.
        val toe = (0 until 12).map { i -> CogoPoint("$i", "PE", i * 5.0, (i * i) * 0.05, 100.0 + i * 0.1) }
        val crest = (0 until 12).map { i -> CogoPoint("c$i", "CR", i * 5.0, 15.0 + (i * i) * 0.05, 104.0) }
        val shuffled = (toe + crest).shuffled(java.util.Random(7))
        val lines = Linework.build(shuffled)
        val pe = lines.filter { it.code.code == "PE" }
        assertEquals(1, pe.size)
        val xs = pe[0].points.map { it.x }
        assertTrue(xs == xs.sorted() || xs == xs.sortedDescending(), "$xs")
        assertEquals(12, pe[0].points.size)
        assertEquals(1, lines.count { it.code.code == "CR" })
        assertTrue(pe[0].polyline().zs != null)
    }

    @Test
    fun aLargeGapSplitsTheLineAndLineNumbersStayApart() {
        val a = (0 until 5).map { CogoPoint("a$it", "PE1", it * 5.0, 0.0, 10.0) }
        val b = (0 until 5).map { CogoPoint("b$it", "PE1", 500 + it * 5.0, 0.0, 10.0) }
        val c = (0 until 5).map { CogoPoint("c$it", "PE2", it * 5.0, 3.0, 10.0) }
        val lines = Linework.build(a + b + c)
        assertEquals(2, lines.count { it.key == "1" })
        assertEquals(1, lines.count { it.key == "2" })
    }

    @Test
    fun pointCodesAndTableRoundTrip() {
        assertEquals(0, Linework.build(listOf(CogoPoint("1", "POSTE", 0.0, 0.0, 0.0), CogoPoint("2", "POSTE", 1.0, 0.0, 0.0))).size)
        val back = PointCodes.fromTsv(PointCodes.toTsv(PointCodes.defaults))
        assertEquals(PointCodes.defaults, back)
    }

    @Test
    fun densifyAddsPointsOnTheBreakline() {
        val l = Polyline("L", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), false, null, zs = doubleArrayOf(0.0, 10.0))
        val pts = Linework.densify(listOf(l), 2.0)
        assertEquals(5, pts.size)
        pts.forEach { assertEquals(it.x, it.z, 1e-9) }
    }
}
