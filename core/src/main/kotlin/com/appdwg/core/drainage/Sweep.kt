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
enum class GradeMode { SURFACE_TOP, SURFACE_INVERT, FIXED, SLOPE, PROFILE, HYDRAULIC }

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
    /** Rise over run (0.01 is 1 %), for [GradeMode.SLOPE]. Positive rises along the drawn path. */
    val slope: Double = 0.0,
    /** Minimum downhill grade (0.005 is 0.5 %), for [GradeMode.HYDRAULIC]. */
    val minSlope: Double = 0.005,
    /** Cover over a buried device, metres. */
    val minCover: Double = 0.60,
    /** Excavation deeper than this is kept, and reported as needing a drop or pumping. */
    val maxDepth: Double = 6.0,
    /** A buried pipe or culvert. A gutter or curb stays on the surface. */
    val buried: Boolean = false,
    /** When [flowAuto] is off, true sends the water toward the drawn start. */
    val flowToStart: Boolean = false,
    /** The lower end is downstream. */
    val flowAuto: Boolean = true,
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
    /** Inner waterway in plan. Empty when the solid has no opening. */
    val channel: List<Point2> = emptyList(),
    /** One polygon per bore. A double barrel keeps both openings. */
    val channels: List<List<Point2>> = emptyList(),
    /** True width of the device in plan, metres. The plan view uses it to choose the zoom fallback. */
    val widthM: Double = 0.0,
    /** Vertical extent of the drawn solid, metres. */
    val heightM: Double = 0.0,
    /** Width of the inner opening, metres. Zero when the solid is filled. */
    val innerWidthM: Double = 0.0,
) {
    /** Hydraulic grade used to place this solid. Null for the older grade modes. */
    var hydraulic: HydraulicGrade.Result? = null
}

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
        val hydraulics = hydraulicOf(dense, grade, terrain, signedOffset, maxY - minY, maxX - minX, section.areaM2 ?: ringArea(ring))
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
            val reference = hydraulics?.samples?.getOrNull(i)?.invert
                ?: referenceZ(grade, node.distance - dense.first().distance, length, node.station, terrain(cx, cy), profileAt(node.station))
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
            .also { it.hydraulic = hydraulics }
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
        val refY = when (grade.mode) {
            GradeMode.SURFACE_TOP -> box
            GradeMode.HYDRAULIC -> box + if (grade.buried) grade.minCover else 0.0
            else -> 0.0
        }
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

    /**
     * Concrete shell swept along [nodes]. [outer] is the outside of the wall and [inner] is the
     * waterway. An empty [inner] is a filled solid, such as a curb. The quantity sweep is left
     * unchanged; this mesh is only for drawing.
     *
     * When the computed grade would put the solid in the terrain, the whole device is lifted by
     * one constant so its lowest point sits 2 cm above the highest ground it crosses. The grade
     * along the device is kept. Buried culverts therefore show on the surface instead of
     * disappearing inside the TIN.
     */
    fun visual(
        outer: List<SectionPoint>,
        inner: List<SectionPoint>,
        nodes: List<PathNode>,
        signedOffset: Double,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double? = { _, _ -> null },
        profileAt: (Double) -> Double? = { null },
        widthM: Double = 0.0,
        heightM: Double = 0.0,
        innerWidthM: Double = 0.0,
    ): SweepMesh? {
        if (outer.size < 3 || nodes.size < 2) return null
        val dense = densify(nodes)
        if (dense.size < 2) return null
        val samples = outer + inner
        val minY = samples.minOf { it.y }
        val maxY = samples.maxOf { it.y }
        val minX = outer.minOf { it.x }
        val maxX = outer.maxOf { it.x }
        val length = dense.last().distance - dense.first().distance
        if (length < 1e-6) return null
        val hydraulics = hydraulicOf(dense, grade, terrain, signedOffset, maxY - minY, maxX - minX, ringArea(outer))
        val invertSource = inner.ifEmpty { outer }
        val invertLow = invertSource.minOf { it.y }
        val invertX = invertSource.filter { abs(it.y - invertLow) < 1e-6 }.map { it.x }.average()
        val openMouth = inner.isNotEmpty() && freeSurface(inner)
        val outerPlaced = ArrayList<List<Triple<Double, Double, Double>>>(dense.size)
        val innerPlaced = ArrayList<List<Triple<Double, Double, Double>>>(dense.size)
        val frames = ArrayList<SweepFrame>(dense.size)
        val frameMinZ = ArrayList<Double>(dense.size)
        val grounds = ArrayList<Pair<Double, Double>>(dense.size)
        var held = Double.NaN
        for (i in dense.indices) {
            val node = dense[i]
            val tangent = tangentAt(dense, i)
            val miter = if (node.corner) miterOf(dense, i) else 1.0
            val rightX = tangent.second
            val rightY = -tangent.first
            val cx = node.x + rightX * signedOffset * miter
            val cy = node.y + rightY * signedOffset * miter
            val sampled = hydraulics?.samples?.getOrNull(i)?.invert
                ?: referenceZ(grade, node.distance - dense.first().distance, length, node.station, terrain(cx, cy), profileAt(node.station))
            val reference = if (sampled.isFinite()) sampled.also { held = it } else held
            if (!reference.isFinite()) continue
            val refY = when (grade.mode) {
                GradeMode.SURFACE_TOP -> maxY
                GradeMode.PROFILE -> 0.0
                else -> minY
            }
            fun place(ring: List<SectionPoint>) = ring.map { point ->
                val across = (point.x + signedOffset) * miter
                Triple(node.x + rightX * across, node.y + rightY * across, reference + (point.y - refY))
            }
            val placedOuter = place(outer)
            val placedInner = if (inner.isEmpty()) emptyList() else place(inner)
            outerPlaced += placedOuter
            if (placedInner.isNotEmpty()) innerPlaced += placedInner
            val low = (placedOuter + placedInner).minOf { it.third }
            frameMinZ += low
            grounds += cx to cy
            frames += SweepFrame(
                node.x, node.y, node.distance, node.station, rightX, rightY, miter,
                invertZ = reference + (minY - refY),
                topZ = reference + (maxY - refY),
            )
        }
        if (outerPlaced.size < 2) return null
        val raw = if (innerPlaced.isEmpty()) loft(outerPlaced) else loftShell(outerPlaced, innerPlaced, openMouth)
        val lift = if (grade.mode == GradeMode.HYDRAULIC) {
            if (grade.buried) 0.0 else HydraulicGrade.SURFACE_SEAT
        } else {
            visualLift(frameMinZ, grounds, terrain)
        }
        val triangles = raise(raw, lift)
        val left = track(frames, minX, signedOffset)
        val right = track(frames, maxX, signedOffset)
        val invert = track(frames, invertX, signedOffset)
        val channel = if (inner.isEmpty()) emptyList() else {
            val inLeft = track(frames, inner.minOf { it.x }, signedOffset)
            val inRight = track(frames, inner.maxOf { it.x }, signedOffset)
            inLeft + inRight.asReversed()
        }
        return SweepMesh(
            frames, triangles, enclosedVolume(triangles), length, left + right.asReversed(), invert, false,
            channel, if (channel.size >= 3) listOf(channel) else emptyList(),
            widthM, heightM, innerWidthM,
        ).also { it.hydraulic = hydraulics }
    }

    /**
     * Vertical solid of a plan outline. [bottom] and [top] are world elevations before the
     * terrain seat in [liftTriangles]. A non-empty [inner] leaves the opening clear.
     */
    fun extrude(
        outer: List<SectionPoint>,
        inner: List<SectionPoint>,
        x: Double,
        y: Double,
        rotationDeg: Double,
        skewDeg: Double,
        bottom: Double,
        top: Double,
        widthM: Double,
        heightM: Double,
        innerWidthM: Double,
    ): SweepMesh? {
        if (outer.size < 3 || top - bottom < 1e-4) return null
        val heading = Math.toRadians(rotationDeg)
        val dx = cos(heading)
        val dy = sin(heading)
        val skew = tan(Math.toRadians(skewDeg.coerceIn(-60.0, 60.0)))
        fun at(ring: List<SectionPoint>, z: Double) = ring.map { point ->
            val placed = local(x, y, point.x, point.y, dx, dy, skew)
            Triple(placed.x, placed.y, z)
        }
        val lowOuter = at(outer, bottom)
        val highOuter = at(outer, top)
        val triangles = if (inner.size >= 3) {
            loftShell(listOf(lowOuter, highOuter), listOf(at(inner, bottom), at(inner, top)), openMouth = false)
        } else {
            loft(listOf(lowOuter, highOuter))
        }
        val footprint = lowOuter.map { Point2(it.first, it.second) }.let { it + it.first() }
        val channel = if (inner.size < 3) emptyList() else at(inner, bottom).map { Point2(it.first, it.second) }.let { it + it.first() }
        val invert = listOf(Point2(x, y))
        return SweepMesh(
            emptyList(), triangles, enclosedVolume(triangles), 0.0, footprint, invert, false,
            channel, if (channel.size >= 3) listOf(channel) else emptyList(),
            widthM, heightM, innerWidthM,
        )
    }

    /** Moves every vertex by [lift] metres. A hydraulic surface device uses a fixed seat, not a drape. */
    fun offsetZ(triangles: DoubleArray, lift: Double): DoubleArray = raise(triangles, lift)

    /** Lifts a triangle soup so its lowest vertex clears [ground] by 2 cm when it would not. */
    fun liftTriangles(triangles: DoubleArray, ground: Double?): DoubleArray {
        if (ground == null || triangles.size < 3) return triangles
        var minZ = Double.POSITIVE_INFINITY
        var i = 2
        while (i < triangles.size) {
            if (triangles[i] < minZ) minZ = triangles[i]
            i += 3
        }
        if (minZ == Double.POSITIVE_INFINITY) return triangles
        return raise(triangles, visualLift(listOf(minZ), listOf(0.0 to 0.0)) { _, _ -> ground })
    }

    fun gradeElevation(grade: GradeSpec, distance: Double, length: Double, station: Double, terrainZ: Double?, profileZ: Double?): Double =
        referenceZ(grade, distance, length, station, terrainZ, profileZ)

    private fun track(frames: List<SweepFrame>, acrossX: Double, signedOffset: Double): List<Point2> = frames.map { frame ->
        val across = (acrossX + signedOffset) * frame.miter
        Point2(frame.x + frame.rightX * across, frame.y + frame.rightY * across)
    }

    private fun visualLift(frameMinZ: List<Double>, samples: List<Pair<Double, Double>>, terrain: (Double, Double) -> Double?): Double {
        var lift = 0.0
        for (i in frameMinZ.indices) {
            val ground = terrain(samples[i].first, samples[i].second) ?: continue
            val bury = ground - frameMinZ[i]
            val need = if (bury > -CLEARANCE) bury + CLEARANCE else 0.0
            if (need > lift) lift = need
        }
        return lift
    }

    private fun raise(triangles: DoubleArray, lift: Double): DoubleArray {
        if (lift == 0.0 || triangles.isEmpty()) return triangles
        val out = triangles.copyOf()
        var i = 2
        while (i < out.size) {
            out[i] += lift
            i += 3
        }
        return out
    }

    /** True when the edge that closes the ring is the free water surface, not a wall. */
    private fun freeSurface(ring: List<SectionPoint>): Boolean {
        if (ring.size < 3) return false
        val a = ring.last()
        val b = ring.first()
        val dx = abs(b.x - a.x)
        val dy = abs(b.y - a.y)
        val top = ring.maxOf { it.y }
        val mid = (a.y + b.y) / 2.0
        val height = top - ring.minOf { it.y }
        val nearTop = mid >= top - 0.02 || mid >= top - height * 0.15
        return nearTop && dx > 1e-3 && dx >= dy
    }

    private fun local(ox: Double, oy: Double, lx: Double, ly: Double, dx: Double, dy: Double, skew: Double): Point2 {
        val sx = lx + ly * skew
        return Point2(ox + sx * dx - ly * dy, oy + sx * dy + ly * dx)
    }

    private fun referenceZ(grade: GradeSpec, distance: Double, length: Double, station: Double, terrainZ: Double?, profileZ: Double?): Double {
        // Missing ground stays unknown. Callers hold the last real sample instead of dropping to 0.
        val ground = terrainZ ?: Double.NaN
        return when (grade.mode) {
            GradeMode.SURFACE_TOP -> ground
            GradeMode.SURFACE_INVERT -> ground - grade.depth
            GradeMode.FIXED -> grade.z0 + (grade.z1 - grade.z0) * (if (length <= 0.0) 0.0 else distance / length)
            GradeMode.SLOPE -> grade.z0 + grade.slope * distance
            GradeMode.PROFILE -> profileZ ?: grade.z0
            // Cover line only. The sweep replaces this with the monotonic grade.
            GradeMode.HYDRAULIC -> ground
        }.let { if (station.isNaN()) it else it }
    }

    private fun hydraulicOf(
        dense: List<PathNode>,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double?,
        signedOffset: Double,
        height: Double,
        width: Double,
        area: Double,
    ): HydraulicGrade.Result? {
        if (grade.mode != GradeMode.HYDRAULIC || dense.size < 2) return null
        val distances = DoubleArray(dense.size)
        val raw = arrayOfNulls<Double>(dense.size)
        val origin = dense.first().distance
        for (i in dense.indices) {
            val node = dense[i]
            val tangent = tangentAt(dense, i)
            val miter = if (node.corner) miterOf(dense, i) else 1.0
            val rightX = tangent.second
            val rightY = -tangent.first
            distances[i] = node.distance - origin
            val cx = node.x + rightX * signedOffset * miter
            val cy = node.y + rightY * signedOffset * miter
            raw[i] = terrain(cx, cy)
        }
        val (grounds, offSurface) = GroundCover.fill(raw)
        if (grounds.any { !it.isFinite() }) return null
        return HydraulicGrade.design(
            distances, grounds, height, width, area, grade.buried,
            minSlope = grade.minSlope, minCover = if (grade.buried) grade.minCover else 0.0,
            maxDepth = grade.maxDepth,
            downstreamAtStart = if (grade.flowAuto) null else grade.flowToStart,
        ).copy(offSurface = offSurface)
    }

    private fun ringArea(ring: List<SectionPoint>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
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

    /**
     * Walls between [outer] and [inner]. [openMouth] skips the edge that closes each ring,
     * so a ditch keeps its channel and only the bank tops are sealed.
     */
    private fun loftShell(
        outer: List<List<Triple<Double, Double, Double>>>,
        inner: List<List<Triple<Double, Double, Double>>>,
        openMouth: Boolean,
    ): DoubleArray {
        if (outer.size < 2 || inner.size < 2 || outer.first().size < 3 || inner.first().size < 3) return DoubleArray(0)
        val tris = ArrayList<Double>()
        val count = minOf(outer.first().size, inner.first().size)
        val edges = if (openMouth) count - 1 else count
        fun stitch(
            a: List<Triple<Double, Double, Double>>,
            b: List<Triple<Double, Double, Double>>,
            reverse: Boolean,
        ) {
            for (i in 0 until edges) {
                val j = (i + 1) % a.size
                if (reverse) {
                    addTri(tris, a[i], b[j], a[j])
                    addTri(tris, a[i], b[i], b[j])
                } else {
                    addTri(tris, a[i], a[j], b[j])
                    addTri(tris, a[i], b[j], b[i])
                }
            }
        }
        for (k in 0 until outer.size - 1) {
            stitch(outer[k], outer[k + 1], reverse = false)
            // The bore faces the other way, so the shell encloses the wall and not the opening.
            stitch(inner[k], inner[k + 1], reverse = true)
            if (openMouth && count >= 2) {
                // Only the two corners of the free surface. Sealing every vertex cuts the wall.
                val lips = intArrayOf(0, count - 1)
                for (lip in lips) {
                    addTri(tris, outer[k][lip], outer[k + 1][lip], inner[k + 1][lip])
                    addTri(tris, outer[k][lip], inner[k + 1][lip], inner[k][lip])
                }
            }
            if (tris.size / 9 > MAX_TRIANGLES) break
        }
        fun cap(
            o: List<Triple<Double, Double, Double>>,
            inn: List<Triple<Double, Double, Double>>,
            reverse: Boolean,
        ) {
            for (i in 0 until edges) {
                val j = (i + 1) % o.size
                if (reverse) {
                    addTri(tris, o[i], inn[j], o[j])
                    addTri(tris, o[i], inn[i], inn[j])
                } else {
                    addTri(tris, o[i], o[j], inn[j])
                    addTri(tris, o[i], inn[j], inn[i])
                }
            }
        }
        if (tris.size / 9 <= MAX_TRIANGLES) {
            cap(outer.first(), inner.first(), reverse = true)
            cap(outer.last(), inner.last(), reverse = false)
        }
        if (tris.size / 9 > MAX_TRIANGLES) return DoubleArray(MAX_TRIANGLES * 9) { tris[it] }
        return tris.toDoubleArray()
    }

    private fun addTri(
        tris: ArrayList<Double>,
        a: Triple<Double, Double, Double>,
        b: Triple<Double, Double, Double>,
        c: Triple<Double, Double, Double>,
    ) {
        val ux = b.first - a.first
        val uy = b.second - a.second
        val uz = b.third - a.third
        val vx = c.first - a.first
        val vy = c.second - a.second
        val vz = c.third - a.third
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        if (cx * cx + cy * cy + cz * cz < 1e-16) return
        tris += a.first; tris += a.second; tris += a.third
        tris += b.first; tris += b.second; tris += b.third
        tris += c.first; tris += c.second; tris += c.third
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

    /** Closed-shell volume. A straight prism is the material section times the path length. */
    fun enclosedVolume(triangles: DoubleArray): Double = volume(triangles)

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
    private const val CLEARANCE = 0.02
}

/** Plan drawing of one placed device: footprint, invert and the direction of flow. */
data class DrainageGlyph(
    val id: String,
    val footprint: List<Point2>,
    val invert: List<Point2>,
    val arrows: List<Pair<Point2, Double>>,
    val label: String?,
    val simplified: Boolean,
    /** Inner opening. Empty when the device is drawn as a filled footprint only. */
    val channel: List<Point2> = emptyList(),
    /** Every bore, when a device has more than one. */
    val channels: List<List<Point2>> = emptyList(),
    /** Plan width in metres. Below about 3 px the plan keeps only the centerline. */
    val widthM: Double = 0.0,
)

data class DeviceTakeoff(
    val id: String,
    val code: String,
    val length: Double,
    val concrete: Double,
    val excavation: Double,
    val formwork: Double,
    val formworkKnown: Boolean,
    val backfill: Double = 0.0,
    val bedding: Double = 0.0,
    val spoil: Double = 0.0,
    val fill: Double = 0.0,
    val trench: Double = 0.0,
    val shoring: Boolean = false,
    val tooDeep: Boolean = false,
    val invertUp: Double = Double.NaN,
    val invertDown: Double = Double.NaN,
    val gradeSlope: Double = Double.NaN,
    val cutDepth: Double = Double.NaN,
    /** Triangles kept for this device after its own budget. */
    val drawnTriangles: Int = 0,
) {
    operator fun plus(other: DeviceTakeoff) = DeviceTakeoff(
        "", "", length + other.length, concrete + other.concrete, excavation + other.excavation,
        formwork + other.formwork, formworkKnown || other.formworkKnown,
        backfill + other.backfill, bedding + other.bedding, spoil + other.spoil, fill + other.fill,
        trench + other.trench, shoring || other.shoring, tooDeep || other.tooDeep,
        cutDepth = listOf(cutDepth, other.cutDepth).filter { it.isFinite() }.maxOrNull() ?: Double.NaN,
        drawnTriangles = drawnTriangles + other.drawnTriangles,
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
