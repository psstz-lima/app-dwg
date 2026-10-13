package com.appdwg.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.floor

/** A GIS feature read from GeoJSON or a shapefile, in plan coordinates. */
sealed interface GisFeature {
    val attributes: Map<String, String>
    data class Point(val p: Point3, override val attributes: Map<String, String>) : GisFeature
    data class Line(val pts: List<Point3>, val closed: Boolean, override val attributes: Map<String, String>) : GisFeature
}

/** Plan position from longitude/latitude (degrees) or from projected coordinates kept as they are. */
fun interface GeoToPlan { fun plan(x: Double, y: Double): Point2 }

object Gis {
    /** Longitudes and latitudes are recognised by their range; anything else is taken as projected (E, N). */
    fun looksGeographic(xs: List<Double>, ys: List<Double>) = xs.isNotEmpty() && xs.all { it in -180.0..180.0 } && ys.all { it in -90.0..90.0 }

    fun utmZoneOf(lon: Double) = (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)

    /**
     * Drawing from GIS features: points become COGO points (name and code from the usual attribute
     * names), lines go on a layer named by the "layer"/"camada" attribute or [defaultLayer].
     */
    fun toProject(features: List<GisFeature>, defaultLayer: String, epsg: String?): LandXmlProject {
        fun attr(a: Map<String, String>, vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.entries.firstOrNull { it.key.equals(k, true) }?.value?.takeIf { it.isNotBlank() } }
        val points = ArrayList<CgPoint>(); val lines = ArrayList<Polyline>()
        features.forEachIndexed { i, f ->
            when (f) {
                is GisFeature.Point -> points += CgPoint(attr(f.attributes, "name", "nome", "ponto", "id", "number", "numero") ?: (i + 1).toString(),
                    attr(f.attributes, "code", "codigo", "código", "desc", "descricao", "descrição", "description"), f.p)
                is GisFeature.Line -> if (f.pts.size >= 2) {
                    val has3d = f.pts.any { !it.z.isNaN() && it.z != 0.0 }
                    lines += Polyline(attr(f.attributes, "layer", "camada", "tipo", "type") ?: defaultLayer,
                        DoubleArray(f.pts.size) { f.pts[it].x }, DoubleArray(f.pts.size) { f.pts[it].y }, f.closed,
                        zs = if (has3d) DoubleArray(f.pts.size) { f.pts[it].z } else null)
                }
            }
        }
        return LandXmlProject("meter", epsg?.let { CoordinateSystemInfo(null, it, null) }, emptyList(), emptyList(), points, emptyList(), polylines = lines)
    }

    // ---- GeoJSON ----

    /** Features of a GeoJSON text; [toPlan] places each coordinate. */
    fun readGeoJson(text: String, toPlan: GeoToPlan): List<GisFeature> {
        val root = Json.parse(text)
        val out = ArrayList<GisFeature>()
        fun pos(a: Any?): Point3? {
            val l = a as? List<*> ?: return null
            val x = (l.getOrNull(0) as? Number)?.toDouble() ?: return null
            val y = (l.getOrNull(1) as? Number)?.toDouble() ?: return null
            val p = toPlan.plan(x, y)
            return Point3(p.x, p.y, (l.getOrNull(2) as? Number)?.toDouble() ?: Double.NaN)
        }
        fun ring(a: Any?) = (a as? List<*>).orEmpty().mapNotNull { pos(it) }
        fun geometry(g: Map<*, *>?, attrs: Map<String, String>) {
            g ?: return
            val c = g["coordinates"]
            when (g["type"]) {
                "Point" -> pos(c)?.let { out += GisFeature.Point(it, attrs) }
                "MultiPoint" -> (c as? List<*>).orEmpty().forEach { p -> pos(p)?.let { out += GisFeature.Point(it, attrs) } }
                "LineString" -> out += GisFeature.Line(ring(c), false, attrs)
                "MultiLineString" -> (c as? List<*>).orEmpty().forEach { out += GisFeature.Line(ring(it), false, attrs) }
                "Polygon" -> (c as? List<*>).orEmpty().forEach { out += GisFeature.Line(ring(it).dropLastIfClosing(), true, attrs) }
                "MultiPolygon" -> (c as? List<*>).orEmpty().forEach { poly -> (poly as? List<*>).orEmpty().forEach { out += GisFeature.Line(ring(it).dropLastIfClosing(), true, attrs) } }
                "GeometryCollection" -> (g["geometries"] as? List<*>).orEmpty().forEach { geometry(it as? Map<*, *>, attrs) }
            }
        }
        fun feature(f: Map<*, *>) {
            val attrs = (f["properties"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to (v?.let { if (it is Double && it == floor(it)) it.toLong().toString() else it.toString() } ?: "") }
            geometry(f["geometry"] as? Map<*, *>, attrs)
        }
        when ((root as? Map<*, *>)?.get("type")) {
            "FeatureCollection" -> ((root as Map<*, *>)["features"] as? List<*>).orEmpty().forEach { (it as? Map<*, *>)?.let(::feature) }
            "Feature" -> feature(root as Map<*, *>)
            else -> geometry(root as? Map<*, *>, emptyMap())
        }
        return out
    }

    private fun List<Point3>.dropLastIfClosing() = if (size > 2 && first().x == last().x && first().y == last().y) dropLast(1) else this

    /** All x and y values of a GeoJSON text, to tell longitudes from projected coordinates. */
    fun geoJsonExtent(text: String): Pair<List<Double>, List<Double>> {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        readGeoJson(text) { x, y -> xs += x; ys += y; Point2(x, y) }
        return xs to ys
    }

    /** GeoJSON (RFC 7946, longitude/latitude) of points and lines; [toGeo] gives (lon, lat). */
    fun writeGeoJson(points: List<CogoPoint>, lines: List<Polyline>, toGeo: (Double, Double) -> Pair<Double, Double>, w: Appendable) {
        fun n(v: Double, d: Int) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
        fun coord(x: Double, y: Double, z: Double?): String {
            val (lon, lat) = toGeo(x, y)
            return "[" + n(lon, 8) + "," + n(lat, 8) + (z?.takeIf { !it.isNaN() }?.let { "," + n(it, 3) } ?: "") + "]"
        }
        fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        w.append("{\"type\":\"FeatureCollection\",\"features\":[\n")
        var first = true
        fun sep() { if (!first) w.append(",\n"); first = false }
        for (p in points) {
            sep()
            w.append("{\"type\":\"Feature\",\"properties\":{\"name\":").append(str(p.number)).append(",\"code\":").append(str(p.description))
                .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":").append(coord(p.x, p.y, p.z)).append("}}")
        }
        for (l in lines) {
            if (l.xs.size < 2) continue
            sep()
            val pts = l.xs.indices.map { coord(l.xs[it], l.ys[it], l.zs?.get(it)) }
            val geom = if (l.closed && l.xs.size >= 3) "{\"type\":\"Polygon\",\"coordinates\":[[" + (pts + pts.first()).joinToString(",") + "]]}"
            else "{\"type\":\"LineString\",\"coordinates\":[" + pts.joinToString(",") + "]}"
            w.append("{\"type\":\"Feature\",\"properties\":{\"layer\":").append(str(l.layer)).append("},\"geometry\":").append(geom).append("}")
        }
        w.append("\n]}\n")
    }

    // ---- Shapefile ----

    /** Features of a shapefile: the .shp geometry, attributes from the .dbf when given. */
    fun readShapefile(shp: ByteArray, dbf: ByteArray?, toPlan: GeoToPlan): List<GisFeature> {
        val attrs = dbf?.let { readDbf(it) }.orEmpty()
        val b = ByteBuffer.wrap(shp)
        require(shp.size >= 100 && b.order(ByteOrder.BIG_ENDIAN).getInt(0) == 9994) { "não é um arquivo .shp" }
        val out = ArrayList<GisFeature>()
        var pos = 100
        var rec = 0
        while (pos + 8 <= shp.size) {
            b.order(ByteOrder.BIG_ENDIAN)
            val len = b.getInt(pos + 4) * 2
            val start = pos + 8
            if (start + len > shp.size || len < 4) break
            b.order(ByteOrder.LITTLE_ENDIAN)
            val type = b.getInt(start)
            val a = attrs.getOrNull(rec).orEmpty()
            fun pt(off: Int, z: Double) = toPlan.plan(b.getDouble(off), b.getDouble(off + 8)).let { Point3(it.x, it.y, z) }
            when (type) {
                1, 11, 21 -> out += GisFeature.Point(pt(start + 4, if (type == 11) b.getDouble(start + 20) else Double.NaN), a)
                8, 18, 28 -> {
                    val n = b.getInt(start + 36)
                    val zOff = start + 40 + 16 * n + 16
                    for (i in 0 until n) out += GisFeature.Point(pt(start + 40 + 16 * i, if (type == 18) b.getDouble(zOff + 8 * i) else Double.NaN), a)
                }
                3, 5, 13, 15, 23, 25 -> {
                    val parts = b.getInt(start + 36); val n = b.getInt(start + 40)
                    val pIdx = IntArray(parts) { b.getInt(start + 44 + 4 * it) }
                    val ptOff = start + 44 + 4 * parts
                    val zOff = ptOff + 16 * n + 16
                    val hasZ = type == 13 || type == 15
                    val closed = type == 5 || type == 15 || type == 25
                    for (k in 0 until parts) {
                        val from = pIdx[k]; val to = if (k + 1 < parts) pIdx[k + 1] else n
                        val pts = (from until to).map { pt(ptOff + 16 * it, if (hasZ) b.getDouble(zOff + 8 * it) else Double.NaN) }
                        out += GisFeature.Line(if (closed) pts.dropLastIfClosing() else pts, closed, a)
                    }
                }
            }
            pos = start + len
            rec++
        }
        return out
    }

    /** One shapefile of a ZIP: its name (without extension), .shp, .dbf and .prj. */
    class ShapefileParts(val name: String, val shp: ByteArray, val dbf: ByteArray?, val prj: String?)

    /** Every shapefile in a ZIP (any folder), each with the .dbf and .prj of the same name. */
    fun unzipShapefiles(zip: ByteArray): List<ShapefileParts> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name.lowercase()] = z.readBytes()
            }
        }
        return files.keys.filter { it.endsWith(".shp") }.sorted().map { shp ->
            val base = shp.removeSuffix(".shp")
            ShapefileParts(base.substringAfterLast('/'), files[shp]!!, files["$base.dbf"], files["$base.prj"]?.toString(Charsets.ISO_8859_1))
        }
    }

    /** The first shapefile of a ZIP: .shp, .dbf and .prj. */
    fun unzipShapefile(zip: ByteArray): Triple<ByteArray, ByteArray?, String?>? {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name.lowercase()] = z.readBytes()
            }
        }
        val shp = files.keys.firstOrNull { it.endsWith(".shp") } ?: return null
        val base = shp.removeSuffix(".shp")
        return Triple(files[shp]!!, files["$base.dbf"], files["$base.prj"]?.toString(Charsets.ISO_8859_1))
    }

    /** True for a .prj of geographic coordinates (degrees). */
    fun prjIsGeographic(prj: String?) = prj != null && prj.trimStart().startsWith("GEOGCS", true)

    /** EPSG of a SIRGAS 2000 / UTM .prj (319xx), when it names the zone. */
    fun prjEpsg(prj: String?): String? {
        prj ?: return null
        Regex("UTM[_ ]zone[_ ](\\d{1,2})([NS])", RegexOption.IGNORE_CASE).find(prj)?.let { m ->
            val z = m.groupValues[1].toInt(); val s = m.groupValues[2].equals("S", true)
            val u = prj.uppercase()
            val datum = when {
                "SIRGAS" in u -> Datum.SIRGAS2000
                "SAD" in u && "96" in u -> Datum.SAD69_96
                "SAD" in u || "SOUTH_AMERICAN_1969" in u -> Datum.SAD69
                "CORREGO" in u -> Datum.CORREGO_ALEGRE
                "WGS" in u -> Datum.WGS84
                else -> return null
            }
            return CrsCatalog.byId(CrsCatalog.utm(datum, z, s, null).id)?.let { it.epsg ?: it.id }
        }
        return null
    }

    /** WKT of SIRGAS 2000 / UTM zone [zone] (south or north). */
    fun sirgasUtmPrj(zone: Int, south: Boolean): String {
        val cm = zone * 6 - 183
        return "PROJCS[\"SIRGAS_2000_UTM_Zone_${zone}${if (south) "S" else "N"}\",GEOGCS[\"GCS_SIRGAS_2000\",DATUM[\"D_SIRGAS_2000\"," +
            "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]," +
            "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",${if (south) "10000000.0" else "0.0"}]," +
            "PARAMETER[\"Central_Meridian\",$cm.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]"
    }

    /**
     * ZIP with up to three shapefiles in plan coordinates (pontos: PointZ, linhas: PolyLineZ,
     * poligonos: PolygonZ), each with .shp, .shx, .dbf and .prj ([prj] when known).
     */
    fun writeShapefileZip(points: List<CogoPoint>, lines: List<Polyline>, prj: String?, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            fun set(base: String, shp: ByteArray, shx: ByteArray, dbf: ByteArray) {
                put("$base.shp", shp); put("$base.shx", shx); put("$base.dbf", dbf); prj?.let { put("$base.prj", it.toByteArray(Charsets.ISO_8859_1)) }
                put("$base.cpg", "UTF-8".toByteArray())
            }
            if (points.isNotEmpty()) {
                val geoms = points.map { p -> listOf(listOf(Point3(p.x, p.y, if (p.z.isNaN()) 0.0 else p.z))) }
                val (shp, shx) = shapes(11, geoms)
                set("pontos", shp, shx, dbf(listOf("NOME" to 20, "CODIGO" to 40, "COTA" to 16), points.map { listOf(it.number, it.description, if (it.z.isNaN()) "" else String.format(java.util.Locale.ROOT, "%.3f", it.z)) }))
            }
            val open = lines.filter { !it.closed && it.xs.size >= 2 }
            val closed = lines.filter { it.closed && it.xs.size >= 3 }
            fun geom(l: Polyline, close: Boolean): List<List<Point3>> {
                val pts = l.xs.indices.map { Point3(l.xs[it], l.ys[it], l.zs?.get(it) ?: 0.0) }
                return listOf(if (close) pts + pts.first() else pts)
            }
            if (open.isNotEmpty()) {
                val (shp, shx) = shapes(13, open.map { geom(it, false) })
                set("linhas", shp, shx, dbf(listOf("CAMADA" to 60), open.map { listOf(it.layer) }))
            }
            if (closed.isNotEmpty()) {
                // Polygon rings go clockwise in a shapefile.
                val (shp, shx) = shapes(15, closed.map { l -> geom(l, true).map { r -> if (signedArea(r) > 0) r.reversed() else r } })
                set("poligonos", shp, shx, dbf(listOf("CAMADA" to 60), closed.map { listOf(it.layer) }))
            }
        }
    }

    private fun signedArea(r: List<Point3>): Double {
        var a = 0.0
        for (i in 0 until r.size - 1) a += r[i].x * r[i + 1].y - r[i + 1].x * r[i].y
        return a / 2
    }

    /** .shp and .shx bytes for Z shapes of [type] (11 PointZ, 13 PolyLineZ, 15 PolygonZ); each geometry is a list of parts. */
    private fun shapes(type: Int, geoms: List<List<List<Point3>>>): Pair<ByteArray, ByteArray> {
        val recs = geoms.map { parts ->
            val all = parts.flatten()
            val bb = ByteBuffer.allocate(if (type == 11) 36 else 4 + 32 + 8 + 4 * parts.size + 16 * all.size + 16 + 8 * all.size + 16 + 8 * all.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(type)
            if (type == 11) {
                val p = all.first(); bb.putDouble(p.x); bb.putDouble(p.y); bb.putDouble(p.z); bb.putDouble(0.0)
            } else {
                bb.putDouble(all.minOf { it.x }); bb.putDouble(all.minOf { it.y }); bb.putDouble(all.maxOf { it.x }); bb.putDouble(all.maxOf { it.y })
                bb.putInt(parts.size); bb.putInt(all.size)
                var k = 0
                for (p in parts) { bb.putInt(k); k += p.size }
                for (p in all) { bb.putDouble(p.x); bb.putDouble(p.y) }
                bb.putDouble(all.minOf { it.z }); bb.putDouble(all.maxOf { it.z })
                for (p in all) bb.putDouble(p.z)
                bb.putDouble(0.0); bb.putDouble(0.0)
                for (p in all) bb.putDouble(0.0)
            }
            bb.array()
        }
        val all = geoms.flatten().flatten()
        fun header(lengthBytes: Int): ByteArray {
            val h = ByteBuffer.allocate(100)
            h.order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, lengthBytes / 2)
            h.order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, type)
                .putDouble(36, all.minOf { it.x }).putDouble(44, all.minOf { it.y }).putDouble(52, all.maxOf { it.x }).putDouble(60, all.maxOf { it.y })
                .putDouble(68, all.minOf { it.z }).putDouble(76, all.maxOf { it.z })
            return h.array()
        }
        val shp = ByteArrayOutputStream(); val shx = ByteArrayOutputStream()
        val shpLen = 100 + recs.sumOf { 8 + it.size }
        shp.write(header(shpLen)); shx.write(header(100 + 8 * recs.size))
        var offset = 100
        recs.forEachIndexed { i, r ->
            val h = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(r.size / 2).array()
            shp.write(h); shp.write(r)
            shx.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(offset / 2).putInt(r.size / 2).array())
            offset += 8 + r.size
        }
        return shp.toByteArray() to shx.toByteArray()
    }

    /** dBASE III table of text fields (UTF-8, cut to the field width). */
    private fun dbf(fields: List<Pair<String, Int>>, rows: List<List<String>>): ByteArray {
        val recLen = 1 + fields.sumOf { it.second }
        val headLen = 32 + 32 * fields.size + 1
        val b = ByteBuffer.allocate(headLen + recLen * rows.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(3).put(126).put(10).put(13).putInt(rows.size).putShort(headLen.toShort()).putShort(recLen.toShort())
        b.position(32)
        for ((name, w) in fields) {
            val nb = name.toByteArray(Charsets.ISO_8859_1).copyOf(11)
            b.put(nb).put('C'.code.toByte()).putInt(0).put(w.toByte()).put(0)
            b.position(b.position() + 14)
        }
        b.put(0x0D)
        for (r in rows) {
            b.put(' '.code.toByte())
            fields.forEachIndexed { k, (_, w) ->
                var v = r.getOrElse(k) { "" }.toByteArray(Charsets.UTF_8)
                if (v.size > w) v = v.copyOf(w)
                b.put(v); repeat(w - v.size) { b.put(' '.code.toByte()) }
            }
        }
        b.put(0x1A)
        return b.array()
    }

    /** Rows of a .dbf as name → text (trimmed); deleted rows are skipped. */
    fun readDbf(d: ByteArray): List<Map<String, String>> {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (d.size < 32) return emptyList()
        val n = b.getInt(4); val headLen = b.getShort(8).toInt() and 0xFFFF; val recLen = b.getShort(10).toInt() and 0xFFFF
        val fields = ArrayList<Pair<String, Int>>()
        var p = 32
        while (p + 32 <= headLen && d[p] != 0x0D.toByte()) {
            val name = String(d, p, 11, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
            fields += name to (d[p + 16].toInt() and 0xFF)
            p += 32
        }
        // UTF-8 when it decodes cleanly, Latin-1 otherwise (older files).
        fun text(off: Int, len: Int): String {
            val bytes = d.copyOfRange(off, off + len)
            val utf = String(bytes, Charsets.UTF_8)
            return (if ('�' in utf) String(bytes, Charsets.ISO_8859_1) else utf).trim()
        }
        val out = ArrayList<Map<String, String>>()
        for (i in 0 until n) {
            val off = headLen + i * recLen
            if (off + recLen > d.size) break
            var q = off + 1
            val row = LinkedHashMap<String, String>()
            for ((name, w) in fields) { row[name] = text(q, w); q += w }
            out += if (d[off] == '*'.code.toByte()) emptyMap() else row
        }
        return out
    }
}
