package com.appdwg.core

import kotlin.math.hypot

/** Terrain profile of one surface across an alignment; NaN where the surface does not reach. */
class SectionLine(val surface: String, val elevations: DoubleArray)

/** Cross section ("seção transversal") of the surfaces at one station, square to the alignment. */
class CrossSection(
    val alignment: String,
    val station: Double,
    /** Point on the axis at the station. */
    val center: Point2,
    /** Unit vector pointing to the right of the alignment (positive offsets). */
    val right: Point2,
    /** Offsets sampled, from left (negative) to right. */
    val offsets: DoubleArray,
    val lines: List<SectionLine>,
    /** Design grade elevation on the axis. */
    val design: Double?,
) {
    /** Plan position at [offset] metres from the axis. */
    fun pointAt(offset: Double) = Point2(center.x + right.x * offset, center.y + right.y * offset)

    /** Terrain on the axis, from the first surface that reaches it. */
    val terrainOnAxis: Double?
        get() = lines.firstNotNullOfOrNull { l -> interpolate(l.elevations, 0.0).takeIf { !it.isNaN() } }

    /** Design minus terrain on the axis: positive is fill (aterro), negative is cut (corte). */
    val cutFill: Double? get() = design?.let { d -> terrainOnAxis?.let { d - it } }

    /** Elevation of [line] at [offset], interpolated between samples; NaN off the surface. */
    fun interpolate(elevations: DoubleArray, offset: Double): Double {
        if (offsets.size < 2) return Double.NaN
        val step = offsets[1] - offsets[0]
        val f = (offset - offsets[0]) / step
        if (f < 0 || f > offsets.size - 1) return Double.NaN
        val i = f.toInt().coerceAtMost(offsets.size - 2)
        val t = f - i
        return elevations[i] * (1 - t) + elevations[i + 1] * t
    }

    companion object {
        /**
         * Section of [surfaces] across [alignment] at [station], [halfWidth] metres to each side, with
         * [samples] points. Null when the alignment has no geometry.
         */
        fun at(alignment: Alignment, station: Double, surfaces: List<Surface>, halfWidth: Double, samples: Int = 401): CrossSection? {
            val sta = station.coerceIn(alignment.startStation, alignment.endStation)
            val c = alignment.pointAt(sta) ?: return null
            // Tangent from points just before and after; pointAt clamps at the ends.
            val a = alignment.pointAt(sta - 0.5)!!
            val b = alignment.pointAt(sta + 0.5)!!
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len == 0.0) return null
            val right = Point2((b.y - a.y) / len, -(b.x - a.x) / len)
            val offsets = DoubleArray(samples) { -halfWidth + 2 * halfWidth * it / (samples - 1) }
            val lines = surfaces.mapNotNull { s ->
                val z = DoubleArray(samples) { i ->
                    s.elevationAt(Point2(c.x + right.x * offsets[i], c.y + right.y * offsets[i])) ?: Double.NaN
                }
                if (z.all { it.isNaN() }) null else SectionLine(s.name, z)
            }
            return CrossSection(alignment.name, sta, c, right, offsets, lines, alignment.profile?.elevationAt(sta))
        }
    }
}
