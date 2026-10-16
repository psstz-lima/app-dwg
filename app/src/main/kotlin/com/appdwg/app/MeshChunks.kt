package com.appdwg.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A surface's triangles sorted into a grid of plan cells, so a picture tile draws only the
 * triangles under it. Without this every tile drew the whole TIN again: a million triangles and
 * thirty tiles is thirty million triangles a frame, which is what made 3D Máxima stall.
 *
 * [indices] is the surface's index buffer reordered cell by cell, row by row. Cell c holds
 * [count]`[c]` indices from [first]`[c]`. [boxes] has min x, min y, max x, max y of every vertex
 * of the cell's triangles, so a triangle that crosses a cell edge is still found.
 */
internal class MeshChunks private constructor(
    val indices: IntArray,
    val first: IntArray,
    val count: IntArray,
    val boxes: FloatArray,
) {
    val cells: Int get() = first.size

    /**
     * Index ranges, as (first index, index count) pairs, of the cells that touch the rectangle
     * from [left] to [left] + [width] in x and from [top] − [height] to [top] in y. Neighbouring
     * cells come back as one range.
     */
    fun ranges(left: Float, top: Float, width: Float, height: Float): IntArray {
        val x0 = min(left, left + width)
        val x1 = max(left, left + width)
        val y0 = min(top, top - height)
        val y1 = max(top, top - height)
        var out = IntArray(16)
        var n = 0
        var runFirst = -1
        var runCount = 0
        for (c in 0 until cells) {
            val k = count[c]
            if (k == 0) continue
            val b = c * 4
            val hit = boxes[b] <= x1 && boxes[b + 2] >= x0 && boxes[b + 1] <= y1 && boxes[b + 3] >= y0
            if (!hit) continue
            if (runFirst >= 0 && runFirst + runCount == first[c]) {
                runCount += k
                continue
            }
            if (runFirst >= 0) {
                if (n + 2 > out.size) out = out.copyOf(out.size * 2)
                out[n++] = runFirst
                out[n++] = runCount
            }
            runFirst = first[c]
            runCount = k
        }
        if (runFirst >= 0) {
            if (n + 2 > out.size) out = out.copyOf(out.size * 2)
            out[n++] = runFirst
            out[n++] = runCount
        }
        return out.copyOf(n)
    }

    companion object {
        /** About this many triangles per cell. Fewer cells would draw more outside a tile; more, more calls. */
        private const val TRIANGLES_PER_CELL = 2_000
        private const val MAX_SIDE = 48

        /** Cells across and down for [triangles] triangles. */
        fun side(triangles: Int): Int =
            sqrt(triangles.coerceAtLeast(0) / TRIANGLES_PER_CELL.toDouble()).toInt().coerceIn(1, MAX_SIDE)

        /** [vertices] holds [stride] floats per vertex, x and y first. [indices] is three per triangle. */
        fun build(vertices: FloatArray, stride: Int, indices: IntArray): MeshChunks {
            val triangles = indices.size / 3
            val vertexCount = if (stride > 0) vertices.size / stride else 0
            if (triangles == 0 || vertexCount == 0) {
                return MeshChunks(indices.copyOf(triangles * 3), intArrayOf(0), intArrayOf(triangles * 3), floatArrayOf(0f, 0f, 0f, 0f))
            }
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (v in 0 until vertexCount) {
                val x = vertices[v * stride]
                val y = vertices[v * stride + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val side = side(triangles)
            val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f
            val spanY = (maxY - minY).takeIf { it > 0f } ?: 1f
            val cellOf = IntArray(triangles)
            val sizes = IntArray(side * side)
            for (t in 0 until triangles) {
                var cx = 0f
                var cy = 0f
                for (j in 0 until 3) {
                    val v = indices[t * 3 + j].coerceIn(0, vertexCount - 1)
                    cx += vertices[v * stride]
                    cy += vertices[v * stride + 1]
                }
                cx /= 3f
                cy /= 3f
                val col = ((cx - minX) / spanX * side).toInt().coerceIn(0, side - 1)
                val row = ((cy - minY) / spanY * side).toInt().coerceIn(0, side - 1)
                val cell = row * side + col
                cellOf[t] = cell
                sizes[cell]++
            }
            val first = IntArray(side * side)
            var at = 0
            for (c in sizes.indices) {
                first[c] = at
                at += sizes[c] * 3
            }
            val fill = first.copyOf()
            val out = IntArray(triangles * 3)
            val boxes = FloatArray(side * side * 4)
            for (c in 0 until side * side) {
                boxes[c * 4] = Float.POSITIVE_INFINITY
                boxes[c * 4 + 1] = Float.POSITIVE_INFINITY
                boxes[c * 4 + 2] = Float.NEGATIVE_INFINITY
                boxes[c * 4 + 3] = Float.NEGATIVE_INFINITY
            }
            for (t in 0 until triangles) {
                val c = cellOf[t]
                val b = c * 4
                for (j in 0 until 3) {
                    val index = indices[t * 3 + j]
                    out[fill[c]++] = index
                    val v = index.coerceIn(0, vertexCount - 1)
                    val x = vertices[v * stride]
                    val y = vertices[v * stride + 1]
                    if (x < boxes[b]) boxes[b] = x
                    if (y < boxes[b + 1]) boxes[b + 1] = y
                    if (x > boxes[b + 2]) boxes[b + 2] = x
                    if (y > boxes[b + 3]) boxes[b + 3] = y
                }
            }
            return MeshChunks(out, first, IntArray(side * side) { sizes[it] * 3 }, boxes)
        }
    }
}
