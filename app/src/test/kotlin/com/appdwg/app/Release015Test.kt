package com.appdwg.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release015Test {
    @Test
    fun rasterBudgetKeepsASharpPictureInsideTheMemoryCap() {
        assertEquals(8000, RasterBudget.maxSide(8000, 6000))
        assertTrue(RasterBudget.maxSide(20000, 20000) < 20000)
        val side = RasterBudget.maxSide(20000, 10000)
        val scale = 20000.0 / side
        assertTrue(20000.0 / scale * (10000.0 / scale) <= RasterBudget.PIXELS.toDouble() + 1)
        assertFalse(RasterBudget.tileTiff(3000, 2000))
        assertTrue(RasterBudget.tileTiff(7000, 5000))
        assertTrue(RasterBudget.tileTiff(10000, 8000))
    }

    @Test
    fun downloadNamesAvoidCollisionsAndTheCloseQuestionWaitsForCloudCopies() {
        assertEquals("foto.tif", DownloadNames.unique("foto.tif", emptySet()))
        assertEquals("foto (2).tif", DownloadNames.unique("foto.tif", setOf("foto.tif")))
        assertEquals("foto (3).tif", DownloadNames.unique("foto.tif", setOf("foto.tif", "foto (2).tif")))
        assertEquals("image/tiff", DownloadNames.mime("Orto.TIFF"))
        assertEquals("text/xml", DownloadNames.mime("superficie.xml"))
        assertFalse(DownloadNames.shouldAsk(0))
        assertTrue(DownloadNames.shouldAsk(2))
    }

    @Test
    fun aMissingLanguagePackIsNotOffered() {
        assertTrue(LanguagePacks.installed("en", "en"))
        assertTrue(LanguagePacks.installed("pt", "pt"))
        assertFalse(LanguagePacks.installed("pt", "en"))
        assertFalse(LanguagePacks.installed(null, "en"))
        val packs = File(root(), "app/src/main/kotlin/com/appdwg/app/LanguagePacks.kt").readText()
        assertTrue(packs.contains("createConfigurationContext"))
        val theme = File(root(), "app/src/main/kotlin/com/appdwg/app/Theme.kt").readText()
        assertTrue(theme.contains("setApplicationLocales"))
        val chrome = File(root(), "app/src/main/kotlin/com/appdwg/app/Chrome.kt").readText()
        assertTrue(chrome.contains("languageReady"))
        assertTrue(chrome.contains("enabled = ready"))
        val activity = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertFalse(activity.contains("configChanges"))
    }

    @Test
    fun appCodeDoesNotOpenStreamsWithTheOldHttpOrBitmapApis() {
        val src = File(root(), "app/src/main/kotlin")
        val hits = src.walk().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.contains("HttpURLConnection") || line.contains("decodeStream") || line.contains(".openConnection(")) {
                    "${file.name}:${index + 1}: $line"
                } else null
            }
        }.toList()
        assertEquals(emptyList<String>(), hits)
        val downloads = File(root(), "app/src/main/kotlin/com/appdwg/app/PublicDownloads.kt").readText()
        assertTrue(downloads.contains("MediaStore.Downloads"))
        assertFalse(File(root(), "app/src/main/AndroidManifest.xml").readText().contains("WRITE_EXTERNAL_STORAGE"))
    }

    @Test
    fun imageHeaderReadsPngAndJpegWithoutABitmap() {
        val png = ByteArray(24)
        png[0] = 0x89.toByte(); png[1] = 'P'.code.toByte(); png[2] = 'N'.code.toByte(); png[3] = 'G'.code.toByte()
        png[16] = 0; png[17] = 0; png[18] = 0x10; png[19] = 0
        png[20] = 0; png[21] = 0; png[22] = 0x08; png[23] = 0
        val info = ImageHeader.parse(png)
        assertEquals(4096, info!!.width)
        assertEquals(2048, info.height)
        assertEquals("image/png", info.mime)
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 16, 0, 32, 3, 0, 0, 0,
        )
        val jpg = ImageHeader.parse(jpeg)
        assertEquals(32, jpg!!.width)
        assertEquals(16, jpg.height)
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
}
