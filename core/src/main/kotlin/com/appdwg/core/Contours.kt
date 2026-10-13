package com.appdwg.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Contour line at one [elevation], as a polyline (closed when it ends where it starts). */
class ContourLine(val elevation: Double, val xs: DoubleArray, val ys: DoubleArray)

/** Contour segments of one level: x0, y0, x1, y1 per segment. */
class ContourLevel(val elevation: Double, val segments: DoubleArray) {
    val count get() = segments.size / 4
}

object Contours {
    /**
     * Segments of every [interval] level crossing the triangles of [surface] inside [region] (the
     * whole surface when null). Null when [cancelled] stops it or there would be more than
     * [maxSegments] segments.
     */
    fun segments(
        surface: Surface,
        interval: Double,
        region: Bounds? = null,
        maxSegments: Int = 3_000_000,
        cancelled: () -> Boolean = { false },
    ): List<ContourLevel>? {
        require(interval > 0)
        val byLevel = HashMap<Long, DoubleArrayBuilder>()
        var total = 0
        var visited = 0
        val xs = surface.xs; val ys = surface.ys; val zs = surface.zs; val tri = surface.triangles
        val ok = surface.forEachTriangle(region) { t ->
            if (++visited % 8192 == 0 && cancelled()) return@forEachTriangle false
            val a = tri[3 * t]; val b = tri[3 * t + 1]; val c = tri[3 * t + 2]
            val zmin = min(zs[a], min(zs[b], zs[c]))
            val zmax = max(zs[a], max(zs[b], zs[c]))
            var k = ceil(zmin / interval).toLong()
            val kEnd = floor(zmax / interval).toLong()
            while (k <= kEnd) {
                // Level nudged off the vertices so a contour through a vertex still gives one segment.
                val level = k * interval
                val l = if (level == zs[a] || level == zs[b] || level == zs[c]) level + 1e-9 else level
                var n = 0
                val p = DoubleArray(4)
                fun edge(i: Int, j: Int) {
                    val zi = zs[i]; val zj = zs[j]
                    if ((zi < l) != (zj < l) && n < 4) {
                        val f = (l - zi) / (zj - zi)
                        p[n] = xs[i] + (xs[j] - xs[i]) * f
                        p[n + 1] = ys[i] + (ys[j] - ys[i]) * f
                        n += 2
                    }
                }
                edge(a, b); edge(b, c); edge(c, a)
                if (n == 4) {
                    byLevel.getOrPut(k) { DoubleArrayBuilder() }.add(p)
                    if (++total > maxSegments) return@forEachTriangle false
                }
                k++
            }
            true
        }
        if (!ok) return null
        return byLevel.entries.sortedBy { it.key }.map { (k, v) -> ContourLevel(k * interval, v.toArray()) }
    }

    /** Joins the segments of each level into polylines, for export. */
    fun lines(levels: List<ContourLevel>): List<ContourLine> {
        val out = ArrayList<ContourLine>()
        for (lv in levels) {
            val s = lv.segments
            val n = lv.count
            // Endpoints are matched by rounding to a millimetre.
            fun key(x: Double, y: Double) = Math.round(x * 1000) * 31_000_003L + Math.round(y * 1000)
            val ends = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) {
                ends.getOrPut(key(s[4 * i], s[4 * i + 1])) { ArrayList(2) }.add(2 * i)
                ends.getOrPut(key(s[4 * i + 2], s[4 * i + 3])) { ArrayList(2) }.add(2 * i + 1)
            }
            val used = BooleanArray(n)
            fun px(e: Int) = s[4 * (e / 2) + 2 * (e % 2)]
            fun py(e: Int) = s[4 * (e / 2) + 2 * (e % 2) + 1]
            for (start in 0 until n) {
                if (used[start]) continue
                used[start] = true
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                xs += s[4 * start]; ys += s[4 * start + 1]; xs += s[4 * start + 2]; ys += s[4 * start + 3]
                // Walk forward from the segment's second end, then backward from its first.
                for (dir in 0..1) {
                    var endX = if (dir == 0) xs.last() else xs.first()
                    var endY = if (dir == 0) ys.last() else ys.first()
                    while (true) {
                        val next = ends[key(endX, endY)]?.firstOrNull { !used[it / 2] } ?: break
                        used[next / 2] = true
                        val other = next xor 1
                        endX = px(other); endY = py(other)
                        if (dir == 0) { xs += endX; ys += endY } else { xs.add(0, endX); ys.add(0, endY) }
                    }
                }
                out += ContourLine(lv.elevation, xs.toDoubleArray(), ys.toDoubleArray())
            }
        }
        return out
    }

    /** Round interval (0.1, 0.2, 0.25, 0.5, 1, 2, 5 … m) giving about [count] contours over [range] metres. */
    fun autoInterval(range: Double, count: Int = 40): Double {
        val steps = doubleArrayOf(0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0)
        val raw = range / count
        return steps.firstOrNull { it >= raw } ?: 100.0
    }

    private class DoubleArrayBuilder {
        private var a = DoubleArray(64)
        private var n = 0
        fun add(v: DoubleArray) {
            if (n + v.size > a.size) a = a.copyOf(max(a.size * 2, n + v.size))
            System.arraycopy(v, 0, a, n, v.size); n += v.size
        }
        fun toArray() = a.copyOf(n)
    }
}
