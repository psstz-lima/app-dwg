package com.appdwg.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DxfReaderTest {

    private fun dxf(vararg entities: String) = buildString {
        append("0\nSECTION\n2\nHEADER\n9\n\$INSUNITS\n70\n6\n0\nENDSEC\n")
        append("0\nSECTION\n2\nENTITIES\n")
        entities.forEach { append(it.trimIndent()).append('\n') }
        append("0\nENDSEC\n0\nEOF\n")
    }

    private fun read(text: String) = DxfReader(text.reader().buffered()).read()

    private fun face(layer: String, vararg c: Double) = buildString {
        append("0\n3DFACE\n8\n$layer\n")
        for (k in 0..3) {
            val i = minOf(k, c.size / 3 - 1)
            append("${10 + k}\n${c[3 * i]}\n${20 + k}\n${c[3 * i + 1]}\n${30 + k}\n${c[3 * i + 2]}\n")
        }
    }

    @Test
    fun detectsDxf() {
        assertTrue(DxfReader.looksLikeDxf("  0\r\nSECTION\r\n  2\r\nHEADER"))
        assertTrue(!DxfReader.looksLikeDxf("<?xml version=\"1.0\"?>"))
    }

    @Test
    fun facesBecomeSurfaceWithElevations() {
        val p = read(dxf(
            face("TERRENO", 0.0, 0.0, 100.0, 10.0, 0.0, 110.0, 10.0, 10.0, 120.0),
            face("TERRENO", 0.0, 0.0, 100.0, 10.0, 10.0, 120.0, 0.0, 10.0, 110.0),
        ))
        assertEquals("meter", p.linearUnit)
        val s = p.surfaces.single()
        assertEquals("TERRENO", s.name)
        assertEquals(2, s.triangleCount)
        assertEquals(4, s.vertexCount)
        assertEquals(110.0, s.elevationAt(Point2(5.0, 5.0))!!, 1e-9)
        assertNull(s.elevationAt(Point2(20.0, 5.0)))
    }

    @Test
    fun flatFacesAreDrawnAsLines() {
        val p = read(dxf(face("MOLDURA", 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 10.0, 0.0)))
        assertTrue(p.surfaces.isEmpty())
        assertEquals(1, p.polylines.size)
        assertTrue(p.polylines.single().closed)
    }

    @Test
    fun readsLinesAndPolylines() {
        val p = read(dxf(
            "0\nLINE\n8\nEIXO\n10\n1.0\n20\n2.0\n30\n0.0\n11\n3.0\n21\n4.0\n31\n0.0",
            "0\nLWPOLYLINE\n8\nBORDO\n90\n3\n70\n1\n10\n0.0\n20\n0.0\n10\n5.0\n20\n0.0\n10\n5.0\n20\n5.0",
            "0\nPOLYLINE\n8\nFEAT\n66\n1\n70\n8\n0\nVERTEX\n8\nFEAT\n10\n1.0\n20\n1.0\n30\n10.0\n70\n32\n" +
                "0\nVERTEX\n8\nFEAT\n10\n2.0\n20\n3.0\n30\n11.0\n70\n32\n0\nSEQEND\n8\nFEAT",
            "0\nPOINT\n8\nPT\n10\n7.0\n20\n8.0\n30\n9.0",
        ))
        assertEquals(listOf("EIXO", "BORDO", "FEAT"), p.polylines.map { it.layer })
        assertEquals(listOf(3.0, 4.0), listOf(p.polylines[0].xs[1], p.polylines[0].ys[1]))
        assertTrue(p.polylines[1].closed)
        assertEquals(listOf(1.0, 2.0), p.polylines[2].xs.toList())
        assertEquals(Point3(7.0, 8.0, 9.0), p.cgPoints.single().position)
        val b = assertNotNull(p.bounds)
        assertEquals(Bounds(0.0, 0.0, 7.0, 8.0), b)
    }

    @Test
    fun bulgeMakesArc() {
        // Bulge 1 is a half circle; going from (0,0) to (10,0) counter-clockwise passes below the chord.
        val pts = DxfReader.withBulges(listOf(Point2(0.0, 0.0), Point2(10.0, 0.0)), listOf(1.0, 0.0), false)
        assertTrue(pts.size > 10)
        pts.forEach { assertEquals(5.0, hypot(it.x - 5.0, it.y), 1e-9) }
        assertTrue(pts.all { it.y <= 1e-9 })
    }

    @Test
    fun mirroredExtrusionFlipsX() {
        // Extrusion (0,0,-1): the object X axis points west.
        val p = read(dxf("0\nCIRCLE\n8\nC\n10\n100.0\n20\n50.0\n30\n0.0\n40\n2.0\n210\n0.0\n220\n0.0\n230\n-1.0"))
        val b = p.polylines.single().bounds!!
        assertEquals(-102.0, b.minX, 1e-9)
        assertEquals(-98.0, b.maxX, 1e-9)
        assertEquals(48.0, b.minY, 1e-9)
    }

    @Test
    fun usesLayerColorsAndHidesFrozenLayers() {
        val tables = "0\nSECTION\n2\nTABLES\n0\nTABLE\n2\nLAYER\n" +
            "0\nLAYER\n2\nEIXO\n70\n0\n62\n1\n" +
            "0\nLAYER\n2\nCONGELADA\n70\n1\n62\n7\n" +
            "0\nLAYER\n2\nDESLIGADA\n70\n0\n62\n-3\n" +
            "0\nENDTAB\n0\nENDSEC\n"
        val text = dxf(
            "0\nLINE\n8\nEIXO\n10\n0\n20\n0\n11\n1\n21\n1",
            "0\nLINE\n8\nEIXO\n62\n3\n10\n0\n20\n0\n11\n1\n21\n1",
            "0\nLINE\n8\nCONGELADA\n10\n0\n20\n0\n11\n1\n21\n1",
            "0\nLINE\n8\nDESLIGADA\n10\n0\n20\n0\n11\n1\n21\n1",
        ).replace("0\nSECTION\n2\nENTITIES", tables + "0\nSECTION\n2\nENTITIES")
        val p = read(text)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), p.polylines.map { it.color })
        assertEquals(listOf("Não exibidos: 2 objetos em camadas desligadas ou congeladas"), p.warnings)
    }

    @Test
    fun aciColors() {
        assertNull(DxfReader.aciColor(7))
        assertEquals(0xFFFF8080.toInt(), DxfReader.aciColor(11))
        assertEquals(0xFFA60000.toInt(), DxfReader.aciColor(12))
        assertEquals(0xFF828282.toInt(), DxfReader.aciColor(253))
    }

    @Test
    fun listsSkippedEntities() {
        val p = read(dxf("0\nINSERT\n8\n0\n2\nBLOCO", "0\nINSERT\n8\n0\n2\nBLOCO", "0\nMTEXT\n8\n0\n1\nTexto", "0\nVIEWPORT\n8\n0"))
        assertEquals(listOf("Não exibidos: 2 blocos (INSERT)", "Não exibidos: 1 textos (MTEXT)"), p.warnings)
    }
}
