package com.appdwg.core

/**
 * Design (finished grade) vertical profile built from points of vertical intersection.
 * Vertical curves are parabolic; circular curves are treated as parabolas of the same length,
 * which is the usual approximation for road grades.
 */
class Profile(val name: String, val pvis: List<Pvi>) {

    data class Pvi(
        val station: Double,
        val elevation: Double,
        /** Length of the vertical curve centred on this PVI, zero for a sharp grade break. */
        val curveLength: Double = 0.0,
    )

    val startStation get() = pvis.firstOrNull()?.station
    val endStation get() = pvis.lastOrNull()?.station

    /** Design elevation at [station], or null outside the profile. */
    fun elevationAt(station: Double): Double? {
        if (pvis.size < 2) return null
        if (station < pvis.first().station - 1e-9 || station > pvis.last().station + 1e-9) return null

        for (i in 1 until pvis.size - 1) {
            val p = pvis[i]
            val half = p.curveLength / 2
            if (half > 0 && station >= p.station - half && station <= p.station + half) {
                val g1 = grade(i - 1, i)
                val g2 = grade(i, i + 1)
                val x = station - (p.station - half)
                val zBvc = p.elevation - g1 * half
                return zBvc + g1 * x + (g2 - g1) * x * x / (2 * p.curveLength)
            }
        }

        var i = 0
        while (i < pvis.size - 2 && station > pvis[i + 1].station) i++
        return pvis[i].elevation + grade(i, i + 1) * (station - pvis[i].station)
    }

    /** Grade between two PVIs as a ratio (0.02 = 2 %). */
    fun grade(from: Int, to: Int): Double {
        val a = pvis[from]
        val b = pvis[to]
        val d = b.station - a.station
        return if (d == 0.0) 0.0 else (b.elevation - a.elevation) / d
    }
}
