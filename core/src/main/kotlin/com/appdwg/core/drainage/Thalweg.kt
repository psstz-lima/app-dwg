package com.appdwg.core.drainage

import com.appdwg.core.Idf
import com.appdwg.core.OutletSuggestion
import com.appdwg.core.Point2
import kotlin.math.hypot

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

    /**
     * Ends of a short barrel through [x], [y], perpendicular to the nearest segment of [line].
     * A north-south fallback is used when the line has no length.
     */
    fun barrelAcross(line: List<Point2>, x: Double, y: Double, half: Double = 7.5): Pair<Point2, Point2> {
        var best = Double.POSITIVE_INFINITY
        var nx = 0.0
        var ny = 1.0
        for (i in 0 until line.size - 1) {
            val ax = line[i].x
            val ay = line[i].y
            val dx = line[i + 1].x - ax
            val dy = line[i + 1].y - ay
            val len = hypot(dx, dy)
            if (len < 1e-6) continue
            val t = (((x - ax) * dx + (y - ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val gap = hypot(x - px, y - py)
            if (gap < best) {
                best = gap
                nx = -dy / len
                ny = dx / len
            }
        }
        return Point2(x - nx * half, y - ny * half) to Point2(x + nx * half, y + ny * half)
    }
}
