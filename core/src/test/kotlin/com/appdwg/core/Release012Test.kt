package com.appdwg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the 0.1.2 release choices that have to stay true in source: version, shrinking, locales, tiles. */
class Release012Test {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }

    @Test
    fun versionAndOptimizedShrinking() {
        val app = File(root(), "app/build.gradle.kts").readText()
        assertTrue(app.contains("versionCode = 5"))
        assertTrue(app.contains("versionName = \"0.1.4\""))
        assertTrue(app.contains("isMinifyEnabled = true"))
        assertTrue(app.contains("isShrinkResources = true"))
        val root = File(root(), "build.gradle.kts").readText()
        assertTrue(Regex("""com\.android\.application[^\n]*9\.""").containsMatchIn(root))
        val props = File(root(), "gradle.properties").readText()
        assertTrue(props.contains("android.r8.optimizedResourceShrinking=true"))
    }

    @Test
    fun noAdvertisingIdAndGmtAssociation() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("AD_ID"))
        assertFalse(manifest.contains("com.google.android.gms.permission.AD_ID"))
        assertTrue(manifest.contains(".gmt"))
        assertTrue(manifest.contains("application/vnd.gromatic.project"))
        assertTrue(manifest.contains("locales_config"))
        val locales = File(root(), "app/src/main/res/xml/locales_config.xml").readText()
        assertTrue(locales.contains("pt-BR"))
        assertTrue(locales.contains("en-US"))
    }

    @Test
    fun tilesGoThroughCoilNotBitmapFactory() {
        val basemap = File(root(), "app/src/main/kotlin/com/appdwg/app/Basemap.kt").readText()
        val tiles = File(root(), "app/src/main/kotlin/com/appdwg/app/TileImages.kt").readText()
        assertFalse(basemap.contains("HttpURLConnection"))
        assertFalse(basemap.contains("BitmapFactory"))
        assertFalse(tiles.contains("HttpURLConnection"))
        assertFalse(tiles.contains("BitmapFactory"))
        assertTrue(tiles.contains("coil3"))
        assertTrue(tiles.contains("RGB_565"))
        assertTrue(tiles.contains("ARGB_8888"))
    }

    @Test
    fun englishStringsCoverPortugueseResources() {
        fun names(path: String): Set<String> {
            val text = File(root(), path).readText()
            val string = Regex("""<(?:string|plurals) name="([^"]+)"""")
            return string.findAll(text).map { it.groupValues[1] }.toSet()
        }
        assertEquals(names("app/src/main/res/values/strings.xml"), names("app/src/main/res/values-en/strings.xml"))
    }

    @Test
    fun languagePickerUsesAppCompatLocales() {
        val theme = File(root(), "app/src/main/kotlin/com/appdwg/app/Theme.kt").readText()
        assertTrue(theme.contains("AppCompatDelegate.setApplicationLocales"))
        assertTrue(theme.contains("pt-BR"))
        assertTrue(theme.contains("en-US"))
    }

    @Test
    fun keepRulesStillNameTheFormatsPlayMustNotStrip() {
        val rules = File(root(), "app/proguard-rules.pro").readText()
        for (name in listOf(
            "gromatic_pro_mensal", "gromatic_pro_anual", "LandXmlReader", "DxfReader", "KmlWriter",
            "PointFile", "Gis", "TiffReader", "ProjectCache", "NtripClient", "native <methods>",
        )) assertTrue(rules.contains(name), name)
    }
}
