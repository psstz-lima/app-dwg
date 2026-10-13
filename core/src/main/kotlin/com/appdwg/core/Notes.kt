package com.appdwg.core

import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Field note ("anotação") at a plan position, with optional photo file name and elevation. */
data class FieldNote(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val text: String,
    /** File name of the photo in the app's notes folder, or null. */
    val photo: String?,
    /** ISO date and time it was taken. */
    val time: String,
)

/** Layer the notes are drawn on and exported to. */
const val NOTES_LAYER = "_ANOTACOES"

object NotesExport {
    /** CSV: id; E; N; cota; data; texto; foto (semicolons, decimal points). */
    fun csv(notes: List<FieldNote>): String = buildString {
        append("id;E;N;cota;data;texto;foto\r\n")
        for (n in notes) append(listOf(n.id, f3(n.x), f3(n.y), if (n.z.isNaN()) "" else f3(n.z), n.time, n.text.replace(';', ',').replace("\n", " "), n.photo ?: "").joinToString(";")).append("\r\n")
    }

    /**
     * KMZ: a KML with a placemark per note (photo shown in the balloon) and the photos in the zip.
     * [photo] gives the bytes of a photo by file name.
     */
    fun kmz(notes: List<FieldNote>, zone: Int, south: Boolean, name: String, out: OutputStream, photo: (String) -> ByteArray?) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val kml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>${esc(name)}</name>\n")
            for (n in notes) {
                val (lat, lon) = Utm.toGeographic(n.x, n.y, zone, south)
                append("<Placemark><name>${esc(n.text.lineSequence().firstOrNull()?.take(40) ?: n.id)}</name><description><![CDATA[")
                append(n.text.replace("]]>", "] ]>")).append("<br/>").append(n.time)
                n.photo?.let { append("<br/><img src=\"files/$it\" width=\"400\"/>") }
                append("]]></description><Point><coordinates>")
                append(String.format(Locale.ROOT, "%.8f,%.8f", lon, lat))
                append("</coordinates></Point></Placemark>\n")
            }
            append("</Document>\n</kml>\n")
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml")); zip.write(kml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            for (n in notes) n.photo?.let { f -> photo(f)?.let { bytes -> zip.putNextEntry(ZipEntry("files/$f")); zip.write(bytes); zip.closeEntry() } }
        }
    }

    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
