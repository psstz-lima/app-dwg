package com.appdwg.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Elevations on a latitude/longitude grid; row 0 is the northern edge, NaN where there is no value. */
class GeoGrid(val north: Double, val west: Double, val dLat: Double, val dLon: Double, val rows: Int, val cols: Int, val values: FloatArray) {
    operator fun get(r: Int, c: Int) = values[r * cols + c]
    fun lat(r: Int) = north - r * dLat
    fun lon(c: Int) = west + c * dLon
}

/**
 * Copernicus DEM GLO-30: a global 30 m surface model (DSM: it includes buildings and trees), in
 * 1° × 1° Cloud Optimized GeoTIFFs on AWS Open Data, read without a key, a window at a time.
 */
object CopernicusDem {
    /** Notice the licence asks for wherever the data, or anything made from it, is shown. */
    const val ATTRIBUTION = "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"
    const val DISCLAIMER = "The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30"

    /** Name of the tile whose south-west corner is at ([lat], [lon]), whole degrees. */
    fun tileName(lat: Int, lon: Int): String {
        val ns = if (lat < 0) "S%02d".format(-lat) else "N%02d".format(lat)
        val ew = if (lon < 0) "W%03d".format(-lon) else "E%03d".format(lon)
        return "Copernicus_DSM_COG_10_${ns}_00_${ew}_00_DEM"
    }

    fun url(name: String) = "https://copernicus-dem-30m.s3.amazonaws.com/$name/$name.tif"

    /** South-west corners of the tiles covering the area. */
    fun tiles(south: Double, west: Double, north: Double, east: Double): List<Pair<Int, Int>> =
        (floor(south).toInt()..floor(north - 1e-9).toInt()).flatMap { la -> (floor(west).toInt()..floor(east - 1e-9).toInt()).map { lo -> la to lo } }

    /**
     * Elevations over the area, about [maxSide] samples along its longer side at most (a reduced
     * level of the files when the area is large). [open] gives a tile's file, or null where there is
     * none (sea, or tiles not released): those samples are NaN. [cancelled] stops between tiles.
     */
    fun grid(south: Double, west: Double, north: Double, east: Double, maxSide: Int, open: (String) -> RandomInput?,
             cancelled: () -> Boolean = { false }, onTile: (Int, Int) -> Unit = { _, _ -> }): GeoGrid {
        // 1 arc second at full resolution; a coarser level when the area would need too many samples.
        var level = 0
        var step = 1.0 / 3600
        while (max((north - south) / step, (east - west) / step) > maxSide && level < 3) { level++; step *= 2 }
        val rows = ceil((north - south) / step).toInt() + 1
        val cols = ceil((east - west) / step).toInt() + 1
        val values = FloatArray(rows * cols) { Float.NaN }
        val list = tiles(south, west, north, east)
        list.forEachIndexed { i, (la, lo) ->
            if (cancelled()) return@forEachIndexed
            onTile(i, list.size)
            val input = open(tileName(la, lo)) ?: return@forEachIndexed
            val reader = TiffReader(input)
            val (tw, th) = reader.levels().getOrNull(level) ?: reader.levels().last()
            val ext = reader.extent() ?: Bounds(lo.toDouble(), la.toDouble(), lo + 1.0, la + 1.0)
            val px = ext.width / tw; val py = ext.height / th
            // The window of this tile the area needs, with a pixel to spare for interpolation.
            val c0 = floor((max(west, ext.minX) - ext.minX) / px).toInt() - 1
            val c1 = ceil((min(east, ext.maxX) - ext.minX) / px).toInt() + 1
            val r0 = floor((ext.maxY - min(north, ext.maxY)) / py).toInt() - 1
            val r1 = ceil((ext.maxY - max(south, ext.minY)) / py).toInt() + 1
            val ww = c1 - c0 + 1; val wh = r1 - r0 + 1
            val win = reader.readFloats(level, c0, r0, ww, wh)
            for (r in 0 until rows) {
                val lat = north - r * step
                if (lat < ext.minY || lat > ext.maxY) continue
                for (c in 0 until cols) {
                    val lon = west + c * step
                    if (lon < ext.minX || lon > ext.maxX) continue
                    // Bilinear between pixel centres.
                    val fx = (lon - ext.minX) / px - 0.5 - c0; val fy = (ext.maxY - lat) / py - 0.5 - r0
                    val x = floor(fx).toInt().coerceIn(0, ww - 2); val y = floor(fy).toInt().coerceIn(0, wh - 2)
                    val tx = (fx - x).coerceIn(0.0, 1.0); val ty = (fy - y).coerceIn(0.0, 1.0)
                    val a = win[y * ww + x]; val b = win[y * ww + x + 1]; val cc = win[(y + 1) * ww + x]; val d = win[(y + 1) * ww + x + 1]
                    val v = (a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty
                    if (!v.isNaN()) values[r * cols + c] = v.toFloat()
                }
            }
        }
        return GeoGrid(north, west, step, step, rows, cols, values)
    }
}

/** A TIN surface from an elevation grid, keeping only the samples needed within a vertical tolerance. */
object GridSurface {
    /**
     * Splits the grid in quarters until each piece is flat within [tolerance] metres (the samples
     * inside it stay within that of the plane through its corners), and triangulates the corners
     * kept. [toPlan] places a latitude/longitude in the project. At most [maxPoints] points: the
     * tolerance is raised until it fits.
     */
    fun build(name: String, g: GeoGrid, tolerance: Double, toPlan: (Double, Double) -> Point2, maxPoints: Int = 400_000): Surface? {
        var tol = max(tolerance, 0.01)
        var keep: BooleanArray
        while (true) {
            keep = BooleanArray(g.rows * g.cols)
            split(g, 0, 0, g.rows - 1, g.cols - 1, tol, keep)
            if (keep.count { it } <= maxPoints || tol > 1000) break
            tol *= 1.6
        }
        val idx = keep.indices.filter { keep[it] && !g.values[it].isNaN() }
        if (idx.size < 3) return null
        val xs = DoubleArray(idx.size); val ys = DoubleArray(idx.size); val zs = DoubleArray(idx.size)
        idx.forEachIndexed { i, k ->
            val p = toPlan(g.lat(k / g.cols), g.lon(k % g.cols))
            xs[i] = p.x; ys[i] = p.y; zs[i] = g.values[k].toDouble()
        }
        return AeccPointCache.triangulate(name, xs, ys, zs)
    }

    private fun split(g: GeoGrid, r0: Int, c0: Int, r1: Int, c1: Int, tol: Double, keep: BooleanArray) {
        for ((r, c) in listOf(r0 to c0, r0 to c1, r1 to c0, r1 to c1)) keep[r * g.cols + c] = true
        if (r1 - r0 <= 1 && c1 - c0 <= 1) return
        val z00 = g[r0, c0]; val z01 = g[r0, c1]; val z10 = g[r1, c0]; val z11 = g[r1, c1]
        var flat = !(z00.isNaN() || z01.isNaN() || z10.isNaN() || z11.isNaN())
        if (flat) {
            loop@ for (r in r0..r1) for (c in c0..c1) {
                val z = g[r, c]
                val ty = (r - r0).toDouble() / (r1 - r0).coerceAtLeast(1); val tx = (c - c0).toDouble() / (c1 - c0).coerceAtLeast(1)
                val plane = (z00 * (1 - tx) + z01 * tx) * (1 - ty) + (z10 * (1 - tx) + z11 * tx) * ty
                if (z.isNaN() || abs(z - plane) > tol) { flat = false; break@loop }
            }
        }
        if (flat) return
        val rm = (r0 + r1) / 2; val cm = (c0 + c1) / 2
        if (r1 - r0 >= 2 && c1 - c0 >= 2) {
            split(g, r0, c0, rm, cm, tol, keep); split(g, r0, cm, rm, c1, tol, keep)
            split(g, rm, c0, r1, cm, tol, keep); split(g, rm, cm, r1, c1, tol, keep)
        } else if (r1 - r0 >= 2) {
            split(g, r0, c0, rm, c1, tol, keep); split(g, rm, c0, r1, c1, tol, keep)
        } else {
            split(g, r0, c0, r1, cm, tol, keep); split(g, r0, cm, r1, c1, tol, keep)
        }
    }
}

/**
 * OpenStreetMap data from the Overpass API, as GeoJSON in longitude/latitude with one layer per
 * kind of feature (main roads, other roads, paths, buildings, water, railways, boundaries).
 */
object Overpass {
    const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    enum class Kind(val ql: List<String>) {
        ROADS(listOf("way[\"highway\"]")),
        BUILDINGS(listOf("way[\"building\"]")),
        WATER(listOf("way[\"waterway\"]", "way[\"natural\"=\"water\"]")),
        RAILWAYS(listOf("way[\"railway\"~\"rail|light_rail|subway|tram|narrow_gauge\"]")),
        BOUNDARIES(listOf("way[\"boundary\"=\"administrative\"]")),
    }

    /** Overpass QL for [kinds] in the box, returning each way with its geometry. */
    fun query(south: Double, west: Double, north: Double, east: Double, kinds: Set<Kind>): String {
        fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.6f", v)
        val box = "(${n(south)},${n(west)},${n(north)},${n(east)})"
        return "[out:json][timeout:90];(" + kinds.flatMap { it.ql }.joinToString("") { "$it$box;" } + ");out geom;"
    }

    /** Pieces of at most [maxDeg] degrees a side, so large areas stay within the server's limits. */
    fun blocks(south: Double, west: Double, north: Double, east: Double, maxDeg: Double = 0.05): List<DoubleArray> {
        val ny = ceil((north - south) / maxDeg).toInt().coerceAtLeast(1); val nx = ceil((east - west) / maxDeg).toInt().coerceAtLeast(1)
        val dy = (north - south) / ny; val dx = (east - west) / nx
        return (0 until ny).flatMap { j -> (0 until nx).map { i -> doubleArrayOf(south + j * dy, west + i * dx, south + (j + 1) * dy, west + (i + 1) * dx) } }
    }

    /** Layer and colour (ARGB) for a way's tags; null to leave it out. */
    fun classify(tags: Map<String, String>): Pair<String, Int>? {
        val h = tags["highway"]
        return when {
            h in setOf("motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link", "primary_link", "secondary_link") -> "OSM_RUAS_PRINCIPAIS" to 0xFFE8743B.toInt()
            h in setOf("tertiary", "residential", "unclassified", "service", "living_street", "tertiary_link", "road") -> "OSM_RUAS" to 0xFFF2C14E.toInt()
            h != null -> "OSM_CAMINHOS" to 0xFFB0A08A.toInt()
            tags.containsKey("railway") -> "OSM_FERROVIAS" to 0xFF7E57C2.toInt()
            tags.containsKey("waterway") || tags["natural"] == "water" -> "OSM_HIDROGRAFIA" to 0xFF1E88E5.toInt()
            tags.containsKey("building") -> "OSM_CONSTRUCOES" to 0xFF9E9E9E.toInt()
            tags["boundary"] == "administrative" -> "OSM_LIMITES" to 0xFFD81B60.toInt()
            else -> null
        }
    }

    /**
     * GeoJSON of the ways in Overpass JSON answers ([answers]), each way once even when it comes in
     * several blocks; properties carry the layer, colour, name and the main OSM tags.
     */
    fun toGeoJson(answers: List<String>): Pair<String, Int> {
        val seen = HashSet<Long>()
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var count = 0
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        for (text in answers) {
            val root = Json.parse(text) as? Map<*, *> ?: continue
            for (e in root["elements"] as? List<*> ?: emptyList<Any?>()) {
                val m = e as? Map<*, *> ?: continue
                if (m["type"] != "way") continue
                val id = (m["id"] as? Number)?.toLong() ?: continue
                if (!seen.add(id)) continue
                val tags = (m["tags"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
                val (layer, color) = classify(tags) ?: continue
                val geom = (m["geometry"] as? List<*>)?.mapNotNull { p ->
                    val q = p as? Map<*, *> ?: return@mapNotNull null
                    val lat = (q["lat"] as? Number)?.toDouble(); val lon = (q["lon"] as? Number)?.toDouble()
                    if (lat == null || lon == null) null else lon to lat
                } ?: continue
                if (geom.size < 2) continue
                if (count > 0) sb.append(',')
                count++
                sb.append("{\"type\":\"Feature\",\"properties\":{\"layer\":\"").append(layer).append("\",\"color\":\"#")
                    .append("%06X".format(color and 0xFFFFFF)).append("\",\"osm_id\":\"").append(id).append('"')
                for (k in listOf("name", "highway", "building", "waterway", "natural", "railway", "boundary", "admin_level")) tags[k]?.let {
                    sb.append(",\"").append(k).append("\":\"").append(esc(it)).append('"')
                }
                sb.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
                geom.forEachIndexed { i, (lon, lat) -> if (i > 0) sb.append(','); sb.append('[').append(lon).append(',').append(lat).append(']') }
                sb.append("]}}")
            }
        }
        sb.append("]}")
        return sb.toString() to count
    }
}

/** Writes an elevation grid as a GeoTIFF of 32-bit floats in latitude/longitude (EPSG:4326). */
object GeoTiffWriter {
    fun writeFloat(g: GeoGrid, out: java.io.OutputStream) {
        val entries = 13
        val ifd = 8
        val ifdSize = 2 + entries * 12 + 4
        var p = ifd + ifdSize
        val bitsPos = p; p += 0
        val scalePos = p; p += 24
        val tiePos = p; p += 48
        val keys = shortArrayOf(1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326)
        val keysPos = p; p += keys.size * 2
        val dataPos = p
        val b = java.nio.ByteBuffer.allocate(dataPos + g.values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifd)
        b.putShort(entries.toShort())
        fun e(tag: Int, type: Int, count: Int, value: Int) { b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        e(256, 4, 1, g.cols); e(257, 4, 1, g.rows); e(258, 3, 1, 32); e(259, 3, 1, 1); e(262, 3, 1, 1)
        e(273, 4, 1, dataPos); e(277, 3, 1, 1); e(278, 4, 1, g.rows); e(279, 4, 1, g.values.size * 4); e(339, 3, 1, 3)
        e(33550, 12, 3, scalePos); e(33922, 12, 6, tiePos); e(34735, 3, keys.size, keysPos)
        b.putInt(0)
        check(bitsPos == b.position())
        b.putDouble(g.dLon).putDouble(g.dLat).putDouble(0.0)
        // Samples are pixel centres: the picture's corner is half a pixel beyond the first one.
        b.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(g.west - g.dLon / 2).putDouble(g.north + g.dLat / 2).putDouble(0.0)
        keys.forEach { b.putShort(it) }
        g.values.forEach { b.putFloat(it) }
        out.write(b.array())
    }
}
