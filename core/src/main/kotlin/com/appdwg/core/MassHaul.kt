package com.appdwg.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One wave of the Brückner diagram between two crossings of the balance line: cut moved forward
 * ([forward] true, the curve above the line) or back to the fills.
 */
data class HaulWave(val start: Double, val end: Double, val volume: Double, val moment: Double, val forward: Boolean) {
    /** Mean haul distance of the wave, m. */
    val dmt get() = if (volume > 0) moment / volume else 0.0
}

/** Haul-distance class (DNIT bands), with the volume hauled in it. */
data class HaulBand(val from: Double, val to: Double?, val volume: Double, val moment: Double) {
    val label get() = if (to == null) "acima de ${from.toInt()} m" else "${from.toInt()} a ${to.toInt()} m"
}

class MassHaul(
    val waves: List<HaulWave>,
    /** Cut used as fill in the same interval, not hauled along the road. */
    val lateral: Double,
    /** Cut left over at the end (to spoil) and fill missing (from borrow), m³. */
    val spoil: Double,
    val borrow: Double,
    val bands: List<HaulBand>,
) {
    val hauled get() = waves.sumOf { it.volume }
    val moment get() = waves.sumOf { it.moment }
    /** Mean haul distance of all the material moved along the road. */
    val dmt get() = if (hauled > 0) moment / hauled else 0.0

    companion object {
        /** DNIT haul bands (m) for earthwork pricing; the last one is open. */
        val BANDS = doubleArrayOf(0.0, 50.0, 200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0, 1400.0, 1600.0, 1800.0, 2000.0, 3000.0, 5000.0)

        /**
         * Mass distribution from the volume rows of a corridor, balance line at zero. Each wave
         * between crossings carries the material that rises (or falls) in it; its moment is the area
         * between the curve and the line, so DMT = area / volume. What is left at the end goes to
         * spoil (above zero) or comes from borrow (below).
         */
        fun of(rows: List<VolumeRow>, swell: Double = 1.0): MassHaul {
            val lateral = rows.sumOf { min(it.cut, it.fill * swell) }
            if (rows.size < 2) return MassHaul(emptyList(), lateral, 0.0, 0.0, emptyList())
            // Curve points with exact zero crossings inserted.
            val s = ArrayList<Double>(); val y = ArrayList<Double>()
            s += rows[0].station; y += 0.0
            for (i in 1 until rows.size) {
                val y0 = y.last(); val y1 = rows[i].accumulated; val s0 = s.last(); val s1 = rows[i].station
                if ((y0 > 0 && y1 < 0) || (y0 < 0 && y1 > 0)) {
                    s += s0 + (s1 - s0) * y0 / (y0 - y1); y += 0.0
                }
                s += s1; y += y1
            }
            val end = y.last()
            val waves = ArrayList<HaulWave>()
            var i = 0
            while (i < s.size - 1) {
                // Skip flat stretches on the line.
                if (y[i] == 0.0 && y[i + 1] == 0.0) { i++; continue }
                val forward = y[i + 1] > 0 || (y[i + 1] == 0.0 && y[i] > 0)
                var j = i + 1
                while (j < s.size - 1 && y[j] != 0.0) j++
                val last = j == s.size - 1 && y[j] != 0.0
                // In the last open wave only what lies beyond the end level is balanced.
                val level = if (last) abs(end) else 0.0
                var rise = 0.0; var area = 0.0
                for (k in i until j) {
                    val a = abs(y[k]); val b = abs(y[k + 1])
                    if (b > a) rise += max(0.0, b - max(a, level))
                    area += clipArea(a - level, b - level, s[k + 1] - s[k])
                }
                // Material above the end level rises and falls back: what rises is the volume moved.
                if (last) {
                    var fall = 0.0
                    for (k in i until j) { val a = abs(y[k]); val b = abs(y[k + 1]); if (b < a) fall += max(0.0, max(a, level) - max(b, level)) }
                    rise = min(rise, fall)
                }
                if (rise > 0) waves += HaulWave(s[i], s[j], rise, area, forward)
                i = j
            }
            val bands = ArrayList<HaulBand>()
            for (b in BANDS.indices) {
                val from = BANDS[b]; val to = BANDS.getOrNull(b + 1)
                val inBand = waves.filter { it.dmt >= from && (to == null || it.dmt < to) }
                if (inBand.isNotEmpty()) bands += HaulBand(from, to, inBand.sumOf { it.volume }, inBand.sumOf { it.moment })
            }
            return MassHaul(waves, lateral, max(0.0, end), max(0.0, -end), bands)
        }

        /** Area of the positive part of a straight segment from height a to b over width w. */
        private fun clipArea(a: Double, b: Double, w: Double): Double = when {
            a >= 0 && b >= 0 -> (a + b) / 2 * w
            a <= 0 && b <= 0 -> 0.0
            else -> { val p = max(a, b); val n = -min(a, b); p * p / (p + n) * w / 2 }
        }
    }
}
