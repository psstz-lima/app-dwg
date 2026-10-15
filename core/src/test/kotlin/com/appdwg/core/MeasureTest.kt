package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasureTest {

    @Test
    fun legsGiveDistanceAzimuthRiseAndSlope() {
        val m = Measure.of(
            listOf(Point2(500000.0, 7800000.0), Point2(500030.0, 7800040.0)),
            listOf(100.0, 102.5),
        )
        val leg = m.legs.single()
        assertEquals(50.0, leg.distance, 1e-9)
        assertEquals(36.8699, leg.azimuth, 1e-4)
        assertEquals(2.5, leg.rise!!, 1e-9)
        assertEquals(5.0, leg.slopePercent!!, 1e-9)
        assertEquals(50.0624, leg.slopeDistance!!, 1e-4)
        assertNull(m.area)
    }

    @Test
    fun azimuthIsClockwiseFromNorth() {
        val o = Point2(0.0, 0.0)
        assertEquals(0.0, Measure.azimuth(o, Point2(0.0, 1.0)), 1e-9)
        assertEquals(90.0, Measure.azimuth(o, Point2(1.0, 0.0)), 1e-9)
        assertEquals(180.0, Measure.azimuth(o, Point2(0.0, -1.0)), 1e-9)
        assertEquals(270.0, Measure.azimuth(o, Point2(-1.0, 0.0)), 1e-9)
    }

    @Test
    fun threePointsCloseAnArea() {
        // 100 m x 50 m rectangle at UTM coordinates, tapped clockwise.
        val pts = listOf(
            Point2(761100.0, 7848700.0), Point2(761100.0, 7848750.0),
            Point2(761200.0, 7848750.0), Point2(761200.0, 7848700.0),
        )
        val m = Measure.of(pts, listOf(null, 10.0, 11.0, 12.0))
        assertEquals(5000.0, m.area!!, 1e-6)
        assertEquals(300.0, m.perimeter!!, 1e-9)
        assertEquals(200.0, m.length, 1e-9)
        assertNull(m.rise)
        assertNull(m.slopeLength)
    }

    @Test
    fun formatsDegreesAndArea() {
        assertEquals("45°30'00\"", Measure.dms(45.5))
        assertEquals("0°00'00\"", Measure.dms(359.99999))
        assertEquals("5.000,00 m²", Measure.area(5000.0))
        assertEquals("12.500,00 m² (1,2500 ha)", Measure.area(12500.0))
    }
}
