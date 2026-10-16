package com.appdwg.core.drainage

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One placed device as an IFC distribution element with a drainage property set. */
data class IfcDevice(
    val id: String,
    val code: String,
    val name: String,
    val classification: String,
    val length: Double,
    val invertUp: Double,
    val invertDown: Double,
    val slope: Double,
    val phase: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val connectedTo: String,
    /** Real catalog rings, outer first or any order. A hole is every ring after the largest. */
    val section: List<List<SectionPoint>> = emptyList(),
    /** World vertices. Two or more sweep one solid per segment. One vertex is a point device. */
    val path: List<IfcPathPoint> = emptyList(),
)

/** A vertex of the device axis, metres. */
data class IfcPathPoint(val x: Double, val y: Double, val z: Double)

/**
 * IFC4 property exchange for the drainage network. Geometry stays in the app's meshes;
 * each device is an [IfcPipeSegment] with placement and a property set a coordinator can read.
 */
object IfcDrainage {
    private const val GUID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$"

    /** 22-character IFC global id, stable for the same text. */
    fun guid(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val bits = BooleanArray(132)
        for (i in 0 until 16) {
            val value = digest[i].toInt() and 0xFF
            for (bit in 0 until 8) bits[4 + i * 8 + bit] = ((value shr (7 - bit)) and 1) == 1
        }
        val out = CharArray(22)
        for (c in 0 until 22) {
            var packed = 0
            for (k in 0 until 6) if (bits[c * 6 + k]) packed = packed or (1 shl (5 - k))
            out[c] = GUID_CHARS[packed]
        }
        return String(out)
    }

    fun write(project: String, devices: List<IfcDevice>, out: Appendable) {
        val b = StringBuilder()
        fun add(line: String) { b.append(line).append('\n') }
        add("ISO-10303-21;")
        add("HEADER;")
        add("FILE_DESCRIPTION(('ViewDefinition [CoordinationView]'),'2;1');")
        add("FILE_NAME('drenagem.ifc','',('Gromatic'),(''),'Gromatic','Gromatic','');")
        add("FILE_SCHEMA(('IFC4'));")
        add("ENDSEC;")
        add("DATA;")
        add("#1=IFCPERSON($,'Gromatic',$,$,$,$,$,$);")
        add("#2=IFCORGANIZATION($,'Gromatic',$,$,$);")
        add("#3=IFCPERSONANDORGANIZATION(#1,#2,$);")
        add("#4=IFCAPPLICATION(#2,'0.1.9','Gromatic','Gromatic');")
        add("#5=IFCOWNERHISTORY(#3,#4,$,.NOCHANGE.,$,$,$,0);")
        add("#6=IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.);")
        add("#7=IFCUNITASSIGNMENT((#6));")
        add("#8=IFCCARTESIANPOINT((0.,0.,0.));")
        add("#9=IFCAXIS2PLACEMENT3D(#8,$,$);")
        add("#10=IFCGEOMETRICREPRESENTATIONCONTEXT($,'Model',3,1.0E-5,#9,$);")
        add("#11=IFCPROJECT('${guid("project:$project")}',#5,'${esc(project)}',$,$,$,$,(#10),#7);")
        add("#12=IFCLOCALPLACEMENT($,#9);")
        add("#13=IFCSITE('${guid("site:$project")}',#5,'Site',$,$,#12,$,$,.ELEMENT.,$,$,0.,$,$);")
        add("#14=IFCRELAGGREGATES('${guid("site-rel:$project")}',#5,$,$,#11,(#13));")
        var n = 20
        val deviceIds = ArrayList<Int>()
        for (device in devices) {
            val point = n++
            add("#$point=IFCCARTESIANPOINT((${f(device.x)},${f(device.y)},${f(device.z)}));")
            val axis = n++
            add("#$axis=IFCAXIS2PLACEMENT3D(#$point,$,$);")
            val place = n++
            add("#$place=IFCLOCALPLACEMENT(#12,#$axis);")
            val (entity, predefined) = ifcKind(device)
            val sink = IfcSink(b, n)
            val profile = profileDef(sink, device.section)
            val localZ = direction(sink, 0.0, 0.0, 1.0)
            val solids = sweepSolids(sink, device, profile, localZ)
            n = sink.n
            val shape = n++
            add("#$shape=IFCSHAPEREPRESENTATION(#10,'Body','SweptSolid',(${solids.joinToString(",") { "#$it" }}));")
            val definition = n++
            add("#$definition=IFCPRODUCTDEFINITIONSHAPE($,$,(#$shape));")
            val element = n++
            add("#$element=$entity('${guid(device.id)}',#5,'${esc(device.code)}','${esc(device.name)}',$,#$place,#$definition,$,$predefined);")
            deviceIds += element
            val props = ArrayList<Int>()
            fun prop(name: String, value: String): Int {
                val id = n++
                add("#$id=$value;")
                val wrap = n++
                add("#$wrap=IFCPROPERTYSINGLEVALUE('$name',$,#$id,$);")
                props += wrap
                return wrap
            }
            prop("Code", "IFCLABEL('${esc(device.code)}')")
            prop("Classification", "IFCLABEL('${esc(device.classification)}')")
            prop("Length", "IFCLENGTHMEASURE(${f(device.length)})")
            prop("InvertUpstream", "IFCLENGTHMEASURE(${f(device.invertUp)})")
            prop("InvertDownstream", "IFCLENGTHMEASURE(${f(device.invertDown)})")
            prop("Slope", "IFCREAL(${f(device.slope)})")
            prop("Phase", "IFCINTEGER(${device.phase})")
            if (device.connectedTo.isNotBlank()) prop("ConnectedTo", "IFCLABEL('${esc(device.connectedTo)}')")
            val set = n++
            add("#$set=IFCPROPERTYSET('${guid("pset:" + device.id)}',#5,'Pset_GromaticDrainage',$,(${props.joinToString(",") { "#$it" }}));")
            val rel = n++
            add("#$rel=IFCRELDEFINESBYPROPERTIES('${guid("rel:" + device.id)}',#5,$,$,(#$element),#$set);")
            val cls = n++
            add("#$cls=IFCCLASSIFICATIONREFERENCE($,'DNIT IPR-736','${esc(device.classification)}',$,$);")
            val relCls = n++
            add("#$relCls=IFCRELASSOCIATESCLASSIFICATION('${guid("cls:" + device.id)}',#5,$,$,(#$element),#$cls);")
        }
        if (deviceIds.isNotEmpty()) {
            add("#${n}=IFCRELCONTAINEDINSPATIALSTRUCTURE('${guid("contains:$project")}',#5,$,$,(${deviceIds.joinToString(",") { "#$it" }}),#13);")
        }
        add("ENDSEC;")
        add("END-ISO-10303-21;")
        out.append(b)
    }

    private class IfcSink(val b: StringBuilder, var n: Int) {
        fun add(line: String) { b.append(line).append('\n') }
        fun id(): Int = n++
    }

    private fun direction(sink: IfcSink, x: Double, y: Double, z: Double): Int {
        val id = sink.id()
        sink.add("#$id=IFCDIRECTION((${f(x)},${f(y)},${f(z)}));")
        return id
    }

    private fun point3(sink: IfcSink, x: Double, y: Double, z: Double): Int {
        val id = sink.id()
        sink.add("#$id=IFCCARTESIANPOINT((${f(x)},${f(y)},${f(z)}));")
        return id
    }

    private fun polyline(sink: IfcSink, ring: List<SectionPoint>): Int {
        val ids = ring.map { point ->
            val id = sink.id()
            sink.add("#$id=IFCCARTESIANPOINT((${f(point.x)},${f(point.y)}));")
            id
        }
        val id = sink.id()
        sink.add("#$id=IFCPOLYLINE((${(ids + ids.first()).joinToString(",") { "#$it" }}));")
        return id
    }

    private fun ringArea(ring: List<SectionPoint>): Double {
        var area = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            area += a.x * b.y - b.x * a.y
        }
        return abs(area) * 0.5
    }

    private fun profileDef(sink: IfcSink, rings: List<List<SectionPoint>>): Int {
        val usable = rings.filter { it.size >= 3 }
        if (usable.isEmpty()) {
            return closedProfile(
                sink,
                listOf(SectionPoint(-0.50, 0.0), SectionPoint(0.50, 0.0), SectionPoint(0.50, 0.50), SectionPoint(-0.50, 0.50)),
                emptyList(),
            )
        }
        val outer = usable.maxBy { ringArea(it) }
        return closedProfile(sink, outer, usable.filter { it !== outer })
    }

    private fun closedProfile(sink: IfcSink, outer: List<SectionPoint>, holes: List<List<SectionPoint>>): Int {
        val outerCurve = polyline(sink, outer)
        val inners = holes.map { polyline(sink, it) }
        val id = sink.id()
        if (inners.isEmpty()) sink.add("#$id=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,$,#$outerCurve);")
        else sink.add("#$id=IFCARBITRARYPROFILEDEFWITHVOIDS(.AREA.,$,#$outerCurve,(${inners.joinToString(",") { "#$it" }}));")
        return id
    }

    /** One extruded solid per path segment. A single vertex is a point device standing on its section. */
    private fun sweepSolids(sink: IfcSink, device: IfcDevice, profile: Int, localZ: Int): List<Int> {
        val path = device.path.filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        if (path.size >= 2) {
            val solids = ArrayList<Int>()
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dz = b.z - a.z
                val len = hypot(dx, hypot(dy, dz))
                if (len < 1e-4) continue
                val (zx, zy, zz) = unit(dx, dy, dz, len)
                val (xx, xy, xz) = across(zx, zy, zz)
                solids += extrude(sink, profile, localZ, a, zx, zy, zz, xx, xy, xz, len)
            }
            if (solids.isNotEmpty()) return solids
        }
        val origin = path.firstOrNull() ?: IfcPathPoint(device.x, device.y, if (device.z.isFinite()) device.z else 0.0)
        val rings = device.section.filter { it.size >= 3 }
        val span = rings.maxOfOrNull { ring -> max(ring.maxOf { it.x } - ring.minOf { it.x }, ring.maxOf { it.y } - ring.minOf { it.y }) } ?: 0.50
        val depth = if (device.length > 0.05 && path.size < 2) device.length else max(span, 0.30)
        return listOf(extrude(sink, profile, localZ, origin, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, depth))
    }

    private fun extrude(
        sink: IfcSink,
        profile: Int,
        localZ: Int,
        origin: IfcPathPoint,
        zx: Double,
        zy: Double,
        zz: Double,
        xx: Double,
        xy: Double,
        xz: Double,
        length: Double,
    ): Int {
        val at = point3(sink, origin.x, origin.y, origin.z)
        val axis = direction(sink, zx, zy, zz)
        val ref = direction(sink, xx, xy, xz)
        val place = sink.id()
        sink.add("#$place=IFCAXIS2PLACEMENT3D(#$at,#$axis,#$ref);")
        val solid = sink.id()
        sink.add("#$solid=IFCEXTRUDEDAREASOLID(#$profile,#$place,#$localZ,${f(length)});")
        return solid
    }

    private fun unit(x: Double, y: Double, z: Double, len: Double) = Triple(x / len, y / len, z / len)

    /** Horizontal perpendicular of the run, so the section stands upright. */
    private fun across(zx: Double, zy: Double, zz: Double): Triple<Double, Double, Double> {
        val len = hypot(zx, zy)
        if (len < 1e-6 || abs(zz) > 0.999) return Triple(1.0, 0.0, 0.0)
        return Triple(-zy / len, zx / len, 0.0)
    }

    private fun ifcKind(device: IfcDevice): Pair<String, String> {
        val text = (device.code + " " + device.name + " " + device.classification).lowercase()
        return when {
            "caixa" in text || "boca" in text || "dissip" in text -> "IFCDISTRIBUTIONCHAMBERELEMENT" to ".SUMP."
            "bueiro" in text || "bstc" in text || "bdtc" in text || "tubo" in text -> "IFCPIPESEGMENT" to ".CULVERT."
            "sarjeta" in text || "valeta" in text || "meio" in text || "descida" in text -> "IFCPIPESEGMENT" to ".GUTTER."
            else -> "IFCPIPESEGMENT" to ".NOTDEFINED."
        }
    }

    private fun f(value: Double) = if (value.isFinite()) String.format(Locale.US, "%.4f", value) else "0."

    private fun esc(text: String) = text.replace("\\", "\\\\").replace("'", "''")
}
