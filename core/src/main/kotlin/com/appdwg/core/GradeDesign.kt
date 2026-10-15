package com.appdwg.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Grade the app proposes for an alignment, with its estimated earthwork. */
class GradeSuggestion(
    val kind: Kind,
    val pvis: List<Profile.Pvi>,
    /** Estimated from simplified sections; the corridor gives the exact figures. */
    val cut: Double,
    val fill: Double,
    /** Steepest grade, percent. */
    val maxGrade: Double,
) {
    enum class Kind { LEAST_EARTHWORK, BALANCED }
}

/**
 * Automatic grade ("greide") on a horizontal alignment: dynamic programming over candidate PVIs
 * every [GradeRules.pviSpacing] metres and elevations every few centimetres, keeping each grade
 * within the maximum and measuring earthwork on simplified sections (platform width plus cut or
 * fill slopes, on the ground averaged across the platform). The grade is then simplified to the
 * PVIs that matter and given vertical curves by K.
 */
object GradeDesign {
    /** Limits of the grade. */
    data class GradeRules(
        /** Steepest grade, percent. */
        val maxGrade: Double = 8.0,
        /** Distance between candidate PVIs, metres. */
        val pviSpacing: Double = 100.0,
        /** K of the vertical curves: length = K × |Δg| in percent. */
        val k: Double = 20.0,
        /** Elevations at the start and end; null ties the grade into the ground there. */
        val startElevation: Double? = null,
        val endElevation: Double? = null,
        /** Cut needed per m³ of fill (1 = none), for the balanced grade. */
        val swell: Double = 1.0,
    )

    /** Ground along the alignment: stations and the ground averaged across the platform there. */
    class Ground(val stations: DoubleArray, val z: DoubleArray)

    /** Ground every [step] metres along [a], averaged over the axis and both platform edges. */
    fun ground(a: Alignment, terrain: Surface, halfWidth: Double, step: Double = 10.0): Ground? {
        val st = ArrayList<Double>(); val zs = ArrayList<Double>()
        val n = max(1, ceil((a.endStation - a.startStation) / step).toInt())
        for (i in 0..n) {
            val s = min(a.endStation, a.startStation + i * step)
            val c = a.pointAt(s) ?: continue
            val p0 = a.pointAt(max(a.startStation, s - 0.5)) ?: continue; val p1 = a.pointAt(min(a.endStation, s + 0.5)) ?: continue
            val len = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y).takeIf { it > 0 } ?: continue
            val rx = (p1.y - p0.y) / len; val ry = -(p1.x - p0.x) / len
            val samples = listOf(0.0, -halfWidth, halfWidth).mapNotNull { o -> terrain.elevationAt(Point2(c.x + rx * o, c.y + ry * o)) }
            val z = terrain.elevationAt(c) ?: continue
            st += s; zs += (z + samples.average()) / 2
        }
        return if (st.size >= 2) Ground(st.toDoubleArray(), zs.toDoubleArray()) else null
    }

    /** Least-earthwork and balanced grades for [a] on [terrain] with the [template]. */
    fun suggest(a: Alignment, terrain: Surface, template: Template, rules: GradeRules, cancelled: () -> Boolean = { false }): List<GradeSuggestion> {
        val width = width(template.left) + width(template.right)
        val g = ground(a, terrain, width / 2) ?: return emptyList()
        val ditches = template.left.ditchWidth + template.right.ditchWidth
        val model = Model(g, width, ditches, template.pavement, template.cut.ratio, template.fill.ratio, rules)
        val least = model.solve(0.0, cancelled) ?: return emptyList()
        val out = arrayListOf(model.result(GradeSuggestion.Kind.LEAST_EARTHWORK, least))
        // Balanced: a price on cut against fill, found by bisection, until they match.
        var lo = -0.95; var hi = 0.95
        var best: DoubleArray? = null
        var bestNet = Double.MAX_VALUE
        for (iteration in 0 until 14) {
            val lambda = (lo + hi) / 2
            val z = model.solve(lambda, cancelled) ?: return out
            val (cut, fill) = model.volumes(z)
            val net = cut - fill * rules.swell
            if (abs(net) < bestNet) { bestNet = abs(net); best = z }
            if (abs(net) < 0.01 * (cut + fill) + 1) break
            if (net > 0) lo = lambda else hi = lambda
        }
        best?.let { out += model.result(GradeSuggestion.Kind.BALANCED, it) }
        return out
    }

    private fun width(s: SideTemplate) = s.laneWidth + s.shoulderWidth + s.gutterWidth

    private class Model(
        val g: Ground, val width: Double, val ditches: Double, val pavement: Double,
        val cutRatio: Double, val fillRatio: Double, val rules: GradeRules,
    ) {
        val start = g.stations.first(); val end = g.stations.last()
        /** Candidate PVI stations. */
        val nodes: DoubleArray = run {
            val n = max(1, ((end - start) / rules.pviSpacing).roundToInt())
            DoubleArray(n + 1) { start + (end - start) * it / n }
        }
        val zMin = g.z.min() - 30; val zMax = g.z.max() + 30
        /** Elevation step: fine enough for the grade, coarse enough to stay quick on a phone. */
        val dz = max(0.1, (zMax - zMin) / 600)
        val levels = ((zMax - zMin) / dz).toInt() + 1

        fun groundAt(s: Double): Double {
            var i = g.stations.binarySearch(s)
            if (i >= 0) return g.z[i]
            i = -i - 1
            if (i <= 0) return g.z.first()
            if (i >= g.stations.size) return g.z.last()
            val t = (s - g.stations[i - 1]) / (g.stations[i] - g.stations[i - 1])
            return g.z[i - 1] + (g.z[i] - g.z[i - 1]) * t
        }

        /** Ground samples between consecutive nodes, every 10 m at most. */
        val samples: Array<DoubleArray> = Array(nodes.size - 1) { k ->
            val a = nodes[k]; val b = nodes[k + 1]
            val m = max(1, ceil((b - a) / 10).toInt())
            DoubleArray(m) { j -> a + (b - a) * (j + 0.5) / m }
        }
        val sampleGround = Array(samples.size) { k -> DoubleArray(samples[k].size) { groundAt(samples[k][it]) } }

        /**
         * Cut and fill section areas for height [h] of the grade above the ground: the earthwork
         * runs [pavement] below the grade, and cut sections also open their ditches.
         */
        fun cutArea(h: Double): Double { val d = pavement - h; return if (d > 0) (width + ditches) * d + cutRatio * d * d else 0.0 }
        fun fillArea(h: Double): Double { val d = h - pavement; return if (d > 0) width * d + fillRatio * d * d else 0.0 }

        fun level(z: Double) = ((z - zMin) / dz).roundToInt().coerceIn(0, levels - 1)
        fun z(level: Int) = zMin + level * dz

        /** Elevation of each node minimising cut + fill + λ (cut − swell·fill). */
        fun solve(lambda: Double, cancelled: () -> Boolean): DoubleArray? {
            val n = nodes.size
            val inf = Double.MAX_VALUE
            var cost = DoubleArray(levels) { inf }
            val from = Array(n) { IntArray(levels) { -1 } }
            val first = level(rules.startElevation ?: groundAt(start))
            cost[first] = 0.0
            for (k in 0 until n - 1) {
                if (cancelled()) return null
                val len = nodes[k + 1] - nodes[k]
                val reach = max(1, floor(rules.maxGrade / 100 * len / dz).toInt())
                val s = samples[k]; val gz = sampleGround[k]
                val next = DoubleArray(levels) { inf }
                val prev = cost
                val back = from[k + 1]
                val stepLen = len / s.size
                Parallel.forChunks(levels, 64) { l0, l1 ->
                    for (j in l0 until l1) {
                        val zj = z(j)
                        var best = inf; var arg = -1
                        for (i in max(0, j - reach)..min(levels - 1, j + reach)) {
                            val c0 = prev[i]
                            if (c0 == inf) continue
                            val zi = z(i)
                            var c = c0
                            for (q in s.indices) {
                                val t = (s[q] - nodes[k]) / len
                                val h = zi + (zj - zi) * t - gz[q]
                                val cu = cutArea(h) * stepLen; val fi = fillArea(h) * stepLen
                                c += cu + fi + lambda * (cu - rules.swell * fi)
                                if (c >= best) break
                            }
                            if (c < best) { best = c; arg = i }
                        }
                        next[j] = best; back[j] = arg
                    }
                    true
                }
                cost = next
            }
            var last = rules.endElevation?.let { level(it) } ?: level(groundAt(end))
            if (cost[last] == Double.MAX_VALUE) {
                // The end cannot be reached within the grade limit: take the nearest reachable level.
                last = (0 until levels).filter { cost[it] < Double.MAX_VALUE }.minByOrNull { abs(it - last) } ?: return null
            }
            val zs = DoubleArray(n)
            var l = last
            for (k in n - 1 downTo 0) { zs[k] = z(l); if (k > 0) l = from[k][l] }
            return zs
        }

        fun volumes(zs: DoubleArray): Pair<Double, Double> {
            var cut = 0.0; var fill = 0.0
            for (k in samples.indices) {
                val len = nodes[k + 1] - nodes[k]
                val stepLen = len / samples[k].size
                for (q in samples[k].indices) {
                    val t = (samples[k][q] - nodes[k]) / len
                    val h = zs[k] + (zs[k + 1] - zs[k]) * t - sampleGround[k][q]
                    cut += cutArea(h) * stepLen; fill += fillArea(h) * stepLen
                }
            }
            return cut to fill
        }

        /** The grade through the nodes, keeping only PVIs that change it, with vertical curves. */
        fun result(kind: GradeSuggestion.Kind, zs: DoubleArray): GradeSuggestion {
            val keep = simplify(nodes, zs, 0.15)
            var pvis = keep.map { Profile.Pvi(nodes[it], zs[it]) }
            pvis = AlignmentBuilder.curvesFromK(pvis, rules.k).mapIndexed { i, p ->
                // A curve may not run past the middle of either neighbouring tangent.
                if (i == 0 || i == pvis.size - 1) p else
                    p.copy(curveLength = min(p.curveLength, min(p.station - pvis[i - 1].station, pvis[i + 1].station - p.station)))
            }
            val (cut, fill) = volumes(zs)
            var steep = 0.0
            for (i in 1 until pvis.size) steep = max(steep, abs((pvis[i].elevation - pvis[i - 1].elevation) / (pvis[i].station - pvis[i - 1].station) * 100))
            return GradeSuggestion(kind, pvis, cut, fill, steep)
        }
    }

    /** Indices of the points of the polyline (x, z) Douglas–Peucker keeps within [tolerance] metres. */
    internal fun simplify(x: DoubleArray, z: DoubleArray, tolerance: Double): List<Int> {
        val keep = BooleanArray(x.size)
        keep[0] = true; keep[x.size - 1] = true
        fun run(a: Int, b: Int) {
            if (b - a < 2) return
            var far = -1; var dmax = tolerance
            for (i in a + 1 until b) {
                val t = (x[i] - x[a]) / (x[b] - x[a])
                val d = abs(z[i] - (z[a] + (z[b] - z[a]) * t))
                if (d > dmax) { dmax = d; far = i }
            }
            if (far >= 0) { keep[far] = true; run(a, far); run(far, b) }
        }
        run(0, x.size - 1)
        return x.indices.filter { keep[it] }
    }
}
