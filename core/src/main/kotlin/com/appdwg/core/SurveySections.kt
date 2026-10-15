package com.appdwg.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cross sections of a works measurement: the profiles of each survey state (primitiva first) on
 * the same offsets, completed where a later survey does not reach, with the cut and fill areas
 * between states and the volumes by average end areas.
 */
object SurveySections {
    /** Profiles after completion; [filled] marks the samples the app worked out itself. */
    class Completed(val elevations: List<DoubleArray>, val filled: List<BooleanArray>) {
        val anyFilled get() = filled.any { f -> f.any { it } }
    }

    /**
     * Completes [lines] (one per state, in date order, sampled on the same offsets): gaps between
     * measured samples are interpolated in a straight line; past the ends of a later survey the
     * earlier state holds (ground not worked since).
     */
    fun complete(lines: List<DoubleArray>): Completed {
        val out = ArrayList<DoubleArray>(); val marks = ArrayList<BooleanArray>()
        for ((k, line) in lines.withIndex()) {
            val e = line.copyOf(); val f = BooleanArray(e.size)
            val valid = e.indices.filter { !e[it].isNaN() }
            if (valid.isNotEmpty()) {
                // Inner gaps: straight between the measured neighbours.
                for (w in 0 until valid.size - 1) {
                    val a = valid[w]; val b = valid[w + 1]
                    for (i in a + 1 until b) { e[i] = e[a] + (e[b] - e[a]) * (i - a) / (b - a); f[i] = true }
                }
            }
            // Outer gaps of a later state: the state before.
            if (k > 0) {
                val prev = out[k - 1]
                for (i in e.indices) if (e[i].isNaN() && !prev[i].isNaN()) { e[i] = prev[i]; f[i] = true }
            }
            out += e; marks += f
        }
        return Completed(out, marks)
    }

    /** Cut (b below a) and fill (b above a) areas between two profiles on [offsets], where both exist. */
    fun areas(offsets: DoubleArray, a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        var cut = 0.0; var fill = 0.0
        for (i in 0 until offsets.size - 1) {
            val d0 = b[i] - a[i]; val d1 = b[i + 1] - a[i + 1]
            if (d0.isNaN() || d1.isNaN()) continue
            val w = offsets[i + 1] - offsets[i]
            if (d0 * d1 >= 0) {
                val area = (d0 + d1) / 2 * w
                if (area > 0) fill += area else cut -= area
            } else {
                // The profiles cross inside the step: two triangles.
                val t = d0 / (d0 - d1)
                val a0 = d0 * t * w / 2; val a1 = d1 * (1 - t) * w / 2
                if (a0 > 0) fill += a0 else cut -= a0
                if (a1 > 0) fill += a1 else cut -= a1
            }
        }
        return cut to fill
    }

    /** One row of the volume table: cut and fill areas at a station and the volumes since the previous one. */
    class VolumeRow(val station: Double, val cutArea: Double, val fillArea: Double, val cutVolume: Double, val fillVolume: Double)

    /** Volumes by average end areas between consecutive stations; the first row has none. */
    fun volumes(stations: List<Double>, cut: List<Double>, fill: List<Double>): List<VolumeRow> = stations.indices.map { i ->
        if (i == 0) VolumeRow(stations[0], cut[0], fill[0], 0.0, 0.0) else {
            val l = stations[i] - stations[i - 1]
            VolumeRow(stations[i], cut[i], fill[i], (cut[i] + cut[i - 1]) / 2 * l, (fill[i] + fill[i - 1]) / 2 * l)
        }
    }

    /**
     * Straight axis through the middle of [surface], along its longest direction (principal axis of
     * its points), spanning it end to end. Null for a surface without spread.
     */
    fun autoAxis(surface: Surface, name: String): Alignment? {
        val n = surface.xs.size
        if (n < 3) return null
        val cx = surface.xs.average(); val cy = surface.ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) { val dx = surface.xs[i] - cx; val dy = surface.ys[i] - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        if (sxx + syy <= 0) return null
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(angle); val uy = sin(angle)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until n) { val t = (surface.xs[i] - cx) * ux + (surface.ys[i] - cy) * uy; if (t < lo) lo = t; if (t > hi) hi = t }
        if (hi - lo <= 0) return null
        // From west to east (or south to north), so stations read like a map.
        val (s, e) = if (ux < -1e-9 || (kotlin.math.abs(ux) <= 1e-9 && uy < 0)) hi to lo else lo to hi
        val start = Point2(cx + ux * s, cy + uy * s); val end = Point2(cx + ux * e, cy + uy * e)
        return Alignment(name, 0.0, listOf(AlignmentElement.Line(start, end)), null)
    }
}
