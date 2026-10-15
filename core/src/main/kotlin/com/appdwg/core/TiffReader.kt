package com.appdwg.core

import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/** Byte source that can be read at any position, such as a file. */
interface RandomInput {
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

/**
 * Decoded picture as ARGB pixels, row by row from the top. [extent] is where the picture lies in plan
 * coordinates when the file says so (GeoTIFF), from the outer edge of its corner pixels.
 */
class Raster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val extent: Bounds? = null,
    /** Size of the full picture in the file, before downsampling, which world files refer to. */
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
)

/**
 * TIFF and BigTIFF reader for orthophotos and other rasters, written for the app so it needs no
 * native library. It reads 8- and 16-bit gray, palette, RGB and RGBA images in strips or tiles,
 * uncompressed or compressed with LZW, Deflate, PackBits or JPEG (JPEG through [jpeg], which the
 * platform provides), with or without the horizontal predictor.
 *
 * Large images are downsampled while they are read so the result fits in [maxSize] pixels per side;
 * when the file has overviews (reduced-resolution copies, as GDAL writes them) the smallest one that
 * is still large enough is used.
 */
class TiffReader(
    private val input: RandomInput,
    /** Decodes one JPEG stream to pixels; null when JPEG is not available. */
    private val jpeg: ((ByteArray) -> Raster?)? = null,
    /** Decodes one JPEG stream reduced by a power of two (cheap in JPEG); falls back to [jpeg]. */
    private val jpegReduced: ((ByteArray, Int) -> Raster?)? = null,
) {
    private var onProgress: (Float) -> Unit = {}
    private var little = true
    private var big = false

    /** [onProgress] gets the fraction of image blocks decoded so far. */
    fun read(maxSize: Int, onProgress: (Float) -> Unit = {}): Raster {
        this.onProgress = onProgress
        val images = images()
        val full = images.firstOrNull() ?: error(tr("TIFF sem imagem"))
        // Overviews keep the aspect of the full image; masks (subfile type 4) are skipped.
        val candidates = images.filter { it.subfileType and 4 == 0 && it.width > 0 && it.height > 0 }
            .filter { abs(it.width.toDouble() / it.height - full.width.toDouble() / full.height) < 0.02 }
            .sortedBy { it.width }
        val chosen = candidates.firstOrNull { max(it.width, it.height) >= maxSize } ?: candidates.lastOrNull() ?: full
        val raster = decode(chosen, maxSize)
        return Raster(raster.width, raster.height, raster.pixels, geoExtent(full), full.width, full.height)
    }

    /** How the full-resolution picture is cut into blocks (tiles or strips), for reading it piece by piece. */
    class Blocks internal constructor(
        val width: Int,
        val height: Int,
        val blockWidth: Int,
        val blockHeight: Int,
        /** Plan extent from the GeoTIFF tags, or null. */
        val extent: Bounds?,
        internal val ifd: Any,
    ) {
        /** True when the blocks are JPEG, which has no transparency. */
        val opaque get() = (ifd as Ifd).int(259, 1).toInt() == 7
        val across get() = (width + blockWidth - 1) / blockWidth
        val down get() = (height + blockHeight - 1) / blockHeight
        val count get() = across * down
    }

    private var opened: Blocks? = null

    /** The full-resolution picture's layout. */
    fun blocks(): Blocks = opened ?: run {
        val full = images().firstOrNull() ?: error(tr("TIFF sem imagem"))
        val tiled = full.tags.containsKey(322)
        val bw = if (tiled) full.int(322).toInt() else full.width
        val bh = if (tiled) full.int(323).toInt() else min(full.height, full.int(278, full.height.toLong()).toInt().coerceAtLeast(1))
        Blocks(full.width, full.height, bw, bh, geoExtent(full), full).also { opened = it }
    }

    /**
     * Block [index] of the full-resolution picture (row by row from the top left), reduced by
     * [reduce] (1, 2, 4, 8…): one pixel out of [reduce] along each side. Null when the block is empty.
     */
    fun readBlock(index: Int, reduce: Int = 1): Raster? {
        val b = blocks()
        val ifd = b.ifd as Ifd
        val samples = ifd.int(277, 1).toInt()
        val bits = ifd.tags[258]?.first()?.toInt() ?: 1
        val compression = ifd.int(259, 1).toInt()
        val photometric = ifd.int(262, if (samples >= 3) 2 else 1).toInt()
        val predictor = ifd.int(317, 1).toInt()
        require(bits == 8 || bits == 16) { tr("TIFF com {0} bits por amostra não é suportado", bits) }
        require(compression in SUPPORTED) { tr("compressão TIFF {0} não é suportada", compression) }
        val tiled = ifd.tags.containsKey(322)
        val offsets = ifd.tags[if (tiled) 324 else 273] ?: error(tr("TIFF sem dados"))
        val counts = ifd.tags[if (tiled) 325 else 279] ?: error(tr("TIFF sem dados"))
        if (index !in offsets.indices) return null
        val bx = (index % b.across) * b.blockWidth
        val by = (index / b.across) * b.blockHeight
        val cols = min(b.blockWidth, b.width - bx)
        val rows = min(b.blockHeight, b.height - by)
        if (cols <= 0 || rows <= 0) return null
        val length = counts.getOrElse(index) { 0L }.toInt()
        if (length <= 0) return null
        val data = bytes(offsets[index], length)
        val outW = (cols + reduce - 1) / reduce
        val outH = (rows + reduce - 1) / reduce
        if (compression == 7) {
            val jpegTables = ifd.raw[347]?.let { (pos, len) -> bytes(pos, len.toInt()) }
            val stream = if (jpegTables != null && jpegTables.size > 4) jpegTables.copyOfRange(0, jpegTables.size - 2) + data.copyOfRange(2, data.size) else data
            val tile = jpegReduced?.invoke(stream, reduce) ?: (jpeg ?: error(tr("JPEG em TIFF não disponível"))).invoke(stream)?.let { reduceRaster(it, reduce) } ?: return null
            // The decoder works on the whole block; edge blocks are cut to the picture.
            val out = IntArray(outW * outH)
            for (y in 0 until min(outH, tile.height)) System.arraycopy(tile.pixels, y * tile.width, out, y * outW, min(outW, tile.width))
            return Raster(outW, outH, out)
        }
        val bytesPerSample = bits / 8
        val pixelBytes = samples * bytesPerSample
        val rowBytes = b.blockWidth * pixelBytes
        val expected = rowBytes * (if (tiled) b.blockHeight else rows)
        val pixels = when (compression) {
            1 -> data
            5 -> lzw(data, expected)
            8, 32946 -> inflate(data, expected)
            32773 -> packBits(data, expected)
            else -> error(tr("compressão TIFF {0} não é suportada", compression))
        }
        if (predictor == 2) undoPredictor(pixels, rowBytes, samples, bytesPerSample, if (tiled) b.blockHeight else rows)
        val colormap = ifd.tags[320]
        val out = IntArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            val p = (y * reduce) * rowBytes + (x * reduce) * pixelBytes
            if (p + pixelBytes <= pixels.size) out[y * outW + x] = argb(pixels, p, samples, bytesPerSample, photometric, colormap)
        }
        return Raster(outW, outH, out)
    }

    /** Sizes of the full picture and its overviews, in file order: level 0 is the full picture. */
    fun levels(): List<Pair<Int, Int>> = images().filter { it.subfileType and 4 == 0 }.map { it.width to it.height }

    /**
     * Values of a one-band float image (a DEM) in the window ([x0], [y0]) of [w] x [h] pixels of
     * [level] (see [levels]), row by row from the top; NaN outside the picture. Reads only the
     * blocks the window touches. Takes 32-bit floats, plain or with the floating-point predictor.
     */
    fun readFloats(level: Int, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        val ifd = images().filter { it.subfileType and 4 == 0 }.getOrNull(level) ?: error(tr("TIFF sem imagem"))
        val bits = ifd.tags[258]?.first()?.toInt() ?: 1
        val format = ifd.int(339, 1).toInt()
        val samples = ifd.int(277, 1).toInt()
        val compression = ifd.int(259, 1).toInt()
        val predictor = ifd.int(317, 1).toInt()
        require(bits == 32 && format == 3 && samples == 1) { tr("TIFF não é uma grade de cotas (float de 32 bits)") }
        require(compression == 1 || compression == 8 || compression == 32946 || compression == 5) { tr("compressão TIFF {0} não é suportada", compression) }
        val tiled = ifd.tags.containsKey(322)
        val width = ifd.width; val height = ifd.height
        val bw = if (tiled) ifd.int(322).toInt() else width
        val bh = if (tiled) ifd.int(323).toInt() else min(height, ifd.int(278, height.toLong()).toInt().coerceAtLeast(1))
        val offsets = ifd.tags[if (tiled) 324 else 273] ?: error(tr("TIFF sem dados"))
        val counts = ifd.tags[if (tiled) 325 else 279] ?: error(tr("TIFF sem dados"))
        val across = (width + bw - 1) / bw
        val out = FloatArray(w * h) { Float.NaN }
        val bx0 = max(0, x0) / bw; val bx1 = min(width - 1, x0 + w - 1) / bw
        val by0 = max(0, y0) / bh; val by1 = min(height - 1, y0 + h - 1) / bh
        if (x0 + w <= 0 || y0 + h <= 0 || x0 >= width || y0 >= height) return out
        for (by in by0..by1) for (bx in bx0..bx1) {
            val index = by * across + bx
            val length = counts.getOrElse(index) { 0L }.toInt()
            if (length <= 0) continue
            val rows = if (tiled) bh else min(bh, height - by * bh)
            val expected = bw * 4 * rows
            val data = bytes(offsets[index], length)
            val raw = when (compression) {
                1 -> data
                5 -> lzw(data, expected)
                else -> inflate(data, expected)
            }
            val values = FloatArray(bw * rows)
            if (predictor == 3) {
                // Floating-point predictor: per row, bytes differenced, then stored as byte planes (most significant first).
                val rowBytes = bw * 4
                for (r in 0 until rows) {
                    val start = r * rowBytes
                    if (start + rowBytes > raw.size) break
                    for (i in start + 1 until start + rowBytes) raw[i] = (raw[i] + raw[i - 1]).toByte()
                    for (x in 0 until bw) {
                        val b0 = raw[start + x].toInt() and 0xFF; val b1 = raw[start + bw + x].toInt() and 0xFF
                        val b2 = raw[start + 2 * bw + x].toInt() and 0xFF; val b3 = raw[start + 3 * bw + x].toInt() and 0xFF
                        values[r * bw + x] = Float.fromBits((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3)
                    }
                }
            } else {
                for (i in values.indices) {
                    val p = i * 4
                    if (p + 4 > raw.size) break
                    values[i] = Float.fromBits(u32(raw, p).toInt())
                }
            }
            for (r in 0 until rows) {
                val y = by * bh + r - y0
                if (y !in 0 until h) continue
                for (c in 0 until bw) {
                    val x = bx * bw + c - x0
                    if (x !in 0 until w || bx * bw + c >= width) continue
                    out[y * w + x] = values[r * bw + c]
                }
            }
        }
        return out
    }

    /** Plan extent of the full picture from its GeoTIFF tags (the outer edge of its corner pixels). */
    fun extent(): Bounds? = images().firstOrNull()?.let { geoExtent(it) }

    private fun reduceRaster(r: Raster, k: Int): Raster {
        if (k <= 1) return r
        val w = (r.width + k - 1) / k; val h = (r.height + k - 1) / k
        return Raster(w, h, IntArray(w * h) { i -> r.pixels[(i / w) * k * r.width + (i % w) * k] })
    }

    /** Reads the header and every image directory: the full picture first, then overviews. */
    private fun images(): List<Ifd> {
        val head = bytes(0, 16)
        little = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
            else -> error(tr("não é um arquivo TIFF"))
        }
        big = when (u16(head, 2)) {
            42 -> false
            43 -> true
            else -> error(tr("não é um arquivo TIFF"))
        }
        var next = if (big) i64(head, 8) else u32(head, 4)
        val images = ArrayList<Ifd>()
        val seen = HashSet<Long>()
        while (next != 0L && next < input.size && seen.add(next) && images.size < 64) {
            val ifd = readIfd(next)
            images += ifd.first
            next = ifd.second
        }
        return images
    }

    /**
     * Plan extent of a GeoTIFF from its pixel scale and tie point, or from a transformation matrix
     * without rotation. Null when the file has neither, or the picture is rotated.
     */
    private fun geoExtent(ifd: Ifd): Bounds? {
        val w = ifd.width.toDouble()
        val h = ifd.height.toDouble()
        val scale = ifd.doubles(TAG_PIXEL_SCALE)
        val tie = ifd.doubles(TAG_TIEPOINT)
        val (minX, maxY, sx, sy) = when {
            scale != null && scale.size >= 2 && tie != null && tie.size >= 6 ->
                listOf(tie[3] - tie[0] * scale[0], tie[4] + tie[1] * scale[1], scale[0], scale[1])
            else -> {
                val m = ifd.doubles(TAG_TRANSFORMATION)?.takeIf { it.size >= 16 } ?: return null
                if (m[1] != 0.0 || m[4] != 0.0) return null
                listOf(m[3], m[7], m[0], -m[5])
            }
        }
        if (!(sx > 0 && sy > 0) || minX.isNaN() || maxY.isNaN()) return null
        return Bounds(minX, maxY - h * sy, minX + w * sx, maxY)
    }

    private class Ifd(val tags: Map<Int, LongArray>, val raw: Map<Int, Pair<Long, Long>>) {
        fun int(tag: Int, default: Long = 0) = tags[tag]?.firstOrNull() ?: default
        val width get() = int(256).toInt()
        val height get() = int(257).toInt()
        val subfileType get() = int(254).toInt()
        /** Values of a DOUBLE tag, which are kept as their raw bits. */
        fun doubles(tag: Int) = tags[tag]?.let { v -> DoubleArray(v.size) { Double.fromBits(v[it]) } }
    }

    /** Reads one IFD; returns it and the offset of the next one. */
    private fun readIfd(offset: Long): Pair<Ifd, Long> {
        val countSize = if (big) 8 else 2
        val entrySize = if (big) 20 else 12
        val countBytes = bytes(offset, countSize)
        val count = if (big) i64(countBytes, 0) else u16(countBytes, 0).toLong()
        require(count in 0..4096) { tr("TIFF inválido") }
        val table = bytes(offset + countSize, (count * entrySize).toInt() + (if (big) 8 else 4))
        val tags = HashMap<Int, LongArray>()
        val raw = HashMap<Int, Pair<Long, Long>>()
        for (k in 0 until count.toInt()) {
            val p = k * entrySize
            val tag = u16(table, p)
            val type = u16(table, p + 2)
            val n = if (big) i64(table, p + 4) else u32(table, p + 4)
            val size = TYPE_SIZE.getOrElse(type) { 0 }
            if (size == 0 || n <= 0) continue
            val total = n * size
            val inline = if (big) 8 else 4
            val valueOffset = p + (if (big) 12 else 8)
            val data: ByteArray
            val dataPos: Long
            if (total <= inline) {
                data = table.copyOfRange(valueOffset, valueOffset + total.toInt())
                dataPos = -1
            } else {
                dataPos = if (big) i64(table, valueOffset) else u32(table, valueOffset)
                // Big arrays (strip and tile offsets) are read in full; byte blobs only by position.
                data = if (type == 7 || type == 1 && total > 65536) ByteArray(0) else bytes(dataPos, total.toInt())
            }
            raw[tag] = (if (dataPos >= 0) dataPos else offset + countSize + valueOffset) to total
            if (type == 7 || data.isEmpty()) continue
            tags[tag] = LongArray(n.toInt()) { i ->
                when (type) {
                    1, 2, 6 -> (data[i].toLong() and 0xFF)
                    3, 8 -> u16(data, i * 2).toLong()
                    4, 9 -> u32(data, i * 4)
                    12, 16, 17, 18 -> i64(data, i * 8)
                    5, 10 -> u32(data, i * 8)
                    else -> 0L
                }
            }
        }
        val nextPos = (count * entrySize).toInt()
        val next = if (big) i64(table, nextPos) else u32(table, nextPos)
        return Ifd(tags, raw) to next
    }

    private fun decode(ifd: Ifd, maxSize: Int): Raster {
        val width = ifd.width
        val height = ifd.height
        val samples = ifd.int(277, 1).toInt()
        val bits = ifd.tags[258]?.first()?.toInt() ?: 1
        val compression = ifd.int(259, 1).toInt()
        val photometric = ifd.int(262, if (samples >= 3) 2 else 1).toInt()
        val planar = ifd.int(284, 1).toInt()
        val predictor = ifd.int(317, 1).toInt()
        val sampleFormat = ifd.int(339, 1).toInt()
        require(bits == 8 || bits == 16) { tr("TIFF com {0} bits por amostra não é suportado", bits) }
        require(sampleFormat == 1) { tr("TIFF com amostras em ponto flutuante não é suportado") }
        require(planar == 1 || samples == 1) { tr("TIFF com planos de cor separados não é suportado") }
        require(compression in SUPPORTED) { tr("compressão TIFF {0} não é suportada", compression) }
        require(predictor == 1 || predictor == 2) { tr("preditor TIFF {0} não é suportado", predictor) }

        val tiled = ifd.tags.containsKey(322)
        val blockW = if (tiled) ifd.int(322).toInt() else width
        val blockH = if (tiled) ifd.int(323).toInt() else min(height, ifd.int(278, height.toLong()).toInt().coerceAtLeast(1))
        val offsets = ifd.tags[if (tiled) 324 else 273] ?: error(tr("TIFF sem dados"))
        val counts = ifd.tags[if (tiled) 325 else 279] ?: error(tr("TIFF sem dados"))
        val across = (width + blockW - 1) / blockW

        val step = max(1, (max(width, height) + maxSize - 1) / maxSize)
        val outW = (width + step - 1) / step
        val outH = (height + step - 1) / step
        val out = IntArray(outW * outH)

        val bytesPerSample = bits / 8
        val pixelBytes = samples * bytesPerSample
        val colormap = ifd.tags[320]
        val jpegTables = ifd.raw[347]?.let { (pos, len) -> bytes(pos, len.toInt()) }

        // Blocks are independent and each writes its own output pixels: they are decoded on all cores.
        val done = java.util.concurrent.atomic.AtomicInteger()
        fun block(b: Int) {
            val bx = (b % across) * blockW
            val by = (b / across) * blockH
            if (by >= height) return
            val rows = min(blockH, height - by)
            // Skips blocks that hold no sampled row or column.
            val firstRow = ((by + step - 1) / step) * step
            if (firstRow >= by + rows) return
            val firstCol = ((bx + step - 1) / step) * step
            if (firstCol >= min(bx + blockW, width)) return
            val length = counts.getOrElse(b) { 0L }.toInt()
            if (length <= 0) return
            val data = bytes(offsets[b], length)

            if (compression == 7) {
                val decoder = jpeg ?: error(tr("JPEG em TIFF não disponível"))
                val stream = if (jpegTables != null && jpegTables.size > 4) {
                    // Abbreviated stream: shared tables (without EOI) followed by the block (without SOI).
                    jpegTables.copyOfRange(0, jpegTables.size - 2) + data.copyOfRange(2, data.size)
                } else data
                val tile = decoder(stream) ?: return
                var y = firstRow
                while (y < by + rows) {
                    val ty = y - by
                    if (ty < tile.height) {
                        var x = firstCol
                        while (x < min(bx + blockW, width)) {
                            val tx = x - bx
                            if (tx < tile.width) out[(y / step) * outW + x / step] = tile.pixels[ty * tile.width + tx]
                            x += step
                        }
                    }
                    y += step
                }
                return
            }

            val rowBytes = blockW * pixelBytes
            val expected = rowBytes * (if (tiled) blockH else rows)
            val pixels = when (compression) {
                1 -> data
                5 -> lzw(data, expected)
                8, 32946 -> inflate(data, expected)
                32773 -> packBits(data, expected)
                else -> error(tr("compressão TIFF {0} não é suportada", compression))
            }
            if (predictor == 2) undoPredictor(pixels, rowBytes, samples, bytesPerSample, if (tiled) blockH else rows)

            var y = firstRow
            while (y < by + rows) {
                val rowStart = (y - by) * rowBytes
                var x = firstCol
                while (x < min(bx + blockW, width)) {
                    val p = rowStart + (x - bx) * pixelBytes
                    if (p + pixelBytes <= pixels.size) {
                        out[(y / step) * outW + x / step] = argb(pixels, p, samples, bytesPerSample, photometric, colormap)
                    }
                    x += step
                }
                y += step
            }
        }
        Parallel.forChunks(offsets.size, 1) { from, until ->
            for (b in from until until) {
                block(b)
                val n = done.incrementAndGet()
                if (n % 64 == 0) onProgress(n.toFloat() / offsets.size)
            }
            true
        }
        return Raster(outW, outH, out)
    }

    private fun sample(buf: ByteArray, p: Int, bytesPerSample: Int): Int =
        if (bytesPerSample == 1) buf[p].toInt() and 0xFF
        else if (little) buf[p + 1].toInt() and 0xFF else buf[p].toInt() and 0xFF // high byte of 16 bits

    private fun argb(buf: ByteArray, p: Int, samples: Int, bps: Int, photometric: Int, colormap: LongArray?): Int {
        return when {
            photometric == 3 && colormap != null -> {
                // Palette entries are 16-bit; red, green and blue tables follow each other.
                val i = if (bps == 1) buf[p].toInt() and 0xFF else u16(buf, p)
                val n = colormap.size / 3
                if (i >= n) 0 else {
                    val r = (colormap[i] shr 8).toInt(); val g = (colormap[n + i] shr 8).toInt(); val b = (colormap[2 * n + i] shr 8).toInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            samples >= 3 -> {
                val r = sample(buf, p, bps); val g = sample(buf, p + bps, bps); val b = sample(buf, p + 2 * bps, bps)
                val a = if (samples >= 4) sample(buf, p + 3 * bps, bps) else 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                var v = sample(buf, p, bps)
                if (photometric == 0) v = 255 - v
                val a = if (samples >= 2) sample(buf, p + bps, bps) else 0xFF
                (a shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    private fun undoPredictor(buf: ByteArray, rowBytes: Int, samples: Int, bps: Int, rows: Int) {
        for (r in 0 until rows) {
            val start = r * rowBytes
            if (start + rowBytes > buf.size) return
            if (bps == 1) {
                for (i in start + samples until start + rowBytes) buf[i] = (buf[i] + buf[i - samples]).toByte()
            } else {
                val stride = samples * 2
                var i = start + stride
                while (i + 1 < start + rowBytes) {
                    val v = (u16(buf, i) + u16(buf, i - stride)) and 0xFFFF
                    if (little) { buf[i] = v.toByte(); buf[i + 1] = (v shr 8).toByte() }
                    else { buf[i] = (v shr 8).toByte(); buf[i + 1] = v.toByte() }
                    i += 2
                }
            }
        }
    }

    // ---- Byte access ----

    private fun bytes(position: Long, length: Int): ByteArray {
        require(position >= 0 && length >= 0 && position + length <= input.size) { tr("TIFF truncado") }
        return ByteArray(length).also { input.read(position, it) }
    }

    private fun u16(b: ByteArray, p: Int) =
        if (little) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, p: Int): Long =
        if (little) (u16(b, p).toLong()) or (u16(b, p + 2).toLong() shl 16)
        else (u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong()

    private fun i64(b: ByteArray, p: Int): Long =
        if (little) u32(b, p) or (u32(b, p + 4) shl 32) else (u32(b, p) shl 32) or u32(b, p + 4)

    private fun abs(v: Double) = if (v < 0) -v else v

    companion object {
        private const val TAG_PIXEL_SCALE = 33550
        private const val TAG_TIEPOINT = 33922
        private const val TAG_TRANSFORMATION = 34264

        private val SUPPORTED = setOf(1, 5, 7, 8, 32946, 32773)

        /** Bytes per value of each TIFF field type. */
        private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 0, 0, 8, 8, 8)

        /** TIFF LZW (most significant bit first, code width grows one code early). */
        internal fun lzw(src: ByteArray, expected: Int): ByteArray {
            val out = ByteArray(expected)
            val prefix = IntArray(4096)
            val suffix = ByteArray(4096)
            val length = IntArray(4096)
            val first = ByteArray(4096)
            for (i in 0 until 256) { suffix[i] = i.toByte(); first[i] = i.toByte(); length[i] = 1 }
            var op = 0
            var bitPos = 0L
            val totalBits = src.size * 8L
            var width = 9
            var next = 258
            var old = -1

            fun readCode(): Int {
                if (bitPos + width > totalBits) return 257
                var v = 0
                for (k in 0 until width) {
                    val bit = (src[((bitPos + k) shr 3).toInt()].toInt() shr (7 - ((bitPos + k) and 7).toInt())) and 1
                    v = (v shl 1) or bit
                }
                bitPos += width
                return v
            }

            fun write(code: Int) {
                val n = length[code]
                var c = code
                var i = op + n - 1
                while (c >= 0 && i >= op) {
                    if (i < expected) out[i] = suffix[c]
                    i--
                    c = if (length[c] > 1) prefix[c] else -1
                }
                op += n
            }

            while (op < expected) {
                val code = readCode()
                if (code == 257) break
                if (code == 256) {
                    width = 9
                    next = 258
                    old = -1
                    continue
                }
                if (old == -1) {
                    if (code > 255) break
                    write(code)
                    old = code
                    continue
                }
                if (next < 4096) {
                    prefix[next] = old
                    length[next] = length[old] + 1
                    first[next] = first[old]
                    suffix[next] = if (code < next) first[code] else first[old]
                }
                if (code > next) break
                write(code)
                if (next < 4096) next++
                if (next >= (1 shl width) - 1 && width < 12) width++
                old = code
            }
            return out
        }

        internal fun inflate(src: ByteArray, expected: Int): ByteArray {
            val out = ByteArray(expected)
            val inflater = Inflater()
            try {
                inflater.setInput(src)
                var n = 0
                while (n < expected && !inflater.finished()) {
                    val r = inflater.inflate(out, n, expected - n)
                    if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += r
                }
            } finally {
                inflater.end()
            }
            return out
        }

        internal fun packBits(src: ByteArray, expected: Int): ByteArray {
            val out = ByteArray(expected)
            var i = 0
            var o = 0
            while (i < src.size && o < expected) {
                val n = src[i++].toInt()
                when {
                    n >= 0 -> { val c = min(n + 1, min(expected - o, src.size - i)); System.arraycopy(src, i, out, o, c); i += n + 1; o += c }
                    n != -128 -> { val c = min(1 - n, expected - o); if (i < src.size) out.fill(src[i], o, o + c); i++; o += c }
                }
            }
            return out
        }
    }
}
