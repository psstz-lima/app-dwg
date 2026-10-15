package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CogoTest {
    @Test
    fun inverseAndRadiateAreOpposites() {
        val a = Point2(1000.0, 2000.0)
        val p = Cogo.radiate(a, 135.0, 100.0)
        val inv = Cogo.inverse(a, p, 10.0, 15.0)
        assertEquals(100.0, inv.distance, 1e-9)
        assertEquals(135.0, inv.azimuth, 1e-9)
        assertEquals(5.0, inv.gradePercent!!, 1e-9)
    }

    @Test
    fun intersections() {
        val a = Point2(0.0, 0.0); val b = Point2(100.0, 0.0)
        val p = Cogo.bearingBearing(a, 45.0, b, 315.0)!!
        assertEquals(50.0, p.x, 1e-9); assertEquals(50.0, p.y, 1e-9)
        val dd = Cogo.distanceDistance(a, 60.0, b, 80.0)
        assertEquals(2, dd.size)
        dd.forEach { q -> assertEquals(60.0, Cogo.inverse(a, q).distance, 1e-9); assertEquals(80.0, Cogo.inverse(b, q).distance, 1e-9) }
        val bd = Cogo.bearingDistance(a, 90.0, b, 30.0)
        assertEquals(listOf(70.0, 130.0), bd.map { Math.round(it.x * 1e6) / 1e6 })
        assertEquals(45.5, Cogo.fromDms(45.0, 30.0, 0.0), 1e-12)
        assertEquals(50.0, Cogo.toGon(45.0), 1e-12)
        assertEquals(66.666666, Cogo.ratioToPercent(1.5), 1e-5)
    }
}
