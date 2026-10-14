package com.appdwg.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One way to build a platform on the area the user drew, with its earthwork. */
class PlatformOption(
    val kind: Kind,
    /** Elevation of the plane at the outline's reference vertex, as [PlatformDesign.baseElevation]. */
    val baseElevation: Double,
    val gradePercent: Double,
    /** Azimuth the platform rises towards, degrees from north. */
    val gradeAzimuth: Double,
    val cut: Double,
    val fill: Double,
    /** Highest slope from the platform edge to the ground, m. */
    val maxSlopeHeight: Double,
    /** Plan area from the platform edge out to the daylight line, m². */
    val footprint: Double,
) {
    enum class Kind { FLAT_BALANCED, SLOPED_BALANCED, SLOPED_LEAST, FLAT_LEAST }

    /** Fill minus cut, m³. */
    val net get() = fill - cut
}

/**
 * Platform shapes for an outline the user drew: flat or following the ground's fall, with cut and
 * fill balanced or with the least earthwork. Each is designed with the given slopes and measured
 * against the terrain, so the numbers are the ones the platform tool will show.
 */
object PlatformOptions {
    /** Earthwork of a platform: cut, fill, highest slope and footprint. */
    class Earthwork(val cut: Double, val fill: Double, val maxSlopeHeight: Double, val footprint: Double)

    /**
     * The options for [outline] on [terrain]. [maxGrade] caps the fall a sloped platform may take
     * (percent); [swell] is how much cut is needed per m³ of fill (1 = none).
     */
    fun suggest(
        outline: List<Point2>,
        terrain: Surface,
        cut: SlopeRule,
        fill: SlopeRule,
        maxGrade: Double = 2.0,
        swell: Double = 1.0,
        cells: Int = 150_000,
        cancelled: () -> Boolean = { false },
    ): List<PlatformOption> {
        require(outline.size >= 3)
        val ground = outline.mapNotNull { terrain.elevationAt(it) } + inside(outline, terrain)
        if (ground.isEmpty()) return emptyList()
        val lo = ground.min(); val hi = ground.max()
        val fall = groundFall(outline, terrain)
        val grade = min(fall?.first ?: 0.0, maxGrade)
        val azimuth = fall?.second ?: 0.0
        val sloped = grade >= 0.3

        fun work(base: Double, g: Double): Earthwork? {
            if (cancelled()) return null
            return earthwork(PlatformDesign(outline, base, g, azimuth, null, cut, fill, step = 4.0), terrain, cells)
        }
        // A platform's own plane ranges over its outline; the base is searched so the plane stays
        // within the ground's range plus a margin.
        val margin = max(2.0, (hi - lo) * 0.25)

        fun balanced(g: Double): Pair<Double, Earthwork>? {
            var a = lo - margin; var b = hi + margin
            var best: Pair<Double, Earthwork>? = null
            repeat(24) {
                val m = (a + b) / 2
                val w = work(m, g) ?: return best
                if (best == null || abs(w.cut - w.fill * swell) < abs(best!!.second.cut - best!!.second.fill * swell)) best = m to w
                // Higher platform: less cut, more fill.
                if (w.cut - w.fill * swell > 0) a = m else b = m
                if (b - a < 0.01) return best
            }
            return best
        }

        fun least(g: Double): Pair<Double, Earthwork>? {
            // Golden-section search of cut + fill, which falls then rises with the elevation.
            val phi = (sqrt(5.0) - 1) / 2
            var a = lo - margin; var b = hi + margin
            var c = b - phi * (b - a); var d = a + phi * (b - a)
            var wc = work(c, g) ?: return null; var wd = work(d, g) ?: return null
            while (b - a > 0.05) {
                if (wc.cut + wc.fill < wd.cut + wd.fill) { b = d; d = c; wd = wc; c = b - phi * (b - a); wc = work(c, g) ?: return null }
                else { a = c; c = d; wc = wd; d = a + phi * (b - a); wd = work(d, g) ?: return null }
            }
            return if (wc.cut + wc.fill < wd.cut + wd.fill) c to wc else d to wd
        }

        val jobs = buildList<Pair<PlatformOption.Kind, Double>> {
            add(PlatformOption.Kind.FLAT_BALANCED to 0.0)
            if (sloped) { add(PlatformOption.Kind.SLOPED_BALANCED to grade); add(PlatformOption.Kind.SLOPED_LEAST to grade) }
            add(PlatformOption.Kind.FLAT_LEAST to 0.0)
        }
        // The options are independent: each on its own core.
        return Parallel.map(jobs.size) { k ->
            val (kind, g) = jobs[k]
            val r = when (kind) {
                PlatformOption.Kind.FLAT_BALANCED, PlatformOption.Kind.SLOPED_BALANCED -> balanced(g)
                else -> least(g)
            } ?: return@map null
            PlatformOption(kind, r.first, g, azimuth, r.second.cut, r.second.fill, r.second.maxSlopeHeight, r.second.footprint)
        }.filterNotNull()
    }

    /** Cut, fill, highest slope and footprint of [d] against [terrain] on about [cells] cells. */
    fun earthwork(d: PlatformDesign, terrain: Surface, cells: Int = 150_000): Earthwork? {
        val r = Platform.design(d, terrain)
        val diff = SurfaceCompare.difference(terrain, r.surface, cells) ?: return null
        var maxH = 0.0
        for (p in r.edge) terrain.elevationAt(Point2(p.x, p.y))?.let { maxH = max(maxH, abs(p.z - it)) }
        val footprint = abs(Platform.signedArea(r.daylight.map { Point2(it.x, it.y) })) - abs(Platform.signedArea(d.outline))
        return Earthwork(diff.cutVolume, diff.fillVolume, maxH, max(0.0, footprint))
    }

    /** Ground heights on a sparse grid inside [outline]. */
    private fun inside(outline: List<Point2>, terrain: Surface): List<Double> {
        val b = Bounds(outline.minOf { it.x }, outline.minOf { it.y }, outline.maxOf { it.x }, outline.maxOf { it.y })
        val step = max(b.width, b.height) / 40
        if (step <= 0) return emptyList()
        val out = ArrayList<Double>()
        var y = b.minY + step / 2
        while (y < b.maxY) {
            var x = b.minX + step / 2
            while (x < b.maxX) {
                val p = Point2(x, y)
                if (SurfaceEdit.inside(p, outline)) terrain.elevationAt(p)?.let { out += it }
                x += step
            }
            y += step
        }
        return out
    }

    /**
     * Fall of the ground under [outline] from a least-squares plane: slope in percent and the
     * azimuth it rises towards (degrees from north). Null when there are too few heights.
     */
    fun groundFall(outline: List<Point2>, terrain: Surface): Pair<Double, Double>? {
        val b = Bounds(outline.minOf { it.x }, outline.minOf { it.y }, outline.maxOf { it.x }, outline.maxOf { it.y })
        val step = max(b.width, b.height) / 40
        if (step <= 0) return null
        val cx = b.center.x; val cy = b.center.y
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sz = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sxz = 0.0; var syz = 0.0
        var y = b.minY + step / 2
        while (y < b.maxY) {
            var x = b.minX + step / 2
            while (x < b.maxX) {
                val p = Point2(x, y)
                val z = if (SurfaceEdit.inside(p, outline)) terrain.elevationAt(p) else null
                if (z != null) {
                    val u = x - cx; val v = y - cy
                    n++; sx += u; sy += v; sz += z; sxx += u * u; syy += v * v; sxy += u * v; sxz += u * z; syz += v * z
                }
                x += step
            }
            y += step
        }
        if (n < 3) return null
        // Normal equations of z = a + bx·u + by·v.
        val m = arrayOf(doubleArrayOf(n, sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
        val r = doubleArrayOf(sz, sxz, syz)
        val sol = solve3(m, r) ?: return null
        val gx = sol[1]; val gy = sol[2]
        val slope = hypot(gx, gy) * 100
        val az = ((Math.toDegrees(atan2(gx, gy)) + 360) % 360).let { if (it > 360 - 1e-9) 0.0 else it }
        return slope to az
    }

    private fun solve3(m: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (abs(d) < 1e-12) return null
        return DoubleArray(3) { k -> det(Array(3) { i -> DoubleArray(3) { j -> if (j == k) r[i] else m[i][j] } }) / d }
    }
}
