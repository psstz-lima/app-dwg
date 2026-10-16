package com.appdwg.core.drainage

import java.security.MessageDigest
import java.util.Locale

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
)

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
            val length = device.length.coerceAtLeast(0.3)
            val profilePoint = n++
            add("#$profilePoint=IFCCARTESIANPOINT((0.,0.));")
            val profileAxis = n++
            add("#$profileAxis=IFCAXIS2PLACEMENT2D(#$profilePoint,$);")
            val profile = n++
            add("#$profile=IFCRECTANGLEPROFILEDEF(.AREA.,$,#$profileAxis,0.60,0.40);")
            val direction = n++
            add("#$direction=IFCDIRECTION((0.,1.,0.));")
            val solidPlace = n++
            add("#$solidPlace=IFCAXIS2PLACEMENT3D(#$point,$,$);")
            val solid = n++
            add("#$solid=IFCEXTRUDEDAREASOLID(#$profile,#$solidPlace,#$direction,${f(length)});")
            val shape = n++
            add("#$shape=IFCSHAPEREPRESENTATION(#10,'Body','SweptSolid',(#$solid));")
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
