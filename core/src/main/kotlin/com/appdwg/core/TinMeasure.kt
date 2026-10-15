package com.appdwg.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measuring on a TIN the same way the plan measures on the ground: a ray into the surface,
 * the area of the facets inside a polygon, the length of a line as it lies on the triangles,
 * and the cut and fill between two surfaces inside that polygon.
 */
object TinMeasure {
    /** Nearest hit on [surfaces], or null when the ray misses all of them. */
    fun nearestHit(surfaces: List<Surface>, ray: Ray): RayHit? {
        var best: RayHit? = null
        for (s in surfaces) {
            val hit = s.rayHit(ray.ox, ray.oy, ray.oz, ray.dx, ray.dy, ray.dz) ?: continue
            if (best == null || hit.t < best.t) best = hit
        }
        return best
    }

    /**
     * Area, elevations and slope of [surface] inside [polygon], plus cut and fill against [other]
     * when it is set. Null when the polygon does not cover the surface.
     * [facets] holds world-space triangles of the clip (9 doubles each) when [geometry] is true
     * and the clip stays small enough to draw.
     */
    fun area(surface: Surface, polygon: List<Point2>, other: Surface? = null, geometry: Boolean = false): AreaOnSurface? {
        if (polygon.size < 3) return null
        val ears = triangulate(polygon)
        if (ears.isEmpty()) return null
        val region = bounds(polygon, 1e-4)
        var surfaceArea = 0.0
        var planOn = 0.0
        var zWeighted = 0.0
        var slopeWeighted = 0.0
        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        val geom = ArrayList<Double>()
        var store = geometry
        val seen = HashSet<Int>()
        surface.forEachNear(region) { t ->
            if (!seen.add(t)) return@forEachNear
            val tri = triangle(surface, t)
            val slope = slopeOf(surface, t)
            for (e in ears.indices step 3) {
                val ear = listOf(polygon[ears[e]], polygon[ears[e + 1]], polygon[ears[e + 2]])
                val clip = clipConvex(tri, ear)
                if (clip.size < 3) continue
                val zs = DoubleArray(clip.size) { surface.zOn(t, clip[it].x, clip[it].y) }
                store = addFan(clip, zs, slope, geom, store) { plan, area3, meanZ, sl ->
                    planOn += plan
                    surfaceArea += area3
                    zWeighted += plan * meanZ
                    slopeWeighted += plan * sl
                }
                for (z in zs) {
                    if (z < minZ) minZ = z
                    if (z > maxZ) maxZ = z
                }
            }
        }
        if (planOn <= 0.0) return null
        val volumes = other?.let { cutFill(surface, it, polygon, ears) }
        return AreaOnSurface(
            surfaceName = surface.name,
            surfaceArea = surfaceArea,
            planArea = abs(shoelace(polygon)),
            perimeterPlan = ringLength(polygon),
            perimeter3d = ground(surface, polygon, closed = true)?.length ?: 0.0,
            minElevation = minZ,
            maxElevation = maxZ,
            averageElevation = zWeighted / planOn,
            averageSlopePercent = if (planOn > 0) slopeWeighted / planOn * 100 else 0.0,
            cut = volumes?.first,
            fill = volumes?.second,
            baseName = if (volumes != null) surface.name else null,
            otherName = other?.name,
            facets = if (store) geom.toDoubleArray() else DoubleArray(0),
        )
    }

    /** Length of [points] as it lies on [surface]. The closing edge is included when [closed]. */
    fun ground(surface: Surface, points: List<Point2>, closed: Boolean): GroundPath? {
        if (points.size < 2) return null
        val count = if (closed) points.size else points.size - 1
        var length = 0.0
        val seg = ArrayList<Double>()
        var any = false
        for (i in 0 until count) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val piece = trace(surface, a, b)
            if (piece.isEmpty()) continue
            any = true
            var k = 0
            while (k + 5 < piece.size) {
                val dx = piece[k + 3] - piece[k]
                val dy = piece[k + 4] - piece[k + 1]
                val dz = piece[k + 5] - piece[k + 2]
                length += sqrt(dx * dx + dy * dy + dz * dz)
                k += 6
            }
            seg.addAll(piece)
        }
        return if (any) GroundPath(length, seg.toDoubleArray()) else null
    }

    /**
     * Cut and fill between [base] and [other] inside [polygon].
     * Fill is where [other] is above [base]; cut is where it is below. Volumes are m³.
     */
    fun cutFill(base: Surface, other: Surface, polygon: List<Point2>): Pair<Double, Double>? {
        if (polygon.size < 3) return null
        val ears = triangulate(polygon)
        if (ears.isEmpty()) return null
        return cutFill(base, other, polygon, ears)
    }

    private fun cutFill(base: Surface, other: Surface, polygon: List<Point2>, ears: List<Int>): Pair<Double, Double>? {
        val region = bounds(polygon, 1e-4)
        val acc = doubleArrayOf(0.0, 0.0)
        var any = false
        val seenBase = HashSet<Int>()
        base.forEachNear(region) { bt ->
            if (!seenBase.add(bt)) return@forEachNear
            val bpoly = triangle(base, bt)
            for (e in ears.indices step 3) {
                val ear = listOf(polygon[ears[e]], polygon[ears[e + 1]], polygon[ears[e + 2]])
                val piece = clipConvex(bpoly, ear)
                if (piece.size < 3) continue
                val pb = bounds(piece, 1e-6)
                val seenOther = HashSet<Int>()
                other.forEachNear(pb) { ot ->
                    if (!seenOther.add(ot)) return@forEachNear
                    val sub = clipConvex(piece, triangle(other, ot))
                    if (sub.size < 3) return@forEachNear
                    for (i in 1 until sub.size - 1) {
                        val p0 = sub[0]; val p1 = sub[i]; val p2 = sub[i + 1]
                        if (abs(cross(p0, p1, p2)) <= 0) continue
                        val d0 = other.zOn(ot, p0.x, p0.y) - base.zOn(bt, p0.x, p0.y)
                        val d1 = other.zOn(ot, p1.x, p1.y) - base.zOn(bt, p1.x, p1.y)
                        val d2 = other.zOn(ot, p2.x, p2.y) - base.zOn(bt, p2.x, p2.y)
                        any = true
                        // A triangle the zero contour crosses has cut on one side and fill on the other.
                        splitVolume(acc, p0, d0, p1, d1, p2, d2)
                    }
                }
            }
        }
        return if (any) acc[0] to acc[1] else null
    }

    /** Adds the prism of one triangle, splitting it where the difference changes sign. [acc] is cut, fill. */
    private fun splitVolume(acc: DoubleArray, p0: Point2, d0: Double, p1: Point2, d1: Double, p2: Point2, d2: Double) {
        val eps = 1e-9
        fun sign(d: Double) = when {
            d > eps -> 1
            d < -eps -> -1
            else -> 0
        }
        val s0 = sign(d0); val s1 = sign(d1); val s2 = sign(d2)
        if (s0 * s1 >= 0 && s0 * s2 >= 0 && s1 * s2 >= 0) {
            addNet(acc, p0, d0, p1, d1, p2, d2)
            return
        }
        val ps = arrayOf(p0, p1, p2)
        val ds = doubleArrayOf(d0, d1, d2)
        val alone = if (s0 != 0 && s0 != s1 && s0 != s2) 0
            else if (s1 != 0 && s1 != s0 && s1 != s2) 1
            else 2
        val i1 = (alone + 1) % 3
        val i2 = (alone + 2) % 3
        val q1 = zeroOn(ps[alone], ds[alone], ps[i1], ds[i1])
        val q2 = zeroOn(ps[alone], ds[alone], ps[i2], ds[i2])
        addNet(acc, ps[alone], ds[alone], q1, 0.0, q2, 0.0)
        addNet(acc, q1, 0.0, ps[i1], ds[i1], ps[i2], ds[i2])
        addNet(acc, q1, 0.0, ps[i2], ds[i2], q2, 0.0)
    }

    private fun zeroOn(a: Point2, da: Double, b: Point2, db: Double): Point2 {
        val t = if (da == db) 0.0 else da / (da - db)
        return Point2(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }

    private fun addNet(acc: DoubleArray, p0: Point2, d0: Double, p1: Point2, d1: Double, p2: Point2, d2: Double) {
        val plan = abs(cross(p0, p1, p2)) / 2
        if (plan <= 0) return
        val vol = plan * (d0 + d1 + d2) / 3
        if (vol >= 0) acc[1] += vol else acc[0] -= vol
    }

    /** World-space segments (x y z x y z …) where the segment lies on the TIN. */
    private fun trace(surface: Surface, a: Point2, b: Point2): List<Double> {
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 1e-9) return emptyList()
        val region = Bounds(
            min(a.x, b.x) - 1e-4, min(a.y, b.y) - 1e-4,
            max(a.x, b.x) + 1e-4, max(a.y, b.y) + 1e-4,
        )
        val spans = ArrayList<Span>()
        val seen = HashSet<Int>()
        surface.forEachNear(region) { t ->
            if (!seen.add(t)) return@forEachNear
            val iv = segmentInTriangle(a, b, triangle(surface, t)) ?: return@forEachNear
            if (iv.second - iv.first > 1e-12) spans += Span(iv.first, iv.second, t)
        }
        if (spans.isEmpty()) return emptyList()
        val times = ArrayList<Double>(spans.size * 2)
        for (s in spans) { times += s.t0; times += s.t1 }
        times.sort()
        val unique = ArrayList<Double>()
        for (t in times) if (unique.isEmpty() || t - unique.last() > 1e-9) unique += t
        val out = ArrayList<Double>()
        for (i in 0 until unique.size - 1) {
            val t0 = unique[i]
            val t1 = unique[i + 1]
            val mid = (t0 + t1) / 2
            val span = spans.firstOrNull { mid >= it.t0 - 1e-9 && mid <= it.t1 + 1e-9 } ?: continue
            val p0 = at(a, b, t0)
            val p1 = at(a, b, t1)
            out += p0.x; out += p0.y; out += surface.zOn(span.tri, p0.x, p0.y)
            out += p1.x; out += p1.y; out += surface.zOn(span.tri, p1.x, p1.y)
        }
        return out
    }

    private class Span(val t0: Double, val t1: Double, val tri: Int)

    private fun at(a: Point2, b: Point2, t: Double) = Point2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Parameter range of segment a→b inside a CCW triangle, or null. */
    private fun segmentInTriangle(a: Point2, b: Point2, tri: List<Point2>): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.x - a.x
        val dy = b.y - a.y
        for (i in tri.indices) {
            val p = tri[i]
            val q = tri[(i + 1) % tri.size]
            val ex = q.x - p.x
            val ey = q.y - p.y
            val c0 = ex * (a.y - p.y) - ey * (a.x - p.x)
            val c1 = ex * dy - ey * dx
            if (abs(c1) < 1e-14) {
                if (c0 < -1e-8) return null
            } else {
                val t = -c0 / c1
                if (c1 > 0) { if (t > t0) t0 = t } else if (t < t1) t1 = t
            }
            if (t0 > t1 + 1e-12) return null
        }
        return if (t1 >= t0) t0 to t1 else null
    }

    /** Returns whether facet geometry is still being stored. */
    private fun addFan(
        clip: List<Point2>, zs: DoubleArray, slope: Double, geom: ArrayList<Double>, store: Boolean,
        add: (plan: Double, area3: Double, meanZ: Double, slope: Double) -> Unit,
    ): Boolean {
        var keep = store
        for (i in 1 until clip.size - 1) {
            val p0 = clip[0]; val p1 = clip[i]; val p2 = clip[i + 1]
            val plan = abs(cross(p0, p1, p2)) / 2
            if (plan <= 0) continue
            val z0 = zs[0]; val z1 = zs[i]; val z2 = zs[i + 1]
            val area3 = area3(p0, z0, p1, z1, p2, z2)
            if (keep) {
                if (geom.size + 9 > MAX_FACET_DOUBLES) { geom.clear(); keep = false }
                else {
                    geom += p0.x; geom += p0.y; geom += z0
                    geom += p1.x; geom += p1.y; geom += z1
                    geom += p2.x; geom += p2.y; geom += z2
                }
            }
            add(plan, area3, (z0 + z1 + z2) / 3, slope)
        }
        return keep
    }

    /** CCW triangle in plan. */
    private fun triangle(s: Surface, t: Int): List<Point2> {
        val i0 = s.triangles[3 * t]
        val i1 = s.triangles[3 * t + 1]
        val i2 = s.triangles[3 * t + 2]
        val pts = listOf(Point2(s.xs[i0], s.ys[i0]), Point2(s.xs[i1], s.ys[i1]), Point2(s.xs[i2], s.ys[i2]))
        return if (cross(pts[0], pts[1], pts[2]) < 0) listOf(pts[0], pts[2], pts[1]) else pts
    }

    /** Rise over run of the triangle. A vertical face contributes nothing (its plan area is zero). */
    private fun slopeOf(s: Surface, t: Int): Double {
        val i0 = s.triangles[3 * t]
        val i1 = s.triangles[3 * t + 1]
        val i2 = s.triangles[3 * t + 2]
        val ux = s.xs[i1] - s.xs[i0]; val uy = s.ys[i1] - s.ys[i0]; val uz = s.zs[i1] - s.zs[i0]
        val vx = s.xs[i2] - s.xs[i0]; val vy = s.ys[i2] - s.ys[i0]; val vz = s.zs[i2] - s.zs[i0]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        if (abs(nz) < 1e-12) return 0.0
        return hypot(nx, ny) / abs(nz)
    }

    private fun area3(a: Point2, za: Double, b: Point2, zb: Double, c: Point2, zc: Double): Double {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = zb - za
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = zc - za
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        return 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
    }

    /** Indices into [poly] of a triangulation. A convex fan is used when ear clipping gives up. */
    private fun triangulate(poly: List<Point2>): List<Int> {
        val n = poly.size
        if (n < 3) return emptyList()
        if (n == 3) return listOf(0, 1, 2)
        val idx = ArrayList<Int>(n)
        if (shoelace(poly) >= 0) for (i in 0 until n) idx += i else for (i in n - 1 downTo 0) idx += i
        val out = ArrayList<Int>()
        var guard = 0
        while (idx.size > 3 && guard++ < n * n) {
            var clipped = false
            var i = 0
            while (i < idx.size) {
                val ia = idx[(i + idx.size - 1) % idx.size]
                val ib = idx[i]
                val ic = idx[(i + 1) % idx.size]
                if (!ear(poly, idx, ia, ib, ic)) { i++; continue }
                out += ia; out += ib; out += ic
                idx.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
        else if (out.size < (n - 2) * 3) {
            out.clear()
            for (i in 1 until n - 1) { out += 0; out += i; out += i + 1 }
        }
        return out
    }

    private fun ear(poly: List<Point2>, idx: List<Int>, ia: Int, ib: Int, ic: Int): Boolean {
        val a = poly[ia]; val b = poly[ib]; val c = poly[ic]
        if (cross(a, b, c) <= 1e-12) return false
        for (j in idx) {
            if (j == ia || j == ib || j == ic) continue
            if (inTri(poly[j], a, b, c)) return false
        }
        return true
    }

    private fun inTri(p: Point2, a: Point2, b: Point2, c: Point2): Boolean =
        cross(a, b, p) >= -1e-9 && cross(b, c, p) >= -1e-9 && cross(c, a, p) >= -1e-9

    /** [subject] clipped to convex CCW [clip]. */
    private fun clipConvex(subject: List<Point2>, clip: List<Point2>): List<Point2> {
        var output = subject
        for (i in clip.indices) {
            if (output.isEmpty()) return emptyList()
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = output
            val next = ArrayList<Point2>(input.size + 1)
            var s = input.last()
            for (e in input) {
                val eIn = left(a, b, e)
                val sIn = left(a, b, s)
                if (eIn) {
                    if (!sIn) next += intersection(s, e, a, b)
                    next += e
                } else if (sIn) next += intersection(s, e, a, b)
                s = e
            }
            output = next
        }
        return output
    }

    private fun left(a: Point2, b: Point2, p: Point2) = cross(a, b, p) >= -1e-8

    private fun intersection(p: Point2, q: Point2, a: Point2, b: Point2): Point2 {
        val d1x = q.x - p.x; val d1y = q.y - p.y
        val d2x = b.x - a.x; val d2y = b.y - a.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-18) return p
        val t = ((a.x - p.x) * d2y - (a.y - p.y) * d2x) / den
        return Point2(p.x + t * d1x, p.y + t * d1y)
    }

    private fun cross(a: Point2, b: Point2, c: Point2) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun shoelace(p: List<Point2>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2
    }

    private fun ringLength(p: List<Point2>): Double {
        var s = 0.0
        for (i in p.indices) s += hypot(p[(i + 1) % p.size].x - p[i].x, p[(i + 1) % p.size].y - p[i].y)
        return s
    }

    private fun bounds(p: List<Point2>, pad: Double) = Bounds(
        p.minOf { it.x } - pad, p.minOf { it.y } - pad, p.maxOf { it.x } + pad, p.maxOf { it.y } + pad,
    )

    private const val MAX_FACET_DOUBLES = 180_000
}

/** 3D measure of one polygon on a surface. Cut and fill are set when a second surface was given. */
class AreaOnSurface(
    val surfaceName: String,
    /** Area of the TIN facets inside the polygon. */
    val surfaceArea: Double,
    /** Plan area of the polygon itself. */
    val planArea: Double,
    val perimeterPlan: Double,
    /** Length of the polygon boundary as it lies on the TIN. */
    val perimeter3d: Double,
    val minElevation: Double,
    val maxElevation: Double,
    /** Plan-area-weighted mean elevation of the facets. */
    val averageElevation: Double,
    /** Plan-area-weighted mean grade, in percent. */
    val averageSlopePercent: Double,
    /** Volume where [otherName] is below [baseName]. */
    val cut: Double?,
    /** Volume where [otherName] is above [baseName]. */
    val fill: Double?,
    val baseName: String?,
    val otherName: String?,
    /** World xyz of the clipped facets, nine doubles per triangle. Empty when not requested. */
    val facets: DoubleArray,
)

/** A line draped on a TIN: [length] in metres and world xyz pairs in [segments]. */
class GroundPath(val length: Double, val segments: DoubleArray)

/**
 * The same snap targets as the plan (COGO points, polyline vertices, alignment vertices),
 * limited to those near a hit so a huge drawing is not projected in full.
 */
object SnapTargets {
    fun near(
        project: LandXmlProject,
        userPoints: List<CogoPoint>,
        hidden: Set<String>,
        x: Double, y: Double, radius: Double,
        surface: Surface?,
        fallbackZ: Double,
    ): List<Point3> {
        if (radius <= 0) return emptyList()
        val r2 = radius * radius
        val out = ArrayList<Point3>()
        fun keep(px: Double, py: Double, z: Double) {
            val dx = px - x; val dy = py - y
            if (dx * dx + dy * dy > r2) return
            val zz = when {
                !z.isNaN() -> z
                surface != null -> surface.elevationAt(Point2(px, py)) ?: fallbackZ
                else -> fallbackZ
            }
            out += Point3(px, py, zz)
        }
        for (c in project.cgPoints) keep(c.position.x, c.position.y, c.position.z)
        if (POINTS_LAYER !in hidden) for (p in userPoints) keep(p.x, p.y, p.z)
        for (l in project.polylines) {
            if (l.layer in hidden) continue
            val zs = l.zs
            for (i in l.xs.indices) keep(l.xs[i], l.ys[i], zs?.get(i) ?: Double.NaN)
        }
        for (a in project.alignments) for (v in a.vertices) keep(v.x, v.y, Double.NaN)
        return out
    }

    /** Nearest target whose projection falls within [radiusPx] of the tap. Returns its plan position. */
    fun nearest(targets: List<Point3>, sx: Double, sy: Double, radiusPx: Double, project: (Point3) -> Pair<Double, Double>?): Point2? {
        var best: Point2? = null
        var bestD = radiusPx
        for (p in targets) {
            val s = project(p) ?: continue
            val d = hypot(s.first - sx, s.second - sy)
            if (d < bestD) {
                bestD = d
                best = Point2(p.x, p.y)
            }
        }
        return best
    }
}
