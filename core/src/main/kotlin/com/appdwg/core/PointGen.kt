package com.appdwg.core

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** A line picked on the plan: a drawing polyline or an alignment, by index. */
sealed interface LineRef {
    data class Drawing(val index: Int) : LineRef
    data class Axis(val index: Int) : LineRef
}

/** Point generated along a line: where it is, the distance along the line, its offset and the line's elevation there. */
data class LinePoint(val position: Point2, val distance: Double, val offset: Double, val lineZ: Double?)

object PointGen {
    /** The drawing polyline or alignment closest to [p], within [maxDist] metres; hidden layers are skipped. */
    fun nearestLine(project: LandXmlProject, p: Point2, maxDist: Double, hiddenLayers: Set<String> = emptySet()): LineRef? {
        var best: LineRef? = null
        var bestD = maxDist
        fun segDist(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((p.x - ax) * dx + (p.y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(p.x - (ax + dx * t), p.y - (ay + dy * t))
        }
        project.polylines.forEachIndexed { i, l ->
            if (l.layer in hiddenLayers) return@forEachIndexed
            val n = l.xs.size
            for (k in 0 until n - 1 + if (l.closed) 1 else 0) {
                val j = (k + 1) % n
                val d = segDist(l.xs[k], l.ys[k], l.xs[j], l.ys[j])
                if (d < bestD) { bestD = d; best = LineRef.Drawing(i) }
            }
        }
        project.alignments.forEachIndexed { i, a ->
            for (k in 0 until a.vertices.size - 1) {
                val d = segDist(a.vertices[k].x, a.vertices[k].y, a.vertices[k + 1].x, a.vertices[k + 1].y)
                if (d < bestD) { bestD = d; best = LineRef.Axis(i) }
            }
        }
        return best
    }

    /** Vertices, 3D elevations (or null), closure and start distance of a picked line. */
    class Path(
        val xs: DoubleArray,
        val ys: DoubleArray,
        val zs: DoubleArray?,
        val closed: Boolean,
        val start: Double = 0.0,
        /** Distances (from 0) of the "vertices" worth a point; null means every vertex. Alignments list their element ends. */
        val breaks: DoubleArray? = null,
    )

    fun path(project: LandXmlProject, ref: LineRef): Path? = when (ref) {
        is LineRef.Drawing -> project.polylines.getOrNull(ref.index)?.let { Path(it.xs, it.ys, it.zs, it.closed) }
        is LineRef.Axis -> project.alignments.getOrNull(ref.index)?.let { a ->
            // Element ends (tangent, curve and spiral points), not the dense vertices of the drawn curve.
            val ends = a.elements.runningFold(0.0) { acc, e -> acc + e.length }.toDoubleArray()
            Path(DoubleArray(a.vertices.size) { a.vertices[it].x }, DoubleArray(a.vertices.size) { a.vertices[it].y }, null, false, a.startStation, ends)
        }
    }

    /**
     * Points every [spacing] metres along [path] (distances counted from its start value, so stations
     * for an alignment), at each of [offsets] (positive to the right of the line's direction). Vertices
     * are added when [includeVertices]; the end point always is. [from] / [to] limit the range.
     */
    fun alongLine(
        path: Path,
        spacing: Double,
        offsets: List<Double>,
        includeVertices: Boolean,
        from: Double? = null,
        to: Double? = null,
    ): List<LinePoint> {
        require(spacing > 0)
        val xs = if (path.closed) path.xs + path.xs[0] else path.xs
        val ys = if (path.closed) path.ys + path.ys[0] else path.ys
        val zs = path.zs?.let { if (path.closed) it + it[0] else it }
        val n = xs.size
        if (n < 2) return emptyList()
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[n - 1]
        val a = ((from ?: path.start) - path.start).coerceIn(0.0, total)
        val b = ((to ?: (path.start + total)) - path.start).coerceIn(a, total)
        val ds = sortedSetOf<Double>()
        // Whole multiples of the spacing in station terms, then the range ends and vertices.
        var k = ceil((path.start + a) / spacing - 1e-9)
        // Adding 0.0 turns −0.0 into 0.0, which a sorted set would keep as a different value.
        while (k * spacing - path.start <= b + 1e-9) { ds += k * spacing - path.start + 0.0; k++ }
        ds += a; ds += b
        if (includeVertices) for (c in path.breaks ?: cum) if (c in a..b) ds += c
        val out = ArrayList<LinePoint>()
        for (d in ds) {
            var i = 0
            while (i < n - 2 && cum[i + 1] < d) i++
            val segLen = cum[i + 1] - cum[i]
            val t = if (segLen == 0.0) 0.0 else ((d - cum[i]) / segLen).coerceIn(0.0, 1.0)
            val x = xs[i] + (xs[i + 1] - xs[i]) * t
            val y = ys[i] + (ys[i + 1] - ys[i]) * t
            val z = zs?.let { it[i] + (it[i + 1] - it[i]) * t }
            // Direction: the segment's, or the average of both at a vertex.
            var dx = xs[i + 1] - xs[i]; var dy = ys[i + 1] - ys[i]
            if (t >= 1.0 - 1e-9 && i + 2 < n) { dx = dx / max(segLen, 1e-12) + (xs[i + 2] - xs[i + 1]) / max(cum[i + 2] - cum[i + 1], 1e-12); dy = dy / max(segLen, 1e-12) + (ys[i + 2] - ys[i + 1]) / max(cum[i + 2] - cum[i + 1], 1e-12) }
            val len = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
            val rx = dy / len; val ry = -dx / len
            for (o in offsets) out += LinePoint(Point2(x + rx * o, y + ry * o), path.start + d, o, z)
        }
        return out
    }

    /**
     * Grid of points spaced [dx] by [dy] metres, rotated [rotationDeg] (counterclockwise) about [origin],
     * keeping the ones inside [area] (a closed outline).
     */
    fun grid(area: List<Point2>, dx: Double, dy: Double, rotationDeg: Double = 0.0, origin: Point2 = area.first(), max: Int = 200_000): List<Point2> {
        require(dx > 0 && dy > 0)
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val s = sin(r)
        // Area in the grid's own axes.
        val local = area.map { p -> val x = p.x - origin.x; val y = p.y - origin.y; Point2(x * c + y * s, -x * s + y * c) }
        val minX = local.minOf { it.x }; val maxX = local.maxOf { it.x }
        val minY = local.minOf { it.y }; val maxY = local.maxOf { it.y }
        val out = ArrayList<Point2>()
        var j = floor(minY / dy)
        while (j * dy <= maxY) {
            var i = floor(minX / dx)
            while (i * dx <= maxX) {
                val lx = i * dx; val ly = j * dy
                if (inside(local, lx, ly)) {
                    out += Point2(origin.x + lx * c - ly * s, origin.y + lx * s + ly * c)
                    require(out.size <= max) { tr("malha com mais de {0} pontos: aumente o espaçamento", max) }
                }
                i++
            }
            j++
        }
        return out
    }

    /** Point in polygon by ray casting. */
    fun inside(poly: List<Point2>, x: Double, y: Double): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Parses a list of offsets typed as "-3,5; 0; 3,5" (semicolons or spaces). */
    fun parseOffsets(text: String): List<Double>? {
        val parts = text.split(';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
    }
}
