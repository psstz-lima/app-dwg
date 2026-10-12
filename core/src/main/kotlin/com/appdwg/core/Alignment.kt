package com.appdwg.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Horizontal geometry element of an alignment, in plan coordinates. */
sealed interface AlignmentElement {
    val start: Point2
    val end: Point2
    val length: Double

    data class Line(override val start: Point2, override val end: Point2) : AlignmentElement {
        override val length get() = hypot(end.x - start.x, end.y - start.y)
    }

    data class Arc(
        override val start: Point2,
        override val end: Point2,
        val center: Point2,
        val clockwise: Boolean,
    ) : AlignmentElement {
        val radius get() = hypot(start.x - center.x, start.y - center.y)

        /** Signed sweep angle in radians (counterclockwise positive). */
        val sweep: Double
            get() {
                val a0 = atan2(start.y - center.y, start.x - center.x)
                val a1 = atan2(end.y - center.y, end.x - center.x)
                return if (clockwise) -normalize(a0 - a1) else normalize(a1 - a0)
            }

        override val length get() = abs(sweep) * radius

        private fun normalize(a: Double): Double {
            var r = a % (2 * PI)
            if (r <= 0) r += 2 * PI
            return r
        }
    }

    /**
     * Clothoid transition. The tangent at [start] points to [pi]. A radius of
     * [Double.POSITIVE_INFINITY] means a straight end.
     */
    data class Spiral(
        override val start: Point2,
        override val end: Point2,
        val pi: Point2,
        override val length: Double,
        val radiusStart: Double,
        val radiusEnd: Double,
        val clockwise: Boolean,
    ) : AlignmentElement
}

data class StationOffset(
    val station: Double,
    /** Distance from the alignment; positive to the right in the direction of increasing station. */
    val offset: Double,
    val pointOnAlignment: Point2,
    /** True when the point projects before the start or past the end of the alignment. */
    val outside: Boolean,
)

class Alignment(
    val name: String,
    val startStation: Double,
    val elements: List<AlignmentElement>,
    val profile: Profile?,
    /** Spacing in metres used to approximate arcs and spirals. */
    step: Double = 1.0,
) {
    /** Dense polyline of the alignment with the station at each vertex. */
    val vertices: List<Point2>
    val stations: DoubleArray

    init {
        // Samples are evenly spaced along each element, so stations follow the true element length
        // rather than the slightly shorter chords.
        val pts = ArrayList<Point2>()
        val sta = ArrayList<Double>()
        var elementStart = startStation
        for (e in elements) {
            val sampled = sample(e, step)
            val last = sampled.size - 1
            for ((k, p) in sampled.withIndex()) {
                if (k == 0 && pts.isNotEmpty() && pts.last() == p) continue
                pts.add(p)
                sta.add(elementStart + e.length * k / last)
            }
            elementStart += e.length
        }
        vertices = pts
        stations = sta.toDoubleArray()
    }

    val endStation get() = if (stations.isEmpty()) startStation else stations.last()
    val length get() = endStation - startStation
    val bounds get() = Bounds.of(vertices.asSequence())

    /** Plan position at [station], clamped to the alignment's extent. */
    fun pointAt(station: Double): Point2? {
        if (vertices.isEmpty()) return null
        if (station <= stations.first()) return vertices.first()
        if (station >= stations.last()) return vertices.last()
        var lo = 0
        var hi = stations.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (stations[mid] <= station) lo = mid else hi = mid
        }
        val t = (station - stations[lo]) / (stations[hi] - stations[lo])
        val a = vertices[lo]
        val b = vertices[hi]
        return Point2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    /** Projects [p] onto the alignment and returns its station and offset. */
    fun stationOffset(p: Point2): StationOffset? {
        if (vertices.size < 2) return null
        var best: StationOffset? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val rawT = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
            val t = rawT.coerceIn(0.0, 1.0)
            val qx = a.x + dx * t
            val qy = a.y + dy * t
            val dist = hypot(p.x - qx, p.y - qy)
            if (dist < bestDist) {
                bestDist = dist
                val cross = dx * (p.y - a.y) - dy * (p.x - a.x)
                val outside = (i == 0 && rawT < 0) || (i == vertices.size - 2 && rawT > 1)
                best = StationOffset(
                    station = stations[i] + (stations[i + 1] - stations[i]) * t,
                    offset = if (cross > 0) -dist else dist,
                    pointOnAlignment = Point2(qx, qy),
                    outside = outside,
                )
            }
        }
        return best
    }

    companion object {
        fun sample(e: AlignmentElement, step: Double): List<Point2> = when (e) {
            is AlignmentElement.Line -> listOf(e.start, e.end)
            is AlignmentElement.Arc -> sampleArc(e, step)
            is AlignmentElement.Spiral -> sampleSpiral(e, step)
        }

        private fun sampleArc(e: AlignmentElement.Arc, step: Double): List<Point2> {
            val r = e.radius
            val sweep = e.sweep
            val n = max(1, ceil(abs(sweep) * r / step).toInt())
            val a0 = atan2(e.start.y - e.center.y, e.start.x - e.center.x)
            val pts = ArrayList<Point2>(n + 1)
            pts.add(e.start)
            for (i in 1 until n) {
                val a = a0 + sweep * i / n
                pts.add(Point2(e.center.x + r * cos(a), e.center.y + r * sin(a)))
            }
            pts.add(e.end)
            return pts
        }

        private fun sampleSpiral(e: AlignmentElement.Spiral, step: Double): List<Point2> {
            val len = e.length
            if (len <= 0) return listOf(e.start, e.end)
            val sign = if (e.clockwise) -1.0 else 1.0
            val k0 = sign * curvature(e.radiusStart)
            val k1 = sign * curvature(e.radiusEnd)
            val heading0 = atan2(e.pi.y - e.start.y, e.pi.x - e.start.x)
            fun heading(s: Double) = heading0 + k0 * s + (k1 - k0) * s * s / (2 * len)

            val n = max(2, ceil(len / step).toInt())
            val raw = ArrayList<Point2>(n + 1)
            raw.add(e.start)
            var x = e.start.x
            var y = e.start.y
            for (i in 1..n) {
                val s0 = len * (i - 1) / n
                val s1 = len * i / n
                val sm = (s0 + s1) / 2
                val h = s1 - s0
                // Simpson's rule over the sub-step.
                x += h / 6 * (cos(heading(s0)) + 4 * cos(heading(sm)) + cos(heading(s1)))
                y += h / 6 * (sin(heading(s0)) + 4 * sin(heading(sm)) + sin(heading(s1)))
                raw.add(Point2(x, y))
            }
            // Spread any small mismatch with the exported end point along the spiral so there is no gap.
            val ex = e.end.x - raw.last().x
            val ey = e.end.y - raw.last().y
            return raw.mapIndexed { i, p -> Point2(p.x + ex * i / n, p.y + ey * i / n) }
        }

        private fun curvature(radius: Double) =
            if (radius.isInfinite() || radius == 0.0 || radius.isNaN()) 0.0 else 1.0 / radius
    }
}
