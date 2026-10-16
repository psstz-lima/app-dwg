package com.appdwg.core.drainage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogAuditTest {
    @Test
    fun theBundledAlbumCalculatesOffline() {
        val report = CatalogAudit.check(CatalogLoader.bundled())
        assertTrue(report.devices >= 300, "devices ${report.devices}")
        assertTrue(report.placeable > 100, "placeable ${report.placeable}")
        assertTrue(report.sectioned > 50, "sectioned ${report.sectioned}")
        assertEquals(emptyList(), report.problems)
    }

    @Test
    fun aBrokenSectionIsReported() {
        val broken = DeviceDef(
            code = "QUEBRADO",
            name = "Seção inválida",
            family = "X",
            chapter = 1,
            category = DeviceCategory.LINEAR,
            subtype = "valeta",
            position = null,
            parameters = emptyList(),
            section = CrossSection("trapezoid_open", "m", listOf(listOf(SectionPoint(Double.NaN, 0.0), SectionPoint(0.0, 1.0), SectionPoint(1.0, 0.0)))),
            materials = emptyList(),
            quantities = Quantities(emptyList()),
            connections = emptyList(),
            adaptableTo = emptyList(),
            page = SourcePage("", 0, ""),
            reviewNotes = emptyList(),
            note = null,
            variantsNote = null,
            origin = "teste",
        )
        val report = CatalogAudit.check(DeviceCatalog(1, "teste", "teste", "", "", emptyMap(), emptyMap(), listOf(broken)))
        assertEquals(listOf("QUEBRADO"), report.problems)
    }
}
