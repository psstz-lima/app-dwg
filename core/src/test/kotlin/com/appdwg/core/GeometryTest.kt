package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeometryTest {

    @Test
    fun clothoidMatchesSeriesExpansion() {
        // Entry spiral from a tangent heading east into a left curve: L = 50 m, R = 200 m.
        val l = 50.0
        val r = 200.0
        val x = l - l * l * l / (40 * r * r)
        val y = l * l / (6 * r) - l * l * l * l / (336 * r * r * r)
        val spiral = AlignmentElement.Spiral(
            start = Point2(0.0, 0.0),
            end = Point2(x, y),
            pi = Point2(30.0, 0.0),
            length = l,
            radiusStart = Double.POSITIVE_INFINITY,
            radiusEnd = r,
            clockwise = false,
        )
        val pts = Alignment.sample(spiral, 1.0)
        // Halfway along, the offset from the tangent is (L/2)^3 / (6 R L).
        val half = pts[pts.size / 2]
        assertEquals(25.0, half.x, 0.01)
        assertEquals(25.0 * 25 * 25 / (6 * r * l), half.y, 0.01)
        assertEquals(x, pts.last().x, 1e-9)
        assertEquals(y, pts.last().y, 1e-9)
    }

    @Test
    fun clockwiseArcSweepsNegative() {
        val arc = AlignmentElement.Arc(Point2(0.0, 0.0), Point2(100.0, -100.0), Point2(0.0, -100.0), clockwise = true)
        assertEquals(-Math.PI / 2, arc.sweep, 1e-12)
        assertEquals(50 * Math.PI, arc.length, 1e-9)
    }

    @Test
    fun formatsBrazilianStations() {
        assertEquals("12 + 5,40", StationFormat.estaca(245.4))
        assertEquals("0 + 0,00", StationFormat.estaca(0.0))
        assertEquals("1 + 0,00", StationFormat.estaca(19.999))
        assertEquals("3,25 E", StationFormat.offset(-3.25))
        assertEquals("3,25 D", StationFormat.offset(3.25))
    }
}
