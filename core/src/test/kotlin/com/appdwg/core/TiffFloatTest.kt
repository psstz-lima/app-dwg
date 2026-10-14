package com.appdwg.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Float DEM TIFFs as Copernicus publishes them: 32-bit floats, tiled, Deflate, floating-point predictor. */
class TiffFloatTest {
    private val w = 37; private val h = 23; private val tile = 16
    private fun z(x: Int, y: Int) = 700f + x * 1.25f - y * 0.5f + (x * y % 7) * 0.125f

    /** Encodes one tile the way libtiff does with predictor 3. */
    private fun encodeTile(tx: Int, ty: Int, predictor: Int): ByteArray {
        val rowBytes = tile * 4
        val raw = ByteArray(rowBytes * tile)
        for (r in 0 until tile) {
            val start = r * rowBytes
            for (c in 0 until tile) {
                val x = tx * tile + c; val y = ty * tile + r
                val bits = if (x < w && y < h) z(x, y).toBits() else 0
                if (predictor == 3) for (k in 0..3) raw[start + k * tile + c] = (bits ushr (24 - 8 * k)).toByte()
                else ByteBuffer.wrap(raw, start + c * 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits)
            }
            if (predictor == 3) for (i in start + rowBytes - 1 downTo start + 1) raw[i] = (raw[i] - raw[i - 1]).toByte()
        }
        val d = Deflater(); d.setInput(raw); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    private fun tiff(predictor: Int): RandomInput {
        val across = (w + tile - 1) / tile; val down = (h + tile - 1) / tile
        val tiles = (0 until down).flatMap { ty -> (0 until across).map { tx -> encodeTile(tx, ty, predictor) } }
        val entries = 13
        val ifdOffset = 8
        val ifdSize = 2 + entries * 12 + 4
        var data = ifdOffset + ifdSize
        val offsetsPos = data; data += tiles.size * 4
        val countsPos = data; data += tiles.size * 4
        val scalePos = data; data += 24
        val tiePos = data; data += 48
        val b = ByteBuffer.allocate(data + tiles.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        b.putShort(entries.toShort())
        fun e(tag: Int, type: Int, count: Int, value: Int) { b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        e(256, 4, 1, w); e(257, 4, 1, h); e(258, 3, 1, 32); e(259, 3, 1, 8); e(262, 3, 1, 1); e(277, 3, 1, 1)
        e(317, 3, 1, predictor); e(322, 3, 1, tile); e(323, 3, 1, tile)
        e(324, 4, tiles.size, offsetsPos); e(325, 4, tiles.size, countsPos); e(339, 3, 1, 3)
        e(33550, 12, 3, scalePos)
        b.putInt(0)
        var pos = data
        b.position(offsetsPos); tiles.forEach { b.putInt(pos); pos += it.size }
        b.position(countsPos); tiles.forEach { b.putInt(it.size) }
        b.position(scalePos); b.putDouble(1.0).putDouble(1.0).putDouble(0.0)
        b.position(data); tiles.forEach { b.put(it) }
        val bytes = b.array()
        return object : RandomInput {
            override val size = bytes.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = System.arraycopy(bytes, position.toInt(), buffer, offset, length)
        }
    }

    private fun check(predictor: Int) {
        val r = TiffReader(tiff(predictor))
        assertEquals(listOf(w to h), r.levels())
        val v = r.readFloats(0, 5, 3, 20, 15)
        for (y in 0 until 15) for (x in 0 until 20) assertEquals(z(5 + x, 3 + y), v[y * 20 + x], "($x, $y)")
        // Outside the picture there are no values.
        val edge = r.readFloats(0, w - 2, h - 2, 4, 4)
        assertEquals(z(w - 1, h - 1), edge[1 * 4 + 1]); assertTrue(edge[3].isNaN())
    }

    @Test fun floatingPointPredictor() = check(3)
    @Test fun plainFloats() = check(1)
}
