package com.appdwg.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Side slope of a cut or a fill: [ratio] horizontal metres per vertical metre (H:V), a bench every
 * [benchHeight] metres of height ([benchHeight] 0 = no benches) [benchWidth] wide, with a cross fall
 * of [benchFall] percent towards the toe of the slope above it.
 */
data class SlopeRule(val ratio: Double, val benchHeight: Double = 0.0, val benchWidth: Double = 0.0, val benchFall: Double = 0.0) {
    init { require(ratio > 0) { "inclinação do talude deve ser maior que zero" } }
}

/**
 * Platform ("plataforma") to design: a plan polygon whose edge elevation is either given per vertex
 * ([vertexElevations]) or a plane through [baseElevation] at the first vertex rising [gradePercent]
 * towards azimuth [gradeAzimuth] (degrees from north).
 */
data class PlatformDesign(
    val outline: List<Point2>,
    val baseElevation: Double,
    val gradePercent: Double = 0.0,
    val gradeAzimuth: Double = 0.0,
    val vertexElevations: List<Double>? = null,
    val cut: SlopeRule,
    val fill: SlopeRule,
    /** Spacing of the slope profiles along the edge, metres. */
    val step: Double = 2.0,
    /** Longest a slope may run looking for the terrain, metres. */
    val maxReach: Double = 300.0,
)

/** Designed surface with its edges, for drawing, volumes and export. */
class PlatformResult(
    val surface: Surface,
    /** Platform edge (where the slopes start): toe in cut, crest in fill. */
    val edge: List<Point3>,
    /** Daylight line (where the slopes meet the terrain), also the surface boundary. */
    val daylight: List<Point3>,
    /** Bench edges, each a polyline (may be several pieces). */
    val benches: List<List<Point3>>,
    /** Slope profiles that ran out of terrain before meeting it. */
    val unresolved: Int,
)

object Platform {
    /** Designs [d] against [terrain], naming the surface [name]. */
    fun design(d: PlatformDesign, terrain: Surface, name: String = "Plataforma"): PlatformResult {
        require(d.outline.size >= 3) { "a plataforma precisa de 3 vértices ou mais" }
        val ccw = signedArea(d.outline) > 0
        val poly = if (ccw) d.outline else d.outline.reversed()
        val vz = d.vertexElevations?.let { if (ccw) it else it.reversed() }
        val a0 = poly[0]
        val dirX = sin(Math.toRadians(d.gradeAzimuth)); val dirY = cos(Math.toRadians(d.gradeAzimuth))
        // Counterclockwise outline: the outward normal of an edge a→b is (dy, −dx).
        fun planeZ(p: Point2) = d.baseElevation + d.gradePercent / 100 * ((p.x - a0.x) * dirX + (p.y - a0.y) * dirY)

        // Edge samples with outward directions; convex corners get a fan of profiles.
        val starts = ArrayList<Point3>()
        val dirs = ArrayList<Point2>()
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            val za = vz?.get(i) ?: planeZ(a)
            val zb = vz?.get((i + 1) % n) ?: planeZ(b)
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len == 0.0) continue
            val nx = (b.y - a.y) / len; val ny = -(b.x - a.x) / len
            // Corner fan at a: from the previous edge's normal to this one's.
            val prev = poly[(i - 1 + n) % n]
            val plen = hypot(a.x - prev.x, a.y - prev.y)
            if (plen > 0) {
                val pnx = (a.y - prev.y) / plen; val pny = -(a.x - prev.x) / plen
                val turn = normalize(atan2(ny, nx) - atan2(pny, pnx))
                // On a counterclockwise outline the normals turn left at a convex corner.
                if (turn > 0) {
                    val steps = max(1, ceil(abs(turn) / Math.toRadians(15.0)).toInt())
                    for (k in 0..steps) {
                        val ang = atan2(pny, pnx) + turn * k / steps
                        starts += Point3(a.x, a.y, za); dirs += Point2(cos(ang), sin(ang))
                    }
                } else {
                    // Concave corner: one profile along the bisector.
                    val bx = pnx + nx; val by = pny + ny; val bl = hypot(bx, by)
                    starts += Point3(a.x, a.y, za); dirs += if (bl > 1e-9) Point2(bx / bl, by / bl) else Point2(nx, ny)
                }
            }
            val m = max(1, ceil(len / d.step).toInt())
            for (k in 1 until m) {
                val t = k.toDouble() / m
                starts += Point3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, za + (zb - za) * t)
                dirs += Point2(nx, ny)
            }
        }

        var unresolved = 0
        val profiles = starts.indices.map { i ->
            val p = profile(starts[i], dirs[i], d, terrain)
            if (p.second) unresolved++
            p.first
        }

        // Surface: the platform top by ear clipping, then strips between neighbouring profiles.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val tri = ArrayList<Int>()
        fun add(p: Point3): Int { xs += p.x; ys += p.y; zs += p.z; return xs.size - 1 }
        val ring = starts.map(::add)
        // The corner fans repeat their vertex; the top is clipped on the distinct points.
        val distinct = starts.indices.filter { i -> i == 0 || starts[i].x != starts[i - 1].x || starts[i].y != starts[i - 1].y }
            .filter { i -> i != starts.size - 1 || starts[i].x != starts[0].x || starts[i].y != starts[0].y }
        for (t in earClip(distinct.map { Point2(starts[it].x, starts[it].y) })) tri += ring[distinct[t]]
        val ids = profiles.mapIndexed { i, pts -> listOf(ring[i]) + pts.drop(1).map(::add) }
        for (i in ids.indices) {
            val a = ids[i]; val b = ids[(i + 1) % ids.size]
            val k = max(a.size, b.size)
            for (j in 0 until k - 1) {
                val a0i = a[min(j, a.size - 1)]; val a1 = a[min(j + 1, a.size - 1)]
                val b0 = b[min(j, b.size - 1)]; val b1 = b[min(j + 1, b.size - 1)]
                if (a0i != a1) { tri += a0i; tri += a1; tri += b0 }
                if (b0 != b1) { tri += a1; tri += b1; tri += b0 }
            }
        }
        val surface = Surface(name, xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray(), tri.toIntArray())

        // Bench edges: the k-th break of each profile, joined while neighbours have it.
        val benches = ArrayList<List<Point3>>()
        val maxBreaks = profiles.maxOf { it.size } - 2
        for (k in 1..maxBreaks) {
            var run = ArrayList<Point3>()
            for (p in profiles + profiles.take(1)) {
                if (p.size - 1 > k) run += p[k] else { if (run.size > 1) benches += run; run = ArrayList() }
            }
            if (run.size > 1) benches += run
        }
        return PlatformResult(surface, starts + starts.take(1), profiles.map { it.last() } + profiles.take(1).map { it.last() }, benches, unresolved)
    }

    /**
     * Breaks of the slope profile from [start] outward along [dir] until it meets the terrain: the
     * start, the bench edges and the daylight point. The flag is true when it never met the terrain.
     */
    fun profile(start: Point3, dir: Point2, d: PlatformDesign, terrain: Surface): Pair<List<Point3>, Boolean> {
        val ground0 = terrain.elevationAt(Point2(start.x, start.y)) ?: return listOf(start) to true
        val cut = ground0 > start.z
        val rule = if (cut) d.cut else d.fill
        val sign = if (cut) 1.0 else -1.0
        val pts = arrayListOf(start)
        var x = start.x; var y = start.y; var z = start.z; var run = 0.0
        fun ground(px: Double, py: Double) = terrain.elevationAt(Point2(px, py))
        // Walks a straight piece of the profile in small steps; returns the daylight point if met.
        fun walk(dx: Double, dz: Double): Point3? {
            val steps = max(1, ceil(hypot(dx, 1e-12) / 0.5).toInt())
            var px = x; var py = y; var pz = z
            var before = (ground(px, py) ?: return null) - pz
            for (s in 1..steps) {
                val nx = x + dir.x * dx * s / steps; val ny = y + dir.y * dx * s / steps; val nz = z + dz * s / steps
                val g = ground(nx, ny) ?: return null
                val after = g - nz
                // In cut the slope rises until it reaches the ground (difference drops to zero).
                if (sign * after <= 0) {
                    val f = before / (before - after)
                    return Point3(px + (nx - px) * f, py + (ny - py) * f, pz + (nz - pz) * f)
                }
                px = nx; py = ny; pz = nz; before = after
            }
            return null
        }
        // Stops at the last point still over the terrain when the slope runs off the surface.
        fun offTerrain(dx: Double) = ground(x + dir.x * dx, y + dir.y * dx) == null
        while (run < d.maxReach) {
            // Slope piece up to the next bench (or a long one without benches).
            val height = if (rule.benchHeight > 0) rule.benchHeight else d.maxReach / rule.ratio
            val dx = height * rule.ratio
            walk(dx, sign * height)?.let { pts += it; return pts to false }
            if (offTerrain(dx)) return pts to true
            x += dir.x * dx; y += dir.y * dx; z += sign * height; run += dx
            if (rule.benchHeight <= 0) break
            pts += Point3(x, y, z)
            if (rule.benchWidth > 0) {
                // Bench falls towards the toe of the slope above it: outward in cut, inward in fill.
                val dz = -sign * rule.benchWidth * rule.benchFall / 100
                walk(rule.benchWidth, dz)?.let { pts += it; return pts to false }
                if (offTerrain(rule.benchWidth)) return pts to true
                x += dir.x * rule.benchWidth; y += dir.y * rule.benchWidth; z += dz; run += rule.benchWidth
                pts += Point3(x, y, z)
            }
        }
        return pts to true
    }

    private fun normalize(a: Double): Double {
        var r = a
        while (r > Math.PI) r -= 2 * Math.PI
        while (r < -Math.PI) r += 2 * Math.PI
        return r
    }

    fun signedArea(p: List<Point2>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += (a.x - p[0].x) * (b.y - p[0].y) - (b.x - p[0].x) * (a.y - p[0].y) }
        return s / 2
    }

    /** Triangles (vertex indices) of a simple polygon by ear clipping; works for concave outlines. */
    fun earClip(p: List<Point2>): IntArray {
        val idx = p.indices.toMutableList()
        val ccw = signedArea(p) > 0
        val out = ArrayList<Int>()
        fun cross(a: Point2, b: Point2, c: Point2) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun inside(pt: Point2, a: Point2, b: Point2, c: Point2): Boolean {
            val d1 = cross(a, b, pt); val d2 = cross(b, c, pt); val d3 = cross(c, a, pt)
            return if (ccw) d1 >= 0 && d2 >= 0 && d3 >= 0 else d1 <= 0 && d2 <= 0 && d3 <= 0
        }
        var guard = 0
        while (idx.size > 3 && guard++ < p.size * p.size) {
            var clipped = false
            for (k in idx.indices) {
                val ia = idx[(k - 1 + idx.size) % idx.size]; val ib = idx[k]; val ic = idx[(k + 1) % idx.size]
                val a = p[ia]; val b = p[ib]; val c = p[ic]
                val cr = cross(a, b, c)
                if (if (ccw) cr <= 1e-12 else cr >= -1e-12) continue
                if (idx.any { it != ia && it != ib && it != ic && inside(p[it], a, b, c) }) continue
                out += ia; out += ib; out += ic
                idx.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
        return out.toIntArray()
    }
}
