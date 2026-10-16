package com.appdwg.core.drainage

import com.appdwg.core.Alignment
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/** One end of a placed device: where it can meet another, with the invert and the flow direction. */
data class Socket(
    val deviceId: String,
    /** "montante" is the upstream end. "jusante" is the downstream end. */
    val role: String,
    val x: Double,
    val y: Double,
    val invertZ: Double,
    /** Radians, from east, pointing downstream. */
    val direction: Double,
    val width: Double,
    val height: Double,
)

/** A snapped pair. [drop] is the upstream invert minus the downstream invert. */
data class NetworkLink(
    val upstreamId: String,
    val downstreamId: String,
    val drop: Double,
    val gap: Double,
    val sectionChange: Boolean,
)

enum class NetworkCheck { REVERSE_SLOPE, DROP, NO_OUTLET, CAPACITY }

data class NetworkIssue(
    val deviceId: String,
    val check: NetworkCheck,
    val drop: Double? = null,
    val flow: Double? = null,
    val capacity: Double? = null,
)

data class Waterway(val width: Double, val height: Double, val area: Double, val perimeter: Double) {
    val radius: Double get() = if (perimeter > 1e-9) area / perimeter else 0.0
}

data class DrainageGraph(
    val sockets: List<Socket>,
    val links: List<NetworkLink>,
    val issues: List<NetworkIssue>,
    /** Manning capacity of each reach, m³/s. Zero when the slope does not fall. */
    val capacity: Map<String, Double>,
)

/**
 * The drainage network implied by devices that already sit near each other.
 * Safe to call off the UI thread: a long reach is swept on the same budget as placement.
 */
object DrainageNetwork {
    /** Plan distance that counts as the same connection. */
    const val SNAP_METRES = 2.0

    /** Drop left on purpose when a grade is rewritten to meet the next device. */
    const val MATCH_DROP = 0.02

    /** A connection drop above this is reported. */
    const val MAX_DROP = 0.60

    /** Width or height change that counts as a section transition. */
    const val SECTION_STEP = 0.05

    fun analyze(
        placed: List<PlacedDevice>,
        sectionOf: (PlacedDevice) -> CrossSection?,
        outlet: (PlacedDevice) -> Boolean,
        roughness: (PlacedDevice) -> Double,
        terrain: (Double, Double) -> Double? = { _, _ -> null },
        alignmentOf: (String) -> Alignment? = { null },
        designFlow: (String) -> Double = { 0.0 },
    ): DrainageGraph {
        val sockets = ArrayList<Socket>()
        val capacity = HashMap<String, Double>()
        val issues = ArrayList<NetworkIssue>()
        for (device in placed) {
            val section = sectionOf(device)
            val ends = endsOf(device, section, terrain, alignmentOf) ?: continue
            sockets += ends
            val fall = ends[0].invertZ - ends[1].invertZ
            val length = hypot(ends[1].x - ends[0].x, ends[1].y - ends[0].y)
            if (fall < -0.001 && length > 0.05) issues += NetworkIssue(device.id, NetworkCheck.REVERSE_SLOPE)
            val water = section?.let { waterway(it) }
            val slope = if (length > 0.05 && fall > 0.0) fall / length else 0.0
            val q = if (water == null) 0.0 else manning(water.area, water.radius, slope, roughness(device))
            capacity[device.id] = q
            val wanted = designFlow(device.id)
            if (wanted > 0.0 && wanted > q + 1e-6) {
                issues += NetworkIssue(device.id, NetworkCheck.CAPACITY, flow = wanted, capacity = q)
            }
        }
        val links = link(sockets)
        for (link in links) {
            if (link.drop < -0.001 || link.drop > MAX_DROP) {
                issues += NetworkIssue(link.upstreamId, NetworkCheck.DROP, drop = link.drop)
            }
        }
        val linkedDown = links.map { it.upstreamId }.toSet()
        for (device in placed) {
            if (outlet(device)) continue
            if (sockets.none { it.deviceId == device.id && it.role == ROLE_DOWN }) continue
            if (device.id !in linkedDown) issues += NetworkIssue(device.id, NetworkCheck.NO_OUTLET)
        }
        return DrainageGraph(sockets, links, issues, capacity)
    }

    /** Manning discharge, m³/s. SI units: area m², hydraulic radius m, slope m/m, n s/m⅓. */
    fun manning(area: Double, radius: Double, slope: Double, n: Double): Double {
        if (area <= 0.0 || radius <= 0.0 || slope <= 0.0 || n <= 0.0) return 0.0
        return (1.0 / n) * area * radius.pow(2.0 / 3.0) * sqrt(slope)
    }

    fun waterway(section: CrossSection): Waterway? {
        val pts = section.points
        if (pts.size < 3) return null
        var area2 = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area2 += a.x * b.y - b.x * a.y
        }
        val area = abs(area2) / 2.0
        if (area < 1e-9) return null
        val width = pts.maxOf { it.x } - pts.minOf { it.x }
        val height = pts.maxOf { it.y } - pts.minOf { it.y }
        val top = pts.maxOf { it.y }
        val open = section.type.endsWith("_open") || section.type == "rect_trench"
        var perimeter = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val free = open && a.y >= top - 1e-4 && b.y >= top - 1e-4
            if (!free) perimeter += hypot(b.x - a.x, b.y - a.y)
        }
        if (perimeter < 1e-9) return null
        return Waterway(width, height, area, perimeter)
    }

    /** Concrete unless the device is grassed or stone. */
    fun roughnessOf(device: DeviceDef): Double {
        val text = (device.materials.joinToString(" ") { it.name } + " " + device.name).lowercase()
        return when {
            "grama" in text || "veget" in text -> 0.030
            "pedra" in text || "enrocamento" in text -> 0.035
            else -> 0.015
        }
    }

    /** Dissipators and named outlets are allowed to end the network. */
    fun isOutlet(device: DeviceDef): Boolean {
        val family = device.family.lowercase()
        val text = (family + " " + device.subtype + " " + device.name).lowercase()
        return family == "des" || "dissip" in text || "saída" in text || "saida" in text
    }

    /**
     * Moves [device] onto the nearest opposite socket and, for a fixed grade or a constant slope,
     * sets the invert a small drop below (or above) that socket. Surface grades keep their elevation.
     */
    fun snap(device: PlacedDevice, sockets: List<Socket>): PlacedDevice {
        val mine = sockets.filter { it.deviceId == device.id }
        val others = sockets.filter { it.deviceId != device.id }
        val up = mine.find { it.role == ROLE_UP }
        val down = mine.find { it.role == ROLE_DOWN }
        var grade = device.grade()
        var moved = device
        up?.let { end ->
            nearest(others, end, ROLE_DOWN)?.let { target ->
                moved = moveEnd(moved, start = true, target)
                grade = matchInvert(moved.grade(), atStart = true, target.invertZ - MATCH_DROP, lengthOf(moved))
                moved = moved.withGrade(grade)
            }
        }
        down?.let { end ->
            nearest(others, end, ROLE_UP)?.let { target ->
                moved = moveEnd(moved, start = false, target)
                grade = matchInvert(moved.grade(), atStart = false, target.invertZ + MATCH_DROP, lengthOf(moved))
                moved = moved.withGrade(grade)
            }
        }
        return moved
    }

    /**
     * Rewrites fixed grades and constant slopes downstream of [originId] so each connection
     * keeps [MATCH_DROP]. Surface grades are left on the terrain.
     */
    fun propagate(devices: List<PlacedDevice>, links: List<NetworkLink>, originId: String): List<PlacedDevice> {
        val byId = devices.associateBy { it.id }.toMutableMap()
        val next = links.groupBy { it.upstreamId }
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(originId)
        seen.add(originId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val upstream = byId[id] ?: continue
            val end = endInvert(upstream) ?: continue
            for (link in next[id].orEmpty()) {
                if (!seen.add(link.downstreamId)) continue
                val downstream = byId[link.downstreamId] ?: continue
                val updated = shiftStart(downstream, end - MATCH_DROP)
                if (updated != null) byId[link.downstreamId] = updated
                queue.add(link.downstreamId)
            }
        }
        return devices.map { byId[it.id] ?: it }
    }

    /** Shifts [originId] and every device snapped to it, so a move keeps the chain together. */
    fun chainTranslate(devices: List<PlacedDevice>, links: List<NetworkLink>, originId: String, dx: Double, dy: Double): List<PlacedDevice> {
        if (dx == 0.0 && dy == 0.0) return devices
        val neighbors = HashMap<String, MutableSet<String>>()
        for (link in links) {
            if (link.gap > SNAP_METRES) continue
            neighbors.getOrPut(link.upstreamId) { HashSet() }.add(link.downstreamId)
            neighbors.getOrPut(link.downstreamId) { HashSet() }.add(link.upstreamId)
        }
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(originId)
        seen.add(originId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (other in neighbors[id].orEmpty()) if (seen.add(other)) queue.add(other)
        }
        return devices.map { if (it.id in seen) shift(it, dx, dy) else it }
    }

    private fun link(sockets: List<Socket>): List<NetworkLink> {
        val down = sockets.filter { it.role == ROLE_DOWN }
        val up = sockets.filter { it.role == ROLE_UP }
        val pairs = ArrayList<Triple<Double, Socket, Socket>>()
        for (from in down) for (to in up) {
            if (from.deviceId == to.deviceId) continue
            val gap = hypot(from.x - to.x, from.y - to.y)
            if (gap <= SNAP_METRES) pairs += Triple(gap, from, to)
        }
        pairs.sortBy { it.first }
        val usedFrom = HashSet<String>()
        val usedTo = HashSet<String>()
        val links = ArrayList<NetworkLink>()
        for ((gap, from, to) in pairs) {
            if (from.deviceId in usedFrom || to.deviceId in usedTo) continue
            usedFrom += from.deviceId
            usedTo += to.deviceId
            val change = abs(from.width - to.width) > SECTION_STEP || abs(from.height - to.height) > SECTION_STEP
            links += NetworkLink(from.deviceId, to.deviceId, from.invertZ - to.invertZ, gap, change)
        }
        return links
    }

    private fun nearest(sockets: List<Socket>, end: Socket, role: String): Socket? {
        return sockets.filter { it.role == role }
            .minByOrNull { hypot(it.x - end.x, it.y - end.y) }
            ?.takeIf { hypot(it.x - end.x, it.y - end.y) <= SNAP_METRES }
    }

    private fun endsOf(
        device: PlacedDevice,
        section: CrossSection?,
        terrain: (Double, Double) -> Double?,
        alignmentOf: (String) -> Alignment?,
    ): List<Socket>? {
        val size = section?.let { waterway(it) }
        val width = size?.width ?: 0.0
        val height = size?.height ?: 0.0
        return when (device) {
            is PlacedDevice.Linear -> {
                val nodes = nodesOf(device, alignmentOf) ?: return null
                val profile = (device.profile ?: device.alignment)?.let(alignmentOf)?.profile
                val mesh = Sweep.sweep(
                    section ?: rectangle(),
                    nodes,
                    Sweep.signedOffset(device.side, device.offset),
                    device.grade,
                    terrain,
                    { station -> profile?.elevationAt(station) },
                ) ?: return null
                if (mesh.frames.size < 2) return null
                val start = mesh.frames.first()
                val end = mesh.frames.last()
                val uphill = device.grade.mode == GradeMode.HYDRAULIC && start.invertZ + 1e-4 < end.invertZ
                val a = if (uphill) end else start
                val b = if (uphill) start else end
                val direction = atan2(b.y - a.y, b.x - a.x)
                listOf(
                    Socket(device.id, ROLE_UP, a.x, a.y, a.invertZ, direction, width, height),
                    Socket(device.id, ROLE_DOWN, b.x, b.y, b.invertZ, direction, width, height),
                )
            }
            is PlacedDevice.Point -> {
                val mesh = Sweep.point(
                    section, device.x, device.y, device.rotationDeg, device.skewDeg,
                    device.grade, terrain(device.x, device.y), null,
                )
                val frames = mesh.frames
                if (frames.size >= 2) {
                    val a = frames.first()
                    val b = frames.last()
                    val direction = atan2(b.y - a.y, b.x - a.x)
                    listOf(
                        Socket(device.id, ROLE_UP, a.x, a.y, a.invertZ, direction, width, height),
                        Socket(device.id, ROLE_DOWN, b.x, b.y, b.invertZ, direction, width, height),
                    )
                } else {
                    val direction = Math.toRadians(device.rotationDeg)
                    val z = device.grade.z0
                    listOf(
                        Socket(device.id, ROLE_UP, device.x, device.y, z, direction, width, height),
                        Socket(device.id, ROLE_DOWN, device.x + kotlin.math.cos(direction), device.y + kotlin.math.sin(direction), z, direction, width, height),
                    )
                }
            }
        }
    }

    private fun rectangle() = CrossSection(
        "rect_trench", "m",
        listOf(listOf(SectionPoint(-0.5, 0.0), SectionPoint(-0.5, -0.5), SectionPoint(0.5, -0.5), SectionPoint(0.5, 0.0))),
    )

    private fun nodesOf(device: PlacedDevice.Linear, alignmentOf: (String) -> Alignment?): List<PathNode>? {
        if (device.source == PathSource.ALIGNMENT) {
            val alignment = device.alignment?.let(alignmentOf)
            if (alignment != null) return Sweep.alignment(alignment, device.start, device.end).ifEmpty { null }
        }
        if (device.xs.size < 2) return null
        return Sweep.polyline(device.xs.toDoubleArray(), device.ys.toDoubleArray(), device.start, device.end).ifEmpty { null }
    }

    private fun moveEnd(device: PlacedDevice, start: Boolean, target: Socket): PlacedDevice {
        if (device !is PlacedDevice.Linear || device.source == PathSource.ALIGNMENT || device.xs.size < 2) return device
        val xs = device.xs.toMutableList()
        val ys = device.ys.toMutableList()
        val index = if (start) 0 else xs.lastIndex
        xs[index] = target.x
        ys[index] = target.y
        return device.copy(xs = xs, ys = ys, source = PathSource.POLYLINE)
    }

    private fun matchInvert(grade: GradeSpec, atStart: Boolean, invert: Double, length: Double): GradeSpec = when (grade.mode) {
        GradeMode.FIXED -> if (atStart) grade.copy(z0 = invert) else grade.copy(z1 = invert)
        GradeMode.SLOPE -> if (atStart) grade.copy(z0 = invert) else {
            val slope = if (length > 1e-6) (invert - grade.z0) / length else grade.slope
            grade.copy(slope = slope)
        }
        else -> grade
    }

    private fun endInvert(device: PlacedDevice): Double? = when (device) {
        is PlacedDevice.Linear -> when (device.grade.mode) {
            GradeMode.FIXED -> device.grade.z1
            GradeMode.SLOPE -> device.grade.z0 + device.grade.slope * lengthOf(device)
            else -> null
        }
        is PlacedDevice.Point -> if (device.grade.mode == GradeMode.FIXED) device.grade.z0 else null
    }

    private fun shiftStart(device: PlacedDevice, invert: Double): PlacedDevice? = when (device) {
        is PlacedDevice.Linear -> when (device.grade.mode) {
            GradeMode.FIXED -> {
                val delta = invert - device.grade.z0
                device.copy(grade = device.grade.copy(z0 = device.grade.z0 + delta, z1 = device.grade.z1 + delta))
            }
            GradeMode.SLOPE -> device.copy(grade = device.grade.copy(z0 = invert))
            else -> null
        }
        is PlacedDevice.Point -> if (device.grade.mode == GradeMode.FIXED) device.copy(grade = device.grade.copy(z0 = invert)) else null
    }

    private fun lengthOf(device: PlacedDevice): Double = when (device) {
        is PlacedDevice.Linear -> {
            var length = 0.0
            for (i in 1 until device.xs.size) length += hypot(device.xs[i] - device.xs[i - 1], device.ys[i] - device.ys[i - 1])
            if (length > 0.0) length else {
                val span = (device.end ?: 0.0) - (device.start ?: 0.0)
                if (span > 0.0) span else 0.0
            }
        }
        is PlacedDevice.Point -> 1.0
    }

    private fun shift(device: PlacedDevice, dx: Double, dy: Double): PlacedDevice = when (device) {
        is PlacedDevice.Point -> device.copy(x = device.x + dx, y = device.y + dy)
        is PlacedDevice.Linear -> if (device.xs.isEmpty()) device else device.copy(
            xs = device.xs.map { it + dx },
            ys = device.ys.map { it + dy },
            source = PathSource.POLYLINE,
        )
    }

    private fun PlacedDevice.grade(): GradeSpec = when (this) {
        is PlacedDevice.Linear -> grade
        is PlacedDevice.Point -> grade
    }

    private fun PlacedDevice.withGrade(grade: GradeSpec): PlacedDevice = when (this) {
        is PlacedDevice.Linear -> copy(grade = grade)
        is PlacedDevice.Point -> copy(grade = grade)
    }

    private const val ROLE_UP = "montante"
    private const val ROLE_DOWN = "jusante"
}
