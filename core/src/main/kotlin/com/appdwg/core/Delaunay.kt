package com.appdwg.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Delaunay triangulation of plan points, after the "Delaunator" sweep-hull algorithm
 * (Vladimir Agafonkin, ISC licence): O(n log n), flat arrays, fast enough for millions of points.
 * [triangles] holds three point indices per triangle, all with the same winding.
 */
class Delaunay(private val coords: DoubleArray) {
    val triangles: IntArray
    private val halfedges: IntArray

    private val n = coords.size / 2
    private val hashSize: Int
    private val hullPrev: IntArray
    private val hullNext: IntArray
    private val hullTri: IntArray
    private val hullHash: IntArray
    private val ids: IntArray
    private val dists: DoubleArray
    private var cx = 0.0
    private var cy = 0.0
    private var hullStart = 0
    private var trianglesLen = 0
    private val edgeStack = IntArray(512)
    private val tri: IntArray
    private val half: IntArray

    init {
        val maxTriangles = max(2 * n - 5, 0)
        tri = IntArray(maxTriangles * 3)
        half = IntArray(maxTriangles * 3)
        hashSize = ceilSqrt(n)
        hullPrev = IntArray(n)
        hullNext = IntArray(n)
        hullTri = IntArray(n)
        hullHash = IntArray(hashSize) { -1 }
        ids = IntArray(n)
        dists = DoubleArray(n)
        update()
        triangles = tri.copyOf(trianglesLen)
        halfedges = half.copyOf(trianglesLen)
    }

    private fun update() {
        if (n < 3) return
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = coords[2 * i]; val y = coords[2 * i + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
            ids[i] = i
        }
        val mx = (minX + maxX) / 2
        val my = (minY + maxY) / 2

        // Seed triangle: the point closest to the centre, its nearest neighbour, and the point
        // making the smallest circumcircle with them.
        var i0 = 0; var i1 = 0; var i2 = 0
        var minDist = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            val d = dist(mx, my, coords[2 * i], coords[2 * i + 1])
            if (d < minDist) { i0 = i; minDist = d }
        }
        val i0x = coords[2 * i0]; val i0y = coords[2 * i0 + 1]
        minDist = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            if (i == i0) continue
            val d = dist(i0x, i0y, coords[2 * i], coords[2 * i + 1])
            if (d < minDist && d > 0) { i1 = i; minDist = d }
        }
        var i1x = coords[2 * i1]; var i1y = coords[2 * i1 + 1]
        var minRadius = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            if (i == i0 || i == i1) continue
            val r = circumradius(i0x, i0y, i1x, i1y, coords[2 * i], coords[2 * i + 1])
            if (r < minRadius) { i2 = i; minRadius = r }
        }
        var i2x = coords[2 * i2]; var i2y = coords[2 * i2 + 1]
        if (minRadius == Double.POSITIVE_INFINITY) return // all points on a line

        if (orient(i0x, i0y, i1x, i1y, i2x, i2y) < 0) {
            val i = i1; val x = i1x; val y = i1y
            i1 = i2; i1x = i2x; i1y = i2y
            i2 = i; i2x = x; i2y = y
        }
        circumcenter(i0x, i0y, i1x, i1y, i2x, i2y)

        for (i in 0 until n) dists[i] = dist(coords[2 * i], coords[2 * i + 1], cx, cy)
        quicksort(ids, dists, 0, n - 1)

        hullStart = i0
        var hullSize = 3
        hullNext[i0] = i1; hullPrev[i2] = i1
        hullNext[i1] = i2; hullPrev[i0] = i2
        hullNext[i2] = i0; hullPrev[i1] = i0
        hullTri[i0] = 0; hullTri[i1] = 1; hullTri[i2] = 2
        hullHash.fill(-1)
        hullHash[hashKey(i0x, i0y)] = i0
        hullHash[hashKey(i1x, i1y)] = i1
        hullHash[hashKey(i2x, i2y)] = i2

        trianglesLen = 0
        addTriangle(i0, i1, i2, -1, -1, -1)

        var xp = 0.0; var yp = 0.0
        for (k in 0 until n) {
            val i = ids[k]
            val x = coords[2 * i]; val y = coords[2 * i + 1]
            // Skips near-duplicates.
            if (k > 0 && abs(x - xp) <= EPSILON && abs(y - yp) <= EPSILON) continue
            xp = x; yp = y
            if (i == i0 || i == i1 || i == i2) continue

            // A visible edge on the convex hull, found through the angle hash.
            var start = 0
            val key = hashKey(x, y)
            for (j in 0 until hashSize) {
                start = hullHash[(key + j) % hashSize]
                if (start != -1 && start != hullNext[start]) break
            }
            start = hullPrev[start]
            var e = start
            var q = hullNext[e]
            while (orient(x, y, coords[2 * e], coords[2 * e + 1], coords[2 * q], coords[2 * q + 1]) >= 0) {
                e = q
                if (e == start) { e = -1; break }
                q = hullNext[e]
            }
            if (e == -1) continue // likely a near-duplicate

            var t = addTriangle(e, i, hullNext[e], -1, -1, hullTri[e])
            hullTri[i] = legalize(t + 2)
            hullTri[e] = t
            hullSize++

            // Walks forward through the hull, adding triangles and flipping recursively.
            var nn = hullNext[e]
            q = hullNext[nn]
            while (orient(x, y, coords[2 * nn], coords[2 * nn + 1], coords[2 * q], coords[2 * q + 1]) < 0) {
                t = addTriangle(nn, i, q, hullTri[i], -1, hullTri[nn])
                hullTri[i] = legalize(t + 2)
                hullNext[nn] = nn
                hullSize--
                nn = q
                q = hullNext[nn]
            }
            // Walks backward from the other side.
            if (e == start) {
                q = hullPrev[e]
                while (orient(x, y, coords[2 * q], coords[2 * q + 1], coords[2 * e], coords[2 * e + 1]) < 0) {
                    t = addTriangle(q, i, e, -1, hullTri[e], hullTri[q])
                    legalize(t + 2)
                    hullTri[q] = t
                    hullNext[e] = e
                    hullSize--
                    e = q
                    q = hullPrev[e]
                }
            }
            hullStart = e
            hullPrev[i] = e
            hullNext[e] = i
            hullPrev[nn] = i
            hullNext[i] = nn
            hullHash[hashKey(x, y)] = i
            hullHash[hashKey(coords[2 * e], coords[2 * e + 1])] = e
        }
    }

    private fun hashKey(x: Double, y: Double): Int =
        (floor(pseudoAngle(x - cx, y - cy) * hashSize).toInt() % hashSize + hashSize) % hashSize

    private fun legalize(a0: Int): Int {
        var a = a0
        var i = 0
        var ar: Int
        while (true) {
            val b = half[a]
            val a0e = a - a % 3
            ar = a0e + (a + 2) % 3
            if (b == -1) {
                if (i == 0) break
                a = edgeStack[--i]
                continue
            }
            val b0 = b - b % 3
            val al = a0e + (a + 1) % 3
            val bl = b0 + (b + 2) % 3
            val p0 = tri[ar]; val pr = tri[a]; val pl = tri[al]; val p1 = tri[bl]
            val illegal = inCircle(
                coords[2 * p0], coords[2 * p0 + 1], coords[2 * pr], coords[2 * pr + 1],
                coords[2 * pl], coords[2 * pl + 1], coords[2 * p1], coords[2 * p1 + 1],
            )
            if (illegal) {
                tri[a] = p1
                tri[b] = p0
                val hbl = half[bl]
                if (hbl == -1) {
                    // The flipped edge was on the hull: fixes the hull triangle reference.
                    var e = hullStart
                    do {
                        if (hullTri[e] == bl) { hullTri[e] = a; break }
                        e = hullPrev[e]
                    } while (e != hullStart)
                }
                link(a, hbl)
                link(b, half[ar])
                link(ar, bl)
                val br = b0 + (b + 1) % 3
                if (i < edgeStack.size) edgeStack[i++] = br
            } else {
                if (i == 0) break
                a = edgeStack[--i]
            }
        }
        return ar
    }

    private fun link(a: Int, b: Int) {
        half[a] = b
        if (b != -1) half[b] = a
    }

    private fun addTriangle(i0: Int, i1: Int, i2: Int, a: Int, b: Int, c: Int): Int {
        val t = trianglesLen
        tri[t] = i0; tri[t + 1] = i1; tri[t + 2] = i2
        link(t, a); link(t + 1, b); link(t + 2, c)
        trianglesLen += 3
        return t
    }

    private fun circumcenter(ax: Double, ay: Double, bx: Double, by: Double, qx: Double, qy: Double) {
        val dx = bx - ax; val dy = by - ay
        val ex = qx - ax; val ey = qy - ay
        val bl = dx * dx + dy * dy
        val cl = ex * ex + ey * ey
        val d = 0.5 / (dx * ey - dy * ex)
        cx = ax + (ey * bl - dy * cl) * d
        cy = ay + (dx * cl - ex * bl) * d
    }

    companion object {
        private const val EPSILON = 1.1102230246251565e-16 * 2

        private fun ceilSqrt(n: Int) = kotlin.math.ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)

        private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = ax - bx; val dy = ay - by
            return dx * dx + dy * dy
        }

        /** Monotonic replacement for atan2, in [0, 1). */
        private fun pseudoAngle(dx: Double, dy: Double): Double {
            val p = dx / (abs(dx) + abs(dy))
            return (if (dy > 0) 3 - p else 1 + p) / 4
        }

        /** Positive when (p, q, r) turn clockwise, as in Delaunator. */
        private fun orient(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Double =
            (qy - py) * (rx - qx) - (qx - px) * (ry - qy)

        private fun inCircle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean {
            val dx = ax - px; val dy = ay - py
            val ex = bx - px; val ey = by - py
            val fx = cx - px; val fy = cy - py
            val ap = dx * dx + dy * dy
            val bp = ex * ex + ey * ey
            val cp = fx * fx + fy * fy
            return dx * (ey * cp - bp * fy) - dy * (ex * cp - bp * fx) + ap * (ex * fy - ey * fx) < 0
        }

        private fun circumradius(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            val dx = bx - ax; val dy = by - ay
            val ex = cx - ax; val ey = cy - ay
            val bl = dx * dx + dy * dy
            val cl = ex * ex + ey * ey
            val d = 0.5 / (dx * ey - dy * ex)
            val x = (ey * bl - dy * cl) * d
            val y = (dx * cl - ex * bl) * d
            return x * x + y * y
        }

        private fun quicksort(ids: IntArray, dists: DoubleArray, left: Int, right: Int) {
            if (right - left <= 20) {
                for (i in left + 1..right) {
                    val temp = ids[i]
                    val tempDist = dists[temp]
                    var j = i - 1
                    while (j >= left && dists[ids[j]] > tempDist) ids[j + 1] = ids[j--]
                    ids[j + 1] = temp
                }
            } else {
                val median = (left + right) shr 1
                var i = left + 1
                var j = right
                swap(ids, median, i)
                if (dists[ids[left]] > dists[ids[right]]) swap(ids, left, right)
                if (dists[ids[i]] > dists[ids[right]]) swap(ids, i, right)
                if (dists[ids[left]] > dists[ids[i]]) swap(ids, left, i)
                val temp = ids[i]
                val tempDist = dists[temp]
                while (true) {
                    do i++ while (dists[ids[i]] < tempDist)
                    do j-- while (dists[ids[j]] > tempDist)
                    if (j < i) break
                    swap(ids, i, j)
                }
                ids[left + 1] = ids[j]
                ids[j] = temp
                if (right - i + 1 >= j - left) {
                    quicksort(ids, dists, i, right)
                    quicksort(ids, dists, left, j - 1)
                } else {
                    quicksort(ids, dists, left, j - 1)
                    quicksort(ids, dists, i, right)
                }
            }
        }

        private fun swap(a: IntArray, i: Int, j: Int) {
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
    }
}
