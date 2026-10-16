package com.appdwg.core.drainage

import java.util.Locale
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
    ) {
        val structs = structures(graph)
        val bySocket = HashMap<String, PipeStruct>()
        for (struct in structs) for (key in struct.sockets) bySocket[key] = struct
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">\n")
        out.append("  <Units><Metric linearUnit=\"meter\" areaUnit=\"squareMeter\" volumeUnit=\"cubicMeter\" angularUnit=\"decimal degrees\"/></Units>\n")
        out.append("  <Application name=\"Gromatic\"/>\n")
        out.append("  <PipeNetworks>\n")
        out.append("    <PipeNetwork name=\"${esc(name)}\">\n")
        out.append("      <Structs>\n")
        for (struct in structs) {
            out.append(
                "        <Struct name=\"${esc(struct.name)}\" elevRim=\"${f3(struct.rim)}\" elevSump=\"${f3(struct.sump)}\">" +
                    "<Center>${f3(struct.y)} ${f3(struct.x)}</Center></Struct>\n",
            )
        }
        out.append("      </Structs>\n")
        out.append("      <Pipes>\n")
        var pipe = 1
        val seen = HashSet<String>()
        for (socket in graph.sockets) {
            if (!seen.add(socket.deviceId)) continue
            val start = bySocket[key(socket.deviceId, "montante")] ?: continue
            val end = bySocket[key(socket.deviceId, "jusante")] ?: continue
            if (start.name == end.name) continue
            val length = hypot(end.x - start.x, end.y - start.y)
            if (length < 1e-4) continue
            val up = graph.sockets.first { it.deviceId == socket.deviceId && it.role == "montante" }
            val down = graph.sockets.first { it.deviceId == socket.deviceId && it.role == "jusante" }
            val slope = (up.invertZ - down.invertZ) / length
            val shape = if (circular(socket.deviceId) || kotlin.math.abs(up.width - up.height) <= 0.02) {
                "<CircPipe diameter=\"${f3(maxOf(up.width, up.height))}\"/>"
            } else {
                "<RectPipe width=\"${f3(up.width)}\" height=\"${f3(up.height)}\"/>"
            }
            out.append(
                "        <Pipe name=\"P$pipe\" refStart=\"${esc(start.name)}\" refEnd=\"${esc(end.name)}\" " +
                    "length=\"${f3(length)}\" slope=\"${f3(slope)}\">$shape</Pipe>\n",
            )
            pipe++
        }
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

    private fun key(id: String, role: String) = "$id|$role"

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun f3(value: Double) = String.format(Locale.ROOT, "%.3f", value)
}

private data class PipeStruct(
    val name: String,
    val x: Double,
    val y: Double,
    val sump: Double,
    val rim: Double,
    val sockets: List<String>,
)
