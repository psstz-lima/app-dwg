package com.appdwg.core.drainage

import com.appdwg.core.Alignment
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * LandXML 1.2 pipe network for Civil 3D. Structures are the device ends; a snapped connection
 * shares one structure. This is a separate file from the surface LandXML.
 */
object PipeNetworkWriter {
    fun write(
        name: String,
        graph: DrainageGraph,
        circular: (String) -> Boolean = { false },
        out: Appendable,
        alignments: List<DrainAlignment> = emptyList(),
        profiles: List<DrainProfile> = emptyList(),
        bends: Map<String, List<PipePoint>> = emptyMap(),
    ) {
        val structs = structures(graph).toMutableList()
        val bySocket = HashMap<String, PipeStruct>()
        for (struct in structs) for (key in struct.sockets) bySocket[key] = struct
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">\n")
        out.append("  <Units><Metric linearUnit=\"meter\" areaUnit=\"squareMeter\" volumeUnit=\"cubicMeter\" angularUnit=\"decimal degrees\"/></Units>\n")
        out.append("  <Application name=\"Gromatic\"/>\n")
        out.append("  <PipeNetworks>\n")
        out.append("    <PipeNetwork name=\"${esc(name)}\">\n")
        val pipes = ArrayList<String>()
        var pipe = 1
        var nextStruct = structs.size + 1
        val seen = HashSet<String>()
        for (socket in graph.sockets) {
            if (!seen.add(socket.deviceId)) continue
            val start = bySocket[key(socket.deviceId, "montante")] ?: continue
            val end = bySocket[key(socket.deviceId, "jusante")] ?: continue
            if (start.name == end.name) continue
            val up = graph.sockets.first { it.deviceId == socket.deviceId && it.role == "montante" }
            val down = graph.sockets.first { it.deviceId == socket.deviceId && it.role == "jusante" }
            val shape = if (circular(socket.deviceId)) {
                "<CircPipe diameter=\"${f3(maxOf(up.width, up.height))}\"/>"
            } else {
                "<RectPipe width=\"${f3(up.width)}\" height=\"${f3(up.height)}\"/>"
            }
            val samples = bends[socket.deviceId].orEmpty().filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
            val ordered = if (samples.size >= 3) orient(samples, start, end) else listOf(
                PipePoint(start.x, start.y, up.invertZ),
                PipePoint(end.x, end.y, down.invertZ),
            )
            var prevName = start.name
            var prevX = start.x
            var prevY = start.y
            var prevZ = up.invertZ
            for (i in 1 until ordered.size) {
                val last = i == ordered.lastIndex
                val x = if (last) end.x else ordered[i].x
                val y = if (last) end.y else ordered[i].y
                val z = if (last) down.invertZ else ordered[i].z
                val name = if (last) end.name else {
                    val made = "S${nextStruct++}"
                    structs += PipeStruct(made, x, y, z, z + up.height, emptyList())
                    made
                }
                val length = hypot(x - prevX, y - prevY)
                if (length >= 1e-4 && prevName != name) {
                    val slope = (prevZ - z) / length
                    pipes += "        <Pipe name=\"P$pipe\" refStart=\"${esc(prevName)}\" refEnd=\"${esc(name)}\" " +
                        "length=\"${f3(length)}\" slope=\"${f3(slope)}\">$shape</Pipe>\n"
                    pipe++
                }
                prevName = name
                prevX = x
                prevY = y
                prevZ = z
            }
        }
        out.append("      <Structs>\n")
        for (struct in structs) {
            out.append(
                "        <Struct name=\"${esc(struct.name)}\" elevRim=\"${f3(struct.rim)}\" elevSump=\"${f3(struct.sump)}\">" +
                    "<Center>${f3(struct.y)} ${f3(struct.x)}</Center></Struct>\n",
            )
        }
        out.append("      </Structs>\n")
        out.append("      <Pipes>\n")
        for (line in pipes) out.append(line)
        out.append("      </Pipes>\n")
        out.append("    </PipeNetwork>\n")
        out.append("  </PipeNetworks>\n")
        if (alignments.isNotEmpty()) writeAlignments(alignments, profiles, out)
        out.append("</LandXML>\n")
    }

    private fun writeAlignments(alignments: List<DrainAlignment>, profiles: List<DrainProfile>, out: Appendable) {
        out.append("  <Alignments>\n")
        for (alignment in alignments) {
            val profile = profiles.find { it.alignmentId == alignment.id }
            out.append(
                "    <Alignment name=\"${esc(alignment.name)}\" length=\"${f3(alignment.length)}\" staStart=\"0\">\n",
            )
            out.append("      <CoordGeom>\n")
            val nodes = alignment.nodes
            for (i in 0 until nodes.size - 1) {
                val a = nodes[i]
                val b = nodes[i + 1]
                out.append("        <Line><Start>${f3(a.y)} ${f3(a.x)}</Start><End>${f3(b.y)} ${f3(b.x)}</End></Line>\n")
            }
            out.append("      </CoordGeom>\n")
            if (profile != null && profile.samples.size >= 2) {
                out.append("      <Profile>\n")
                out.append("        <ProfAlign name=\"invert\">\n")
                for (sample in profile.samples) {
                    if (sample.structure) continue
                    out.append("          <PVI>${f3(sample.station)} ${f3(sample.invert)}</PVI>\n")
                }
                out.append("        </ProfAlign>\n")
                out.append("        <ProfSurf name=\"terrain\">\n")
                out.append("          <PntList2D>")
                out.append(profile.samples.joinToString(" ") { "${f3(it.station)} ${f3(it.terrain)}" })
                out.append("</PntList2D>\n")
                out.append("        </ProfSurf>\n")
                out.append("      </Profile>\n")
            }
            out.append("    </Alignment>\n")
        }
        out.append("  </Alignments>\n")
    }

    private fun structures(graph: DrainageGraph): List<PipeStruct> {
        val parent = HashMap<String, String>()
        fun find(id: String): String {
            val known = parent[id]
            if (known == null || known == id) {
                parent[id] = id
                return id
            }
            val root = find(known)
            parent[id] = root
            return root
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        for (socket in graph.sockets) find(key(socket.deviceId, socket.role))
        for (link in graph.links) union(key(link.upstreamId, "jusante"), key(link.downstreamId, "montante"))
        val groups = LinkedHashMap<String, MutableList<Socket>>()
        for (socket in graph.sockets) groups.getOrPut(find(key(socket.deviceId, socket.role))) { ArrayList() }.add(socket)
        return groups.values.sortedWith(compareBy({ it.minOf { s -> s.x } }, { it.minOf { s -> s.y } })).mapIndexed { index, sockets ->
            val sump = sockets.minOf { it.invertZ }
            val height = sockets.maxOf { it.height }
            PipeStruct(
                name = "S${index + 1}",
                x = sockets.map { it.x }.average(),
                y = sockets.map { it.y }.average(),
                sump = sump,
                rim = sump + height,
                sockets = sockets.map { key(it.deviceId, it.role) },
            )
        }
    }

    /**
     * Vertices of a reach, including bends and arc samples. A straight reach has two.
     * Invert comes from the swept grade so each Civil 3D segment keeps the real fall.
     */
    fun vertices(
        device: PlacedDevice,
        section: CrossSection?,
        terrain: (Double, Double) -> Double? = { _, _ -> null },
        alignmentOf: (String) -> Alignment? = { null },
    ): List<PipePoint> {
        val linear = device as? PlacedDevice.Linear ?: return emptyList()
        val nodes = if (linear.source == PathSource.ALIGNMENT) {
            val alignment = linear.alignment?.let(alignmentOf) ?: return emptyList()
            Sweep.alignment(alignment, linear.start, linear.end)
        } else {
            if (linear.xs.size < 2) return emptyList()
            Sweep.polyline(linear.xs.toDoubleArray(), linear.ys.toDoubleArray(), linear.start, linear.end)
        }
        if (nodes.size < 2) return emptyList()
        val frames = section?.let {
            Sweep.sweep(
                it, nodes, Sweep.signedOffset(linear.side, linear.offset), linear.grade, terrain,
                profileAt = { station -> (linear.profile ?: linear.alignment)?.let(alignmentOf)?.profile?.elevationAt(station) },
            )
        }?.frames
        val origin = nodes.first().distance
        val length = nodes.last().distance - origin
        return nodes.map { node ->
            val z = frames?.minByOrNull { abs(it.distance - node.distance) }?.invertZ
                ?: invertAlong(linear.grade, node.distance - origin, length)
            PipePoint(node.x, node.y, z)
        }
    }

    private fun orient(points: List<PipePoint>, start: PipeStruct, end: PipeStruct): List<PipePoint> {
        val toStart = hypot(points.first().x - start.x, points.first().y - start.y)
        val toEnd = hypot(points.first().x - end.x, points.first().y - end.y)
        return if (toEnd + 1e-3 < toStart) points.asReversed() else points
    }

    private fun invertAlong(grade: GradeSpec, distance: Double, length: Double): Double = when (grade.mode) {
        GradeMode.FIXED -> grade.z0 + (grade.z1 - grade.z0) * (if (length <= 1e-9) 0.0 else distance / length)
        GradeMode.SLOPE -> grade.z0 + grade.slope * distance
        else -> grade.z0
    }

    private fun key(id: String, role: String) = "$id|$role"

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun f3(value: Double) = String.format(Locale.ROOT, "%.3f", value)
}

data class PipePoint(val x: Double, val y: Double, val z: Double)

private data class PipeStruct(
    val name: String,
    val x: Double,
    val y: Double,
    val sump: Double,
    val rim: Double,
    val sockets: List<String>,
)
