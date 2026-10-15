package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineOpsTest {
    private val l = Polyline("A", doubleArrayOf(0.0, 10.0, 10.0), doubleArrayOf(0.0, 0.0, 10.0), false, zs = doubleArrayOf(100.0, 101.0, 102.0))

    @Test
    fun offsetKeepsDistanceAtCornersAndMovesElevation() {
        // Right of an east-then-north line is south, then east: corner moves to (12, −2).
        val o = LineOps.offset(l, 2.0, "_EDICAO", gradePercent = -50.0)
        assertEquals(0.0, o.xs[0], 1e-9); assertEquals(-2.0, o.ys[0], 1e-9)
        assertEquals(12.0, o.xs[1], 1e-9); assertEquals(-2.0, o.ys[1], 1e-9)
        assertEquals(12.0, o.xs[2], 1e-9); assertEquals(10.0, o.ys[2], 1e-9)
        // 2 m out at −50 % drops 1 m.
        assertEquals(99.0, o.zs!![0], 1e-9)
        assertEquals("_EDICAO", o.layer)
    }

    @Test
    fun propertiesCopiesAndPolar() {
        val i = LineOps.info(l)
        assertEquals(20.0, i.length2d, 1e-9)
        assertEquals(10.0, i.gradePercent!!, 1e-9)
        val c = LineOps.copies(l, 5.0, 0.0, 1.0, 3, "_EDICAO")
        assertEquals(15.0, c[2].xs[0], 1e-9)
        assertEquals(103.0, c[2].zs!![0], 1e-9)
        val p = LineOps.polar(10.0, 90.0)
        assertEquals(10.0, p.x, 1e-9); assertEquals(0.0, p.y, 1e-9)
    }

    @Test
    fun drapeTakesTheSurface() {
        val plane = Surface("T", doubleArrayOf(-10.0, 30.0, 30.0, -10.0), doubleArrayOf(-10.0, -10.0, 30.0, 30.0),
            doubleArrayOf(0.0, 4.0, 4.0, 0.0), intArrayOf(0, 1, 2, 0, 2, 3))
        val d = LineOps.drape(Polyline("A", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), false), plane, 2.5)
        assertEquals(5, d.xs.size)
        assertEquals(2.0, d.zs!!.last(), 1e-9)
    }

    @Test
    fun joinExplodeDivideMirrorReverse() {
        val a = Polyline("A", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 0.0), false)
        val b = Polyline("A", doubleArrayOf(2.0, 1.0), doubleArrayOf(0.0, 0.0), false)
        val j = LineOps.join(listOf(a, b))
        assertEquals(1, j.size); assertEquals(3, j[0].xs.size)
        assertEquals(2, LineOps.explode(l).size)
        val parts = LineOps.divide(l, every = 6.0)
        assertEquals(4, parts.size)
        assertEquals(6.0, LineOps.info(parts[0]).length2d, 1e-9)
        assertEquals(2.0, LineOps.info(parts[3]).length2d, 1e-9)
        assertEquals(5, LineOps.divide(l, parts = 5).size)
        val m = LineOps.mirror(l, Point2(0.0, 0.0), Point2(0.0, 1.0))
        assertEquals(-10.0, m.xs[1], 1e-9)
        assertEquals(10.0, LineOps.reverse(l).ys[0], 1e-9)
        assertTrue(LineOps.rotate(a, Point2(0.0, 0.0), 90.0).ys[1] > 0.999)
    }
}
