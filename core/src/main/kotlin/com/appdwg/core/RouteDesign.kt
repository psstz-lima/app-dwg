package com.appdwg.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Route the app proposes from a start to an end point, with its grade. */
class RouteSuggestion(
    val kind: Kind,
    /** PIs of the horizontal alignment, the ends included, with curve radius and spiral length. */
    val pis: List<Pi>,
    val alignment: Alignment,
    /** Least-earthwork grade on it; null when it could not be worked out. */
    val grade: GradeSuggestion?,
) {
    enum class Kind { SHORTEST, BALANCED, GENTLEST }

    val length get() = alignment.length
}

/**
 * Automatic horizontal alignment ("traçado") from a start to an end point: a least-cost path over
 * the terrain, where a stretch costs its length plus a price for every metre it climbs or falls
 * beyond the maximum grade (the earthwork it would need), turned into PIs with curves no tighter
 * than the minimum radius for the design speed. Three weights of that price give a short route,
 * a balanced one and one that keeps to the grade, each with an automatic grade.
 */
object RouteDesign {
    data class RouteRules(
        /** Steepest grade, percent. */
        val maxGrade: Double = 8.0,
        /** Design speed, km/h: sets the minimum radius. */
        val speed: Double = 60.0,
        /** Maximum superelevation, percent. */
        val emax: Double = 8.0,
        /** Spiral length on each side of a curve, metres (0 = none). */
        val spiral: Double = 40.0,
        /** Areas the route may not cross (buildings, water, reserves). */
        val avoid: List<List<Point2>> = emptyList(),
        /** For the grades of the routes. */
        val k: Double = 20.0,
        val pviSpacing: Double = 100.0,
    )

    /** Minimum curve radius for [speed] km/h and [emax] % superelevation (DNIT side friction). */
    fun minRadius(speed: Double, emax: Double): Double {
        val friction = listOf(30 to 0.20, 40 to 0.18, 50 to 0.16, 60 to 0.15, 70 to 0.15, 80 to 0.14, 90 to 0.14, 100 to 0.13, 110 to 0.12, 120 to 0.11)
        val f = friction.lastOrNull { it.first <= speed }?.second ?: 0.20
        return speed * speed / (127 * (emax / 100 + f))
    }

    /** The routes from [start] to [end] on [terrain], shortest first. */
    fun suggest(
        start: Point2,
        end: Point2,
        terrain: Surface,
        template: Template,
        rules: RouteRules,
        cancelled: () -> Boolean = { false },
    ): List<RouteSuggestion> {
        val dist = hypot(end.x - start.x, end.y - start.y)
        if (dist < 10) return emptyList()
        // Search area: the box of both ends grown by half the distance (at least 300 m).
        val m = max(300.0, dist * 0.5)
        val sb = terrain.bounds ?: return emptyList()
        val region = Bounds(max(sb.minX, min(start.x, end.x) - m), max(sb.minY, min(start.y, end.y) - m),
            min(sb.maxX, max(start.x, end.x) + m), min(sb.maxY, max(start.y, end.y) + m))
        val cell = (max(region.width, region.height) / 300).coerceIn(5.0, 30.0)
        val cols = max(2, ceil(region.width / cell).toInt()); val rows = max(2, ceil(region.height / cell).toInt())
        val g = terrain.rasterize(Bounds(region.minX, region.maxY - rows * cell, region.minX + cols * cell, region.maxY), cols, rows, cancelled) ?: return emptyList()
        val blocked = BooleanArray(cols * rows)
        if (rules.avoid.isNotEmpty()) Parallel.forChunks(rows, 8) { r0, r1 ->
            for (r in r0 until r1) for (c in 0 until cols) {
                val p = Point2(g.bounds.minX + (c + 0.5) * cell, g.bounds.maxY - (r + 0.5) * cell)
                if (rules.avoid.any { it.size >= 3 && SurfaceEdit.inside(p, it) }) blocked[r * cols + c] = true
            }
            true
        }
        // The path keeps a margin of a few cells, which the curves may then use.
        val margin = dilate(blocked, cols, rows, 3)
        fun clear(a: Alignment): Boolean {
            if (rules.avoid.isEmpty()) return true
            var s = a.startStation
            while (s <= a.endStation) {
                val p = a.pointAt(s) ?: return false
                if (rules.avoid.any { it.size >= 3 && SurfaceEdit.inside(p, it) }) return false
                s += cell / 2
            }
            return true
        }
        val rmin = minRadius(rules.speed, rules.emax)
        val weights = listOf(RouteSuggestion.Kind.SHORTEST to 3.0, RouteSuggestion.Kind.BALANCED to 25.0, RouteSuggestion.Kind.GENTLEST to 150.0)
        val paths = Parallel.map(weights.size) { k -> path(g, margin, start, end, rules.maxGrade / 100, weights[k].second, cancelled) }
        if (cancelled()) return emptyList()
        val routes = ArrayList<RouteSuggestion>()
        for ((k, path) in paths.withIndex()) {
            if (path == null || path.size < 2) continue
            val pis = fitPis(path, rmin, rules.spiral, cell) { clear(AlignmentBuilder.fromPis("", it)) } ?: continue
            // The same route found twice adds nothing.
            if (routes.any { r -> r.pis.size == pis.size && r.pis.zip(pis).all { (a, b) -> hypot(a.position.x - b.position.x, a.position.y - b.position.y) < 2 * cell } }) continue
            val a = AlignmentBuilder.fromPis(tr("Traçado"), pis)
            val grade = GradeDesign.suggest(a, terrain, template, GradeDesign.GradeRules(rules.maxGrade, rules.pviSpacing, rules.k), cancelled)
                .firstOrNull { it.kind == GradeSuggestion.Kind.LEAST_EARTHWORK }
            routes += RouteSuggestion(weights[k].first, pis, a, grade)
        }
        return routes
    }

    /**
     * Least-cost path over the grid cells from [start] to [end] (A*, 16 directions so diagonals of
     * any slope come out straight). A step costs its length plus [weight] times the height it
     * climbs or falls beyond [maxGrade] (ratio). Null when the end cannot be reached.
     */
    internal fun path(g: ElevationGrid, blocked: BooleanArray, start: Point2, end: Point2, maxGrade: Double, weight: Double, cancelled: () -> Boolean): List<Point2>? {
        val cols = g.cols; val rows = g.rows; val cs = g.cellSize
        fun cellOf(p: Point2): Int {
            val c = ((p.x - g.bounds.minX) / cs).toInt().coerceIn(0, cols - 1)
            val r = ((g.bounds.maxY - p.y) / cs).toInt().coerceIn(0, rows - 1)
            return r * cols + c
        }
        val s = cellOf(start); val e = cellOf(end)
        if (g.values[s].isNaN() || g.values[e].isNaN()) return null
        val n = cols * rows
        val cost = DoubleArray(n) { Double.MAX_VALUE }
        val from = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val ec = e % cols; val er = e / cols
        fun h(i: Int) = hypot(((i % cols) - ec).toDouble(), ((i / cols) - er).toDouble()) * cs
        val heap = java.util.PriorityQueue<Long>(1024)
        // Entries pack the estimated total (as float bits, which order like the floats for
        // positive values) with the cell index.
        fun push(i: Int, f: Double) { heap.add((java.lang.Float.floatToIntBits(f.toFloat()).toLong() shl 32) or i.toLong()) }
        cost[s] = 0.0; push(s, h(s))
        var steps = 0
        while (heap.isNotEmpty()) {
            if (++steps % 20000 == 0 && cancelled()) return null
            val i = (heap.poll() and 0xFFFFFFFFL).toInt()
            if (closed[i]) continue
            closed[i] = true
            if (i == e) break
            val c = i % cols; val r = i / cols
            val zi = g.values[i]
            for (k in DC.indices) {
                val cc = c + DC[k]; val rr = r + DR[k]
                if (cc < 0 || rr < 0 || cc >= cols || rr >= rows) continue
                val j = rr * cols + cc
                if (closed[j] || blocked[j]) continue
                // A knight's move must not jump over a blocked cell.
                if (k >= 8) {
                    val sc = Integer.signum(DC[k]); val sr = Integer.signum(DR[k])
                    if (blocked[(r + sr) * cols + c + sc] || blocked[(r + DR[k] - sr) * cols + c + DC[k] - sc]) continue
                }
                val zj = g.values[j]
                if (zj.isNaN()) continue
                val len = LEN[k] * cs
                val excess = max(0.0, abs((zj - zi).toDouble()) - maxGrade * len)
                val nc = cost[i] + len + weight * excess
                if (nc < cost[j]) { cost[j] = nc; from[j] = i; push(j, nc + h(j)) }
            }
        }
        if (from[e] < 0 && e != s) return null
        val cells = ArrayList<Int>()
        var i = e
        while (i >= 0) { cells += i; if (i == s) break; i = from[i] }
        cells.reverse()
        val pts = cells.map { Point2(g.bounds.minX + (it % cols + 0.5) * cs, g.bounds.maxY - (it / cols + 0.5) * cs) }.toMutableList()
        pts[0] = start; pts[pts.size - 1] = end
        return pts
    }

    /**
     * PIs along [path] whose curves of at least [rmin] (with [spiral] each side) fit between them:
     * the path is simplified more and more until every pair of neighbouring curves fits on the
     * tangent between them. Radii grow up to three times the minimum where there is room.
     */
    internal fun fitPis(path: List<Point2>, rmin: Double, spiral: Double, cell: Double, ok: (List<Pi>) -> Boolean = { true }): List<Pi>? {
        var tol = cell
        repeat(30) {
            val keep = simplify(path, tol)
            val pts = keep.map { path[it] }
            val pis = radii(pts, rmin, spiral)
            if (pis != null && ok(pis)) return pis
            tol *= 1.3
        }
        return listOf(Pi(path.first()), Pi(path.last())).takeIf(ok)
    }

    /** [mask] grown by [k] cells in every direction. */
    private fun dilate(mask: BooleanArray, cols: Int, rows: Int, k: Int): BooleanArray {
        if (mask.none { it }) return mask
        val out = mask.copyOf()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (!mask[r * cols + c]) continue
            for (rr in max(0, r - k)..min(rows - 1, r + k)) for (cc in max(0, c - k)..min(cols - 1, c + k)) out[rr * cols + cc] = true
        }
        return out
    }

    /** Curves for the corners of [pts], or null when one cannot get [rmin] in the room it has. */
    private fun radii(pts: List<Point2>, rmin: Double, spiral: Double): List<Pi>? {
        if (pts.size == 2) return listOf(Pi(pts[0]), Pi(pts[1]))
        val n = pts.size
        val seg = DoubleArray(n - 1) { hypot(pts[it + 1].x - pts[it].x, pts[it + 1].y - pts[it].y) }
        val out = ArrayList<Pi>()
        out += Pi(pts[0])
        for (i in 1 until n - 1) {
            val ax = pts[i].x - pts[i - 1].x; val ay = pts[i].y - pts[i - 1].y
            val bx = pts[i + 1].x - pts[i].x; val by = pts[i + 1].y - pts[i].y
            val delta = acos(((ax * bx + ay * by) / (seg[i - 1] * seg[i])).coerceIn(-1.0, 1.0))
            if (delta < 1e-3) { out += Pi(pts[i]); continue }
            // Each curve may use half of a tangent shared with another curve, all of one at an end.
            val room = min(if (i == 1) seg[0] else seg[i - 1] / 2, if (i == n - 2) seg[i] else seg[i] / 2)
            val ls = min(spiral, rmin * delta * 0.8)
            val r = (room - ls / 2) / tan(delta / 2)
            if (r < rmin) return null
            out += Pi(pts[i], min(r, 3 * rmin), ls)
        }
        out += Pi(pts[n - 1])
        return out
    }

    /** Indices Douglas–Peucker keeps of [p] within [tolerance] metres. */
    private fun simplify(p: List<Point2>, tolerance: Double): List<Int> {
        val keep = BooleanArray(p.size)
        keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val dx = p[b].x - p[a].x; val dy = p[b].y - p[a].y
            val len = sqrt(dx * dx + dy * dy)
            var far = -1; var dmax = tolerance
            for (i in a + 1 until b) {
                val d = if (len < 1e-9) hypot(p[i].x - p[a].x, p[i].y - p[a].y) else abs((p[i].x - p[a].x) * dy - (p[i].y - p[a].y) * dx) / len
                if (d > dmax) { dmax = d; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.addLast(a to far); stack.addLast(far to b) }
        }
        return p.indices.filter { keep[it] }
    }

    /** 16 directions: the 8 neighbours and the knight's moves. */
    private val DC = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1, 2, 1, -1, -2, -2, -1, 1, 2)
    private val DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1, 1, 2, 2, 1, -1, -2, -2, -1)
    private val LEN = DoubleArray(16) { hypot(DC[it].toDouble(), DR[it].toDouble()) }
}
