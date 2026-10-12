package com.appdwg.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Difference between two surfaces, B minus A, on a regular grid over the area both cover.
 * Positive is fill (aterro: B above A), negative is cut (corte: B below A).
 */
class SurfaceDifference(
    val baseName: String,
    val otherName: String,
    /** B − A per cell; NaN where either surface is missing. Row 0 is the northern edge. */
    val grid: ElevationGrid,
    /** Volumes in m³ and plan areas in m², from the grid cells. */
    val cutVolume: Double,
    val fillVolume: Double,
    val cutArea: Double,
    val fillArea: Double,
    /** Largest cut (negative) and fill found. */
    val minDifference: Double,
    val maxDifference: Double,
    /** Difference that covers 98 % of the cells, for a color scale not ruined by a few spikes. */
    val typicalRange: Double,
) {
    val cellSize get() = grid.cellSize
    val netVolume get() = fillVolume - cutVolume
}

object SurfaceCompare {
    /**
     * Compares [b] against the base [a] on at most [maxCells] cells. Null when they do not overlap
     * or [cancelled] stops it.
     */
    fun difference(a: Surface, b: Surface, maxCells: Int = 2_000_000, cancelled: () -> Boolean = { false }): SurfaceDifference? {
        val ba = a.bounds ?: return null
        val bb = b.bounds ?: return null
        val region = Bounds(max(ba.minX, bb.minX), max(ba.minY, bb.minY), minOf(ba.maxX, bb.maxX), minOf(ba.maxY, bb.maxY))
        if (region.width <= 0 || region.height <= 0) return null
        val cell = sqrt(region.width * region.height / maxCells)
        val cols = max(1, ceil(region.width / cell).toInt())
        val rows = max(1, ceil(region.height / cell).toInt())
        // Square cells: the region grows slightly to whole cells.
        val r = Bounds(region.minX, region.maxY - rows * cell, region.minX + cols * cell, region.maxY)
        val ga = a.rasterize(r, cols, rows, cancelled) ?: return null
        val gb = b.rasterize(r, cols, rows, cancelled) ?: return null
        val diff = FloatArray(cols * rows) { Float.NaN }
        val cellArea = cell * cell
        var cut = 0.0; var fill = 0.0; var cutA = 0.0; var fillA = 0.0
        var lo = 0.0; var hi = 0.0
        val histogram = IntArray(HISTOGRAM_BINS)
        var count = 0
        var maxAbs = 0.0
        for (i in diff.indices) {
            val za = ga.values[i]; val zb = gb.values[i]
            if (za.isNaN() || zb.isNaN()) continue
            val d = (zb - za).toDouble()
            diff[i] = d.toFloat()
            if (d < 0) { cut -= d * cellArea; cutA += cellArea } else if (d > 0) { fill += d * cellArea; fillA += cellArea }
            if (d < lo) lo = d
            if (d > hi) hi = d
            maxAbs = max(maxAbs, abs(d))
            count++
        }
        if (count == 0) return null
        // 98th percentile of |B − A| from a histogram, so no sort of millions of values.
        if (maxAbs > 0) {
            for (d in diff) if (!d.isNaN()) histogram[((abs(d) / maxAbs) * (HISTOGRAM_BINS - 1)).toInt()]++
        }
        var acc = 0
        var bin = 0
        while (bin < HISTOGRAM_BINS - 1 && acc + histogram[bin] < count * 0.98) acc += histogram[bin++]
        val typical = if (maxAbs > 0) maxAbs * (bin + 1) / HISTOGRAM_BINS else 0.0
        return SurfaceDifference(a.name, b.name, ElevationGrid(r, cols, rows, diff), cut, fill, cutA, fillA, lo, hi, typical)
    }

    private const val HISTOGRAM_BINS = 1000
}
