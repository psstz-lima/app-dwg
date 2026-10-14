package com.appdwg.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TiffReaderTest {

    /** The fixtures (made by tiff/make_fixtures.py) are 50 x 30 pixels with this color at (x, y). */
    private fun expected(x: Int, y: Int) = Triple(x * 5 % 256, y * 8 % 256, (x + y) * 2 % 256)

    private fun input(name: String): RandomInput {
        val data = javaClass.classLoader.getResourceAsStream("tiff/$name")!!.readBytes()
        return object : RandomInput {
            override val size = data.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                System.arraycopy(data, position.toInt(), buffer, offset, length)
            }
        }
    }

    private val jpeg: (ByteArray) -> Raster? = { bytes ->
        ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
            Raster(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
    }

    private fun read(name: String, maxSize: Int = 4096) = TiffReader(input(name), jpeg).read(maxSize)

    private fun assertRgb(r: Raster, tolerance: Int = 0, alpha: Int = 0xFF, step: Int = 1) {
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val (er, eg, eb) = expected(x * step, y * step)
            val p = r.pixels[y * r.width + x]
            val msg = "pixel ($x, $y)"
            assertEquals(alpha, p ushr 24, msg)
            assertTrue(abs(((p shr 16) and 0xFF) - er) <= tolerance, msg)
            assertTrue(abs(((p shr 8) and 0xFF) - eg) <= tolerance, msg)
            assertTrue(abs((p and 0xFF) - eb) <= tolerance, msg)
        }
    }

    @Test
    fun lzwStrips() {
        val r = read("lzw_rgb.tif")
        assertEquals(50, r.width)
        assertEquals(30, r.height)
        assertRgb(r)
    }

    @Test
    fun deflateWithPredictorAndAlpha() = assertRgb(read("deflate_rgba_predictor.tif"), alpha = 200)

    @Test
    fun packBitsGray() {
        val r = read("packbits_gray.tif")
        val p = r.pixels[3 * 50 + 7]
        assertEquals(p and 0xFF, (p shr 8) and 0xFF)
        // Gray from (35, 24, 20) with ITU-R 601 weights.
        assertTrue(abs((p and 0xFF) - 27) <= 1)
    }

    @Test
    fun palette() {
        val r = read("palette.tif")
        val p = r.pixels[3 * 50 + 7]
        // 16 colors only, so the nearest palette entry is close to the original.
        assertTrue(abs(((p shr 16) and 0xFF) - 35) < 40)
        assertEquals(0xFF, p ushr 24)
    }

    @Test
    fun jpegTiles() = assertRgb(read("jpeg_rgb.tif"), tolerance = 12)

    @Test
    fun bigTiffTilesAndOverview() {
        assertRgb(read("bigtiff_tiled_overview.tif"))
        // Asking for 25 px picks the 25 x 15 overview, which samples every other pixel.
        val small = read("bigtiff_tiled_overview.tif", maxSize = 25)
        assertEquals(25, small.width)
        assertRgb(small, step = 2)
    }

    @Test
    fun downsamplesWithoutOverview() {
        val r = read("lzw_rgb.tif", maxSize = 10)
        assertEquals(10, r.width)
        assertEquals(6, r.height)
        assertRgb(r, step = 5)
    }

    @Test
    fun rejectsOtherFiles() {
        val notTiff = object : RandomInput {
            override val size = 16L
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = buffer.fill(0, offset, offset + length)
        }
        assertFailsWith<IllegalStateException> { TiffReader(notTiff).read(100) }
    }

    /** Puts the picture back together from its blocks, each reduced by [reduce]. */
    private fun mosaic(name: String, reduce: Int, jpegOk: Boolean = false): Raster {
        val reader = TiffReader(input(name), jpeg)
        val b = reader.blocks()
        val w = (b.width + reduce - 1) / reduce; val h = (b.height + reduce - 1) / reduce
        val out = IntArray(w * h)
        for (i in 0 until b.count) {
            val r = reader.readBlock(i, reduce) ?: continue
            val ox = (i % b.across) * b.blockWidth / reduce; val oy = (i / b.across) * b.blockHeight / reduce
            for (y in 0 until r.height) for (x in 0 until r.width) if (ox + x < w && oy + y < h) out[(oy + y) * w + ox + x] = r.pixels[y * r.width + x]
        }
        return Raster(w, h, out)
    }

    @Test
    fun blocksOfTiles() = assertRgb(mosaic("bigtiff_tiled_overview.tif", 1))

    @Test
    fun blocksOfStrips() = assertRgb(mosaic("lzw_rgb.tif", 1))

    @Test
    fun reducedBlocks() = assertRgb(mosaic("bigtiff_tiled_overview.tif", 2), step = 2)

    @Test
    fun reducedJpegBlocks() = assertRgb(mosaic("jpeg_rgb.tif", 2), tolerance = 12, step = 2)
}
