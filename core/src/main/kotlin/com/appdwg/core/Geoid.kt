package com.appdwg.core

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Geoid undulation N on a regular latitude/longitude grid, as IBGE publishes MAPGEO2015 and
 * hgeoHNOR2020: orthometric height H = ellipsoidal height h − N. Read from a text grid
 * ("lat lon N" per line, any order) or from a GTX file (the binary grid PROJ uses).
 */
class GeoidGrid(
    val name: String,
    private val lat0: Double,
    private val lon0: Double,
    private val dLat: Double,
    private val dLon: Double,
    private val rows: Int,
    private val cols: Int,
    /** Row-major from [lat0] northwards; NaN where the model has no value. */
    private val values: FloatArray,
) {
    val south get() = lat0
    val north get() = lat0 + (rows - 1) * dLat
    val west get() = lon0
    val east get() = lon0 + (cols - 1) * dLon

    /** Bilinear N at the point, or null outside the grid or next to a cell without value. */
    fun undulation(latDeg: Double, lonDeg: Double): Double? {
        // GTX longitudes may run 0..360.
        var lon = lonDeg
        if (lon < west && lon + 360 <= east) lon += 360
        val fr = (latDeg - lat0) / dLat
        val fc = (lon - lon0) / dLon
        if (fr < -1e-9 || fc < -1e-9 || fr > rows - 1 + 1e-9 || fc > cols - 1 + 1e-9) return null
        val r = floor(fr).toInt().coerceIn(0, rows - 2)
        val c = floor(fc).toInt().coerceIn(0, cols - 2)
        val tr = fr - r
        val tc = fc - c
        fun v(i: Int, j: Int) = values[i * cols + j].toDouble()
        val a = v(r, c); val b = v(r, c + 1); val d = v(r + 1, c); val e = v(r + 1, c + 1)
        if (a.isNaN() || b.isNaN() || d.isNaN() || e.isNaN()) return null
        return (a * (1 - tc) + b * tc) * (1 - tr) + (d * (1 - tc) + e * tc) * tr
    }

    companion object {
        /**
         * Text grid: one "latitude longitude N" per line (decimal degrees; separators space, tab,
         * comma or semicolon; decimal comma accepted with ; or tab). Lines that are not three numbers
         * are skipped, so a header is fine. The points must form a regular grid.
         */
        fun readText(name: String, text: String): GeoidGrid {
            val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val ns = ArrayList<Double>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val f = if (';' in line || '\t' in line) line.split(';', '\t').map { it.trim().replace(',', '.') }
                else line.split(Regex("[\\s,]+"))
                if (f.size < 3) continue
                val a = f[0].toDoubleOrNull() ?: continue
                val b = f[1].toDoubleOrNull() ?: continue
                val n = f[2].toDoubleOrNull() ?: continue
                lats += a; lons += b; ns += n
            }
            require(lats.size >= 4) { tr("a grade precisa de pelo menos 4 pontos (latitude, longitude, N)") }
            fun step(v: List<Double>): Double {
                val s = v.map { (it * 1e6).roundToInt() }.distinct().sorted()
                require(s.size >= 2) { tr("a grade precisa de pelo menos 2 latitudes e 2 longitudes") }
                return (1 until s.size).minOf { s[it] - s[it - 1] } / 1e6
            }
            val dLat = step(lats); val dLon = step(lons)
            val la0 = lats.min(); val lo0 = lons.min()
            val rows = ((lats.max() - la0) / dLat).roundToInt() + 1
            val cols = ((lons.max() - lo0) / dLon).roundToInt() + 1
            require(rows.toLong() * cols <= 50_000_000) { tr("grade grande demais") }
            val values = FloatArray(rows * cols) { Float.NaN }
            for (i in lats.indices) {
                val r = ((lats[i] - la0) / dLat).roundToInt(); val c = ((lons[i] - lo0) / dLon).roundToInt()
                require(abs(la0 + r * dLat - lats[i]) < dLat / 10 && abs(lo0 + c * dLon - lons[i]) < dLon / 10) { tr("os pontos não formam uma grade regular") }
                values[r * cols + c] = ns[i].toFloat()
            }
            return GeoidGrid(name, la0, lo0, dLat, dLon, rows, cols, values)
        }

        /** NOAA/PROJ GTX: big-endian header (lat0, lon0, dlat, dlon, rows, cols) then rows of floats from the south. */
        fun readGtx(name: String, input: InputStream): GeoidGrid {
            val d = DataInputStream(input.buffered(1 shl 16))
            val la0 = d.readDouble(); val lo0 = d.readDouble(); val dLat = d.readDouble(); val dLon = d.readDouble()
            val rows = d.readInt(); val cols = d.readInt()
            require(rows in 2..100_000 && cols in 2..100_000 && rows.toLong() * cols <= 50_000_000 && dLat > 0 && dLon > 0) { tr("cabeçalho GTX inválido") }
            val values = FloatArray(rows * cols) { val v = d.readFloat(); if (v == -88.8888f || v.isNaN()) Float.NaN else v }
            return GeoidGrid(name, la0, lo0, dLat, dLon, rows, cols, values)
        }
    }
}
