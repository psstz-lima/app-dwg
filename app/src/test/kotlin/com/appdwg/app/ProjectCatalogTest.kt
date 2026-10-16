package com.appdwg.app

import com.appdwg.core.drainage.CatalogLibraries
import com.appdwg.core.drainage.CatalogLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The project file keeps an imported drainage library so a later open can resolve its devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectCatalogTest {
    @Test
    fun anImportedCatalogIsEmbeddedAndAnOldProjectStillOpens() {
        val library = """
            {"schema":1,"origin":"DER-SP","devices":[{"code":"DER V-01","name":"Valeta municipal","family":"DER","chapter":1,"category":"linear","page":{"source":"DER","pdf_page":1,"desenho":"V"}}]}
        """.trimIndent()
        val stored = CatalogLibraries.append(null, library)
        val saved = JSONObject()
            .put("app", "Gromatic")
            .put("main", "content://drawing")
            .put("drainageCatalog", stored)
        val doc = ProjectDoc.fromJson(saved)
        val again = ProjectDoc.fromJson(JSONObject(doc.toJson().toString()))
        val text = again.drainageCatalogJson!!
        assertTrue(text.contains("DER V-01"))
        val catalog = CatalogLibraries.apply(CatalogLoader.bundled(), text)
        assertEquals("DER-SP", catalog.device("DER V-01")!!.origin)
        assertEquals("DNIT IPR-736", catalog.device("BSTC 100")!!.origin)
        val older = ProjectDoc.fromJson(JSONObject().put("app", "Gromatic").put("main", "content://drawing"))
        assertNull(older.drainageCatalogJson)
    }
}
