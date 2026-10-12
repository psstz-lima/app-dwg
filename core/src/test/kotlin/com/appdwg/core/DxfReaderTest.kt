package com.appdwg.core

import kotlin.math.PI
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
        assertEquals(listOf(10.0, 11.0), p.polylines[2].zs?.toList())
        assertEquals(null, p.polylines[0].zs)
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
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), null, 0xFF00FF00.toInt()), p.polylines.map { it.color })
        assertEquals(setOf("DESLIGADA"), p.layersOff)
        assertEquals(setOf("CONGELADA"), p.layersFrozen)
        assertEquals(listOf(true, false, true, true), p.polylines.map { it.byLayer })
        assertEquals(0xFFFF0000.toInt(), p.layerColors["EIXO"])
        assertEquals(listOf("CONGELADA", "DESLIGADA", "EIXO"), p.layers)
    }

    @Test
    fun aciColors() {
        assertNull(DxfReader.aciColor(7))
        assertEquals(0xFFFF8080.toInt(), DxfReader.aciColor(11))
        assertEquals(0xFFA60000.toInt(), DxfReader.aciColor(12))
        assertEquals(0xFF828282.toInt(), DxfReader.aciColor(253))
    }

    @Test
    fun readsTexts() {
        val p = read(dxf(
            "0\nTEXT\n8\nTXT\n10\n1\n20\n2\n40\n2.5\n50\n90\n1\nEST 10%%d",
            "0\nMTEXT\n8\nTXT\n10\n5\n20\n6\n40\n1\n71\n5\n3\n{\\Fromans.shx|c0;PERFIL}\\P\n1\nESCALA \\H0.5x;1:1000",
        ))
        val (t, m) = p.texts
        assertEquals("EST 10°", t.text)
        assertEquals(PI / 2, t.rotation, 1e-12)
        assertEquals(2.5, t.height)
        assertEquals("PERFIL\nESCALA 1:1000", m.text)
        assertEquals(1, m.hAlign)
        assertEquals(PlanText.MIDDLE, m.vAlign)
    }

    @Test
    fun drawsBlocksMovedScaledAndRotated() {
        val blocks = "0\nSECTION\n2\nBLOCKS\n0\nBLOCK\n8\n0\n2\nSETA\n10\n1\n20\n0\n" +
            "0\nLINE\n8\n0\n62\n0\n10\n1\n20\n0\n11\n2\n21\n0\n" +
            "0\nTEXT\n8\nROTULO\n10\n1\n20\n0\n40\n1\n1\nA\n" +
            "0\nENDBLK\n0\nENDSEC\n"
        val text = dxf("0\nINSERT\n8\nSIMBOLOS\n62\n1\n2\nSETA\n10\n100\n20\n200\n41\n2\n42\n2\n50\n90")
            .replace("0\nSECTION\n2\nENTITIES", blocks + "0\nSECTION\n2\nENTITIES")
        val p = read(text)
        val line = p.polylines.single()
        // Layer 0 and "by block" color come from the insert.
        assertEquals("SIMBOLOS", line.layer)
        assertEquals(0xFFFF0000.toInt(), line.color)
        assertEquals(100.0, line.xs[0], 1e-9); assertEquals(200.0, line.ys[0], 1e-9)
        assertEquals(100.0, line.xs[1], 1e-9); assertEquals(202.0, line.ys[1], 1e-9)
        val label = p.texts.single()
        assertEquals("ROTULO", label.layer)
        assertEquals(2.0, label.height, 1e-9)
        assertEquals(PI / 2, label.rotation, 1e-9)
    }

    @Test
    fun readsHatchBoundaries() {
        val p = read(dxf(
            "0\nHATCH\n8\nMATERIAL\n2\nSOLID\n70\n1\n71\n0\n91\n2\n" +
                "92\n2\n72\n0\n73\n1\n93\n4\n10\n0\n20\n0\n10\n10\n20\n0\n10\n10\n20\n10\n10\n0\n20\n10\n97\n0\n" +
                "92\n1\n93\n2\n72\n1\n10\n2\n20\n2\n11\n4\n21\n2\n72\n2\n10\n3\n20\n2\n40\n1\n50\n0\n51\n180\n73\n1\n97\n0\n" +
                "75\n0\n76\n1\n98\n0",
        ))
        val h = p.hatches.single()
        assertEquals("MATERIAL", h.layer)
        assertEquals(2, h.loops.size)
        assertEquals(4, h.loops[0].xs.size)
        assertEquals(Bounds(2.0, 2.0, 4.0, 3.0), h.loops[1].bounds!!.let {
            Bounds(Math.round(it.minX * 1e9) / 1e9, Math.round(it.minY * 1e9) / 1e9, Math.round(it.maxX * 1e9) / 1e9, Math.round(it.maxY * 1e9) / 1e9)
        })
    }

    @Test
    fun listsSkippedEntities() {
        val p = read(dxf("0\nDIMENSION\n8\n0", "0\nDIMENSION\n8\n0", "0\nVIEWPORT\n8\n0", "0\nINSERT\n8\n0\n2\nSEM_BLOCO"))
        assertEquals(listOf("Não exibidos: 2 cotas (DIMENSION)"), p.warnings)
    }

    @Test
    fun skipsPaperSpace() {
        val p = read(dxf("0\nLINE\n8\n0\n67\n1\n10\n0\n20\n0\n11\n1\n21\n1"))
        assertTrue(p.polylines.isEmpty())
    }
}
