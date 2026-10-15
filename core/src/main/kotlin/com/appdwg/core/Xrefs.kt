package com.appdwg.core

import java.io.BufferedReader
import java.io.Writer

/** Plan transform: x' = a·x + b·y + c, y' = d·x + e·y + f; z' = z + tz. [layer] is the insert's layer. */
class Placement(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double,
    val tz: Double,
    val layer: String,
) {
    fun apply(x: Double, y: Double) = Point2(a * x + b * y + c, d * x + e * y + f)
    val scale get() = kotlin.math.hypot(a, d)
    val rotation get() = kotlin.math.atan2(d, a)
}

/** External reference to another drawing (AutoCAD XREF); [path] is the file as the DXF stores it. */
class XrefDrawing(val name: String, val path: String, val placements: List<Placement>, val overlay: Boolean) {
    val fileName get() = path.substringAfterLast('\\').substringAfterLast('/')
}

object Xrefs {
    /**
     * The project with [drawing] drawn at every placement of [xref]. Its layers are renamed
     * "XREF|layer", as AutoCAD shows them.
     */
    fun attach(project: LandXmlProject, xref: XrefDrawing, drawing: LandXmlProject): LandXmlProject {
        if (xref.placements.isEmpty()) return project
        fun layer(l: String) = "${xref.name}|$l"
        val lines = ArrayList(project.polylines)
        val texts = ArrayList(project.texts)
        val hatches = ArrayList(project.hatches)
        val surfaces = ArrayList(project.surfaces)
        val points = ArrayList(project.cgPoints)
        fun moved(l: Polyline, p: Placement): Polyline {
            val xs = DoubleArray(l.xs.size)
            val ys = DoubleArray(l.xs.size)
            for (i in l.xs.indices) {
                val w = p.apply(l.xs[i], l.ys[i])
                xs[i] = w.x; ys[i] = w.y
            }
            return Polyline(layer(l.layer), xs, ys, l.closed, l.color, l.byLayer, l.zs?.let { z -> DoubleArray(z.size) { z[it] + p.tz } })
        }
        for (p in xref.placements) {
            drawing.polylines.forEach { lines += moved(it, p) }
            drawing.texts.forEach { t ->
                val w = p.apply(t.x, t.y)
                texts += PlanText(layer(t.layer), t.color, t.byLayer, w.x, w.y, t.height * p.scale, t.rotation + p.rotation, t.text, t.hAlign, t.vAlign)
            }
            drawing.hatches.forEach { h -> hatches += Hatch(layer(h.layer), h.color, h.byLayer, h.loops.map { moved(it, p) }) }
            drawing.surfaces.forEach { s ->
                val xs = DoubleArray(s.vertexCount)
                val ys = DoubleArray(s.vertexCount)
                for (i in 0 until s.vertexCount) {
                    val w = p.apply(s.xs[i], s.ys[i])
                    xs[i] = w.x; ys[i] = w.y
                }
                surfaces += Surface(layer(s.name), xs, ys, DoubleArray(s.vertexCount) { s.zs[it] + p.tz }, s.triangles)
            }
            drawing.cgPoints.forEach { c ->
                val w = p.apply(c.position.x, c.position.y)
                points += CgPoint(c.name, c.code?.let(::layer), Point3(w.x, w.y, c.position.z + p.tz))
            }
        }
        return project.copy(
            surfaces = surfaces,
            cgPoints = points,
            polylines = lines,
            texts = texts,
            hatches = hatches,
            layersOff = project.layersOff + drawing.layersOff.map(::layer),
            layersFrozen = project.layersFrozen + drawing.layersFrozen.map(::layer),
            layerColors = project.layerColors + drawing.layerColors.mapKeys { layer(it.key) },
            warnings = project.warnings + drawing.warnings.map { "${xref.name}: $it" },
        )
    }

    /**
     * Copies a DXF, replacing the saved paths of images (IMAGEDEF) and xrefs (BLOCK) found in
     * [changes] (old path to new path). Everything else is copied unchanged.
     */
    fun rewritePaths(input: BufferedReader, output: Writer, changes: Map<String, String>) {
        var type = ""
        while (true) {
            val codeLine = input.readLine() ?: break
            val value = input.readLine() ?: run { output.write(codeLine); output.write("\r\n"); null } ?: break
            val code = codeLine.trim().toIntOrNull()
            if (code == 0) type = value.trim()
            val newValue = if (code == 1 && (type == "IMAGEDEF" || type == "BLOCK")) changes[value.trim()] else null
            output.write(codeLine)
            output.write("\r\n")
            output.write(newValue ?: value)
            output.write("\r\n")
        }
        output.flush()
    }
}
