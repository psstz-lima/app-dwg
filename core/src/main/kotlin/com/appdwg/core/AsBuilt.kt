package com.appdwg.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * As-built check: each surveyed point against the design surface. A point passes when its height
 * is within [AsBuiltReport.tolerance] of the design; points outside the design are listed apart.
 */
data class AsBuiltRow(
    val point: CogoPoint,
    /** Design height under the point, or null when the point falls outside the design surface. */
    val design: Double?,
    /** Station and offset on the alignment, when there is one and the point projects onto it. */
    val stationOffset: StationOffset?,
) {
    /** Surveyed minus design: positive means the work is above the design (fill still to cut). */
    val delta: Double? get() = design?.let { point.z - it }
}

enum class AsBuiltStatus(private val labelPt: String) { OK("OK"), ABOVE("Acima"), BELOW("Abaixo"), OUTSIDE("Fora do projeto"); val label: String get() = tr(labelPt) }

data class AsBuiltReport(
    val surface: String,
    val alignment: String?,
    val tolerance: Double,
    val rows: List<AsBuiltRow>,
) {
    fun status(r: AsBuiltRow): AsBuiltStatus {
        val d = r.delta ?: return AsBuiltStatus.OUTSIDE
        return when {
            abs(d) <= tolerance + 1e-9 -> AsBuiltStatus.OK
            d > 0 -> AsBuiltStatus.ABOVE
            else -> AsBuiltStatus.BELOW
        }
    }

    private val deltas = rows.mapNotNull { it.delta }
    val checked get() = deltas.size
    val outside get() = rows.size - deltas.size
    val passed get() = rows.count { status(it) == AsBuiltStatus.OK }
    val above get() = rows.count { status(it) == AsBuiltStatus.ABOVE }
    val below get() = rows.count { status(it) == AsBuiltStatus.BELOW }
    /** Share of the checked points within tolerance, 0..1. */
    val passRate get() = if (checked == 0) 0.0 else passed.toDouble() / checked
    val mean get() = if (deltas.isEmpty()) 0.0 else deltas.average()
    val rms get() = if (deltas.isEmpty()) 0.0 else sqrt(deltas.sumOf { it * it } / deltas.size)
    val stdDev get() = if (deltas.size < 2) 0.0 else sqrt(deltas.sumOf { (it - mean) * (it - mean) } / (deltas.size - 1))
    val max get() = deltas.maxOrNull()
    val min get() = deltas.minOrNull()

    companion object {
        /** Points without a height are skipped: they cannot be checked. */
        fun check(points: List<CogoPoint>, surface: Surface, alignment: Alignment?, tolerance: Double): AsBuiltReport {
            val rows = points.filter { !it.z.isNaN() }.map { p ->
                val at = Point2(p.x, p.y)
                AsBuiltRow(p, surface.elevationAt(at), alignment?.stationOffset(at)?.takeIf { !it.outside })
            }
            return AsBuiltReport(surface.name, alignment?.name, tolerance, rows)
        }
    }
}
