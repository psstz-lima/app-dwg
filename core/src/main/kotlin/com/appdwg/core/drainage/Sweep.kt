package com.appdwg.core.drainage

import com.appdwg.core.Alignment
import com.appdwg.core.Point2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** How the section sits on the ground or on a grade. */
enum class GradeMode { SURFACE_TOP, SURFACE_INVERT, FIXED, SLOPE, PROFILE }

enum class DeviceSide { LEFT, AXIS, RIGHT }

enum class PathSource { POLYLINE, ALIGNMENT, LINE }

data class GradeSpec(
    val mode: GradeMode,
    /** Invert elevation at the start, for a fixed grade or a constant slope. */
    val z0: Double = 0.0,
    /** Invert elevation at the end of a fixed grade. */
    val z1: Double = 0.0,
    /** Depth of the invert below the terrain, for [GradeMode.SURFACE_INVERT]. */
    val depth: Double = 0.0,
    /** Rise over run (0.01 is 1 %), for [GradeMode.SLOPE]. */
    val slope: Double = 0.0,
)

/** One vertex of the path the section is swept along. [corner] is a miter; a curve sample is not. */
data class PathNode(val x: Double, val y: Double, val distance: Double, val station: Double, val corner: Boolean)

data class SweepFrame(
    val x: Double,
    val y: Double,
    val distance: Double,
    val station: Double,
    val rightX: Double,
    val rightY: Double,
    val miter: Double,
    val invertZ: Double,
    val topZ: Double,
)

/**
 * Solid produced by sweeping a section. [triangles] is world xyz, nine doubles per triangle.
 * [volume] is the closed solid, so a prism is the section area times the path length.
 */
class SweepMesh(
    val frames: List<SweepFrame>,
    val triangles: DoubleArray,
    val volume: Double,
    val length: Double,
    val footprint: List<Point2>,
    val invert: List<Point2>,
    val simplified: Boolean,
)

object Sweep {
    /** Samples along a long path. A 10 km run stays on this budget instead of one vertex per metre. */
    const val MAX_SAMPLES = 2_000

    /** Triangles uploaded for one project. Kept under the plan's drawn-triangle budget. */
    const val MAX_TRIANGLES = 24_000

    fun signedOffset(side: DeviceSide, offset: Double): Double = when (side) {
        DeviceSide.LEFT -> -abs(offset)
        DeviceSide.RIGHT -> abs(offset)
        DeviceSide.AXIS -> offset
    }

    /** Polyline vertices, clipped to distances [from] and [to] along the line. */
    fun polyline(xs: DoubleArray, ys: DoubleArray, from: Double?, to: Double?): List<PathNode> {
        if (xs.size < 2 || xs.size != ys.size) return emptyList()
        val raw = ArrayList<PathNode>(xs.size)
        var distance = 0.0
        for (i in xs.indices) {
            if (i > 0) distance += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            raw += PathNode(xs[i], ys[i], distance, distance, corner = true)
        }
        val end = raw.last().distance
        val a = (from ?: 0.0).coerceIn(0.0, end)
        val b = (to ?: end).coerceIn(a, end)
        if (b - a < 1e-6) return emptyList()
        return clip(raw, a, b)
    }

    /** Alignment between stations, with arc samples that are not mitered and breaks that are. */
    fun alignment(alignment: Alignment, from: Double?, to: Double?): List<PathNode> {
        if (alignment.vertices.size < 2) return emptyList()
        val a = (from ?: alignment.startStation).coerceIn(alignment.startStation, alignment.endStation)
        val b = (to ?: alignment.endStation).coerceIn(a, alignment.endStation)
        if (b - a < 1e-6) return emptyList()
        val step = ((b - a) / (MAX_SAMPLES - 1)).coerceAtLeast(1.0)
        val stations = ArrayList<Double>()
        stations += a
        var s = a
        while (s + step < b - 1e-6) {
            s += step
            stations += s
        }
        if (stations.last() < b - 1e-6) stations += b
        val points = stations.map { alignment.pointAt(it) ?: return emptyList() }
        val nodes = ArrayList<PathNode>(points.size)
        var distance = 0.0
        for (i in points.indices) {
            if (i > 0) distance += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            val turn = turnAngle(points, i)
            nodes += PathNode(points[i].x, points[i].y, distance, stations[i], corner = turn > CORNER_RAD)
        }
        return nodes
    }

    /**
     * Sweeps [section] along [nodes]. The section's x axis points to the right of travel.
     * A corner miters the joint; a curve sample stays perpendicular to the tangent.
     */
    fun sweep(
        section: CrossSection,
        nodes: List<PathNode>,
        signedOffset: Double,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double? = { _, _ -> null },
        profileAt: (Double) -> Double? = { null },
        simplified: Boolean = false,
    ): SweepMesh? {
        val ring = section.points
        if (ring.size < 3 || nodes.size < 2) return null
        val dense = densify(nodes)
        if (dense.size < 2) return null
        val minY = ring.minOf { it.y }
        val maxY = ring.maxOf { it.y }
        val minX = ring.minOf { it.x }
        val maxX = ring.maxOf { it.x }
        val invertX = ring.filter { abs(it.y - minY) < 1e-9 }.map { it.x }.average()
        val length = dense.last().distance - dense.first().distance
        if (length < 1e-6) return null
        val frames = ArrayList<SweepFrame>(dense.size)
        val placed = ArrayList<List<Triple<Double, Double, Double>>>(dense.size)
        for (i in dense.indices) {
            val node = dense[i]
            val tangent = tangentAt(dense, i)
            val miter = if (node.corner) miterOf(dense, i) else 1.0
            val rightX = tangent.second
            val rightY = -tangent.first
            val cx = node.x + rightX * signedOffset * miter
            val cy = node.y + rightY * signedOffset * miter
            val reference = referenceZ(grade, node.distance - dense.first().distance, length, node.station, terrain(cx, cy), profileAt(node.station))
            val refY = when (grade.mode) {
                GradeMode.SURFACE_TOP -> maxY
                GradeMode.PROFILE -> 0.0
                else -> minY
            }
            val world = ring.map { point ->
                val across = (point.x + signedOffset) * miter
                Triple(
                    node.x + rightX * across,
                    node.y + rightY * across,
                    reference + (point.y - refY),
                )
            }
            placed += world
            frames += SweepFrame(
                node.x, node.y, node.distance, node.station, rightX, rightY, miter,
                invertZ = reference + (minY - refY),
                topZ = reference + (maxY - refY),
            )
        }
        val triangles = loft(placed)
        val left = frames.map { frame ->
            val across = (minX + signedOffset) * frame.miter
            Point2(frame.x + frame.rightX * across, frame.y + frame.rightY * across)
        }
        val right = frames.map { frame ->
            val across = (maxX + signedOffset) * frame.miter
            Point2(frame.x + frame.rightX * across, frame.y + frame.rightY * across)
        }
        val invert = frames.map { frame ->
            val across = (invertX + signedOffset) * frame.miter
            Point2(frame.x + frame.rightX * across, frame.y + frame.rightY * across)
        }
        return SweepMesh(frames, triangles, volume(triangles), length, left + right.asReversed(), invert, simplified)
    }

    /** A point device: the catalog section swept one metre, or a labelled box when the section is missing. */
    fun point(
        section: CrossSection?,
        x: Double,
        y: Double,
        rotationDeg: Double,
        skewDeg: Double,
        grade: GradeSpec,
        terrainZ: Double?,
        profileZ: Double?,
        box: Double = 1.0,
    ): SweepMesh {
        val heading = Math.toRadians(rotationDeg)
        val dx = cos(heading)
        val dy = sin(heading)
        val skew = tan(Math.toRadians(skewDeg.coerceIn(-60.0, 60.0)))
        val ring = section?.points.orEmpty()
        if (ring.size >= 3) {
            val sheared = ring.map { SectionPoint(it.x + it.y * skew, it.y) }
            val shaped = CrossSection(section?.type ?: "", section?.units ?: "m", listOf(sheared))
            val nodes = listOf(
                PathNode(x, y, 0.0, 0.0, corner = false),
                PathNode(x + dx, y + dy, 1.0, 1.0, corner = false),
            )
            val mesh = sweep(shaped, nodes, 0.0, grade, { _, _ -> terrainZ }, { profileZ }, simplified = false)
            if (mesh != null) return mesh
        }
        val half = box / 2.0
        val z = referenceZ(grade, 0.0, 1.0, 0.0, terrainZ, profileZ)
        val refY = if (grade.mode == GradeMode.SURFACE_TOP) box else 0.0
        val bottom = z - refY
        val corners = listOf(
            local(x, y, -half, -half, dx, dy, skew),
            local(x, y, half, -half, dx, dy, skew),
            local(x, y, half, half, dx, dy, skew),
            local(x, y, -half, half, dx, dy, skew),
        )
        val low = corners.map { Triple(it.x, it.y, bottom) }
        val high = corners.map { Triple(it.x, it.y, bottom + box) }
        val triangles = loft(listOf(low, high))
        val footprint = corners + corners.first()
        return SweepMesh(
            emptyList(), triangles, volume(triangles), 0.0, footprint, listOf(Point2(x, y)), simplified = true,
        )
    }

    private fun local(ox: Double, oy: Double, lx: Double, ly: Double, dx: Double, dy: Double, skew: Double): Point2 {
        val sx = lx + ly * skew
        return Point2(ox + sx * dx - ly * dy, oy + sx * dy + ly * dx)
    }

    private fun referenceZ(grade: GradeSpec, distance: Double, length: Double, station: Double, terrainZ: Double?, profileZ: Double?): Double {
        val ground = terrainZ ?: 0.0
        return when (grade.mode) {
            GradeMode.SURFACE_TOP -> ground
            GradeMode.SURFACE_INVERT -> ground - grade.depth
            GradeMode.FIXED -> grade.z0 + (grade.z1 - grade.z0) * (if (length <= 0.0) 0.0 else distance / length)
            GradeMode.SLOPE -> grade.z0 + grade.slope * distance
            GradeMode.PROFILE -> profileZ ?: grade.z0
        }.let { if (station.isNaN()) it else it }
    }

    private fun densify(nodes: List<PathNode>): List<PathNode> {
        val length = nodes.last().distance - nodes.first().distance
        val step = (length / (MAX_SAMPLES - 1)).coerceAtLeast(0.5)
        if (length <= step) return nodes
        val out = ArrayList<PathNode>()
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (out.isEmpty()) out += a
            val span = b.distance - a.distance
            if (span > step * 1.5) {
                var d = a.distance + step
                while (d < b.distance - step * 0.25) {
                    val t = (d - a.distance) / span
                    val station = a.station + (b.station - a.station) * t
                    out += PathNode(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, d, station, corner = false)
                    d += step
                }
            }
            out += b
            if (out.size > MAX_SAMPLES * 2) break
        }
        if (out.size <= MAX_SAMPLES) return out
        val stride = out.size / MAX_SAMPLES + 1
        val kept = ArrayList<PathNode>()
        for (i in out.indices) if (i == 0 || i == out.lastIndex || out[i].corner || i % stride == 0) kept += out[i]
        return kept
    }

    private fun clip(nodes: List<PathNode>, from: Double, to: Double): List<PathNode> {
        val out = ArrayList<PathNode>()
        fun at(a: PathNode, b: PathNode, distance: Double): PathNode {
            val span = b.distance - a.distance
            val t = if (span == 0.0) 0.0 else (distance - a.distance) / span
            return PathNode(
                a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, distance,
                a.station + (b.station - a.station) * t, corner = false,
            )
        }
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (b.distance < from - 1e-9 || a.distance > to + 1e-9) continue
            val start = if (a.distance < from) at(a, b, from) else a
            val end = if (b.distance > to) at(a, b, to) else b
            if (out.isEmpty() || hypot(out.last().x - start.x, out.last().y - start.y) > 1e-8) out += start
            if (hypot(out.last().x - end.x, out.last().y - end.y) > 1e-8) out += end
        }
        return out
    }

    private fun tangentAt(nodes: List<PathNode>, index: Int): Pair<Double, Double> {
        val node = nodes[index]
        if (node.corner && index > 0 && index < nodes.lastIndex) {
            val inn = unit(nodes[index].x - nodes[index - 1].x, nodes[index].y - nodes[index - 1].y)
            val out = unit(nodes[index + 1].x - nodes[index].x, nodes[index + 1].y - nodes[index].y)
            return unit(inn.first + out.first, inn.second + out.second)
        }
        val a = nodes[(index - 1).coerceAtLeast(0)]
        val b = nodes[(index + 1).coerceAtMost(nodes.lastIndex)]
        return unit(b.x - a.x, b.y - a.y)
    }

    private fun miterOf(nodes: List<PathNode>, index: Int): Double {
        if (index <= 0 || index >= nodes.lastIndex) return 1.0
        val inn = unit(nodes[index].x - nodes[index - 1].x, nodes[index].y - nodes[index - 1].y)
        val out = unit(nodes[index + 1].x - nodes[index].x, nodes[index + 1].y - nodes[index].y)
        val dot = (inn.first * out.first + inn.second * out.second).coerceIn(-1.0, 1.0)
        val half = acos(dot) / 2.0
        val cosHalf = cos(half).coerceAtLeast(0.25)
        return (1.0 / cosHalf).coerceAtMost(4.0)
    }

    private fun turnAngle(points: List<Point2>, index: Int): Double {
        if (index <= 0 || index >= points.lastIndex) return 0.0
        val inn = unit(points[index].x - points[index - 1].x, points[index].y - points[index - 1].y)
        val out = unit(points[index + 1].x - points[index].x, points[index + 1].y - points[index].y)
        val dot = (inn.first * out.first + inn.second * out.second).coerceIn(-1.0, 1.0)
        return acos(dot)
    }

    private fun unit(x: Double, y: Double): Pair<Double, Double> {
        val length = hypot(x, y)
        if (length < 1e-12) return 1.0 to 0.0
        return x / length to y / length
    }

    private fun loft(frames: List<List<Triple<Double, Double, Double>>>): DoubleArray {
        if (frames.size < 2) return DoubleArray(0)
        val n = frames.first().size
        val tris = ArrayList<Double>()
        fun add(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>, c: Triple<Double, Double, Double>) {
            tris += a.first; tris += a.second; tris += a.third
            tris += b.first; tris += b.second; tris += b.third
            tris += c.first; tris += c.second; tris += c.third
        }
        fun cap(frame: List<Triple<Double, Double, Double>>, reverse: Boolean) {
            for (i in 1 until n - 1) {
                if (reverse) add(frame[0], frame[i + 1], frame[i]) else add(frame[0], frame[i], frame[i + 1])
            }
        }
        cap(frames.first(), reverse = true)
        for (k in 0 until frames.size - 1) {
            val a = frames[k]
            val b = frames[k + 1]
            for (i in 0 until n) {
                val j = (i + 1) % n
                add(a[i], a[j], b[j])
                add(a[i], b[j], b[i])
            }
        }
        cap(frames.last(), reverse = false)
        if (tris.size / 9 > MAX_TRIANGLES) {
            val keep = MAX_TRIANGLES * 9
            return DoubleArray(keep) { tris[it] }
        }
        return tris.toDoubleArray()
    }

    private fun volume(triangles: DoubleArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 8 < triangles.size) {
            val ax = triangles[i]; val ay = triangles[i + 1]; val az = triangles[i + 2]
            val bx = triangles[i + 3]; val by = triangles[i + 4]; val bz = triangles[i + 5]
            val cx = triangles[i + 6]; val cy = triangles[i + 7]; val cz = triangles[i + 8]
            sum += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
            i += 9
        }
        return abs(sum) / 6.0
    }

    private const val CORNER_RAD = 12.0 * Math.PI / 180.0
}

/** Plan drawing of one placed device: footprint, invert and the direction of flow. */
data class DrainageGlyph(
    val id: String,
    val footprint: List<Point2>,
    val invert: List<Point2>,
    val arrows: List<Pair<Point2, Double>>,
    val label: String?,
    val simplified: Boolean,
)

data class DeviceTakeoff(
    val id: String,
    val code: String,
    val length: Double,
    val concrete: Double,
    val excavation: Double,
    val formwork: Double,
    val formworkKnown: Boolean,
) {
    operator fun plus(other: DeviceTakeoff) = DeviceTakeoff(
        "", "", length + other.length, concrete + other.concrete, excavation + other.excavation,
        formwork + other.formwork, formworkKnown || other.formworkKnown,
    )
}

object DrainageTakeoff {
    fun of(id: String, device: DeviceDef, length: Double, section: CrossSection?, point: Boolean): DeviceTakeoff {
        val base = device.section?.areaM2
        val next = section?.areaM2
        val scale = if (base != null && base > 1e-9 && next != null) next / base else 1.0
        val items = device.quantities.items
        val plain = items.filter { it.method == null }
        val used = plain.ifEmpty {
            val method = items.firstOrNull { it.method != null }?.method
            if (method == null) items else items.filter { it.method == method }
        }
        var concrete = 0.0
        var excavation = 0.0
        var formwork = 0.0
        var formworkKnown = false
        for (item in used) {
            val factor = if (item.perMetre) length else if (point) 1.0 else length
            val amount = item.value * factor * if (item.perMetre) scale else 1.0
            val unit = item.unit.lowercase()
            val name = item.name.lowercase()
            when {
                "escav" in name && unit.startsWith("m3") -> excavation += amount
                "concreto" in name && unit.startsWith("m3") -> concrete += amount
                "forma" in name && unit.startsWith("m2") -> { formwork += amount; formworkKnown = true }
            }
        }
        val cut = section?.excavationM3PerM
        if (excavation == 0.0 && cut != null) excavation = cut * length
        return DeviceTakeoff(id, device.code, if (point) 0.0 else length, concrete, excavation, formwork, formworkKnown)
    }

    fun total(rows: List<DeviceTakeoff>): DeviceTakeoff = rows.fold(DeviceTakeoff("", "", 0.0, 0.0, 0.0, 0.0, false), DeviceTakeoff::plus)
}

/** Arrows along [invert], capped so a long device does not draw one per metre. */
fun flowArrows(invert: List<Point2>, length: Double): List<Pair<Point2, Double>> {
    if (invert.size < 2 || length < 1e-6) return emptyList()
    val spacing = max(8.0, length / 40.0)
    val out = ArrayList<Pair<Point2, Double>>()
    var walked = 0.0
    var next = spacing / 2.0
    for (i in 1 until invert.size) {
        val a = invert[i - 1]
        val b = invert[i]
        val span = hypot(b.x - a.x, b.y - a.y)
        if (span < 1e-9) continue
        while (walked + span >= next - 1e-9) {
            val t = ((next - walked) / span).coerceIn(0.0, 1.0)
            out += Point2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) to atan2(b.y - a.y, b.x - a.x)
            next += spacing
            if (out.size >= 80) return out
        }
        walked += span
    }
    return out
}
