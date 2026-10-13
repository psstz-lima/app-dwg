package com.appdwg.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotesTest {
    private val notes = listOf(
        FieldNote("1", 764000.0, 7852150.0, 265.5, "Erosão; talude", "n1.jpg", "2026-10-13T10:00"),
        FieldNote("2", 764010.0, 7852160.0, Double.NaN, "Marco", null, "2026-10-13T10:05"),
    )

    @Test
    fun csvListsNotes() {
        val c = NotesExport.csv(notes).lines()
        assertEquals("id;E;N;cota;data;texto;foto", c[0])
        assertEquals("1;764000.000;7852150.000;265.500;2026-10-13T10:00;Erosão, talude;n1.jpg", c[1])
        assertEquals("2;764010.000;7852160.000;;2026-10-13T10:05;Marco;", c[2])
    }

    @Test
    fun kmzHoldsKmlAndPhotos() {
        val out = ByteArrayOutputStream()
        NotesExport.kmz(notes, CrsCatalog.byEpsg("31983")!!, "Teste", out) { if (it == "n1.jpg") byteArrayOf(1, 2, 3) else null }
        val names = ArrayList<String>(); var kml = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name; if (e.name == "doc.kml") kml = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(listOf("doc.kml", "files/n1.jpg"), names)
        assertTrue(kml.contains("<img src=\"files/n1.jpg\""))
        assertEquals(2, Regex("<Placemark>").findAll(kml).count())
    }
}
