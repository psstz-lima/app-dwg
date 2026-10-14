package com.appdwg.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Drainage basin of one outlet: the cells draining through it, its outline, and the figures used to
 * size a culvert or a channel (area, main stream, time of concentration).
 */
class Basin(
    /** Outlet, on the stream cell the tapped point snapped to. */
    val outlet: Point2,
    val outletCell: Int,
    /** Cells of the basin, as grid indices. */
    val cells: IntArray,
    /** Plan area, m². */
    val area: Double,
    /** Outer boundary, closed (last point not repeated), counterclockwise. */
    val outline: List<Point2>,
    /** Perimeter of the outline, m. */
    val perimeter: Double,
    /** Longest flow path, from its head down to the outlet, with ground heights. */
    val mainStream: List<Point3>,
    /** Length of [mainStream], m. */
    val streamLength: Double,
    /** Height of the head of the main stream above the outlet, m. */
    val streamDrop: Double,
    val minElevation: Double,
    val maxElevation: Double,
    /** Mean ground slope of the basin, percent. */
    val meanSlope: Double,
) {
    /** Mean slope of the main stream (drop over length), m/m. */
    val streamSlope get() = if (streamLength > 0) streamDrop / streamLength else 0.0

    /** Compactness coefficient Kc = 0,28 P / √A: 1 for a circle; near 1 floods peak sharply. */
    val compactness get() = if (area > 0) 0.28 * perimeter / sqrt(area) else 0.0

    /** Form factor Kf = A / L²: low for long narrow basins. */
    val formFactor get() = if (streamLength > 0) area / (streamLength * streamLength) else 0.0

    /** Time of concentration by Kirpich (California Culverts Practice form), minutes: 57 (L³/H)^0,385, L in km, H in m. */
    val kirpichMinutes: Double get() {
        val lKm = streamLength / 1000
        return if (streamDrop <= 0 || lKm <= 0) 0.0 else 57 * (lKm.pow(3) / streamDrop).pow(0.385)
    }
}

/** Rainfall intensity–duration–frequency curve i = K·Tʳ / (t + b)ᶜ, in mm/h, with t in minutes and T in years. */
data class Idf(val k: Double, val a: Double, val b: Double, val c: Double) {
    fun intensity(minutes: Double, years: Double): Double = k * years.pow(a) / (minutes + b).pow(c)
}

/** Peak flow by the rational method. */
class RationalFlow(
    /** Duration used: the time of concentration, never under [Hydrology.MIN_TC_MINUTES]. */
    val minutes: Double,
    /** Rain intensity, mm/h. */
    val intensity: Double,
    /** Peak flow, m³/s. */
    val peak: Double,
    /** The basin is larger than the rational method is meant for. */
    val tooLarge: Boolean,
)

/** Suggested outlet: where a stream leaves an area or crosses a line, with the area draining to it. */
data class OutletSuggestion(val point: Point2, val cell: Int, val area: Double, val kind: Kind) {
    enum class Kind { CROSSING, LEAVES_AREA, LEAVES_TERRAIN }
}

object Hydrology {
    /** Shortest duration used in the rational method, minutes. */
    const val MIN_TC_MINUTES = 10.0

    /** Largest basin the rational method is commonly used for (2 km²). */
    const val RATIONAL_MAX_AREA = 2_000_000.0

    /**
     * Heights with every pit filled up to its spill point, raised by the smallest step inside
     * filled areas so water always finds a way out (priority flood, Barnes et al. 2014, ε variant).
     * NaN cells stay NaN and count as an outside edge.
     */
    fun filled(g: ElevationGrid, cancelled: () -> Boolean = { false }): DoubleArray? {
        val n = g.values.size
        val cols = g.cols; val rows = g.rows
        val z = DoubleArray(n) { g.values[it].toDouble() }
        val done = BooleanArray(n)
        val heap = Heap(n)
        // The grid edge and the cells next to missing data are where water can leave.
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            if (z[i].isNaN()) { done[i] = true; continue }
            var edge = r == 0 || c == 0 || r == rows - 1 || c == cols - 1
            if (!edge) for (k in 0 until 8) { if (g.values[i + DR[k] * cols + DC[k]].isNaN()) { edge = true; break } }
            if (edge) { done[i] = true; heap.push(z[i], i) }
        }
        var popped = 0
        while (heap.size > 0) {
            if (++popped % 65536 == 0 && cancelled()) return null
            val i = heap.pop()
            val c = i % cols; val r = i / cols
            for (k in 0 until 8) {
                val cc = c + DC[k]; val rr = r + DR[k]
                if (cc < 0 || rr < 0 || cc >= cols || rr >= rows) continue
                val j = rr * cols + cc
                if (done[j]) continue
                done[j] = true
                if (z[j] <= z[i]) z[j] = Math.nextUp(z[i])
                heap.push(z[j], j)
            }
        }
        return z
    }

    /**
     * D8 flow on the pit-filled heights: each cell drains to its steepest lower neighbour, and the
     * area through each cell is summed downstream.
     */
    fun flow(g: ElevationGrid, cancelled: () -> Boolean = { false }): FlowGrid? {
        val z = filled(g, cancelled) ?: return null
        val n = z.size
        val cols = g.cols; val rows = g.rows
        val d = g.cellSize
        val diag = d * sqrt(2.0)
        val next = IntArray(n) { -1 }
        val ok = Parallel.forChunks(rows, 16) { r0, r1 ->
            if (cancelled()) return@forChunks false
            for (r in r0 until r1) for (c in 0 until cols) {
                val i = r * cols + c
                val zi = z[i]
                if (zi.isNaN()) continue
                var best = 0.0; var to = -1
                for (k in 0 until 8) {
                    val cc = c + DC[k]; val rr = r + DR[k]
                    if (cc < 0 || rr < 0 || cc >= cols || rr >= rows) continue
                    val j = rr * cols + cc
                    val zj = z[j]
                    if (zj.isNaN()) continue
                    val drop = (zi - zj) / (if (k % 2 == 1) diag else d)
                    if (drop > best) { best = drop; to = j }
                }
                next[i] = to
            }
            true
        }
        if (!ok) return null
        // Topological order (cells with nothing upstream first), so no sort of a million cells.
        val upstream = IntArray(n)
        for (i in 0 until n) { val t = next[i]; if (t >= 0) upstream[t]++ }
        val count = IntArray(n) { if (z[it].isNaN()) 0 else 1 }
        val queue = IntArray(n)
        var head = 0; var tail = 0
        for (i in 0 until n) if (upstream[i] == 0 && !z[i].isNaN()) queue[tail++] = i
        while (head < tail) {
            val i = queue[head++]
            val t = next[i]
            if (t < 0) continue
            count[t] += count[i]
            if (--upstream[t] == 0) queue[tail++] = t
        }
        return FlowGrid(g, next, count)
    }

    /** Stream cell with the largest drainage area within [radius] metres of [p], or null off the grid. */
    fun snap(f: FlowGrid, p: Point2, radius: Double): Int? {
        val g = f.grid
        val cs = g.cellSize
        val c0 = ((p.x - g.bounds.minX) / cs).toInt(); val r0 = ((g.bounds.maxY - p.y) / cs).toInt()
        val k = max(0, (radius / cs).toInt())
        var best = -1
        for (r in r0 - k..r0 + k) for (c in c0 - k..c0 + k) {
            if (c < 0 || r < 0 || c >= g.cols || r >= g.rows) continue
            if ((c - c0) * (c - c0) + (r - r0) * (r - r0) > k * k + 1) continue
            val i = r * g.cols + c
            if (f.count[i] > 0 && (best < 0 || f.count[i] > f.count[best])) best = i
        }
        return best.takeIf { it >= 0 }
    }

    /** Basin draining through [outlet] (a grid cell index). */
    fun basin(f: FlowGrid, outlet: Int): Basin? {
        val g = f.grid
        if (outlet !in f.count.indices || f.count[outlet] <= 0) return null
        val cols = g.cols
        val cs = g.cellSize
        val diag = cs * sqrt(2.0)
        val (start, items) = f.upstreamIndex()
        // Upstream from the outlet, with the distance along the flow to it.
        val cells = IntArray(f.count[outlet])
        val dist = DoubleArray(g.values.size)
        var n = 0
        cells[n++] = outlet
        var h = 0
        while (h < n) {
            val i = cells[h++]
            for (q in start[i] until start[i + 1]) {
                val u = items[q]
                val step = if (u % cols != i % cols && u / cols != i / cols) diag else cs
                dist[u] = dist[i] + step
                if (n < cells.size) cells[n++] = u
            }
        }
        val inBasin = BooleanArray(g.values.size)
        for (k in 0 until n) inBasin[cells[k]] = true
        // Longest flow path: from the farthest cell down to the outlet.
        var far = outlet
        for (k in 0 until n) if (dist[cells[k]] > dist[far]) far = cells[k]
        val stream = ArrayList<Point3>()
        var i = far
        while (true) {
            val p = f.center(i)
            stream += Point3(p.x, p.y, g.values[i].toDouble())
            if (i == outlet) break
            i = f.next[i]
            if (i < 0) break
        }
        val length = dist[far]
        val drop = (g.values[far] - g.values[outlet]).toDouble().coerceAtLeast(0.0)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in 0 until n) { val v = g.values[cells[k]].toDouble(); lo = min(lo, v); hi = max(hi, v) }
        val slopes = TerrainAnalysis.slopes(g)
        var sum = 0.0; var m = 0
        for (k in 0 until n) { val s = slopes[cells[k]]; if (!s.isNaN()) { sum += s; m++ } }
        val outline = outline(g, inBasin)
        var perimeter = 0.0
        for (k in outline.indices) { val a = outline[k]; val b = outline[(k + 1) % outline.size]; perimeter += hypot(b.x - a.x, b.y - a.y) }
        return Basin(f.center(outlet), outlet, cells.copyOf(n), n * cs * cs, outline, perimeter, stream, length, drop, lo, hi, if (m > 0) sum / m else 0.0)
    }

    /** Peak flow of [b] by the rational method, Q = C·i·A / 3,6 (A in km²), for a return period of [years]. */
    fun rational(b: Basin, runoff: Double, idf: Idf, years: Double): RationalFlow {
        val t = max(b.kirpichMinutes, MIN_TC_MINUTES)
        val i = idf.intensity(t, years)
        return RationalFlow(t, i, runoff * i * (b.area / 1e6) / 3.6, b.area > RATIONAL_MAX_AREA)
    }

    /**
     * Places where a stream of at least [minArea] m² crosses [line]: the culverts a road along it
     * would need. Crossings closer than three cells are one.
     */
    fun crossings(f: FlowGrid, line: List<Point2>, minArea: Double): List<OutletSuggestion> {
        if (line.size < 2) return emptyList()
        val min = max(2, (minArea / f.cellArea).toInt())
        val out = ArrayList<OutletSuggestion>()
        val cs = f.grid.cellSize
        for (i in f.count.indices) {
            val j = f.next[i]
            if (f.count[i] < min || j < 0) continue
            val a = f.center(i); val b = f.center(j)
            for (k in 0 until line.size - 1) {
                val x = segmentCross(a, b, line[k], line[k + 1]) ?: continue
                out += OutletSuggestion(x, j, f.count[j] * f.cellArea, OutletSuggestion.Kind.CROSSING)
                break
            }
        }
        // Largest first; a smaller crossing next to a larger one is the same stream.
        out.sortByDescending { it.area }
        val kept = ArrayList<OutletSuggestion>()
        for (s in out) if (kept.none { hypot(it.point.x - s.point.x, it.point.y - s.point.y) < 3 * cs }) kept += s
        return kept
    }

    /**
     * Where water leaves the closed [polygon]: the cells inside whose flow goes out, largest first,
     * at most [limit], at least [minArea] m² each. The first is the natural outlet of the area.
     */
    fun exits(f: FlowGrid, polygon: List<Point2>, minArea: Double = 0.0, limit: Int = 5): List<OutletSuggestion> {
        if (polygon.size < 3) return emptyList()
        val g = f.grid
        val out = ArrayList<OutletSuggestion>()
        for (i in f.count.indices) {
            if (f.count[i] <= 0 || f.count[i] * f.cellArea < minArea) continue
            val p = f.center(i)
            if (!SurfaceEdit.inside(p, polygon)) continue
            val j = f.next[i]
            if (j >= 0 && SurfaceEdit.inside(f.center(j), polygon)) continue
            out += OutletSuggestion(p, i, f.count[i] * f.cellArea, OutletSuggestion.Kind.LEAVES_AREA)
        }
        out.sortByDescending { it.area }
        val kept = ArrayList<OutletSuggestion>()
        for (s in out) if (kept.size < limit && kept.none { hypot(it.point.x - s.point.x, it.point.y - s.point.y) < 5 * g.cellSize }) kept += s
        return kept
    }

    /** Main outlets of the whole terrain: cells whose flow leaves the grid, largest first. */
    fun terrainOutlets(f: FlowGrid, limit: Int = 5): List<OutletSuggestion> {
        val idx = f.count.indices.filter { f.count[it] > 1 && f.next[it] < 0 }.sortedByDescending { f.count[it] }.take(limit)
        return idx.map { OutletSuggestion(f.center(it), it, f.count[it] * f.cellArea, OutletSuggestion.Kind.LEAVES_TERRAIN) }
    }

    /** Outer boundary of the [inside] cells, counterclockwise, without points along straight runs. */
    internal fun outline(g: ElevationGrid, inside: BooleanArray): List<Point2> {
        val cols = g.cols; val rows = g.rows
        fun inn(c: Int, r: Int) = c in 0 until cols && r in 0 until rows && inside[r * cols + c]
        // Directed edges between cell corners, consistently around the inside. Corner (c, r) is the
        // north-west corner of cell (c, r); a key packs a corner into one Long.
        val nextOf = HashMap<Long, MutableList<Long>>()
        fun key(c: Int, r: Int) = c.toLong() shl 32 or (r.toLong() and 0xFFFFFFFFL)
        fun edge(a: Long, b: Long) { nextOf.getOrPut(a) { ArrayList(1) } += b }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (!inside[r * cols + c]) continue
            if (!inn(c, r - 1)) edge(key(c + 1, r), key(c, r))
            if (!inn(c - 1, r)) edge(key(c, r), key(c, r + 1))
            if (!inn(c, r + 1)) edge(key(c, r + 1), key(c + 1, r + 1))
            if (!inn(c + 1, r)) edge(key(c + 1, r + 1), key(c + 1, r))
        }
        // Rings; the longest is the outer boundary (holes are shorter for a basin).
        var best: List<Long> = emptyList()
        while (nextOf.isNotEmpty()) {
            val first = nextOf.keys.first()
            val ring = ArrayList<Long>()
            var k = first
            while (true) {
                ring += k
                val list = nextOf[k] ?: break
                val nx = list.removeAt(list.size - 1)
                if (list.isEmpty()) nextOf.remove(k)
                if (nx == first) break
                k = nx
            }
            if (ring.size > best.size) best = ring
        }
        val cs = g.cellSize
        fun pt(k: Long) = Point2(g.bounds.minX + (k shr 32).toInt() * cs, g.bounds.maxY - k.toInt() * cs)
        val pts = best.map(::pt)
        // Drop corners in the middle of straight runs.
        val out = ArrayList<Point2>()
        for (i in pts.indices) {
            val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) > 1e-9) out += b
        }
        var twice = 0.0
        for (i in out.indices) { val a = out[i]; val b = out[(i + 1) % out.size]; twice += a.x * b.y - b.x * a.y }
        return if (twice < 0) out.asReversed() else out
    }

    private fun segmentCross(a: Point2, b: Point2, c: Point2, d: Point2): Point2? {
        val rx = b.x - a.x; val ry = b.y - a.y
        val sx = d.x - c.x; val sy = d.y - c.y
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-12) return null
        val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den
        val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return Point2(a.x + rx * t, a.y + ry * t)
    }

    /** Neighbour offsets, clockwise from east; odd ones are diagonals. */
    private val DC = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** Min-heap of grid cells by height, in flat arrays. */
    private class Heap(capacity: Int) {
        private val keys = DoubleArray(capacity)
        private val items = IntArray(capacity)
        var size = 0
            private set

        fun push(key: Double, item: Int) {
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= key) break
                keys[i] = keys[p]; items[i] = items[p]; i = p
            }
            keys[i] = key; items[i] = item
        }

        fun pop(): Int {
            val top = items[0]
            val key = keys[--size]; val item = items[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && keys[c + 1] < keys[c]) c++
                if (keys[c] >= key) break
                keys[i] = keys[c]; items[i] = items[c]; i = c
            }
            keys[i] = key; items[i] = item
            return top
        }
    }
}
