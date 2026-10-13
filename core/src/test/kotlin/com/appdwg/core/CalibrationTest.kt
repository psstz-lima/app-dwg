package com.appdwg.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CalibrationTest {

    /** Local grid = GNSS rotated 1.5°, scaled 1.0002, shifted; local heights 2.3 m above, tilted 1 mm/m east. */
    private fun local(m: Point3): Point3 {
        val t = Math.toRadians(1.5); val s = 1.0002
        val x = m.x - 600000; val y = m.y - 7800000
        return Point3(1000 + s * (cos(t) * x - sin(t) * y), 5000 + s * (sin(t) * x + cos(t) * y), m.z + 2.3 + 0.001 * (m.x - 600100))
    }

    private val measured = listOf(Point3(600000.0, 7800000.0, 800.0), Point3(600200.0, 7800050.0, 805.0),
        Point3(600120.0, 7800300.0, 810.0), Point3(599950.0, 7800220.0, 802.0))

    @Test
    fun recoversSimilarityAndPlane() {
        val c = Calibration.fit(measured.mapIndexed { i, m -> CalibrationPair("P$i", m, local(m)) })!!
        assertEquals(1.0002, c.scale, 1e-9)
        assertEquals(1.5, c.rotationDeg, 1e-7)
        assertEquals(0.0, c.horizontalRms, 1e-6)
        assertEquals(0.0, c.verticalRms, 1e-6)
        val p = Point3(600050.0, 7800100.0, 803.0)
        val q = c.apply(p); val want = local(p)
        assertEquals(want.x, q.x, 1e-6); assertEquals(want.y, q.y, 1e-6); assertEquals(want.z, q.z, 1e-6)
    }

    @Test
    fun onePointIsAShift() {
        val c = Calibration.fit(listOf(CalibrationPair("A", Point3(10.0, 20.0, 5.0), Point3(110.0, 220.0, 7.5))))!!
        val q = c.apply(Point3(15.0, 25.0, 6.0))
        assertEquals(115.0, q.x, 1e-9); assertEquals(225.0, q.y, 1e-9); assertEquals(8.5, q.z, 1e-9)
        assertEquals(1.0, c.scale, 1e-12)
    }

    @Test
    fun noHorizontalPointsNoCalibration() {
        assertNull(Calibration.fit(listOf(CalibrationPair("A", Point3(0.0, 0.0, 0.0), Point3(1.0, 1.0, 1.0), useHorizontal = false))))
    }

    @Test
    fun geoidTextGridIsBilinear() {
        val text = "lat lon N\n-20 -44 -5\n-20 -43 -4\n-19 -44 -6\n-19 -43 -3\n"
        val g = GeoidGrid.readText("teste", text)
        assertEquals(-5.0, g.undulation(-20.0, -44.0)!!, 1e-9)
        assertEquals(-4.5, g.undulation(-19.5, -43.5)!!, 1e-6)
        assertNull(g.undulation(-18.0, -43.5))
    }

    @Test
    fun ggaKeepsTheGeoidSeparation() {
        val body = "GPGGA,120000.00,1954.0000,S,04354.0000,W,4,14,0.8,850.000,M,-5.200,M,1.0,0000"
        val sum = body.fold(0) { c, ch -> c xor ch.code }
        val fix = assertNotNull(NmeaParser().line("$" + body + "*" + String.format("%02X", sum)))
        assertEquals(-5.2, fix.geoidSeparation!!, 1e-9)
        assertEquals(844.8, fix.ellipsoidalHeight!!, 1e-9)
    }
}
