package com.appdwg.core

import java.io.StringWriter
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class XrefsTest {

    private fun read(text: String) = DxfReader(text.reader().buffered()).read()

    private val host = "0\nSECTION\n2\nBLOCKS\n" +
        "0\nBLOCK\n8\n0\n2\nTOPO\n70\n4\n10\n0\n20\n0\n3\nTOPO\n1\n..\\BASE\\topo.dwg\n0\nENDBLK\n" +
        "0\nENDSEC\n0\nSECTION\n2\nENTITIES\n" +
        "0\nINSERT\n8\nREF\n2\nTOPO\n10\n100\n20\n200\n30\n5\n50\n90\n" +
        "0\nENDSEC\n0\nSECTION\n2\nOBJECTS\n0\nIMAGEDEF\n5\n1C\n1\nC:\\IMG\\orto.tif\n0\nENDSEC\n0\nEOF\n"

    @Test
    fun readsXrefsAndTheirPlacements() {
        val x = read(host).xrefs.single()
        assertEquals("TOPO", x.name)
        assertEquals("topo.dwg", x.fileName)
        val p = x.placements.single()
        assertEquals("REF", p.layer)
        val w = p.apply(1.0, 0.0)
        assertEquals(100.0, w.x, 1e-9)
        assertEquals(201.0, w.y, 1e-9)
        assertEquals(5.0, p.tz, 1e-9)
    }

    @Test
    fun attachesDrawingAtPlacement() {
        val project = read(host)
        val other = read(
            "0\nSECTION\n2\nENTITIES\n0\nLINE\n8\nEIXO\n10\n0\n20\n0\n30\n1\n11\n10\n21\n0\n31\n1\n" +
                "0\nTEXT\n8\nTXT\n10\n0\n20\n0\n40\n2\n1\nA\n0\nENDSEC\n0\nEOF\n",
        )
        val merged = Xrefs.attach(project, project.xrefs.single(), other)
        val line = merged.polylines.single()
        assertEquals("TOPO|EIXO", line.layer)
        assertEquals(100.0, line.xs[1], 1e-9)
        assertEquals(210.0, line.ys[1], 1e-9)
        assertEquals(6.0, line.zs!![1], 1e-9)
        val t = merged.texts.single()
        assertEquals(PI / 2, t.rotation, 1e-9)
        assertEquals(listOf("TOPO|EIXO", "TOPO|TXT"), merged.layers)
    }

    @Test
    fun rewritesSavedPaths() {
        val out = StringWriter()
        Xrefs.rewritePaths(host.reader().buffered(), out, mapOf("..\\BASE\\topo.dwg" to "topo.dxf", "C:\\IMG\\orto.tif" to "IMG\\orto.tif"))
        val p = read(out.toString())
        assertEquals("topo.dxf", p.xrefs.single().path)
        assertEquals(host.count { it == '\n' }, out.toString().count { it == '\n' })
        assert(out.toString().contains("IMG\\orto.tif\r\n") && !out.toString().contains("C:\\IMG"))
    }
}
