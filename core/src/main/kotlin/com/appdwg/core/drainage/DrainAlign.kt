package com.appdwg.core.drainage

import com.appdwg.core.Point2
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * One drainage run: a continuous alignment (eixo de drenagem) with station 0 upstream.
 * A confluence or a branch ends the alignment. Parallel runs never share one.
 */
data class DrainAlignment(
    val id: String,
    val name: String,
    val deviceIds: List<String>,
    val nodes: List<DrainNode>,
    val length: Double,
    /** Device this run stops at, when it meets another run and is not merged into it. */
    val junctionId: String?,
)

data class DrainNode(
    val x: Double,
    val y: Double,
    val station: Double,
    val deviceId: String,
    val structure: Boolean,
)

data class DrainMetrics(
    val height: Double,
    val width: Double,
    val area: Double,
    val buried: Boolean,
    val section: List<SectionPoint> = emptyList(),
)

data class DrainProfile(
    val alignmentId: String,
    val name: String,
    val samples: List<DrainProfileSample>,
)

data class DrainProfileSample(
    val station: Double,
    val x: Double,
    val y: Double,
    val terrain: Double,
    val invert: Double,
    val top: Double,
    val depth: Double,
    val slope: Double,
    val deviceId: String,
    val structure: Boolean,
)

data class DrainCrossSection(
    val alignmentId: String,
    val station: Double,
    val terrain: List<SectionPoint>,
    val device: List<SectionPoint>,
    val trench: List<SectionPoint>,
    val deviceId: String,
)

object DrainAlignments {
    const val JOIN_METRES = 1.5

    fun build(
        devices: List<PlacedDevice>,
        names: Map<String, String> = emptyMap(),
        terrain: (Double, Double) -> Double? = { _, _ -> null },
    ): List<DrainAlignment> {
        val linear = devices.filterIsInstance<PlacedDevice.Linear>().filter { it.xs.size >= 2 && it.ys.size >= 2 }
        if (linear.isEmpty()) return emptyList()
        val oriented = LinkedHashMap<String, List<Point2>>()
        for (device in linear) oriented[device.id] = orient(device, terrain)
        val up = HashMap<String, Tip>()
        val down = HashMap<String, Tip>()
        for ((id, pts) in oriented) {
            up[id] = Tip(pts.first().x, pts.first().y)
            down[id] = Tip(pts.last().x, pts.last().y)
        }
        fun downstreamOf(id: String): List<String> {
            val tip = down[id] ?: return emptyList()
            return up.filter { it.key != id && tip.near(it.value) }.keys.toList()
        }
        fun upstreamOf(id: String): List<String> {
            val tip = up[id] ?: return emptyList()
            return down.filter { it.key != id && tip.near(it.value) }.keys.toList()
        }
        fun continues(from: String, to: String) = downstreamOf(from) == listOf(to) && upstreamOf(to) == listOf(from)
        val visited = HashSet<String>()
        val chains = ArrayList<Chain>()
        for (device in linear) {
            if (device.id in visited) continue
            val ups = upstreamOf(device.id)
            val prev = ups.singleOrNull()
            if (prev != null && continues(prev, device.id)) continue
            val ids = ArrayList<String>()
            var current = device.id
            while (current !in visited) {
                visited += current
                ids += current
                val next = downstreamOf(current).singleOrNull() ?: break
                if (!continues(current, next)) break
                current = next
            }
            val last = ids.last()
            val nexts = downstreamOf(last)
            val junction = when {
                nexts.size == 1 && upstreamOf(nexts[0]).size != 1 -> nexts[0]
                nexts.size > 1 -> nexts.first()
                else -> null
            }
            chains += Chain(ids, junction)
        }
        val points = devices.filterIsInstance<PlacedDevice.Point>()
        val built = chains.map { chain -> materialize(chain, oriented) }
        val withPoints = applyPoints(built, points)
        val order = devices.mapIndexed { index, device -> device.id to index }.toMap()
        return withPoints.sortedBy { order[it.deviceIds.firstOrNull()] ?: 0 }.mapIndexed { index, alignment ->
            val named = names[alignment.id]
            if (named.isNullOrBlank()) alignment.copy(name = autoName(index + 1, linear.first { it.id == alignment.deviceIds.first() }))
            else alignment.copy(name = named)
        }
    }

    fun profile(
        alignment: DrainAlignment,
        devices: List<PlacedDevice>,
        terrain: (Double, Double) -> Double?,
        metricsOf: (String) -> DrainMetrics,
    ): DrainProfile {
        val byId = devices.associateBy { it.id }
        val samples = ArrayList<DrainProfileSample>()
        for (id in alignment.deviceIds) {
            val device = byId[id] as? PlacedDevice.Linear ?: continue
            val path = orient(device, terrain)
            if (path.size < 2) continue
            val start = alignment.nodes.firstOrNull { it.deviceId == id && !it.structure }?.station ?: 0.0
            val dense = densify(path, 5.0)
            val distances = DoubleArray(dense.size)
            val grounds = DoubleArray(dense.size)
            var walked = 0.0
            for (i in dense.indices) {
                if (i > 0) walked += hypot(dense[i].x - dense[i - 1].x, dense[i].y - dense[i - 1].y)
                distances[i] = walked
                grounds[i] = terrain(dense[i].x, dense[i].y) ?: 0.0
            }
            val metrics = metricsOf(id)
            val designed = if (device.grade.mode == GradeMode.HYDRAULIC) {
                HydraulicGrade.design(
                    distances, grounds, metrics.height, metrics.width, metrics.area, device.grade.buried,
                    minSlope = device.grade.minSlope, minCover = if (device.grade.buried) device.grade.minCover else 0.0,
                    maxDepth = device.grade.maxDepth,
                    downstreamAtStart = false,
                ).samples
            } else {
                null
            }
            for (i in dense.indices) {
                val ground = grounds[i]
                val invert = designed?.get(i)?.invert ?: ground - if (metrics.buried) metrics.height + 0.6 else metrics.height
                val next = if (i + 1 < dense.size) designed?.get(i)?.slopeDown ?: 0.0 else 0.0
                samples += DrainProfileSample(
                    start + distances[i], dense[i].x, dense[i].y, ground, invert, invert + metrics.height,
                    max(0.0, ground - invert), next, id, structure = false,
                )
            }
        }
        for (node in alignment.nodes) {
            if (!node.structure) continue
            val near = samples.minByOrNull { abs(it.station - node.station) }
            val ground = terrain(node.x, node.y) ?: near?.terrain ?: 0.0
            val invert = near?.invert ?: ground
            samples += DrainProfileSample(node.station, node.x, node.y, ground, invert, near?.top ?: invert, max(0.0, ground - invert), 0.0, node.deviceId, true)
        }
        val ordered = samples.sortedBy { it.station }
        return DrainProfile(alignment.id, alignment.name, ordered)
    }

    fun sections(
        alignment: DrainAlignment,
        profile: DrainProfile,
        terrain: (Double, Double) -> Double?,
        metricsOf: (String) -> DrainMetrics,
        spacing: Double = 20.0,
    ): List<DrainCrossSection> {
        if (profile.samples.isEmpty() || alignment.nodes.size < 2) return emptyList()
        val stations = ArrayList<Double>()
        stations += profile.samples.first().station
        var s = stations.first() + spacing
        val end = profile.samples.last().station
        while (s < end - spacing * 0.25 && stations.size < 24) {
            stations += s
            s += spacing
        }
        if (end - stations.last() > 1.0) stations += end
        for (sample in profile.samples) if (sample.structure && stations.none { abs(it - sample.station) < 0.5 }) stations += sample.station
        return stations.take(24).map { station -> sectionAt(alignment, profile, station, terrain, metricsOf) }
    }

    fun sectionAt(
        alignment: DrainAlignment,
        profile: DrainProfile,
        station: Double,
        terrain: (Double, Double) -> Double?,
        metricsOf: (String) -> DrainMetrics,
    ): DrainCrossSection {
        val sample = profile.samples.minByOrNull { abs(it.station - station) } ?: profile.samples.first()
        val metrics = metricsOf(sample.deviceId)
        val (x, y, rightX, rightY) = pose(alignment.nodes, sample.station)
        val groundLine = ArrayList<SectionPoint>()
        for (offset in OFFSETS) {
            val px = x + rightX * offset
            val py = y + rightY * offset
            groundLine += SectionPoint(offset, terrain(px, py) ?: sample.terrain)
        }
        val device = deviceRing(metrics, sample.invert)
        val trench = trenchRing(metrics, sample.invert, sample.terrain)
        return DrainCrossSection(alignment.id, sample.station, groundLine, device, trench, sample.deviceId)
    }

    private fun deviceRing(metrics: DrainMetrics, invert: Double): List<SectionPoint> {
        val src = metrics.section
        if (src.size >= 3) {
            val minY = src.minOf { it.y }
            val mid = (src.minOf { it.x } + src.maxOf { it.x }) / 2.0
            return src.map { SectionPoint(it.x - mid, invert + (it.y - minY)) }
        }
        val half = metrics.width / 2.0
        return listOf(
            SectionPoint(-half, invert), SectionPoint(-half, invert + metrics.height),
            SectionPoint(half, invert + metrics.height), SectionPoint(half, invert),
        )
    }

    private fun trenchRing(metrics: DrainMetrics, invert: Double, ground: Double): List<SectionPoint> {
        val half = metrics.width / 2.0
        val bed = if (metrics.buried) HydraulicGrade.DEFAULT_BEDDING else 0.0
        val floor = invert - bed
        val dig = max(0.0, ground - floor)
        val bottom = half + HydraulicGrade.DEFAULT_CLEARANCE
        if (dig <= 0.02) {
            val fill = max(0.0, invert - ground)
            val toe = half + HydraulicGrade.DEFAULT_SIDE * fill
            return listOf(SectionPoint(-toe, ground), SectionPoint(-half, invert), SectionPoint(half, invert), SectionPoint(toe, ground))
        }
        val extra = if (dig > HydraulicGrade.SHORING_DEPTH) HydraulicGrade.DEFAULT_SIDE * dig else 0.0
        val top = bottom + extra
        return listOf(SectionPoint(-top, ground), SectionPoint(-bottom, floor), SectionPoint(bottom, floor), SectionPoint(top, ground))
    }

    private fun pose(nodes: List<DrainNode>, station: Double): Pose {
        var i = 0
        while (i + 1 < nodes.size && nodes[i + 1].station < station) i++
        val a = nodes[i]
        val b = nodes[minOf(i + 1, nodes.lastIndex)]
        val span = b.station - a.station
        val t = if (span < 1e-6) 0.0 else ((station - a.station) / span).coerceIn(0.0, 1.0)
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        val len = hypot(b.x - a.x, b.y - a.y)
        val tx = if (len < 1e-6) 1.0 else (b.x - a.x) / len
        val ty = if (len < 1e-6) 0.0 else (b.y - a.y) / len
        return Pose(x, y, ty, -tx)
    }

    private fun materialize(chain: Chain, oriented: Map<String, List<Point2>>): DrainAlignment {
        val raw = ArrayList<Triple<Double, Double, String>>()
        for (id in chain.ids) {
            val pts = oriented[id].orEmpty()
            for (point in pts) {
                val last = raw.lastOrNull()
                if (last != null && hypot(point.x - last.first, point.y - last.second) < 1e-3) continue
                raw += Triple(point.x, point.y, id)
            }
        }
        val nodes = station(raw.map { DrainNode(it.first, it.second, 0.0, it.third, false) })
        val first = chain.ids.first()
        return DrainAlignment("ed-$first", "", chain.ids, nodes, nodes.lastOrNull()?.station ?: 0.0, chain.junction)
    }

    private fun applyPoints(alignments: List<DrainAlignment>, points: List<PlacedDevice.Point>): List<DrainAlignment> {
        if (points.isEmpty()) return alignments
        val hits = HashMap<String, MutableList<Int>>()
        for (point in points) {
            for ((index, alignment) in alignments.withIndex()) {
                if (alignment.nodes.any { hypot(it.x - point.x, it.y - point.y) <= JOIN_METRES }) hits.getOrPut(point.id) { ArrayList() }.add(index)
            }
        }
        val next = alignments.map { it.copy(nodes = it.nodes.toMutableList()) }.toMutableList()
        for (point in points) {
            val where = hits[point.id].orEmpty().distinct()
            if (where.isEmpty()) continue
            val junction = where.size > 1
            for (index in where) {
                val alignment = next[index]
                val nodes = ArrayList(alignment.nodes)
                val nearest = nodes.indices.minByOrNull { hypot(nodes[it].x - point.x, nodes[it].y - point.y) } ?: continue
                val end = nearest == 0 || nearest == nodes.lastIndex
                if (end && hypot(nodes[nearest].x - point.x, nodes[nearest].y - point.y) > 0.05) {
                    val node = DrainNode(point.x, point.y, 0.0, point.id, true)
                    if (nearest == 0) nodes.add(0, node) else nodes += node
                } else if (!end) {
                    nodes.add(nearest + 1, DrainNode(point.x, point.y, 0.0, point.id, true))
                } else {
                    nodes[nearest] = nodes[nearest].copy(structure = true, deviceId = point.id)
                }
                val stationed = station(nodes)
                val junctionId = if (junction) point.id else alignment.junctionId
                next[index] = alignment.copy(nodes = stationed, length = stationed.lastOrNull()?.station ?: 0.0, junctionId = junctionId)
            }
        }
        return next
    }

    private fun station(nodes: List<DrainNode>): List<DrainNode> {
        var walked = 0.0
        val out = ArrayList<DrainNode>(nodes.size)
        for (i in nodes.indices) {
            if (i > 0) walked += hypot(nodes[i].x - nodes[i - 1].x, nodes[i].y - nodes[i - 1].y)
            out += nodes[i].copy(station = walked)
        }
        return out
    }

    private fun orient(device: PlacedDevice.Linear, terrain: (Double, Double) -> Double?): List<Point2> {
        val pts = device.xs.zip(device.ys).map { (x, y) -> Point2(x, y) }
        if (pts.size < 2) return pts
        val downStart = if (!device.grade.flowAuto) device.grade.flowToStart else {
            val z0 = terrain(pts.first().x, pts.first().y)
            val z1 = terrain(pts.last().x, pts.last().y)
            z0 != null && z1 != null && z0 < z1 - 0.01
        }
        return if (downStart) pts.asReversed() else pts
    }

    private fun densify(points: List<Point2>, step: Double): List<Point2> {
        val out = ArrayList<Point2>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (out.isEmpty()) out += a
            val span = hypot(b.x - a.x, b.y - a.y)
            if (span > step * 1.5) {
                var d = step
                while (d < span - step * 0.25) {
                    val t = d / span
                    out += Point2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    d += step
                }
            }
            out += b
        }
        return out
    }

    private fun autoName(index: Int, device: PlacedDevice.Linear): String {
        val side = when (device.side) {
            DeviceSide.LEFT -> "LE"
            DeviceSide.RIGHT -> "LD"
            DeviceSide.AXIS -> "Eixo"
        }
        val family = device.code.substringBefore(' ').ifBlank { device.code }
        return "ED-%02d %s %s".format(Locale.US, index, family, side)
    }

    private data class Tip(val x: Double, val y: Double) {
        fun near(other: Tip) = hypot(x - other.x, y - other.y) <= JOIN_METRES
    }

    private data class Chain(val ids: List<String>, val junction: String?)

    private data class Pose(val x: Double, val y: Double, val rightX: Double, val rightY: Double)

    private val OFFSETS = doubleArrayOf(-6.0, -4.0, -2.0, -1.0, 0.0, 1.0, 2.0, 4.0, 6.0)
}
