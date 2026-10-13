package com.appdwg.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Length, elevations and grade of a line, for its properties. */
data class LineInfo(val length2d: Double, val length3d: Double?, val zStart: Double?, val zEnd: Double?, val vertices: Int) {
    /** End minus start elevation over the plan length, in percent. */
    val gradePercent: Double? get() = if (zStart != null && zEnd != null && length2d > 0) (zEnd - zStart) / length2d * 100 else null
}

/** Editing operations on drawing lines; each returns new lines and leaves the input unchanged. */
object LineOps {
    private fun Polyline.with(xs: DoubleArray = this.xs, ys: DoubleArray = this.ys, zs: DoubleArray? = this.zs, closed: Boolean = this.closed, layer: String = this.layer) =
        Polyline(layer, xs, ys, closed, color, byLayer, zs)

    fun info(l: Polyline): LineInfo {
        var l2 = 0.0; var l3 = 0.0
        val n = l.xs.size
        val segs = if (l.closed) n else n - 1
        for (i in 0 until segs) {
            val j = (i + 1) % n
            val d = hypot(l.xs[j] - l.xs[i], l.ys[j] - l.ys[i])
            l2 += d
            l.zs?.let { z -> l3 += sqrt(d * d + (z[j] - z[i]) * (z[j] - z[i])) }
        }
        return LineInfo(l2, l.zs?.let { l3 }, l.zs?.first(), l.zs?.let { if (l.closed) it.first() else it.last() }, n)
    }

    fun move(l: Polyline, dx: Double, dy: Double, dz: Double = 0.0, layer: String = l.layer): Polyline =
        l.with(DoubleArray(l.xs.size) { l.xs[it] + dx }, DoubleArray(l.ys.size) { l.ys[it] + dy },
            if (dz != 0.0) DoubleArray(l.xs.size) { (l.zs?.get(it) ?: 0.0) + dz } else l.zs, layer = layer)

    /** [count] copies, each [dx], [dy], [dz] further than the last. */
    fun copies(l: Polyline, dx: Double, dy: Double, dz: Double, count: Int, layer: String): List<Polyline> =
        (1..count).map { k -> move(l, dx * k, dy * k, dz * k, layer) }

    /**
     * Parallel line [distance] metres to the right of the line's direction (negative to the left),
     * with mitred corners. The elevation moves by [distance] × [gradePercent] / 100 (a slope down
     * outward when negative), plus [dz].
     */
    fun offset(l: Polyline, distance: Double, layer: String, dz: Double = 0.0, gradePercent: Double = 0.0): Polyline {
        val n = l.xs.size
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        fun normal(i: Int, j: Int): Point2 {
            val dx = l.xs[j] - l.xs[i]; val dy = l.ys[j] - l.ys[i]
            val len = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
            return Point2(dy / len, -dx / len)
        }
        for (i in 0 until n) {
            val prev = if (i > 0) i - 1 else if (l.closed) n - 1 else -1
            val next = if (i < n - 1) i + 1 else if (l.closed) 0 else -1
            val a = if (prev >= 0) normal(prev, i) else null
            val b = if (next >= 0) normal(i, next) else null
            val m = when {
                a == null -> b!!
                b == null -> a
                else -> {
                    // Mitre: along the bisector, long enough to keep the distance from both segments.
                    val bx = a.x + b.x; val by = a.y + b.y
                    val bl = hypot(bx, by)
                    if (bl < 1e-9) a else {
                        val cosHalf = (bx / bl) * a.x + (by / bl) * a.y
                        val k = 1 / max(cosHalf, 0.2)
                        Point2(bx / bl * k, by / bl * k)
                    }
                }
            }
            xs[i] = l.xs[i] + m.x * distance; ys[i] = l.ys[i] + m.y * distance
        }
        val shift = dz + abs(distance) * gradePercent / 100
        val zs = if (l.zs != null || shift != 0.0) DoubleArray(n) { (l.zs?.get(it) ?: 0.0) + shift } else null
        return l.with(xs, ys, zs, layer = layer)
    }

    /** Line with its elevations taken from [surface], adding points every [step] metres (vertices kept). */
    fun drape(l: Polyline, surface: Surface, step: Double): Polyline {
        require(step > 0)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val n = l.xs.size
        val segs = if (l.closed) n else n - 1
        fun add(x: Double, y: Double) { xs += x; ys += y; zs += surface.elevationAt(Point2(x, y)) ?: Double.NaN }
        for (i in 0 until segs) {
            val j = (i + 1) % n
            val d = hypot(l.xs[j] - l.xs[i], l.ys[j] - l.ys[i])
            val k = max(1, ceil(d / step).toInt())
            for (s in 0 until k) add(l.xs[i] + (l.xs[j] - l.xs[i]) * s / k, l.ys[i] + (l.ys[j] - l.ys[i]) * s / k)
        }
        if (!l.closed) add(l.xs[n - 1], l.ys[n - 1])
        // Off the surface keeps the nearest known elevation, so the line stays drawable in 3D.
        val z = zs.toDoubleArray()
        var last = z.firstOrNull { !it.isNaN() } ?: 0.0
        for (i in z.indices) if (z[i].isNaN()) z[i] = last else last = z[i]
        return l.with(xs.toDoubleArray(), ys.toDoubleArray(), z)
    }

    fun flatten(l: Polyline, z: Double): Polyline = l.with(zs = DoubleArray(l.xs.size) { z })

    fun rotate(l: Polyline, center: Point2, degrees: Double): Polyline {
        val r = Math.toRadians(degrees); val c = cos(r); val s = sin(r)
        return l.with(
            DoubleArray(l.xs.size) { center.x + (l.xs[it] - center.x) * c - (l.ys[it] - center.y) * s },
            DoubleArray(l.xs.size) { center.y + (l.xs[it] - center.x) * s + (l.ys[it] - center.y) * c },
        )
    }

    /** Mirror image across the line through [a] and [b]. */
    fun mirror(l: Polyline, a: Point2, b: Point2, layer: String = l.layer): Polyline {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: return l
        val xs = DoubleArray(l.xs.size); val ys = DoubleArray(l.xs.size)
        for (i in l.xs.indices) {
            val t = ((l.xs[i] - a.x) * dx + (l.ys[i] - a.y) * dy) / l2
            val px = a.x + dx * t; val py = a.y + dy * t
            xs[i] = 2 * px - l.xs[i]; ys[i] = 2 * py - l.ys[i]
        }
        return l.with(xs, ys, layer = layer)
    }

    fun reverse(l: Polyline): Polyline = l.with(l.xs.reversedArray(), l.ys.reversedArray(), l.zs?.reversedArray())

    /** Joins open lines end to end where their ends are within [tolerance]; lines that do not touch stay apart. */
    fun join(lines: List<Polyline>, tolerance: Double = 0.01): List<Polyline> {
        val pool = lines.filter { !it.closed && it.xs.size >= 2 }.toMutableList()
        val out = lines.filter { it.closed || it.xs.size < 2 }.toMutableList()
        while (pool.isNotEmpty()) {
            var cur = pool.removeAt(0)
            var grown = true
            while (grown) {
                grown = false
                val it = pool.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    fun near(ax: Double, ay: Double, bx: Double, by: Double) = hypot(ax - bx, ay - by) <= tolerance
                    val cEnd = cur.xs.size - 1; val oEnd = o.xs.size - 1
                    val merged = when {
                        near(cur.xs[cEnd], cur.ys[cEnd], o.xs[0], o.ys[0]) -> concat(cur, o)
                        near(cur.xs[cEnd], cur.ys[cEnd], o.xs[oEnd], o.ys[oEnd]) -> concat(cur, reverse(o))
                        near(cur.xs[0], cur.ys[0], o.xs[oEnd], o.ys[oEnd]) -> concat(o, cur)
                        near(cur.xs[0], cur.ys[0], o.xs[0], o.ys[0]) -> concat(reverse(o), cur)
                        else -> null
                    }
                    if (merged != null) { cur = merged; it.remove(); grown = true }
                }
            }
            out += cur
        }
        return out
    }

    private fun concat(a: Polyline, b: Polyline): Polyline {
        val zs = if (a.zs != null || b.zs != null) (a.zs ?: DoubleArray(a.xs.size)) + (b.zs ?: DoubleArray(b.xs.size)).drop(1) else null
        return a.with(a.xs + b.xs.drop(1), a.ys + b.ys.drop(1), zs, closed = false)
    }

    /** One line per segment. */
    fun explode(l: Polyline): List<Polyline> {
        val n = l.xs.size
        val segs = if (l.closed) n else n - 1
        return (0 until segs).map { i ->
            val j = (i + 1) % n
            l.with(doubleArrayOf(l.xs[i], l.xs[j]), doubleArrayOf(l.ys[i], l.ys[j]), l.zs?.let { doubleArrayOf(it[i], it[j]) }, closed = false)
        }
    }

    /** Splits the line into pieces of [every] metres (the last one shorter), or into [parts] equal pieces. */
    fun divide(l: Polyline, parts: Int? = null, every: Double? = null): List<Polyline> {
        val path = if (l.closed) l.with(l.xs + l.xs[0], l.ys + l.ys[0], l.zs?.let { it + it[0] }, closed = false) else l
        val total = info(path).length2d
        val cuts = when {
            parts != null -> { require(parts >= 1); (1 until parts).map { total * it / parts } }
            every != null -> { require(every > 0); generateSequence(every) { it + every }.takeWhile { it < total - 1e-9 }.toList() }
            else -> emptyList()
        }
        val bounds = listOf(0.0) + cuts + total
        return bounds.zipWithNext().map { (a, b) -> sub(path, a, b) }
    }

    /** Part of an open line between distances [a] and [b]. */
    private fun sub(l: Polyline, a: Double, b: Double): Polyline {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        var acc = 0.0
        fun at(i: Int, t: Double) {
            val j = i + 1
            xs += l.xs[i] + (l.xs[j] - l.xs[i]) * t; ys += l.ys[i] + (l.ys[j] - l.ys[i]) * t
            l.zs?.let { zs += it[i] + (it[j] - it[i]) * t }
        }
        for (i in 0 until l.xs.size - 1) {
            val d = hypot(l.xs[i + 1] - l.xs[i], l.ys[i + 1] - l.ys[i])
            val s0 = acc; val s1 = acc + d
            if (s1 >= a && s0 <= b && d > 0) {
                if (xs.isEmpty()) at(i, ((a - s0) / d).coerceIn(0.0, 1.0))
                if (s1 <= b) { xs += l.xs[i + 1]; ys += l.ys[i + 1]; l.zs?.let { zs += it[i + 1] } } else { at(i, ((b - s0) / d).coerceIn(0.0, 1.0)); break }
            }
            acc = s1
        }
        return l.with(xs.toDoubleArray(), ys.toDoubleArray(), if (l.zs != null) zs.toDoubleArray() else null, closed = false)
    }

    fun relayer(l: Polyline, layer: String, color: Int?): Polyline =
        Polyline(layer, l.xs, l.ys, l.closed, color, color == null, l.zs)

    /** Displacement of [distance] metres towards azimuth [azimuthDeg] (clockwise from north). */
    fun polar(distance: Double, azimuthDeg: Double): Point2 {
        val r = Math.toRadians(azimuthDeg)
        return Point2(distance * sin(r), distance * cos(r))
    }
}
