package com.appdwg.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance, direction and height between two points ("inverso"). */
data class Inverse(val distance: Double, val azimuth: Double, val rise: Double?, val slopeDistance: Double?) {
    val gradePercent: Double? get() = rise?.takeIf { distance > 0 }?.let { it / distance * 100 }
}

/** Field calculations on plan coordinates (azimuths in degrees, clockwise from grid north). */
object Cogo {
    fun inverse(a: Point2, b: Point2, za: Double? = null, zb: Double? = null): Inverse {
        val d = hypot(b.x - a.x, b.y - a.y)
        val rise = if (za != null && zb != null) zb - za else null
        return Inverse(d, Measure.azimuth(a, b), rise, rise?.let { sqrt(d * d + it * it) })
    }

    /** Point at [distance] towards [azimuth] from [from] ("irradiação"). */
    fun radiate(from: Point2, azimuth: Double, distance: Double): Point2 {
        val r = Math.toRadians(azimuth)
        return Point2(from.x + distance * sin(r), from.y + distance * cos(r))
    }

    /** Crossing of the lines leaving [a] towards [azA] and [b] towards [azB]; null when parallel. */
    fun bearingBearing(a: Point2, azA: Double, b: Point2, azB: Double): Point2? {
        val da = Point2(sin(Math.toRadians(azA)), cos(Math.toRadians(azA)))
        val db = Point2(sin(Math.toRadians(azB)), cos(Math.toRadians(azB)))
        val den = da.x * db.y - da.y * db.x
        if (abs(den) < 1e-12) return null
        val t = ((b.x - a.x) * db.y - (b.y - a.y) * db.x) / den
        return Point2(a.x + da.x * t, a.y + da.y * t)
    }

    /** Points at [ra] from [a] and [rb] from [b] (0, 1 or 2 of them). */
    fun distanceDistance(a: Point2, ra: Double, b: Point2, rb: Double): List<Point2> {
        val d = hypot(b.x - a.x, b.y - a.y)
        if (d == 0.0 || d > ra + rb + 1e-9 || d < abs(ra - rb) - 1e-9) return emptyList()
        val x = (ra * ra - rb * rb + d * d) / (2 * d)
        val h = sqrt((ra * ra - x * x).coerceAtLeast(0.0))
        val ux = (b.x - a.x) / d; val uy = (b.y - a.y) / d
        val px = a.x + ux * x; val py = a.y + uy * x
        val p1 = Point2(px - uy * h, py + ux * h); val p2 = Point2(px + uy * h, py - ux * h)
        return if (h < 1e-9) listOf(p1) else listOf(p1, p2)
    }

    /** Points on the line from [a] towards [az] at [r] from [b] (0, 1 or 2 of them, ahead of [a]). */
    fun bearingDistance(a: Point2, az: Double, b: Point2, r: Double): List<Point2> {
        val dx = sin(Math.toRadians(az)); val dy = cos(Math.toRadians(az))
        val fx = a.x - b.x; val fy = a.y - b.y
        val bq = 2 * (fx * dx + fy * dy); val c = fx * fx + fy * fy - r * r
        val disc = bq * bq - 4 * c
        if (disc < 0) return emptyList()
        val s = sqrt(disc)
        return listOf((-bq - s) / 2, (-bq + s) / 2).filter { it >= -1e-9 }.distinct().map { t -> Point2(a.x + dx * t, a.y + dy * t) }
    }

    /** Decimal degrees from degrees, minutes and seconds. */
    fun fromDms(d: Double, m: Double, s: Double): Double = (abs(d) + m / 60 + s / 3600) * if (d < 0) -1 else 1

    /** Decimal degrees to grads (gon). */
    fun toGon(deg: Double) = deg * 400 / 360

    /** Slope in percent from an H:V ratio, and back. */
    fun ratioToPercent(h: Double) = 100 / h
    fun percentToRatio(p: Double) = 100 / p
}
