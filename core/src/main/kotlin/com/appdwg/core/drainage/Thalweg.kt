package com.appdwg.core.drainage

import com.appdwg.core.Idf
import com.appdwg.core.OutletSuggestion

/** A culvert suggested where a thalweg crosses a road or another line. */
data class ThalwegCulvert(
    val x: Double,
    val y: Double,
    val areaM2: Double,
    val discharge: Double,
    val barrel: CulvertBarrel,
    val head: CulvertHead,
)

/**
 * Turns stream crossings into culverts. The crossing list comes from the terrain flow grid;
 * this step only sizes each one, so it stays cheap after the grid exists.
 */
object ThalwegCulverts {
    fun suggest(
        crossings: List<OutletSuggestion>,
        runoff: Double,
        idf: Idf,
        years: Double,
        minutes: Double,
        length: Double = 15.0,
        slope: Double = 0.01,
        allowable: Double = 2.0,
    ): List<ThalwegCulvert> = crossings.map { crossing ->
        val peak = DrainageFlow.peak(crossing.area, runoff, idf, years, minutes)
        val (barrel, head) = Culvert.size(peak.local, length, slope, 0.0, allowable)
        ThalwegCulvert(crossing.point.x, crossing.point.y, crossing.area, peak.local, barrel, head)
    }
}
