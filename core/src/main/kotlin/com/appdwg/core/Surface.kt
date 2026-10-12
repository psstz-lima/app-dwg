package com.appdwg.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Triangulated (TIN) surface. Vertices are stored in flat arrays to keep large terrains compact. */
class Surface(
    val name: String,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val zs: DoubleArray,
    /** Three vertex indices per triangle. */
    val triangles: IntArray,
) {
    val vertexCount get() = xs.size
    val triangleCount get() = triangles.size / 3

    val bounds: Bounds? = if (xs.isEmpty()) null else Bounds(xs.min(), ys.min(), xs.max(), ys.max())

    val minElevation get() = zs.minOrNull()
    val maxElevation get() = zs.maxOrNull()

    private val grid: TriangleGrid? by lazy { bounds?.let { TriangleGrid(this, it) } }

    /** Interpolated elevation at [p], or null when the point is outside the surface. */
    fun elevationAt(p: Point2): Double? {
        val g = grid ?: return null
        var found: Double? = null
        g.forEachCandidate(p.x, p.y) { t ->
            val z = interpolate(t, p.x, p.y)
            if (z != null) {
                found = z
                false
            } else true
        }
        return found
    }

    /**
     * Samples the surface on a regular grid whose longer side has [maxSize] cells, for drawing.
     * Row 0 is the northern edge. Cells outside the surface are NaN.
     */
    fun rasterize(maxSize: Int = 1024): ElevationGrid? {
        val b = bounds ?: return null
        val cellSize = max(b.width, b.height) / maxSize
        if (cellSize <= 0) return null
        val cols = max(1, ceil(b.width / cellSize).toInt())
        val rows = max(1, ceil(b.height / cellSize).toInt())
        val values = FloatArray(cols * rows) { Float.NaN }
        for (t in 0 until triangleCount) {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (k in 0..2) {
                val i = triangles[3 * t + k]
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
                minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            }
            // Cell centres inside the triangle's bounding box.
            val c0 = max(0, ceil((minX - b.minX) / cellSize - 0.5).toInt())
            val c1 = min(cols - 1, floor((maxX - b.minX) / cellSize - 0.5).toInt())
            val r0 = max(0, ceil((b.maxY - maxY) / cellSize - 0.5).toInt())
            val r1 = min(rows - 1, floor((b.maxY - minY) / cellSize - 0.5).toInt())
            for (r in r0..r1) {
                val y = b.maxY - (r + 0.5) * cellSize
                for (c in c0..c1) {
                    val x = b.minX + (c + 0.5) * cellSize
                    val z = interpolate(t, x, y) ?: continue
                    values[r * cols + c] = z.toFloat()
                }
            }
        }
        return ElevationGrid(Bounds(b.minX, b.maxY - rows * cellSize, b.minX + cols * cellSize, b.maxY), cols, rows, values)
    }

    internal fun interpolate(t: Int, x: Double, y: Double): Double? {
        val i0 = triangles[3 * t]
        val i1 = triangles[3 * t + 1]
        val i2 = triangles[3 * t + 2]
        val x0 = xs[i0]; val y0 = ys[i0]
        val x1 = xs[i1]; val y1 = ys[i1]
        val x2 = xs[i2]; val y2 = ys[i2]
        val det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (det == 0.0) return null
        val l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det
        val l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        val l2 = 1 - l0 - l1
        val eps = -1e-9
        if (l0 < eps || l1 < eps || l2 < eps) return null
        return l0 * zs[i0] + l1 * zs[i1] + l2 * zs[i2]
    }
}

/** Elevations sampled on a regular grid; row 0 is the northern edge of [bounds]. */
class ElevationGrid(val bounds: Bounds, val cols: Int, val rows: Int, val values: FloatArray) {
    val cellSize get() = bounds.width / cols
    operator fun get(col: Int, row: Int) = values[row * cols + col]
}

/**
 * Uniform grid over the surface so a point lookup only tests nearby triangles. Cell contents are
 * stored in one flat array (compressed rows) so million-triangle terrains stay small in memory.
 */
private class TriangleGrid(private val s: Surface, private val b: Bounds) {
    private val cols: Int
    private val rows: Int
    private val cellW: Double
    private val cellH: Double
    private val cellStart: IntArray
    private val items: IntArray

    init {
        // Roughly two triangles per cell keeps lookups short without a huge index.
        val n = max(1, s.triangleCount)
        val side = max(1, sqrt(n / 2.0).toInt())
        cols = side
        rows = side
        cellW = max(b.width / cols, 1e-9)
        cellH = max(b.height / rows, 1e-9)

        val counts = IntArray(cols * rows + 1)
        forEachCell { cell, _ -> counts[cell + 1]++ }
        for (i in 1 until counts.size) counts[i] += counts[i - 1]
        cellStart = counts
        items = IntArray(counts.last())
        val fill = counts.copyOf(cols * rows)
        forEachCell { cell, t -> items[fill[cell]++] = t }
    }

    private inline fun forEachCell(action: (cell: Int, triangle: Int) -> Unit) {
        for (t in 0 until s.triangleCount) {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (k in 0..2) {
                val i = s.triangles[3 * t + k]
                minX = min(minX, s.xs[i]); maxX = max(maxX, s.xs[i])
                minY = min(minY, s.ys[i]); maxY = max(maxY, s.ys[i])
            }
            for (cy in row(minY)..row(maxY)) for (cx in col(minX)..col(maxX)) action(cy * cols + cx, t)
        }
    }

    private fun col(x: Double) = floor((x - b.minX) / cellW).toInt().coerceIn(0, cols - 1)
    private fun row(y: Double) = floor((y - b.minY) / cellH).toInt().coerceIn(0, rows - 1)

    /** Calls [action] for each triangle near (x, y) until it returns false. */
    inline fun forEachCandidate(x: Double, y: Double, action: (Int) -> Boolean) {
        if (x < b.minX || x > b.maxX || y < b.minY || y > b.maxY) return
        val cell = row(y) * cols + col(x)
        for (i in cellStart[cell] until cellStart[cell + 1]) if (!action(items[i])) return
    }
}
