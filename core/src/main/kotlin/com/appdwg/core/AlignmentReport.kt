package com.appdwg.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Notable points and element tables of an alignment and its profile, as in a road design report:
 * PC/PT of circular curves, TS/SC/CS/ST of spirals, and PCV/PIV/PTV of vertical curves.
 */
object AlignmentReport {
    /** A named point of the axis: "PC", "PT", "TS"…, its station and plan position. */
    class KeyPoint(val label: String, val station: Double, val point: Point2)

    /** One horizontal element: kind ("Tangente", "Curva circular", "Espiral"), stations, length, radius, azimuth at start (degrees). */
    class HRow(val kind: String, val from: Double, val to: Double, val length: Double, val radius: Double?, val azimuth: Double, val right: Boolean?)

    /** One PVI: station, elevation, grades before and after (%), curve length, K and the curve ends. */
    class VRow(val station: Double, val elevation: Double, val gradeIn: Double?, val gradeOut: Double?, val length: Double, val k: Double?,
               val pcv: Double?, val ptv: Double?)

    private fun kindOf(e: AlignmentElement) = when (e) {
        is AlignmentElement.Line -> 0
        is AlignmentElement.Arc -> 1
        is AlignmentElement.Spiral -> 2
    }

    /** Name of the point where element kind [a] meets kind [b] (0 tangent, 1 arc, 2 spiral). */
    private fun label(a: Int, b: Int) = when {
        a == 0 && b == 1 -> "PC"
        a == 1 && b == 0 -> "PT"
        a == 0 && b == 2 -> "TS"
        a == 2 && b == 1 -> "SC"
        a == 1 && b == 2 -> "CS"
        a == 2 && b == 0 -> "ST"
        a == 1 && b == 1 -> "PCC"
        a == 2 && b == 2 -> "SS"
        else -> null
    }

    fun keyPoints(a: Alignment): List<KeyPoint> {
        val out = ArrayList<KeyPoint>()
        var sta = a.startStation
        val els = a.elements
        out += KeyPoint("PP", sta, els.firstOrNull()?.start ?: return out)
        for (i in 0 until els.size - 1) {
            sta += els[i].length
            label(kindOf(els[i]), kindOf(els[i + 1]))?.let { out += KeyPoint(it, sta, els[i].end) }
        }
        sta += els.last().length
        out += KeyPoint("PF", sta, els.last().end)
        return out
    }

    private fun azimuth(dx: Double, dy: Double): Double {
        var az = Math.toDegrees(atan2(dx, dy))
        if (az < 0) az += 360.0
        return az
    }

    fun horizontal(a: Alignment): List<HRow> {
        var sta = a.startStation
        return a.elements.map { e ->
            val from = sta; sta += e.length
            when (e) {
                is AlignmentElement.Line -> HRow("Tangente", from, sta, e.length, null, azimuth(e.end.x - e.start.x, e.end.y - e.start.y), null)
                is AlignmentElement.Arc -> {
                    val tx = if (e.clockwise) e.start.y - e.center.y else -(e.start.y - e.center.y)
                    val ty = if (e.clockwise) -(e.start.x - e.center.x) else e.start.x - e.center.x
                    HRow("Curva circular", from, sta, e.length, e.radius, azimuth(tx, ty), e.clockwise)
                }
                is AlignmentElement.Spiral -> HRow("Espiral", from, sta, e.length,
                    listOf(e.radiusStart, e.radiusEnd).filter { it.isFinite() }.minOrNull(), azimuth(e.pi.x - e.start.x, e.pi.y - e.start.y), e.clockwise)
            }
        }
    }

    fun vertical(p: Profile): List<VRow> {
        val v = p.pvis
        return v.indices.map { i ->
            val gin = if (i > 0) (v[i].elevation - v[i - 1].elevation) / (v[i].station - v[i - 1].station) * 100 else null
            val gout = if (i < v.size - 1) (v[i + 1].elevation - v[i].elevation) / (v[i + 1].station - v[i].station) * 100 else null
            val l = v[i].curveLength
            val k = if (gin != null && gout != null && l > 0 && abs(gout - gin) > 1e-9) l / abs(gout - gin) else null
            VRow(v[i].station, v[i].elevation, gin, gout, l, k, if (l > 0) v[i].station - l / 2 else null, if (l > 0) v[i].station + l / 2 else null)
        }
    }
}
