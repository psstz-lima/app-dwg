package com.appdwg.core

import kotlin.math.hypot

/**
 * Edits of a TIN, each returning a new surface (the original is left alone): boundaries, deleting
 * and adding points with a local re-triangulation, raising or flattening an area, swapping an edge.
 */
object SurfaceEdit {

    fun inside(p: Point2, poly: List<Point2>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    private fun centroid(s: Surface, t: Int): Point2 {
        val a = s.triangles[3 * t]; val b = s.triangles[3 * t + 1]; val c = s.triangles[3 * t + 2]
        return Point2((s.xs[a] + s.xs[b] + s.xs[c]) / 3, (s.ys[a] + s.ys[b] + s.ys[c]) / 3)
    }

    /** Keeps the triangles whose centre is inside ([keepInside]) or outside the polygon; drops unused points. */
    fun clip(s: Surface, poly: List<Point2>, keepInside: Boolean, name: String = s.name): Surface {
        val keep = ArrayList<Int>()
        for (t in 0 until s.triangleCount) if (inside(centroid(s, t), poly) == keepInside) keep += t
        return rebuild(s, keep.flatMap { listOf(s.triangles[3 * it], s.triangles[3 * it + 1], s.triangles[3 * it + 2]) }, s.zs, name)
    }

    /** Adds [dz] to the points inside the polygon (or sets them to [z] when given). */
    fun raise(s: Surface, poly: List<Point2>, dz: Double, z: Double? = null, name: String = s.name): Surface {
        val zs = DoubleArray(s.vertexCount) { i -> if (inside(Point2(s.xs[i], s.ys[i]), poly)) z ?: (s.zs[i] + dz) else s.zs[i] }
        return Surface(name, s.xs, s.ys, zs, s.triangles)
    }

    /** Removes the points inside the polygon and fills the hole by re-triangulating its rim. */
    fun deletePoints(s: Surface, poly: List<Point2>, name: String = s.name): Surface {
        val gone = BooleanArray(s.vertexCount) { inside(Point2(s.xs[it], s.ys[it]), poly) }
        if (gone.none { it }) return s
        return retriangulate(s, gone, null, name)
    }

    /** Inserts a point inside the surface; null when it falls outside. */
    fun addPoint(s: Surface, p: Point3, name: String = s.name): Surface? {
        val hit = (0 until s.triangleCount).firstOrNull { contains(s, it, p.x, p.y) } ?: return null
        // The triangle hit and its neighbours make room for the new point.
        val v = setOf(s.triangles[3 * hit], s.triangles[3 * hit + 1], s.triangles[3 * hit + 2])
        val affected = (0 until s.triangleCount).filter { t -> (0..2).any { s.triangles[3 * t + it] in v } }
        return retriangulateTriangles(s, affected.toSet(), BooleanArray(s.vertexCount), p, name)
    }

    /** Swaps the edge nearest to [p] shared by two triangles, when the four points make a convex quad. */
    fun flipEdge(s: Surface, p: Point2, name: String = s.name): Surface? {
        val owner = HashMap<Long, MutableList<Int>>()
        fun key(a: Int, b: Int) = if (a < b) a.toLong() shl 32 or b.toLong() else b.toLong() shl 32 or a.toLong()
        for (t in 0 until s.triangleCount) for (e in 0..2) owner.getOrPut(key(s.triangles[3 * t + e], s.triangles[3 * t + (e + 1) % 3])) { ArrayList(2) } += t
        var best: Long? = null; var bestD = Double.MAX_VALUE
        for ((k, ts) in owner) {
            if (ts.size != 2) continue
            val a = (k shr 32).toInt(); val b = (k and 0xffffffffL).toInt()
            val d = segmentDistance(p, s.xs[a], s.ys[a], s.xs[b], s.ys[b])
            if (d < bestD) { bestD = d; best = k }
        }
        val k = best ?: return null
        val a = (k shr 32).toInt(); val b = (k and 0xffffffffL).toInt()
        val (t1, t2) = owner[k]!!
        fun other(t: Int) = (0..2).map { s.triangles[3 * t + it] }.first { it != a && it != b }
        val c = other(t1); val d = other(t2)
        // Convex: c and d on opposite sides of a–b, and a and b on opposite sides of c–d.
        fun side(o: Int, q: Int, r: Int) = (s.xs[q] - s.xs[o]) * (s.ys[r] - s.ys[o]) - (s.ys[q] - s.ys[o]) * (s.xs[r] - s.xs[o])
        if (side(a, b, c) * side(a, b, d) >= 0 || side(c, d, a) * side(c, d, b) >= 0) return null
        val tri = s.triangles.copyOf()
        // Keep the orientation of the first triangle.
        val ccw = side(a, b, c) > 0
        val n1 = if (ccw) intArrayOf(c, a, d) else intArrayOf(c, d, a)
        val n2 = if (ccw) intArrayOf(d, b, c) else intArrayOf(d, c, b)
        for (i in 0..2) { tri[3 * t1 + i] = n1[i]; tri[3 * t2 + i] = n2[i] }
        return Surface(name, s.xs, s.ys, s.zs, tri)
    }

    private fun segmentDistance(p: Point2, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((p.x - ax) * dx + (p.y - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(p.x - (ax + t * dx), p.y - (ay + t * dy))
    }

    private fun contains(s: Surface, t: Int, x: Double, y: Double): Boolean {
        val a = s.triangles[3 * t]; val b = s.triangles[3 * t + 1]; val c = s.triangles[3 * t + 2]
        fun cr(i: Int, j: Int) = (s.xs[j] - s.xs[i]) * (y - s.ys[i]) - (s.ys[j] - s.ys[i]) * (x - s.xs[i])
        val d1 = cr(a, b); val d2 = cr(b, c); val d3 = cr(c, a)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }

    private fun retriangulate(s: Surface, gone: BooleanArray, extra: Point3?, name: String): Surface {
        val affected = (0 until s.triangleCount).filter { t -> (0..2).any { gone[s.triangles[3 * t + it]] } }.toSet()
        return retriangulateTriangles(s, affected, gone, extra, name)
    }

    /**
     * Replaces the [affected] triangles: their remaining points (and [extra]) are triangulated and only
     * new triangles whose centre lies in the area the old ones covered are kept, so the rest of the
     * surface and its outline do not change.
     */
    private fun retriangulateTriangles(s: Surface, affected: Set<Int>, gone: BooleanArray, extra: Point3?, name: String): Surface {
        val ring = affected.flatMap { t -> (0..2).map { s.triangles[3 * t + it] } }.filter { !gone[it] }.distinct()
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>(); val ids = ArrayList<Int>()
        ring.forEach { xs += s.xs[it]; ys += s.ys[it]; zs += s.zs[it]; ids += it }
        val newId = s.vertexCount
        extra?.let { xs += it.x; ys += it.y; zs += it.z; ids += newId }
        val kept = ArrayList<Int>()
        for (t in 0 until s.triangleCount) if (t !in affected) for (i in 0..2) kept += s.triangles[3 * t + i]
        if (xs.size >= 3) {
            val ox = xs[0]; val oy = ys[0]
            val coords = DoubleArray(2 * xs.size) { if (it % 2 == 0) xs[it / 2] - ox else ys[it / 2] - oy }
            val t = Delaunay(coords).triangles
            for (k in 0 until t.size / 3) {
                val cx = (xs[t[3 * k]] + xs[t[3 * k + 1]] + xs[t[3 * k + 2]]) / 3
                val cy = (ys[t[3 * k]] + ys[t[3 * k + 1]] + ys[t[3 * k + 2]]) / 3
                if (affected.any { contains(s, it, cx, cy) }) for (i in 0..2) kept += ids[t[3 * k + i]]
            }
        }
        val allZ = if (extra != null) s.zs + extra.z else s.zs
        val allX = if (extra != null) s.xs + extra.x else s.xs
        val allY = if (extra != null) s.ys + extra.y else s.ys
        return rebuild(Surface(s.name, allX, allY, allZ, s.triangles), kept, allZ, name)
    }

    /** Surface with only the points [tri] uses, renumbered. */
    private fun rebuild(s: Surface, tri: List<Int>, zs: DoubleArray, name: String): Surface {
        val map = IntArray(s.xs.size) { -1 }
        var n = 0
        for (v in tri) if (map[v] < 0) map[v] = n++
        val x = DoubleArray(n); val y = DoubleArray(n); val z = DoubleArray(n)
        for (i in map.indices) if (map[i] >= 0) { x[map[i]] = s.xs[i]; y[map[i]] = s.ys[i]; z[map[i]] = zs[i] }
        return Surface(name, x, y, z, IntArray(tri.size) { map[tri[it]] })
    }
}
