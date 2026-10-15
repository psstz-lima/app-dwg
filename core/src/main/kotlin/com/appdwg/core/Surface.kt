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

    /** Lookup index given from a cache, used instead of building one. */
    @Volatile private var cachedGrid: TriangleGrid? = null
    private val builtGrid: TriangleGrid? by lazy { bounds?.let { TriangleGrid(this, it) } }
    private val grid: TriangleGrid? get() = cachedGrid ?: builtGrid

    /** The lookup index as plain arrays (cells per side, cell starts, triangle list), for caching. */
    fun indexArrays(): Triple<Int, IntArray, IntArray>? = grid?.let { Triple(it.side, it.cellStart, it.items) }

    /** Uses an index saved by [indexArrays] for these same points and triangles. */
    fun useIndex(side: Int, cellStart: IntArray, items: IntArray) {
        val b = bounds ?: return
        cachedGrid = TriangleGrid(this, b, side, cellStart, items)
    }

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
        val grid = Bounds(b.minX, b.maxY - rows * cellSize, b.minX + cols * cellSize, b.maxY)
        val values = FloatArray(cols * rows) { Float.NaN }
        fill(values, grid, cols, rows, cellSize, cellSize, triangleCount, { it }) { false }
        return ElevationGrid(grid, cols, rows, values)
    }

    /**
     * Samples the part of the surface inside [region] on a [cols] x [rows] grid, for a detailed
     * picture of a zoomed-in view. Only triangles near the region are visited. Stops early, returning
     * null, when [cancelled] turns true.
     */
    fun rasterize(region: Bounds, cols: Int, rows: Int, cancelled: () -> Boolean = { false }): ElevationGrid? {
        if (grid == null) return null
        if (cols <= 0 || rows <= 0 || region.width <= 0 || region.height <= 0) return null
        val cellW = region.width / cols
        val cellH = region.height / rows
        val values = FloatArray(cols * rows) { Float.NaN }
        val list = trianglesIn(region)
        val ok = fill(values, region, cols, rows, cellW, cellH, list.size, { list[it] }, cancelled)
        return if (ok) ElevationGrid(region, cols, rows, values) else null
    }

    /** The triangles that may overlap [region] (all of them when null), each once, in index order when null. */
    fun trianglesIn(region: Bounds?): IntArray {
        if (region == null) return IntArray(triangleCount) { it }
        var list = IntArray(1024)
        var n = 0
        forEachTriangle(region) { t ->
            if (n == list.size) list = list.copyOf(n * 2)
            list[n++] = t
            true
        }
        return list.copyOf(n)
    }

    /**
     * Calls [action] once for each triangle that may overlap [region] (all of them when null) until
     * it returns false; returns false when stopped that way.
     */
    fun forEachTriangle(region: Bounds?, action: (Int) -> Boolean): Boolean {
        if (region == null) {
            for (t in 0 until triangleCount) if (!action(t)) return false
            return true
        }
        val g = grid ?: return true
        val seen = BooleanArray(triangleCount)
        return g.forEachTriangleIn(region) { t ->
            if (seen[t]) true else { seen[t] = true; action(t) }
        }
    }

    /**
     * Writes into [values] the height at each cell centre of the [cols] x [rows] grid over [region]
     * covered by one of the [count] triangles given by [triangle], on all cores. A cell centre on
     * an edge shared by two triangles gets the height from either; they agree to rounding.
     */
    private fun fill(
        values: FloatArray, region: Bounds, cols: Int, rows: Int, cellW: Double, cellH: Double,
        count: Int, triangle: (Int) -> Int, cancelled: () -> Boolean,
    ): Boolean = Parallel.forChunks(count, 2048) { from, until ->
        if (cancelled()) return@forChunks false
        for (k in from until until) {
            if ((k - from) % 4096 == 4095 && cancelled()) return@forChunks false
            val t = triangle(k)
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (j in 0..2) {
                val i = triangles[3 * t + j]
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
                minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            }
            // Cell centres inside the triangle's bounding box.
            val c0 = max(0, ceil((minX - region.minX) / cellW - 0.5).toInt())
            val c1 = min(cols - 1, floor((maxX - region.minX) / cellW - 0.5).toInt())
            val r0 = max(0, ceil((region.maxY - maxY) / cellH - 0.5).toInt())
            val r1 = min(rows - 1, floor((region.maxY - minY) / cellH - 0.5).toInt())
            for (r in r0..r1) {
                val y = region.maxY - (r + 0.5) * cellH
                for (c in c0..c1) {
                    val x = region.minX + (c + 0.5) * cellW
                    val z = interpolate(t, x, y) ?: continue
                    values[r * cols + c] = z.toFloat()
                }
            }
        }
        true
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

    /** Height of triangle [t]'s plane at (x, y), including points outside the triangle. */
    fun zOn(t: Int, x: Double, y: Double): Double {
        val i0 = triangles[3 * t]
        val i1 = triangles[3 * t + 1]
        val i2 = triangles[3 * t + 2]
        val x0 = xs[i0]; val y0 = ys[i0]
        val x1 = xs[i1]; val y1 = ys[i1]
        val x2 = xs[i2]; val y2 = ys[i2]
        val det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (det == 0.0) return zs[i0]
        val l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det
        val l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        return l0 * zs[i0] + l1 * zs[i1] + (1 - l0 - l1) * zs[i2]
    }

    /**
     * First intersection of the ray (origin + t · direction, t > 0) with this TIN.
     * Walks the plan grid along the ray, so only triangles in the crossed cells are tested.
     */
    fun rayHit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): RayHit? =
        grid?.rayHit(ox, oy, oz, dx, dy, dz)

    /**
     * Triangles whose cells overlap [region], possibly more than once. No full-surface scratch
     * array, so a tight region on a huge TIN stays cheap. Callers that need each triangle once
     * dedupe the few indices they see.
     */
    fun forEachNear(region: Bounds, action: (Int) -> Unit) {
        val g = grid ?: return
        if (region.maxX < region.minX || region.maxY < region.minY) return
        g.forEachTriangleIn(region) { t -> action(t); true }
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
private class TriangleGrid(private val s: Surface, private val b: Bounds, val side: Int, val cellStart: IntArray, val items: IntArray) {
    private val cols = side
    private val rows = side
    private val cellW = max(b.width / cols, 1e-9)
    private val cellH = max(b.height / rows, 1e-9)

    /** Builds the index: roughly two triangles per cell keeps lookups short without a huge index. */
    constructor(s: Surface, b: Bounds) : this(s, b, max(1, sqrt(max(1, s.triangleCount) / 2.0).toInt()))

    private constructor(s: Surface, b: Bounds, side: Int) : this(s, b, side, build(s, b, side))

    private constructor(s: Surface, b: Bounds, side: Int, built: Pair<IntArray, IntArray>) : this(s, b, side, built.first, built.second)

    companion object {
        /** Calls [action] for each cell each triangle's bounding box touches. */
        private inline fun forEachCell(s: Surface, b: Bounds, side: Int, cellW: Double, cellH: Double, action: (cell: Int, triangle: Int) -> Unit) {
            for (t in 0 until s.triangleCount) {
                var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
                for (k in 0..2) {
                    val i = s.triangles[3 * t + k]
                    minX = min(minX, s.xs[i]); maxX = max(maxX, s.xs[i])
                    minY = min(minY, s.ys[i]); maxY = max(maxY, s.ys[i])
                }
                val c0 = floor((minX - b.minX) / cellW).toInt().coerceIn(0, side - 1); val c1 = floor((maxX - b.minX) / cellW).toInt().coerceIn(0, side - 1)
                val r0 = floor((minY - b.minY) / cellH).toInt().coerceIn(0, side - 1); val r1 = floor((maxY - b.minY) / cellH).toInt().coerceIn(0, side - 1)
                for (cy in r0..r1) for (cx in c0..c1) action(cy * side + cx, t)
            }
        }

        private fun build(s: Surface, b: Bounds, side: Int): Pair<IntArray, IntArray> {
            val cellW = max(b.width / side, 1e-9)
            val cellH = max(b.height / side, 1e-9)
            val counts = IntArray(side * side + 1)
            forEachCell(s, b, side, cellW, cellH) { cell, _ -> counts[cell + 1]++ }
            for (i in 1 until counts.size) counts[i] += counts[i - 1]
            val items = IntArray(counts.last())
            val fill = counts.copyOf(side * side)
            forEachCell(s, b, side, cellW, cellH) { cell, t -> items[fill[cell]++] = t }
            return counts to items
        }
    }

    private fun col(x: Double) = floor((x - b.minX) / cellW).toInt().coerceIn(0, cols - 1)
    private fun row(y: Double) = floor((y - b.minY) / cellH).toInt().coerceIn(0, rows - 1)

    /**
     * Calls [action] for the triangles in the cells overlapping [region] (a triangle can come more
     * than once) until it returns false; returns false when stopped that way.
     */
    inline fun forEachTriangleIn(region: Bounds, action: (Int) -> Boolean): Boolean {
        if (region.maxX < b.minX || region.minX > b.maxX || region.maxY < b.minY || region.minY > b.maxY) return true
        for (cy in row(region.minY)..row(region.maxY)) for (cx in col(region.minX)..col(region.maxX)) {
            val cell = cy * cols + cx
            for (i in cellStart[cell] until cellStart[cell + 1]) if (!action(items[i])) return false
        }
        return true
    }

    /** Calls [action] for each triangle near (x, y) until it returns false. */
    inline fun forEachCandidate(x: Double, y: Double, action: (Int) -> Boolean) {
        if (x < b.minX || x > b.maxX || y < b.minY || y > b.maxY) return
        val cell = row(y) * cols + col(x)
        for (i in cellStart[cell] until cellStart[cell + 1]) if (!action(items[i])) return
    }

    /** Nearest hit of the ray with a triangle in the cells the ray crosses in plan. */
    fun rayHit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): RayHit? {
        val span = slab(ox, oy, dx, dy) ?: return null
        val tStart = max(span.first, 0.0)
        val tEnd = span.second
        if (tEnd < tStart) return null
        var bestT = Double.POSITIVE_INFINITY
        var best: RayHit? = null
        fun consider(tri: Int) {
            val i0 = s.triangles[3 * tri]
            val i1 = s.triangles[3 * tri + 1]
            val i2 = s.triangles[3 * tri + 2]
            val t = rayTriangle(
                ox, oy, oz, dx, dy, dz,
                s.xs[i0], s.ys[i0], s.zs[i0],
                s.xs[i1], s.ys[i1], s.zs[i1],
                s.xs[i2], s.ys[i2], s.zs[i2],
            ) ?: return
            if (t < bestT) {
                bestT = t
                best = RayHit(ox + t * dx, oy + t * dy, oz + t * dz, t, tri)
            }
        }
        if (dx * dx + dy * dy < 1e-24) {
            if (ox < b.minX || ox > b.maxX || oy < b.minY || oy > b.maxY) return null
            val cell = row(oy) * cols + col(ox)
            for (i in cellStart[cell] until cellStart[cell + 1]) consider(items[i])
            return best
        }
        var cx = col(ox + tStart * dx)
        var cy = row(oy + tStart * dy)
        val stepX = if (dx >= 0) 1 else -1
        val stepY = if (dy >= 0) 1 else -1
        val tDeltaX = if (dx != 0.0) kotlin.math.abs(cellW / dx) else Double.POSITIVE_INFINITY
        val tDeltaY = if (dy != 0.0) kotlin.math.abs(cellH / dy) else Double.POSITIVE_INFINITY
        var tMaxX = if (dx != 0.0) {
            val edge = b.minX + (if (stepX > 0) cx + 1 else cx) * cellW
            (edge - ox) / dx
        } else Double.POSITIVE_INFINITY
        var tMaxY = if (dy != 0.0) {
            val edge = b.minY + (if (stepY > 0) cy + 1 else cy) * cellH
            (edge - oy) / dy
        } else Double.POSITIVE_INFINITY
        var tCell = tStart
        val limit = cols + rows + 3
        var steps = 0
        while (steps++ <= limit && tCell <= tEnd + 1e-9 && tCell < bestT) {
            val cell = cy * cols + cx
            for (i in cellStart[cell] until cellStart[cell + 1]) consider(items[i])
            if (tMaxX < tMaxY) {
                tCell = tMaxX
                cx += stepX
                if (cx !in 0 until cols) break
                tMaxX += tDeltaX
            } else {
                tCell = tMaxY
                cy += stepY
                if (cy !in 0 until rows) break
                tMaxY += tDeltaY
            }
        }
        return best
    }

    /** Ray parameters where the plan line enters and leaves the surface bounds. */
    private fun slab(ox: Double, oy: Double, dx: Double, dy: Double): Pair<Double, Double>? {
        var t0 = Double.NEGATIVE_INFINITY
        var t1 = Double.POSITIVE_INFINITY
        fun axis(o: Double, d: Double, minV: Double, maxV: Double): Boolean {
            if (kotlin.math.abs(d) < 1e-15) return o in minV..maxV
            var a = (minV - o) / d
            var c = (maxV - o) / d
            if (a > c) { val s = a; a = c; c = s }
            if (a > t0) t0 = a
            if (c < t1) t1 = c
            return t0 <= t1
        }
        if (!axis(ox, dx, b.minX, b.maxX) || !axis(oy, dy, b.minY, b.maxY)) return null
        return t0 to t1
    }

    private fun rayTriangle(
        ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double,
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, cx: Double, cy: Double, cz: Double,
    ): Double? {
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (kotlin.math.abs(det) < 1e-12) return null
        val inv = 1.0 / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < -1e-8 || u > 1.0 + 1e-8) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < -1e-8 || u + v > 1.0 + 1e-8) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (t > 1e-8) t else null
    }
}
