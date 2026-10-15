package com.appdwg.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixel size of each picture-detail step. The numbers are the ones a 20,000 × 15,000 orthophoto
 * must produce: Máxima native, Alta half, Normal a quarter.
 */
class DrapeQualityTest {
    @Test
    fun eachQualityHasAFixedPixelSize() {
        assertEquals(1, DrapeQuality.reduce(2))
        assertEquals(2, DrapeQuality.reduce(1))
        assertEquals(4, DrapeQuality.reduce(0))
        assertEquals(0, DrapeQuality.pyramidLevel(2))
        assertEquals(1, DrapeQuality.pyramidLevel(1))
        assertEquals(2, DrapeQuality.pyramidLevel(0))

        assertEquals(20_000 to 15_000, DrapeQuality.outputSize(20_000, 15_000, 2))
        assertEquals(10_000 to 7_500, DrapeQuality.outputSize(20_000, 15_000, 1))
        assertEquals(5_000 to 3_750, DrapeQuality.outputSize(20_000, 15_000, 0))

        // Odd edges round up, so the last file pixel is still covered.
        assertEquals(10_001 to 7_501, DrapeQuality.outputSize(20_001, 15_001, 1))
        assertEquals(0 to 0, DrapeQuality.outputSize(0, 15_000, 2))
    }

    @Test
    fun planLevelMatchesTheScreenAndStaysNativeWhenZoomedIn() {
        // One file pixel per screen pixel, and anything finer, is level 0 on Máxima.
        assertEquals(0, DrapeQuality.level(1.0, 2, 12))
        assertEquals(0, DrapeQuality.level(0.25, 2, 12))
        // Eight file pixels per screen pixel is level 3 (2^3). Alta and Normal sit one and two above it.
        assertEquals(3, DrapeQuality.level(8.0, 2, 12))
        assertEquals(4, DrapeQuality.level(8.0, 1, 12))
        assertEquals(5, DrapeQuality.level(8.0, 0, 12))
        // Zoomed past the file: Alta stays at half, Normal at a quarter. Neither goes to native.
        assertEquals(1, DrapeQuality.level(0.5, 1, 12))
        assertEquals(2, DrapeQuality.level(0.5, 0, 12))
        // floor(log2) picks the finer side of the step, then the quality bias, and never past the top.
        assertEquals(1, DrapeQuality.level(3.9, 2, 12))
        assertEquals(2, DrapeQuality.level(3.9, 1, 12))
        assertEquals(12, DrapeQuality.level(1.0e9, 0, 12))
        assertEquals(0, DrapeQuality.level(0.1, 2, 0))
    }

    @Test
    fun anisotropyIsAskedOnlyWhenTheExtensionExists() {
        assertEquals(1f, GlTexture.anisotropy(emptyList(), 16f), 0f)
        assertEquals(1f, GlTexture.anisotropy(listOf("GL_OES_something"), 16f), 0f)
        assertEquals(8f, GlTexture.anisotropy(listOf(GlTexture.ANISOTROPY_EXTENSION), 8f), 0f)
        assertEquals(16f, GlTexture.anisotropy(listOf(GlTexture.ANISOTROPY_EXTENSION), 32f), 0f)
        assertEquals(1f, GlTexture.anisotropy(listOf(GlTexture.ANISOTROPY_EXTENSION), 1f), 0f)
        assertEquals(0x84FE, GlTexture.TEXTURE_MAX_ANISOTROPY_EXT)
        assertEquals(0x84FF, GlTexture.MAX_TEXTURE_MAX_ANISOTROPY_EXT)
    }

    @Test
    fun maximaIsNotCappedBeforeTheGpuAndJpegTilesAreReallyReduced() {
        val viewModel = File(root(), "app/src/main/kotlin/com/appdwg/app/ProjectViewModel.kt").readText()
        val drape = viewModel.substringAfter("suspend fun drapeFor3d").substringBefore("fun removeDrape")
        assertFalse(drape.contains("40_000_000"))
        assertFalse(drape.contains("8192"))
        assertTrue(drape.contains("DrapeQuality"))
        val coil = File(root(), "app/src/main/kotlin/com/appdwg/app/CoilPictures.kt").readText()
        assertFalse(coil.contains("8192 / reduce"))
        assertTrue(coil.contains("ImageHeader.parse"))
        val picture = File(root(), "app/src/main/kotlin/com/appdwg/app/TiledPicture.kt").readText()
        assertTrue(picture.contains("DrapeQuality.level"))
        assertTrue(picture.contains("fun eachGpuTile"))
        assertTrue(picture.contains("pyramid2"))
        val view = File(root(), "app/src/main/kotlin/com/appdwg/app/View3D.kt").readText()
        assertTrue(view.contains("GL_LINEAR_MIPMAP_LINEAR"))
        assertTrue(view.contains("glGenerateMipmap"))
        assertTrue(view.contains("applyAnisotropy"))
        assertTrue(view.contains("eachGpuTile"))
        val menu = File(root(), "app/src/main/kotlin/com/appdwg/app/MainActivity.kt").readText()
        assertTrue(menu.contains("Detalhe da imagem"))
        assertFalse(menu.contains("Detalhe da imagem em 3D"))
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
}
