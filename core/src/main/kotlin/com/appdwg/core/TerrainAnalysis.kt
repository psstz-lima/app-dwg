package com.appdwg.core

import kotlin.math.atan2
import kotlin.math.sqrt

/** Slope class (Embrapa relief classes, in %), with its colour and the plan area in it. */
data class SlopeClass(val from: Double, val to: Double?, val name: String, val color: Int, val area: Double)

/** Downhill flow on a grid: D8 direction of each cell and the area draining through it. */
class FlowGrid(val grid: ElevationGrid, /** Index of the next cell downhill, or −1 (pit, edge or no data). */ val next: IntArray, /** Cells draining through each cell, itself included. */ val count: IntArray) {
    val cellArea get() = grid.cellSize * grid.cellSize

    fun center(i: Int): Point2 {
        val c = i % grid.cols; val r = i / grid.cols
        return Point2(grid.bounds.minX + (c + 0.5) * grid.cellSize, grid.bounds.maxY - (r + 0.5) * grid.cellSize)
    }

    /** Cells draining straight into each cell, as compressed lists: those of cell i are items[start[i] until start[i + 1]]. */
    private val upstream: Pair<IntArray, IntArray> by lazy {
        val start = IntArray(next.size + 1)
        for (t in next) if (t >= 0) start[t + 1]++
        for (i in 1 until start.size) start[i] += start[i - 1]
        val items = IntArray(start.last())
        val fill = start.copyOf(next.size)
        for (i in next.indices) { val t = next[i]; if (t >= 0) items[fill[t]++] = i }
        start to items
    }

    fun upstreamIndex(): Pair<IntArray, IntArray> = upstream

    /** Segments from each cell to the next where the contributing area is at least [minArea] m², with that area. */
    fun channels(minArea: Double): List<Triple<Point2, Point2, Double>> {
        val min = (minArea / cellArea).toInt().coerceAtLeast(2)
        val out = ArrayList<Triple<Point2, Point2, Double>>()
        for (i in count.indices) if (count[i] >= min && next[i] >= 0) out += Triple(center(i), center(next[i]), count[i] * cellArea)
        return out
    }
}

object TerrainAnalysis {
    /** Embrapa relief classes. */
    val CLASSES = listOf(
        Triple(0.0, "Plano", 0xFF1B9E4B.toInt()),
        Triple(3.0, "Suave ondulado", 0xFF8BC34A.toInt()),
        Triple(8.0, "Ondulado", 0xFFFFEB3B.toInt()),
        Triple(20.0, "Forte ondulado", 0xFFFF9800.toInt()),
        Triple(45.0, "Montanhoso", 0xFFE53935.toInt()),
        Triple(75.0, "Escarpado", 0xFF6A1B9A.toInt()),
    )

    fun classOf(slopePercent: Double): Int {
        var k = 0
        while (k < CLASSES.size - 1 && slopePercent >= CLASSES[k + 1].first) k++
        return k
    }

    /** Slope in % of each cell by central differences; NaN where the cell or all its neighbours lack data. */
    fun slopes(g: ElevationGrid): FloatArray {
        val out = FloatArray(g.values.size) { Float.NaN }
        val d = g.cellSize
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val z = g[c, r]
            if (z.isNaN()) continue
            fun at(cc: Int, rr: Int): Float? = if (cc in 0 until g.cols && rr in 0 until g.rows) g[cc, rr].takeIf { !it.isNaN() } else null
            val e = at(c + 1, r); val w = at(c - 1, r); val n = at(c, r - 1); val s = at(c, r + 1)
            val dx = when { e != null && w != null -> (e - w) / (2 * d); e != null -> (e - z) / d; w != null -> (z - w) / d; else -> 0.0 }
            val dy = when { n != null && s != null -> (n - s) / (2 * d); n != null -> (n - z) / d; s != null -> (z - s) / d; else -> 0.0 }
            out[r * g.cols + c] = (sqrt(dx.toDouble() * dx + dy.toDouble() * dy) * 100).toFloat()
        }
        return out
    }

    /** Plan area of each slope class. */
    fun classes(g: ElevationGrid, slopes: FloatArray): List<SlopeClass> {
        val area = DoubleArray(CLASSES.size)
        val cell = g.cellSize * g.cellSize
        for (s in slopes) if (!s.isNaN()) area[classOf(s.toDouble())] += cell
        return CLASSES.mapIndexed { k, (from, name, color) -> SlopeClass(from, CLASSES.getOrNull(k + 1)?.first, tr(name), color, area[k]) }
    }

    /** Downhill direction (radians, counterclockwise from east) of each cell, NaN where flat or missing. */
    fun aspect(g: ElevationGrid, c: Int, r: Int): Double {
        val z = g[c, r]
        if (z.isNaN()) return Double.NaN
        fun at(cc: Int, rr: Int): Float = if (cc in 0 until g.cols && rr in 0 until g.rows) g[cc, rr].takeIf { !it.isNaN() } ?: z else z
        val dx = (at(c + 1, r) - at(c - 1, r)).toDouble()
        val dy = (at(c, r - 1) - at(c, r + 1)).toDouble()
        if (dx == 0.0 && dy == 0.0) return Double.NaN
        return atan2(-dy, -dx)
    }

    /**
     * D8 flow: each cell drains to its steepest lower neighbour after pits are filled to their spill
     * point, so streams run on to the edge of the terrain; the area through a cell is summed downstream.
     */
    fun flow(g: ElevationGrid, cancelled: () -> Boolean = { false }): FlowGrid? = Hydrology.flow(g, cancelled)
}
