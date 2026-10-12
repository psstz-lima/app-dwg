package com.appdwg.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GeorefTest {

    @Test
    fun worldFilePlacesThePictureByItsPixelEdges() {
        // 0,5 m pixels; top-left pixel centred at (600000,25 ; 7800000,75).
        val b = WorldFile.extent("0.5\n0\n0\n-0.5\n600000.25\n7800000.75\n", 200, 100)
        assertEquals(600000.0, b.minX, 1e-9)
        assertEquals(7800001.0, b.maxY, 1e-9)
        assertEquals(600100.0, b.maxX, 1e-9)
        assertEquals(7799951.0, b.minY, 1e-9)
    }

    @Test
    fun worldFileAcceptsDecimalCommas() {
        val b = WorldFile.extent("1,0 0 0 -1,0 10,5 20,5", 10, 10)
        assertEquals(10.0, b.minX, 1e-9)
        assertEquals(21.0, b.maxY, 1e-9)
    }

    @Test
    fun rotatedWorldFileIsRefused() {
        assertFailsWith<IllegalArgumentException> { WorldFile.extent("1 0.1 0 -1 0 0", 10, 10) }
    }

    @Test
    fun geoTiffTiePointGivesTheExtent() {
        val tiff = geoTiff(
            4, 2,
            scale = doubleArrayOf(2.0, 3.0, 0.0),
            tie = doubleArrayOf(0.0, 0.0, 0.0, 500000.0, 7000000.0, 0.0),
        )
        val r = TiffReader(input(tiff)).read(4096)
        val b = r.extent!!
        assertEquals(500000.0, b.minX, 1e-9)
        assertEquals(500008.0, b.maxX, 1e-9)
        assertEquals(7000000.0, b.maxY, 1e-9)
        assertEquals(6999994.0, b.minY, 1e-9)
        assertEquals(4, r.width)
    }

    @Test
    fun plainTiffHasNoExtent() {
        assertNull(TiffReader(input(geoTiff(4, 2, null, null))).read(4096).extent)
    }

    private fun input(data: ByteArray) = object : RandomInput {
        override val size = data.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            System.arraycopy(data, position.toInt(), buffer, offset, length)
        }
    }

    /** Little-endian, uncompressed 8-bit gray TIFF with optional GeoTIFF scale and tie point. */
    private fun geoTiff(w: Int, h: Int, scale: DoubleArray?, tie: DoubleArray?): ByteArray {
        class Entry(val tag: Int, val type: Int, val count: Int, val value: Int, val extra: ByteArray? = null)
        val pixelsAt = 8
        val pixels = ByteArray(w * h) { (it * 10).toByte() }
        var dataAt = pixelsAt + pixels.size
        val extras = ArrayList<ByteArray>()
        fun doubles(d: DoubleArray): Int {
            val b = ByteBuffer.allocate(d.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            d.forEach { b.putDouble(it) }
            extras += b.array()
            return dataAt.also { dataAt += d.size * 8 }
        }
        val entries = mutableListOf(
            Entry(256, 3, 1, w), Entry(257, 3, 1, h), Entry(258, 3, 1, 8), Entry(259, 3, 1, 1),
            Entry(262, 3, 1, 1), Entry(273, 4, 1, pixelsAt), Entry(277, 3, 1, 1), Entry(278, 3, 1, h),
            Entry(279, 4, 1, pixels.size),
        )
        if (scale != null) entries += Entry(33550, 12, scale.size, doubles(scale))
        if (tie != null) entries += Entry(33922, 12, tie.size, doubles(tie))
        val ifdAt = dataAt
        val out = ByteBuffer.allocate(ifdAt + 2 + entries.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdAt)
        out.put(pixels)
        extras.forEach { out.put(it) }
        out.putShort(entries.size.toShort())
        for (e in entries) {
            out.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (e.type == 3 && e.count == 1) out.putShort(e.value.toShort()).putShort(0) else out.putInt(e.value)
        }
        out.putInt(0)
        return out.array()
    }
}
