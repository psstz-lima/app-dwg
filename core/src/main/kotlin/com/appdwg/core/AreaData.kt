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
            // Rows are independent: they are interpolated on all cores.
            Parallel.forChunks(rows, 16) { rFrom, rUntil -> for (r in rFrom until rUntil) {
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
            }; true }
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

/**
 * Sentinel-2 L2A true-colour pictures (the 10 m "visual" TCI band combination) from the public
 * Cloud Optimized GeoTIFFs on AWS, found through the Earth Search STAC API.
 */
object Sentinel2 {
    const val SEARCH = "https://earth-search.aws.element84.com/v1/search"
    fun attribution(year: Int) = "Contains modified Copernicus Sentinel data $year"

    /**
     * One picture: its date, cloud cover (% of the whole scene), UTM EPSG, file, pixel grid and
     * footprint (lon, lat); [scl] is its scene classification file and [areaCloud] the share of
     * cloud over the area once measured (0..1).
     */
    class Scene(val id: String, val date: String, val cloud: Double, val epsg: String, val href: String,
                val originX: Double, val originY: Double, val pixel: Double, val width: Int, val height: Int,
                val footprint: List<Pair<Double, Double>>, val scl: String? = null, val areaCloud: Double? = null) {
        val year get() = date.take(4).toIntOrNull() ?: 0
        /** The Sentinel-2 tile (as "23KPU"): neighbouring tiles of the same day share a date. */
        val tile get() = id.split('_').getOrNull(1) ?: ""
        fun withAreaCloud(f: Double?) = Scene(id, date, cloud, epsg, href, originX, originY, pixel, width, height, footprint, scl, f)
    }

    /**
     * Share (0..1) of cloud, cirrus and cloud shadow among the valid pixels of a piece of the
     * scene classification (SCL classes 3, 8, 9, 10; 0 is no data). Null when nothing is valid.
     */
    fun cloudShare(classes: IntArray): Double? {
        var valid = 0; var cloudy = 0
        for (c in classes) {
            if (c == 0) continue
            valid++
            if (c == 3 || c == 8 || c == 9 || c == 10) cloudy++
        }
        return if (valid == 0) null else cloudy.toDouble() / valid
    }

    /** STAC search for pictures over the box between [from] and [to] (ISO dates), clearest first by date. */
    fun searchBody(south: Double, west: Double, north: Double, east: Double, from: String, to: String, maxCloud: Double, limit: Int = 30): String {
        fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.6f", v)
        return "{\"collections\":[\"sentinel-2-l2a\"],\"bbox\":[${n(west)},${n(south)},${n(east)},${n(north)}]," +
            "\"datetime\":\"${from}T00:00:00Z/${to}T23:59:59Z\",\"query\":{\"eo:cloud_cover\":{\"lt\":$maxCloud}}," +
            "\"sortby\":[{\"field\":\"properties.datetime\",\"direction\":\"desc\"}],\"limit\":$limit}"
    }

    /** Pictures in a STAC answer that cover the whole box, newest first. */
    fun scenes(json: String, south: Double, west: Double, north: Double, east: Double): List<Scene> {
        val root = Json.parse(json) as? Map<*, *> ?: return emptyList()
        return (root["features"] as? List<*>).orEmpty().mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val p = m["properties"] as? Map<*, *> ?: return@mapNotNull null
            val visual = (m["assets"] as? Map<*, *>)?.get("visual") as? Map<*, *> ?: return@mapNotNull null
            val t = (visual["proj:transform"] as? List<*>)?.map { (it as Number).toDouble() } ?: return@mapNotNull null
            val shape = (visual["proj:shape"] as? List<*>)?.map { (it as Number).toInt() } ?: return@mapNotNull null
            val epsg = (p["proj:epsg"] as? Number)?.toInt()?.toString() ?: (p["proj:code"] as? String)?.substringAfter(':') ?: return@mapNotNull null
            val ring = ((m["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*>)?.firstOrNull() as? List<*> ?: return@mapNotNull null
            val footprint = ring.mapNotNull { c -> (c as? List<*>)?.let { (it[0] as Number).toDouble() to (it[1] as Number).toDouble() } }
            val s = Scene(m["id"].toString(), p["datetime"].toString(), (p["eo:cloud_cover"] as? Number)?.toDouble() ?: 100.0, epsg,
                visual["href"].toString(), t[2], t[5], t[0], shape[1], shape[0], footprint,
                ((m["assets"] as? Map<*, *>)?.get("scl") as? Map<*, *>)?.get("href")?.toString())
            s.takeIf { listOf(west to south, west to north, east to south, east to north).all { (x, y) -> inside(x, y, footprint) } }
        }
    }

    private fun inside(x: Double, y: Double, ring: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]; val (xj, yj) = ring[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /**
     * Stretches each colour channel between its 2nd and 98th percentiles, as most viewers do, so
     * the true-colour picture is neither dark nor washed out. Transparent pixels are left alone.
     */
    fun autoContrast(pixels: IntArray) {
        val lo = IntArray(3); val hi = IntArray(3)
        for (ch in 0..2) {
            val hist = IntArray(256); var n = 0
            for (p in pixels) if (p ushr 24 != 0) { hist[(p shr (16 - 8 * ch)) and 0xFF]++; n++ }
            if (n == 0) return
            var acc = 0; lo[ch] = 0; hi[ch] = 255
            for (v in 0..255) { acc += hist[v]; if (acc >= n * 0.02) { lo[ch] = v; break } }
            acc = 0
            for (v in 255 downTo 0) { acc += hist[v]; if (acc >= n * 0.02) { hi[ch] = v; break } }
            if (hi[ch] <= lo[ch]) { lo[ch] = 0; hi[ch] = 255 }
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            if (p ushr 24 == 0) continue
            var out = p and 0xFF000000.toInt()
            for (ch in 0..2) {
                val v = (p shr (16 - 8 * ch)) and 0xFF
                val s = ((v - lo[ch]) * 255 / (hi[ch] - lo[ch])).coerceIn(0, 255)
                out = out or (s shl (16 - 8 * ch))
            }
            pixels[i] = out
        }
    }
}
