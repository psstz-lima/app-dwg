package com.appdwg.core.drainage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val catalog = CatalogLoader.bundled()

    @Test
    fun loadsTheVersionedAlbum() {
        assertEquals(DRAINAGE_SCHEMA, catalog.schema)
        assertEquals("DNIT IPR-736", catalog.origin)
        assertEquals("2026-10-16", catalog.generated)
        assertEquals(396, catalog.devices.size)
        assertEquals(178, catalog.devices.count { it.needsReview })
        assertEquals(9, catalog.chapters.size)
        assertTrue(catalog.sources.containsKey("E2"))
    }

    @Test
    fun vpcg16030ExcavationMatchesTheAlbum() {
        val device = catalog.device("VPCG 160-30")!!
        val section = device.section!!
        assertEquals("trapezoid_open", section.type)
        assertEquals(4, section.points.size)
        // (B + b) / 2 · H with B = 1.60 m, b = 1.00 m, H = 0.30 m.
        assertEquals(0.39, section.areaM2!!, 1e-9)
        assertEquals(0.39, section.excavationM3PerM!!, 1e-9)
        assertEquals(0.39, device.quantities.excavationPerMetre()!!, 1e-9)
        assertEquals("m3/m", device.quantities.items.first { it.name == "escavacao" }.unit)
        assertTrue(device.materials.any { it.name.contains("grama") })
        assertEquals(listOf("DES (dissipador)", "DCD/DAD/EDA (descida)", "CCS"), device.connections.first { it.role == "jusante" }.accepts)
        assertEquals("E2", device.page.source)
        assertEquals(6, device.page.pdfPage)
        assertEquals("1.1(a)", device.page.drawing)
        assertTrue(device.placeable)
        assertTrue(device.calculable)
    }

    @Test
    fun otherSectionsMatchTheAlbum() {
        assertExcavation("VPCG 120-30", 0.27)
        assertExcavation("STG 100-20", 0.10)
        assertExcavation("SZG 90-30", 0.18)
        assertExcavation("DPR 01", 0.20)
        val stc = catalog.device("STC 100-20")!!
        assertEquals(0.10, stc.section!!.areaM2!!, 1e-9)
        assertNull(stc.section!!.excavationM3PerM)
        assertEquals(0.1946, stc.quantities.excavationPerMetre()!!, 1e-9)
        val szc = catalog.device("SZC 90-30")!!
        assertEquals(0.18, szc.section!!.areaM2!!, 1e-9)
        assertEquals(0.3131, szc.quantities.excavationPerMetre()!!, 1e-9)
        // b is published on the geometry, not in params. The section still evaluates.
        val szcc = catalog.device("SZCC 100-25")!!
        assertNotNull(szcc.section!!.areaM2)
        assertTrue(szcc.parameters.any { it.key == "b_cm" && it.number == 50.0 })
    }

    @Test
    fun openCutsAgreeWithTheTabulatedExcavation() {
        var checked = 0
        for (device in catalog.devices) {
            val area = device.section?.excavationM3PerM ?: continue
            val table = device.quantities.excavationPerMetre() ?: continue
            assertEquals(table, area, 1e-3, device.code)
            checked++
        }
        assertTrue(checked >= 12, "checked $checked")
    }

    @Test
    fun expressionsUseParametersAndParentheses() {
        assertEquals(-50.0, ParamExpr.eval("-(a+b)/2", mapOf("a" to 20.0, "b" to 80.0))!!, 1e-9)
        assertEquals(0.39, ParamExpr.eval("(B+b)/2*H", mapOf("B" to 1.6, "b" to 1.0, "H" to 0.3))!!, 1e-9)
        assertNull(ParamExpr.eval("B/2", emptyMap()))
        assertNull(ParamExpr.eval("1/0", emptyMap()))
    }

    @Test
    fun reviewBlocksPlacementAndCalculationsUntilSigned() {
        val device = catalog.device("STC 88-20")!!
        assertTrue(device.needsReview)
        assertTrue(catalog.query("STC 88-20", null, null, null).single().needsReview)
        assertEquals(DeviceAccess.NEEDS_REVIEW, device.access(isPro = true))
        assertEquals(DeviceAccess.NEEDS_REVIEW, device.access(isPro = false))
        assertNull(device.quantitiesFor(10.0, isPro = true))
        assertNull(device.reviewed(" ", "2026-10-16"))
        val signed = device.reviewed("Paulo", "2026-10-16")!!
        assertEquals("Paulo", signed.review!!.by)
        assertTrue(signed.placeable)
        assertTrue(signed.calculable)
        assertEquals(DeviceAccess.OPEN, signed.access(isPro = true))
        assertEquals(DeviceAccess.NEEDS_PRO, signed.access(isPro = false))
        assertNotNull(signed.quantitiesFor(2.0, isPro = true))
        assertNull(signed.quantitiesFor(2.0, isPro = false))
        val updated = catalog.withReview("STC 88-20", "Paulo", "2026-10-16")
        assertTrue(updated.device("STC 88-20")!!.calculable)
        assertEquals(catalog, catalog.withReview("STC 88-20", "", "2026-10-16"))
    }

    @Test
    fun proScalesQuantitiesOnlyAfterTheDeviceIsOpen() {
        val device = catalog.device("VPCG 160-30")!!
        assertEquals(DeviceAccess.NEEDS_PRO, device.access(isPro = false))
        assertNull(device.quantitiesFor(2.0, isPro = false))
        val scaled = device.quantitiesFor(2.0, isPro = true)!!
        assertEquals(0.78, scaled.excavationPerMetre()!!, 1e-9)
        assertEquals("m3", scaled.items.first { it.name == "escavacao" }.unit)
    }

    @Test
    fun searchFiltersByCodeNameChapterAndFamily() {
        assertEquals("VPCG 160-30", catalog.query("vpcg 160-30", null, null, null).single().code)
        assertTrue(catalog.query("valeta de protecao", null, null, null).any { it.code == "VPCG 160-30" })
        val family = catalog.query("", 1, DeviceCategory.LINEAR, "VPCG")
        assertTrue(family.any { it.code == "VPCG 160-30" })
        assertTrue(family.all { it.family == "VPCG" && it.chapter == 1 && it.category == DeviceCategory.LINEAR })
        assertEquals("DAD 170-35", catalog.query("dad 170", null, null, null).single().code)
    }

    @Test
    fun anImportedLibraryRoundTripsAndAWiderPipeKeepsItsDiameter() {
        val library = """
            {"schema":1,"origin":"DER-SP","devices":[{"code":"DER V-01","name":"Valeta municipal","family":"DER","chapter":1,"category":"linear","page":{"source":"DER","pdf_page":1,"desenho":"V"}}]}
        """.trimIndent()
        val stored = CatalogLibraries.append(null, library)
        val again = CatalogLibraries.append(stored, library)
        val merged = CatalogLibraries.apply(catalog, again)
        assertEquals("DER-SP", merged.device("DER V-01")!!.origin)
        assertEquals("DNIT IPR-736", merged.device("VPCG 160-30")!!.origin)
        val pipe = catalog.device("BSTC 150")!!
        val widened = resolveSection(pipe, mapOf("DN_cm" to 200.0))!!
        assertEquals(2.0, widened.points.maxOf { it.x } - widened.points.minOf { it.x }, 1e-6)
    }

    @Test
    fun userLibraryMergesAndCanReplaceACode() {
        val added = CatalogLoader.importUser(
            """
            {"schema":1,"origin":"DER-SP","catalog":"Valetas DER","devices":[{
              "code":"DER V-01","name":"Valeta municipal","family":"DER","chapter":1,
              "category":"linear","subtype":"valeta",
              "params":{"B_cm":100,"H_cm":40,"b_cm":40},
              "geometry":{"type":"trapezoid_open","units":"cm","vertices":["(-B/2,0)","(-b/2,-H)","(b/2,-H)","(B/2,0)"]},
              "materials":["solo"],
              "quantities_per_m":{"escavacao":{"value":0.28,"unit":"m3/m"}},
              "connections":{"jusante":["caixa"]},"page":{"source":"DER","pdf_page":1,"desenho":"V-01"},
              "needs_review":[{"field":"geometry","issue":"conferir no caderno DER"}]
            }]}
            """.trimIndent(),
        )
        assertEquals(397, added.devices.size)
        val custom = added.device("DER V-01")!!
        assertEquals("DER-SP", custom.origin)
        assertEquals(0.28, custom.section!!.excavationM3PerM!!, 1e-9)
        assertEquals(0.28, custom.quantities.excavationPerMetre()!!, 1e-9)
        assertTrue(custom.needsReview)
        assertEquals("DNIT IPR-736", added.device("VPCG 160-30")!!.origin)
        val replaced = added.merge(CatalogLoader.parse(
            """{"schema":1,"origin":"empresa","devices":[{"code":"VPCG 160-30","name":"Valeta da empresa","family":"VPCG","chapter":1,"category":"linear","page":{"source":"EMP","pdf_page":2,"desenho":"A"}}]}""",
        ))
        assertEquals(397, replaced.devices.size)
        assertEquals("empresa", replaced.device("VPCG 160-30")!!.origin)
        assertEquals("Valeta da empresa", replaced.device("VPCG 160-30")!!.name)
    }

    @Test
    fun unknownSchemaIsRefusedAndMissingSchemaStaysVersionOne() {
        val error = assertFailsWith<CatalogFormatException> {
            CatalogLoader.parse("""{"schema":2,"devices":[]}""")
        }
        assertEquals(CatalogFormatException.Kind.SCHEMA, error.kind)
        assertFailsWith<CatalogFormatException> { CatalogLoader.parse("{") }
        val loose = CatalogLoader.parse("""{"origin":"prefeitura","devices":[{"code":"X","category":"point"}]}""")
        assertEquals(1, loose.schema)
        assertEquals(DeviceCategory.POINT, loose.device("X")!!.category)
    }

    private fun assertExcavation(code: String, expected: Double) {
        val device = catalog.device(code)!!
        assertEquals(expected, device.section!!.excavationM3PerM!!, 1e-9, code)
        assertEquals(expected, device.quantities.excavationPerMetre()!!, 1e-3, code)
    }
}
