package com.appdwg.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a stockpile or an area is measured against. */
sealed interface VolumeBase {
    /** Horizontal plane at a given height: "volume até a cota". */
    data class Elevation(val z: Double) : VolumeBase
    /** Horizontal plane at the lowest point of the boundary (toe of a pile). */
    data object LowestEdge : VolumeBase
    /** Horizontal plane at the mean height of the boundary. */
    data object MeanEdge : VolumeBase
    /** Inclined plane fitted to the boundary heights by least squares (pile on sloping ground). */
    data object PlaneEdge : VolumeBase
    /** Another surface underneath (the ground before the pile, a design). */
    data class Other(val surface: Surface) : VolumeBase
}

class VolumeResult(
    val surfaceName: String,
    /** Material above the base (the pile; cut down to the height). */
    val above: Double,
    /** Room below the base (holes; fill up to the height). */
    val below: Double,
    val aboveArea: Double,
    val belowArea: Double,
    /** Plan area measured: the polygon, or the surface where there is no polygon. */
    val area: Double,
    /** Height of a flat base, or null for a plane or a surface. */
    val baseZ: Double?,
    /** Inclined base: z = baseZ0 + slopeX (x − cx) + slopeY (y − cy). */
    val plane: DoubleArray?,
    val minSurface: Double,
    val maxSurface: Double,
    val cellSize: Double,
) {
    val net get() = above - below
}

object Volumes {
    /**
     * Volume of [surface] against [base] inside [polygon] (closed, in plan), or over the whole
     * surface when it is null, on about [maxCells] square cells. Null when nothing overlaps.
     */
    fun of(surface: Surface, polygon: List<Point2>?, base: VolumeBase, maxCells: Int = 1_000_000, cancelled: () -> Boolean = { false }): VolumeResult? {
        val sb = surface.bounds ?: return null
        val region = if (polygon != null && polygon.size >= 3) {
            val pb = Bounds(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })
            Bounds(max(sb.minX, pb.minX), max(sb.minY, pb.minY), min(sb.maxX, pb.maxX), min(sb.maxY, pb.maxY))
        } else sb
        if (region.width <= 0 || region.height <= 0) return null
        val cell = sqrt(region.width * region.height / maxCells)
        val cols = max(1, ceil(region.width / cell).toInt())
        val rows = max(1, ceil(region.height / cell).toInt())
        val r = Bounds(region.minX, region.maxY - rows * cell, region.minX + cols * cell, region.maxY)
        val g = surface.rasterize(r, cols, rows, cancelled) ?: return null
        val inside = polygon?.takeIf { it.size >= 3 }?.let { mask(it, r, cols, rows, cell) }
        val under = (base as? VolumeBase.Other)?.surface?.rasterize(r, cols, rows, cancelled)

        // Boundary heights for the edge-based bases: along the polygon, or the surface's own rim.
        val edge: List<Point3> = when (base) {
            VolumeBase.LowestEdge, VolumeBase.MeanEdge, VolumeBase.PlaneEdge ->
                if (inside != null) sampleEdge(surface, polygon, cell) else rim(surface)
            else -> emptyList()
        }
        if (base !is VolumeBase.Elevation && base !is VolumeBase.Other && edge.isEmpty()) return null
        var flat: Double? = null
        var plane: DoubleArray? = null
        when (base) {
            is VolumeBase.Elevation -> flat = base.z
            VolumeBase.LowestEdge -> flat = edge.minOf { it.z }
            VolumeBase.MeanEdge -> flat = edge.map { it.z }.average()
            VolumeBase.PlaneEdge -> plane = fitPlane(edge) ?: doubleArrayOf(edge.map { it.z }.average(), 0.0, 0.0, 0.0, 0.0)
            is VolumeBase.Other -> {}
        }

        val cellArea = cell * cell
        var above = 0.0; var below = 0.0; var aA = 0.0; var bA = 0.0; var area = 0.0
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (row in 0 until rows) {
            if (cancelled()) return null
            val y = r.maxY - (row + 0.5) * cell
            for (col in 0 until cols) {
                val i = row * cols + col
                if (inside != null && !inside[i]) continue
                val z = g.values[i]
                if (z.isNaN()) continue
                val x = r.minX + (col + 0.5) * cell
                val zb = when {
                    flat != null -> flat
                    plane != null -> plane[0] + plane[1] * (x - plane[3]) + plane[2] * (y - plane[4])
                    else -> under!!.values[i].toDouble().takeIf { !it.isNaN() } ?: continue
                }
                val d = z - zb
                area += cellArea
                if (d > 0) { above += d * cellArea; aA += cellArea } else if (d < 0) { below -= d * cellArea; bA += cellArea }
                lo = min(lo, z.toDouble()); hi = max(hi, z.toDouble())
            }
        }
        if (area == 0.0) return null
        return VolumeResult(surface.name, above, below, aA, bA, area, flat, plane, lo, hi, cell)
    }

    /** Cell centres inside the polygon, by scanlines (even–odd rule). */
    private fun mask(poly: List<Point2>, r: Bounds, cols: Int, rows: Int, cell: Double): BooleanArray {
        val m = BooleanArray(cols * rows)
        val xs = DoubleArray(poly.size)
        for (row in 0 until rows) {
            val y = r.maxY - (row + 0.5) * cell
            var n = 0
            for (k in poly.indices) {
                val a = poly[k]; val b = poly[(k + 1) % poly.size]
                if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) xs[n++] = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
            }
            xs.sort(0, n)
            var k = 0
            while (k + 1 < n) {
                val c0 = ceil((xs[k] - r.minX) / cell - 0.5).toInt().coerceAtLeast(0)
                val c1 = (kotlin.math.floor((xs[k + 1] - r.minX) / cell - 0.5).toInt()).coerceAtMost(cols - 1)
                for (c in c0..c1) m[row * cols + c] = true
                k += 2
            }
        }
        return m
    }

    /** Surface heights along the polygon, about one sample per cell. */
    private fun sampleEdge(s: Surface, poly: List<Point2>, step: Double): List<Point3> {
        val out = ArrayList<Point3>()
        for (k in poly.indices) {
            val a = poly[k]; val b = poly[(k + 1) % poly.size]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val n = max(1, ceil(len / step).toInt())
            for (i in 0 until n) {
                val p = Point2(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
                s.elevationAt(p)?.let { out += Point3(p.x, p.y, it) }
            }
        }
        return out
    }

    /** Vertices on the outer edge of the TIN: edges that belong to one triangle only. */
    fun rim(s: Surface): List<Point3> {
        val count = HashMap<Long, Int>()
        val t = s.triangles
        fun key(a: Int, b: Int) = if (a < b) a.toLong() shl 32 or b.toLong() else b.toLong() shl 32 or a.toLong()
        for (i in t.indices step 3) for (e in 0..2) { val k = key(t[i + e], t[i + (e + 1) % 3]); count[k] = (count[k] ?: 0) + 1 }
        val ids = HashSet<Int>()
        for ((k, c) in count) if (c == 1) { ids += (k shr 32).toInt(); ids += (k and 0xffffffffL).toInt() }
        return ids.map { Point3(s.xs[it], s.ys[it], s.zs[it]) }
    }

    /** z = a + b (x − cx) + c (y − cy) through the points; null when they lie on a line. */
    private fun fitPlane(p: List<Point3>): DoubleArray? {
        if (p.size < 3) return null
        val cx = p.map { it.x }.average(); val cy = p.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sxz = 0.0; var syz = 0.0
        val mz = p.map { it.z }.average()
        for (q in p) {
            val x = q.x - cx; val y = q.y - cy; val z = q.z - mz
            sxx += x * x; syy += y * y; sxy += x * y; sxz += x * z; syz += y * z
        }
        val det = sxx * syy - sxy * sxy
        if (det <= 1e-9 * (sxx * syy).coerceAtLeast(1e-12)) return null
        val b = (sxz * syy - syz * sxy) / det
        val c = (syz * sxx - sxz * sxy) / det
        return doubleArrayOf(mz, b, c, cx, cy)
    }
}
