package com.appdwg.core

import kotlin.math.ceil
import kotlin.math.max

/** Longitudinal profile ("perfil longitudinal") of an alignment: terrain of each surface and design grade. */
class LongProfile(
    val alignment: String,
    /** Stations sampled, evenly from the start to the end. */
    val stations: DoubleArray,
    /** Terrain on the axis per surface; NaN where the surface does not reach. */
    val lines: List<SectionLine>,
    /** Design grade at each station; NaN outside the vertical profile, null without one. */
    val design: DoubleArray?,
    /** Points of vertical intersection of the design profile. */
    val pvis: List<Profile.Pvi>,
) {
    val start get() = stations.first()
    val end get() = stations.last()

    /** Value of [values] at [station], interpolated between samples; NaN outside. */
    fun interpolate(values: DoubleArray, station: Double): Double {
        if (stations.size < 2 || station < start || station > end) return Double.NaN
        val f = (station - start) / (end - start) * (stations.size - 1)
        val i = f.toInt().coerceAtMost(stations.size - 2)
        val t = f - i
        return values[i] * (1 - t) + values[i + 1] * t
    }

    companion object {
        /** Profile of [alignment] over [surfaces] with about [samples] stations (at least one per metre if shorter). */
        fun of(alignment: Alignment, surfaces: List<Surface>, samples: Int = 1500): LongProfile {
            val length = alignment.length
            val n = max(2, minOf(samples, ceil(length).toInt() + 1))
            val stations = DoubleArray(n) { alignment.startStation + length * it / (n - 1) }
            val points = stations.map { alignment.pointAt(it)!! }
            val lines = surfaces.mapNotNull { s ->
                val z = DoubleArray(n) { s.elevationAt(points[it]) ?: Double.NaN }
                if (z.all { it.isNaN() }) null else SectionLine(s.name, z)
            }
            val profile = alignment.profile
            val design = profile?.let { p -> DoubleArray(n) { p.elevationAt(stations[it]) ?: Double.NaN } }
            return LongProfile(alignment.name, stations, lines, design, profile?.pvis.orEmpty())
        }
    }
}
