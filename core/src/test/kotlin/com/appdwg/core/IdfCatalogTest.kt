package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdfCatalogTest {
    @Test
    fun readsEveryLine() {
        assertTrue(IdfCatalog.entries.size >= 120)
        for (e in IdfCatalog.entries) {
            assertTrue(e.idf.k > 0 && e.idf.a in 0.05..0.6 && e.idf.b in 0.0..80.0 && e.idf.c in 0.3..1.6, e.label)
            // A 10-minute, 10-year rain between 60 and 300 mm/h: catches a K left in mm/min.
            assertTrue(e.idf.intensity(10.0, 10.0) in 60.0..300.0, "${e.label}: ${e.idf.intensity(10.0, 10.0)}")
        }
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        val bh = IdfCatalog.search("belo horizonte").single()
        assertEquals(1447.87, bh.idf.k)
        assertTrue(IdfCatalog.search("goiania").any { it.place == "Goiânia" })
        assertTrue(IdfCatalog.search("/es").size > 20)
    }
}
