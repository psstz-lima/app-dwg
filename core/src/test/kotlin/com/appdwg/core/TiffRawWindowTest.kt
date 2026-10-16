package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A striped uncompressed TIFF must give a window without reading the rest of the strip. */
class TiffRawWindowTest {
    @Test
    fun aWindowReadsOnlyItsRowsAndColumns() {
        val width = 64
        val height = 40
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val p = (y * width + x) * 3
            pixels[p] = x.toByte()
            pixels[p + 1] = y.toByte()
            pixels[p + 2] = 7
        }
        val file = striped(width, height, pixels)
        val input = Counting(file)
        val raw = TiffReader(input).readRaw(0, 10, 4, 8, 5, 1)
        assertTrue(raw != null)
        assertEquals(8, raw!!.width)
        assertEquals(5, raw.height)
        assertEquals(10, (raw.pixels[0] shr 16) and 0xFF)
        assertEquals(4, (raw.pixels[0] shr 8) and 0xFF)
        val full = width * height * 3
        assertTrue(input.pixelBytes < full)
        assertEquals(8 * 5 * 3, input.pixelBytes)
    }

    @Test
    fun aPredictedStripStillReadsOnlyTheWindowRows() {
        val width = 64
        val height = 40
        val plain = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val p = (y * width + x) * 3
            plain[p] = x.toByte()
            plain[p + 1] = y.toByte()
            plain[p + 2] = 7
        }
        val stored = plain.copyOf()
        for (y in 0 until height) {
            val row = y * width * 3
            for (i in row + width * 3 - 1 downTo row + 3) stored[i] = (stored[i] - stored[i - 3]).toByte()
        }
        val input = Counting(striped(width, height, stored, predictor = 2))
        val raw = TiffReader(input).readRaw(0, 10, 4, 8, 5, 1)
        assertTrue(raw != null)
        assertEquals(10, (raw!!.pixels[0] shr 16) and 0xFF)
        assertEquals(4, (raw.pixels[0] shr 8) and 0xFF)
        val full = width * height * 3
        assertTrue(input.pixelBytes < full)
        // Predictor 2 is read from column 0 through the window, not across the rest of the strip.
        assertEquals((10 + 8) * 5 * 3, input.pixelBytes)
    }

    private fun striped(width: Int, height: Int, pixels: ByteArray, predictor: Int = 1): ByteArray {
        val entries = if (predictor == 1) 9 else 10
        val ifdAt = 8
        val ifdBytes = 2 + entries * 12 + 4
        val bitsAt = ifdAt + ifdBytes
        val dataAt = bitsAt + 6
        val out = ByteArray(dataAt + pixels.size)
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
        u32(4, ifdAt)
        u16(ifdAt, entries)
        var e = ifdAt + 2
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            u16(e, tag); u16(e + 2, type); u32(e + 4, count); u32(e + 8, value); e += 12
        }
        entry(256, 3, 1, width)
        entry(257, 3, 1, height)
        entry(258, 3, 3, bitsAt)
        entry(259, 3, 1, 1)
        entry(262, 3, 1, 2)
        entry(273, 4, 1, dataAt)
        entry(277, 3, 1, 3)
        entry(278, 3, 1, height)
        entry(279, 4, 1, pixels.size)
        if (predictor != 1) entry(317, 3, 1, predictor)
        u16(bitsAt, 8); u16(bitsAt + 2, 8); u16(bitsAt + 4, 8)
        pixels.copyInto(out, dataAt)
        return out
    }

    private class Counting(private val file: ByteArray) : RandomInput {
        var pixelBytes = 0
        override val size = file.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (position > 200) pixelBytes += length
            for (i in 0 until length) buffer[offset + i] = file[(position + i).toInt()]
        }
    }
}
