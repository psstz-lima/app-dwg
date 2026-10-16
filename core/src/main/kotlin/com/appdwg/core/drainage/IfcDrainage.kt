package com.appdwg.core.drainage

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
        add("#11=IFCPROJECT('${esc(project)}',#5,'${esc(project)}',$,$,$,$,(#10),#7);")
        add("#12=IFCLOCALPLACEMENT($,#9);")
        add("#13=IFCSITE('site',#5,'Site',$,$,#12,$,$,.ELEMENT.,$,$,0.,$,$);")
        add("#14=IFCRELAGGREGATES('site-rel',#5,$,$,#11,(#13));")
        var n = 20
        val deviceIds = ArrayList<Int>()
        for (device in devices) {
            val point = n++
            add("#$point=IFCCARTESIANPOINT((${f(device.x)},${f(device.y)},${f(device.z)}));")
            val axis = n++
            add("#$axis=IFCAXIS2PLACEMENT3D(#$point,$,$);")
            val place = n++
            add("#$place=IFCLOCALPLACEMENT(#12,#$axis);")
            val element = n++
            add("#$element=IFCPIPESEGMENT('${esc(device.id)}',#5,'${esc(device.code)}','${esc(device.name)}',$,#$place,$,$,.NOTDEFINED.);")
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
            add("#$set=IFCPROPERTYSET('pset-${esc(device.id)}',#5,'Pset_GromaticDrainage',$,(${props.joinToString(",") { "#$it" }}));")
            val rel = n++
            add("#$rel=IFCRELDEFINESBYPROPERTIES('rel-${esc(device.id)}',#5,$,$,(#$element),#$set);")
            val cls = n++
            add("#$cls=IFCCLASSIFICATIONREFERENCE($,'DNIT IPR-736','${esc(device.classification)}',$,$);")
            val relCls = n++
            add("#$relCls=IFCRELASSOCIATESCLASSIFICATION('cls-${esc(device.id)}',#5,$,$,(#$element),#$cls);")
        }
        if (deviceIds.isNotEmpty()) {
            add("#${n}=IFCRELCONTAINEDINSPATIALSTRUCTURE('contains',#5,$,$,(${deviceIds.joinToString(",") { "#$it" }}),#13);")
        }
        add("ENDSEC;")
        add("END-ISO-10303-21;")
        out.append(b)
    }

    private fun f(value: Double) = if (value.isFinite()) String.format(Locale.US, "%.4f", value) else "0."

    private fun esc(text: String) = text.replace("\\", "\\\\").replace("'", "''")
}
