package com.appdwg.core

import java.util.Locale

/** Points read from a text file, with the lines that were skipped (1-based) and why. */
class PointFileResult(val points: List<CogoPoint>, val skipped: List<Pair<Int, String>>)

/**
 * Civil 3D point files in PDENZ order: point number, description, easting, northing, elevation.
 * Fields may be separated by comma, semicolon, tab or spaces. Decimals use a point; a decimal comma
 * is accepted only when the separator is not a comma.
 */
object PointFile {
    fun read(text: String): PointFileResult {
        val points = ArrayList<CogoPoint>()
        val skipped = ArrayList<Pair<Int, String>>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) return@forEachIndexed
            val p = parseLine(line)
            if (p == null) skipped += (i + 1) to line else points += p
        }
        return PointFileResult(points, skipped)
    }

    /** One PDENZ line, or null when it is not one (a header, for instance). */
    fun parseLine(line: String): CogoPoint? {
        val sep = when {
            ';' in line -> ";"
            '\t' in line -> "\t"
            line.count { it == ',' } >= 4 -> ","
            else -> null
        }
        val fields = if (sep == null) line.split(Regex("\\s+")) else line.split(sep).map { it.trim() }
        if (fields.size < 5) return null
        fun num(s: String): Double? {
            val t = s.trim()
            return (if (sep == ",") t else t.replace(',', '.')).toDoubleOrNull()
        }
        // With spaces, the description may itself hold spaces: number first, coordinates last.
        val number = fields[0]
        val e = num(fields[fields.size - 3]) ?: return null
        val n = num(fields[fields.size - 2]) ?: return null
        // An empty last field is a point without elevation, as this app writes it.
        val zText = fields[fields.size - 1]
        val z = if (zText.isEmpty() && sep != null) Double.NaN else num(zText) ?: return null
        val description = fields.subList(1, fields.size - 3).joinToString(if (sep == null) " " else sep).trim()
        if (number.isEmpty()) return null
        return CogoPoint(number, description, e, n, z)
    }

    /** PDENZ text, comma separated with decimal points and 3 decimals; unknown elevations are left empty. */
    fun write(points: List<CogoPoint>): String = buildString {
        for (p in points) {
            append(p.number).append(',').append(p.description.replace(',', ' ')).append(',')
            append(f3(p.x)).append(',').append(f3(p.y)).append(',')
            if (!p.z.isNaN()) append(f3(p.z))
            append("\r\n")
        }
    }

    /** Next free point number: one more than the largest whole number in use. */
    fun nextNumber(points: List<CogoPoint>): String =
        ((points.mapNotNull { it.number.toLongOrNull() }.maxOrNull() ?: 0L) + 1).toString()

    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
