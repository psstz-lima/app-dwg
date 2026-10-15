package com.appdwg.app

import com.appdwg.core.RandomInput
import com.appdwg.core.TiffReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner repro: a LandXML surface is open, a TIFF is draped on it, and 3D view is opened.
 * The crash is the drape texture upload (Mali glTexImage2D). A large odd orthophoto is the case
 * that faulted; the GPU is not available here, so the test runs the same size and config decision
 * the renderer uses, on a real odd TIFF plus the large size.
 */
class DrapeTextureTest {
    @Test
    fun drapedOddTiffUploadsAsArgb8888InsideTheGpuLimit() {
        val tiff = oddRgbTiff(width = 7, height = 5)
        val raster = TiffReader(bytes(tiff)).read(4096)
        assertEquals(7, raster.sourceWidth)
        assertEquals(5, raster.sourceHeight)
        assertEquals(7, raster.width)
        assertEquals(5, raster.height)

        val small = GlTexture.plan(raster.width, raster.height, recycled = false, maxTextureSize = 4096)
        assertNotNull(small)
        assertEquals(7, small!!.width)
        assertEquals(5, small.height)
        assertEquals(GlTexture.CONFIG, small.config)
        assertEquals(1, small.width % 2)

        // A draped orthophoto larger than GL_MAX_TEXTURE_SIZE, with an odd side, as on the Redmi.
        val large = GlTexture.plan(9001, 6003, recycled = false, maxTextureSize = 4096)
        assertNotNull(large)
        assertTrue(large!!.width <= 4096)
        assertTrue(large.height <= 4096)
        assertTrue(large.width >= 1 && large.height >= 1)
        assertEquals(GlTexture.CONFIG, large.config)
        assertEquals(1, large.height % 2)
        assertNull(GlTexture.plan(9001, 6003, recycled = true, maxTextureSize = 4096))
        assertNull(GlTexture.plan(0, 6003, recycled = false, maxTextureSize = 4096))
    }

    @Test
    fun rendererUploadsThatCopyOnceOnTheGlThread() {
        val view = File(root(), "app/src/main/kotlin/com/appdwg/app/View3D.kt").readText()
        val upload = view.substringAfter("private fun uploadDrape()").substringBefore("fun update(")
        assertFalse(upload.contains("if (drapeDirty) uploadDrape()"))
        assertTrue(view.contains("if (drapeDirty) uploadDrape()"))
        assertTrue(upload.contains("GlTexture.copy(source, maxSize)"))
        assertTrue(upload.contains("!source.isRecycled"))
        assertTrue(upload.contains("!bitmap.isRecycled"))
        assertTrue(upload.contains("GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)"))
        assertTrue(upload.contains("GLUtils.texImage2D"))
        assertTrue(upload.contains("if (!it.isRecycled) it.recycle()"))
        assertTrue(upload.contains("if (drape === snapshot) drapeDirty = false"))
        assertTrue(upload.contains("catch (e: Throwable)"))
        assertFalse(upload.contains("bitmap.scale"))
        val picture = File(root(), "app/src/main/kotlin/com/appdwg/app/ProjectViewModel.kt").readText()
            .substringAfter("private fun decodePicture")
            .substringBefore("private suspend fun tiledDrape")
        assertTrue(picture.contains("CoilPictures.decode"))
        assertFalse(picture.contains("BitmapFactory.decodeStream"))
        assertFalse(picture.contains("BitmapDecode.decodeStream"))
    }

    /** Uncompressed 8-bit RGB TIFF with an odd width, the shape that Mali rejected. */
    private fun oddRgbTiff(width: Int, height: Int): ByteArray {
        val pixels = width * height * 3
        val ifdStart = 8
        val entries = 10
        val ifdBytes = 2 + entries * 12 + 4
        val bitsAt = ifdStart + ifdBytes
        val stripAt = bitsAt + 6
        val out = ByteArray(stripAt + pixels)
        fun u16(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun u32(at: Int, v: Int) {
            u16(at, v and 0xFFFF)
            u16(at + 2, v ushr 16)
        }
        out[0] = 'I'.code.toByte()
        out[1] = 'I'.code.toByte()
        u16(2, 42)
        u32(4, ifdStart)
        u16(ifdStart, entries)
        var e = ifdStart + 2
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            u16(e, tag); u16(e + 2, type); u32(e + 4, count); u32(e + 8, value); e += 12
        }
        entry(256, 3, 1, width)
        entry(257, 3, 1, height)
        entry(258, 3, 3, bitsAt)
        entry(259, 3, 1, 1)
        entry(262, 3, 1, 2)
        entry(273, 4, 1, stripAt)
        entry(277, 3, 1, 3)
        entry(278, 4, 1, height)
        entry(279, 4, 1, pixels)
        entry(284, 3, 1, 1)
        u32(e, 0)
        u16(bitsAt, 8); u16(bitsAt + 2, 8); u16(bitsAt + 4, 8)
        for (i in 0 until pixels) out[stripAt + i] = (i * 17 and 0xFF).toByte()
        return out
    }

    private fun bytes(data: ByteArray) = object : RandomInput {
        override val size = data.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            System.arraycopy(data, position.toInt(), buffer, offset, length)
        }
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
}
