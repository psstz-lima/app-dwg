package com.appdwg.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** One leg of a measurement, from one tapped point to the next. */
data class MeasureLeg(
    /** Horizontal distance in metres. */
    val distance: Double,
    /** Grid azimuth in degrees, clockwise from north (0 to 360). */
    val azimuth: Double,
    /** Elevation of the end minus the start, when both are on a surface. */
    val rise: Double?,
) {
    /** Slope in percent (rise over horizontal distance). */
    val slopePercent: Double? get() = rise?.takeIf { distance > 0 }?.let { it / distance * 100 }

    /** Distance along the slope, when the rise is known. */
    val slopeDistance: Double? get() = rise?.let { sqrt(distance * distance + it * it) }
}

/** Totals for a list of tapped points, read as a line and, from 3 points on, as a closed area. */
data class Measurement(
    val legs: List<MeasureLeg>,
    /** Horizontal length of the line. */
    val length: Double,
    /** Length along the slope, when every point has an elevation. */
    val slopeLength: Double?,
    /** Last point's elevation minus the first's. */
    val rise: Double?,
    /** Plan area of the polygon closed back to the first point (3 points or more). */
    val area: Double?,
    /** Perimeter of that polygon. */
    val perimeter: Double?,
)

object Measure {
    /** Measures [points]; [elevations] holds each point's terrain elevation, or null off the surfaces. */
    fun of(points: List<Point2>, elevations: List<Double?>): Measurement {
        require(points.size == elevations.size)
        val legs = (1 until points.size).map { i ->
            val a = points[i - 1]
            val b = points[i]
            val za = elevations[i - 1]
            val zb = elevations[i]
            MeasureLeg(hypot(b.x - a.x, b.y - a.y), azimuth(a, b), if (za != null && zb != null) zb - za else null)
        }
        val length = legs.sumOf { it.distance }
        val slopeLength = if (legs.isNotEmpty() && legs.all { it.rise != null }) legs.sumOf { it.slopeDistance!! } else null
        val first = elevations.firstOrNull()
        val last = elevations.lastOrNull()
        val rise = if (points.size >= 2 && first != null && last != null) last - first else null
        val closed = points.size >= 3
        val area = if (closed) abs(shoelace(points)) else null
        val perimeter = if (closed) length + hypot(points.last().x - points[0].x, points.last().y - points[0].y) else null
        return Measurement(legs, length, slopeLength, rise, area, perimeter)
    }

    /** Grid azimuth from [a] to [b] in degrees, clockwise from north. */
    fun azimuth(a: Point2, b: Point2): Double {
        val deg = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
        return if (deg < 0) deg + 360 else deg
    }

    /** Azimuth as degrees, minutes and seconds: 45.5 becomes "45°30'00\"". */
    fun dms(degrees: Double): String {
        var totalSeconds = Math.round(degrees * 3600)
        totalSeconds = ((totalSeconds % 1_296_000) + 1_296_000) % 1_296_000
        val d = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%d°%02d'%02d\"".format(d, m, s)
    }

    /** Area in the shown units, with hectares (or acres) once it is large: "12.500,00 m² (1,2500 ha)". */
    fun area(m2: Double): String = StationFormat.area(m2)

    private fun shoelace(p: List<Point2>): Double {
        // Relative to the first point, so UTM-sized coordinates keep their precision.
        val ox = p[0].x
        val oy = p[0].y
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += (a.x - ox) * (b.y - oy) - (b.x - ox) * (a.y - oy)
        }
        return sum / 2
    }
}
