package com.appdwg.core

import java.io.Writer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Extra lines to write with a drawing, such as the measurement or the cross section line. */
class ExportLine(val layer: String, val points: List<Point2>, val closed: Boolean, val aci: Int)

/**
 * ASCII DXF in the R12 subset (POLYLINE, TEXT, POINT, 3DFACE), which every CAD program reads.
 * Surfaces are written as 3DFACEs only when asked: they can be millions of triangles.
 */
object DxfWriter {
    fun write(
        project: LandXmlProject,
        out: Writer,
        hiddenLayers: Set<String> = emptySet(),
        extra: List<ExportLine> = emptyList(),
        includeSurfaces: Boolean = false,
    ) {
        fun pair(code: Int, value: Any) {
            out.write(code.toString()); out.write("\r\n"); out.write(value.toString()); out.write("\r\n")
        }
        fun num(code: Int, v: Double) = pair(code, f(v))

        val layers = LinkedHashMap<String, Int>()
        fun layer(name: String, argb: Int?) { if (name !in layers) layers[name] = argb?.let(::aci) ?: 7 }
        project.polylines.forEach { if (it.layer !in hiddenLayers) layer(it.layer, project.layerColors[it.layer]) }
        project.texts.forEach { if (it.layer !in hiddenLayers) layer(it.layer, project.layerColors[it.layer]) }
        project.hatches.forEach { if (it.layer !in hiddenLayers) layer(it.layer, project.layerColors[it.layer]) }
        if (project.alignments.isNotEmpty()) layers.getOrPut(ALIGNMENT_LAYER) { 1 }
        if (project.points.isNotEmpty() && POINTS_LAYER !in hiddenLayers) layers.getOrPut(POINTS_LAYER) { 30 }
        if (project.cgPoints.isNotEmpty()) layers.getOrPut(CG_LAYER) { 5 }
        extra.forEach { layers.getOrPut(it.layer) { it.aci } }
        if (includeSurfaces) project.surfaces.forEach { if (it.name !in hiddenLayers) layers.getOrPut(clean(it.name)) { 8 } }

        pair(0, "SECTION"); pair(2, "HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "TABLES")
        pair(0, "TABLE"); pair(2, "LAYER"); pair(70, layers.size)
        for ((name, color) in layers) {
            pair(0, "LAYER"); pair(2, clean(name)); pair(70, 0); pair(62, color); pair(6, "CONTINUOUS")
        }
        pair(0, "ENDTAB")
        pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "ENTITIES")

        fun color(byLayer: Boolean, argb: Int?) { if (!byLayer) pair(62, argb?.let(::aci) ?: 7) }
        fun polyline(layer: String, xs: DoubleArray, ys: DoubleArray, zs: DoubleArray?, closed: Boolean, byLayer: Boolean = true, argb: Int? = null) {
            if (xs.size < 2) return
            pair(0, "POLYLINE"); pair(8, clean(layer)); color(byLayer, argb); pair(66, 1)
            num(10, 0.0); num(20, 0.0); num(30, 0.0)
            pair(70, (if (closed) 1 else 0) or (if (zs != null) 8 else 0))
            for (i in xs.indices) {
                pair(0, "VERTEX"); pair(8, clean(layer))
                num(10, xs[i]); num(20, ys[i]); num(30, zs?.get(i) ?: 0.0)
                if (zs != null) pair(70, 32)
            }
            pair(0, "SEQEND"); pair(8, clean(layer))
        }
        fun text(layer: String, x: Double, y: Double, height: Double, value: String, rotationDeg: Double = 0.0, byLayer: Boolean = true, argb: Int? = null) {
            pair(0, "TEXT"); pair(8, clean(layer)); color(byLayer, argb)
            num(10, x); num(20, y); num(30, 0.0); num(40, height); pair(1, value.replace("\n", " "))
            if (rotationDeg != 0.0) num(50, rotationDeg)
        }

        for (l in project.polylines) if (l.layer !in hiddenLayers) polyline(l.layer, l.xs, l.ys, l.zs, l.closed, l.byLayer, l.color)
        for (h in project.hatches) if (h.layer !in hiddenLayers) h.loops.forEach { polyline(h.layer, it.xs, it.ys, null, true, h.byLayer, h.color) }
        for (t in project.texts) if (t.layer !in hiddenLayers) {
            t.text.lines().forEachIndexed { i, line ->
                // Lines go down the text's own vertical direction.
                val dy = -i * t.height * 1.6
                val x = t.x - dy * sin(t.rotation)
                val y = t.y + dy * cos(t.rotation)
                text(t.layer, x, y, t.height, line, Math.toDegrees(t.rotation), t.byLayer, t.color)
            }
        }
        for (a in project.alignments) {
            polyline(ALIGNMENT_LAYER, DoubleArray(a.vertices.size) { a.vertices[it].x }, DoubleArray(a.vertices.size) { a.vertices[it].y }, null, false)
        }
        for (c in project.cgPoints) {
            pair(0, "POINT"); pair(8, CG_LAYER); num(10, c.position.x); num(20, c.position.y); num(30, if (c.position.z.isNaN()) 0.0 else c.position.z)
        }
        if (POINTS_LAYER !in hiddenLayers) for (p in project.points) {
            val z = if (p.z.isNaN()) 0.0 else p.z
            pair(0, "POINT"); pair(8, POINTS_LAYER); num(10, p.x); num(20, p.y); num(30, z)
            val label = listOf(p.number, p.description, if (p.z.isNaN()) "" else f3(p.z)).filter { it.isNotEmpty() }.joinToString(" ")
            text(POINTS_LAYER, p.x + 0.5, p.y + 0.5, 0.5, label)
        }
        for (e in extra) polyline(e.layer, DoubleArray(e.points.size) { e.points[it].x }, DoubleArray(e.points.size) { e.points[it].y }, null, e.closed)
        if (includeSurfaces) for (s in project.surfaces) {
            if (s.name in hiddenLayers) continue
            val t = s.triangles
            for (k in 0 until s.triangleCount) {
                pair(0, "3DFACE"); pair(8, clean(s.name))
                val idx = intArrayOf(t[3 * k], t[3 * k + 1], t[3 * k + 2], t[3 * k + 2])
                for ((j, i) in idx.withIndex()) { num(10 + j, s.xs[i]); num(20 + j, s.ys[i]); num(30 + j, s.zs[i]) }
            }
        }
        pair(0, "ENDSEC"); pair(0, "EOF")
        out.flush()
    }

    /** Nearest AutoCAD color index to an ARGB color. */
    fun aci(argb: Int): Int {
        var best = 7
        var bestD = Int.MAX_VALUE
        for (i in 1..255) {
            val c = DxfReader.aciColor(i) ?: continue
            val d = sq(((c shr 16) and 0xFF) - ((argb shr 16) and 0xFF)) + sq(((c shr 8) and 0xFF) - ((argb shr 8) and 0xFF)) +
                sq((c and 0xFF) - (argb and 0xFF))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun sq(v: Int) = v * v

    /** Layer names may not hold these characters in DXF. */
    private fun clean(name: String) = name.replace(Regex("[<>/\\\\\":;?*|=`]"), "_")

    const val ALIGNMENT_LAYER = "_EIXO"
    const val CG_LAYER = "_COGO"
}

/** LandXML 1.2 with surfaces (points and faces) and points. */
object LandXmlWriter {
    fun write(surfaces: List<Surface>, points: List<CogoPoint>, cgPoints: List<CgPoint>, out: Writer, epsg: String? = null) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">\n")
        out.write("  <Units><Metric linearUnit=\"meter\" areaUnit=\"squareMeter\" volumeUnit=\"cubicMeter\" angularUnit=\"decimal degrees\"/></Units>\n")
        if (epsg != null) out.write("  <CoordinateSystem epsgCode=\"${esc(epsg)}\"/>\n")
        out.write("  <Application name=\"App DWG\"/>\n")
        if (points.isNotEmpty() || cgPoints.isNotEmpty()) {
            out.write("  <CgPoints>\n")
            // LandXML stores northing first.
            for (p in points) {
                val z = if (p.z.isNaN()) "" else " " + f3(p.z)
                out.write("    <CgPoint name=\"${esc(p.number)}\" desc=\"${esc(p.description)}\">${f3(p.y)} ${f3(p.x)}$z</CgPoint>\n")
            }
            for (c in cgPoints) {
                val z = if (c.position.z.isNaN()) "" else " " + f3(c.position.z)
                out.write("    <CgPoint name=\"${esc(c.name)}\"${c.code?.let { " code=\"${esc(it)}\"" } ?: ""}>${f3(c.position.y)} ${f3(c.position.x)}$z</CgPoint>\n")
            }
            out.write("  </CgPoints>\n")
        }
        if (surfaces.isNotEmpty()) {
            out.write("  <Surfaces>\n")
            for (s in surfaces) {
                out.write("    <Surface name=\"${esc(s.name)}\">\n      <Definition surfType=\"TIN\">\n        <Pnts>\n")
                for (i in 0 until s.vertexCount) out.write("          <P id=\"${i + 1}\">${f3(s.ys[i])} ${f3(s.xs[i])} ${f3(s.zs[i])}</P>\n")
                out.write("        </Pnts>\n        <Faces>\n")
                val t = s.triangles
                for (k in 0 until s.triangleCount) out.write("          <F>${t[3 * k] + 1} ${t[3 * k + 1] + 1} ${t[3 * k + 2] + 1}</F>\n")
                out.write("        </Faces>\n      </Definition>\n    </Surface>\n")
            }
            out.write("  </Surfaces>\n")
        }
        out.write("</LandXML>\n")
        out.flush()
    }
}

/** Transverse Mercator on GRS80 (SIRGAS 2000, practically WGS84): UTM to latitude and longitude. */
object Utm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257222101
    private const val K0 = 0.9996

    /** Zone of a SIRGAS 2000 / UTM south EPSG code (31978 = 18S … 31985 = 25S), or null. */
    fun zoneOfEpsg(epsg: String?): Int? = epsg?.toIntOrNull()?.let { if (it in 31978..31985) it - 31960 else null }

    /** Latitude and longitude in degrees of easting [e] and northing [n] in [zone]; [south] adds the false northing. */
    fun toGeographic(e: Double, n: Double, zone: Int, south: Boolean): Pair<Double, Double> {
        // Karney/Krüger series, accurate to well under a millimetre inside the zone.
        val nn = F / (2 - F)
        val aa = A / (1 + nn) * (1 + nn * nn / 4 + nn * nn * nn * nn / 64)
        val b1 = nn / 2 - 2 * nn * nn / 3 + 37 * nn * nn * nn / 96
        val b2 = nn * nn / 48 + nn * nn * nn / 15
        val b3 = 17 * nn * nn * nn / 480
        val xi = (n - if (south) 10_000_000.0 else 0.0) / (K0 * aa)
        val eta = (e - 500_000.0) / (K0 * aa)
        var xi1 = xi
        var eta1 = eta
        for ((j, b) in listOf(b1, b2, b3).withIndex()) {
            val k = 2.0 * (j + 1)
            xi1 -= b * sin(k * xi) * kotlin.math.cosh(k * eta)
            eta1 -= b * cos(k * xi) * sinh(k * eta)
        }
        val chi = kotlin.math.asin(sin(xi1) / kotlin.math.cosh(eta1))
        val lon0 = Math.toRadians(zone * 6.0 - 183.0)
        val lon = lon0 + kotlin.math.atan2(sinh(eta1), cos(xi1))
        // Conformal to geodetic latitude by iteration.
        val e2 = F * (2 - F)
        val ec = sqrt(e2)
        var phi = chi
        repeat(6) {
            val s = sin(phi)
            phi = 2 * atan(tan(PI / 4 + chi / 2) * Math.pow((1 + ec * s) / (1 - ec * s), ec / 2)) - PI / 2
        }
        return Math.toDegrees(phi) to Math.toDegrees(lon)
    }
}

/** KML of points and lines, in latitude and longitude from UTM. */
object KmlWriter {
    fun write(points: List<CogoPoint>, lines: List<ExportLine>, zone: Int, south: Boolean, name: String, out: Writer) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun coord(x: Double, y: Double, z: Double = Double.NaN): String {
            val (lat, lon) = Utm.toGeographic(x, y, zone, south)
            return String.format(Locale.ROOT, "%.8f,%.8f", lon, lat) + if (z.isNaN()) "" else String.format(Locale.ROOT, ",%.3f", z)
        }
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        out.write("<name>${esc(name)}</name>\n")
        for (p in points) {
            out.write("<Placemark><name>${esc(p.number)}</name><description>${esc(p.description)}" +
                (if (p.z.isNaN()) "" else " cota " + f3(p.z)) + "</description>")
            out.write("<Point><coordinates>${coord(p.x, p.y)}</coordinates></Point></Placemark>\n")
        }
        for (l in lines) {
            val pts = if (l.closed) l.points + l.points.first() else l.points
            out.write("<Placemark><name>${esc(l.layer)}</name><LineString><tessellate>1</tessellate><coordinates>")
            out.write(pts.joinToString(" ") { coord(it.x, it.y) })
            out.write("</coordinates></LineString></Placemark>\n")
        }
        out.write("</Document>\n</kml>\n")
        out.flush()
    }
}

private fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)
private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
