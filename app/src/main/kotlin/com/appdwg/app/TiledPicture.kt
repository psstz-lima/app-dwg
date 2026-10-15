package com.appdwg.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appdwg.core.Bounds
import com.appdwg.core.Raster
import com.appdwg.core.TiffReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A very large picture (an orthophoto TIFF of gigabytes) shown in pieces: only the tiles on screen,
 * at the resolution the zoom needs. Close up, tiles are read straight from the file's own blocks
 * (JPEG blocks decode already reduced, which is cheap). From afar, tiles come from a pyramid of
 * reduced copies built once in the background and kept in the app's cache, so the next opening is
 * immediate. Tiles are [TILE] pixels square; level k shows one pixel for 2^k of the file's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TiledPicture private constructor(
    val name: String,
    /** Where the picture lies in plan coordinates. */
    val bounds: Bounds,
    private val fd: ParcelFileDescriptor,
    private val reader: TiffReader,
    private val dir: File,
) {
    companion object {
        const val TILE = 256
        /**
         * Cache folder for the coarse pyramid. The previous folder was built from JPEG tiles that
         * kept a corner instead of a real downsample, so those copies are left behind.
         */
        const val PYRAMID_DIR = "pyramid2"
        /** Levels up to this one are read from the file's blocks; coarser ones come from the pyramid. */
        private const val DIRECT = 3

        /** Opens [fd] as a tiled picture whose cache lives in [cacheRoot]/[key]; null when the file has no plan position. */
        fun open(name: String, fd: ParcelFileDescriptor, cacheRoot: File, key: String, placement: Bounds?): TiledPicture? {
            val channel = FileInputStream(fd.fileDescriptor).channel
            val input = object : com.appdwg.core.RandomInput {
                override val size = channel.size()
                override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                    val bb = ByteBuffer.wrap(buffer, offset, length)
                    var pos = position
                    while (bb.hasRemaining()) {
                        val n = channel.read(bb, pos)
                        if (n < 0) error(com.appdwg.core.tr("fim inesperado do arquivo"))
                        pos += n
                    }
                }
            }
            val reader = TiffReader(input, jpeg = { decodeJpeg(it, 1) }, jpegReduced = { bytes, k -> decodeJpeg(bytes, k) })
            val b = reader.blocks()
            val where = placement ?: b.extent ?: return null
            return TiledPicture(name, where, fd, reader, File(cacheRoot, key).apply { mkdirs() })
        }

        private fun decodeJpeg(bytes: ByteArray, reduce: Int): Raster? = CoilPictures.raster(bytes, reduce)
    }

    private val layout = reader.blocks()
    val width get() = layout.width
    val height get() = layout.height
    /** Coarsest level: the whole picture in one tile. */
    val topLevel: Int = run { var k = 0; while (max(width, height) shr k > TILE) k++; k }

    private fun tilesAcross(k: Int) = ceil((width shr k).coerceAtLeast(1) / TILE.toDouble()).toInt().coerceAtLeast(1)
    private fun tilesDown(k: Int) = ceil((height shr k).coerceAtLeast(1) / TILE.toDouble()).toInt().coerceAtLeast(1)

    /** Bumped as tiles arrive, so the plan redraws. */
    var version by mutableIntStateOf(0); private set
    /** Fraction of the pyramid built, 1 when done. */
    var built by mutableFloatStateOf(0f); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = Dispatchers.Default.limitedParallelism(4)
    /** Bitmap plus the Compose wrapper, created once so a pan does not allocate a wrapper per tile per frame. */
    private class Tile(val bitmap: Bitmap, val image: ImageBitmap)

    private val memory = object : LruCache<String, Tile>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Tile) = value.bitmap.byteCount
    }
    private val loading = HashSet<String>()
    /** JPEG pictures have no transparency: their tiles take half the memory as RGB 565. */
    private val config = if (layout.opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    private val done = File(dir, "done")
    // The file is read by one tile at a time: a channel serves positional reads from many threads.

    /** One piece to draw: the picture, and where it goes in plan coordinates. */
    class Piece(val image: ImageBitmap, val src: Rect, val plan: Bounds)

    /** Plan extent of tile ([k], [tx], [ty]). */
    private fun tileBounds(k: Int, tx: Int, ty: Int): Bounds {
        val px = bounds.width / width; val py = bounds.height / height
        val size = (TILE.toLong() shl k).toDouble()
        val x0 = bounds.minX + tx * size * px; val y1 = bounds.maxY - ty * size * py
        return Bounds(x0, max(bounds.minY, y1 - size * py), min(bounds.maxX, x0 + size * px), y1)
    }

    /**
     * Tiles covering [view] for a screen showing [metersPerPixel]. [quality] is 0 normal, 1 high,
     * 2 highest: Máxima uses the level whose pixel is about one screen pixel (and the file itself
     * once zoomed in); Alta and Normal stay one and two steps coarser. A tile that is not ready
     * yet is replaced by the nearest coarser one already in memory.
     */
    fun visible(view: Bounds, metersPerPixel: Double, quality: Int = 2): List<Piece> {
        val px = bounds.width / width
        val want = DrapeQuality.level(if (px > 0.0) metersPerPixel / px else Double.POSITIVE_INFINITY, quality, topLevel)
        val out = ArrayList<Piece>()
        val sizeX = bounds.width / width * TILE; val sizeY = bounds.height / height * TILE
        val x0 = floor((view.minX - bounds.minX) / (sizeX * (1 shl want))).toInt().coerceAtLeast(0)
        val x1 = floor((view.maxX - bounds.minX) / (sizeX * (1 shl want))).toInt().coerceAtMost(tilesAcross(want) - 1)
        val y0 = floor((bounds.maxY - view.maxY) / (sizeY * (1 shl want))).toInt().coerceAtLeast(0)
        val y1 = floor((bounds.maxY - view.minY) / (sizeY * (1 shl want))).toInt().coerceAtMost(tilesDown(want) - 1)
        if (x1 < x0 || y1 < y0 || (x1 - x0 + 1) * (y1 - y0 + 1) > 600) return out
        for (ty in y0..y1) for (tx in x0..x1) {
            val own = tile(want, tx, ty, load = true)
            if (own != null) { out += Piece(own.image, Rect(0, 0, own.bitmap.width, own.bitmap.height), tileBounds(want, tx, ty)); continue }
            // A coarser tile, cut to this tile's part of it.
            for (up in 1..6) {
                val k = want + up
                if (k > topLevel) break
                val b = tile(k, tx shr up, ty shr up, load = false) ?: continue
                val n = 1 shl up
                val cw = TILE / n; val ch = TILE / n
                val ox = (tx - ((tx shr up) shl up)) * cw; val oy = (ty - ((ty shr up) shl up)) * ch
                val src = Rect(ox, oy, min(ox + cw, b.bitmap.width), min(oy + ch, b.bitmap.height))
                if (src.width() > 0 && src.height() > 0) out += Piece(b.image, src, tileBounds(want, tx, ty))
                break
            }
        }
        return out
    }

    /** The tile from memory; otherwise starts loading it when [load] (from the file or the pyramid). */
    private fun tile(k: Int, tx: Int, ty: Int, load: Boolean): Tile? {
        val key = "$k/$tx/$ty"
        memory.get(key)?.let { return it }
        if (!load) return null
        synchronized(loading) { if (!loading.add(key)) return null }
        scope.launch(workers) {
            val bmp = runCatching {
                if (k <= DIRECT) direct(k, tx, ty)
                else File(dir, "$k-$tx-$ty.jpg").takeIf { it.exists() }?.let { BitmapDecode.decodeFile(it.path, config) }
            }.getOrNull()
            synchronized(loading) { loading -= key }
            if (bmp != null) { memory.put(key, Tile(bmp, bmp.asImageBitmap())); withContext(Dispatchers.Main) { version++ } }
        }
        return null
    }

    /** Tile ([k] ≤ [DIRECT]) put together from the file's blocks, each decoded reduced by 2^k. */
    private fun direct(k: Int, tx: Int, ty: Int): Bitmap? {
        val f = 1 shl k
        val x0 = tx * TILE * f; val y0 = ty * TILE * f
        if (x0 >= width || y0 >= height) return null
        val w = min(TILE, ceil((width - x0) / f.toDouble()).toInt()); val h = min(TILE, ceil((height - y0) / f.toDouble()).toInt())
        val bmp = createBitmap(w, h, config)
        val bw = layout.blockWidth; val bh = layout.blockHeight
        for (by in y0 / bh..min(layout.down - 1, (y0 + TILE * f - 1) / bh)) for (bx in x0 / bw..min(layout.across - 1, (x0 + TILE * f - 1) / bw)) {
            val r = reader.readBlock(by * layout.across + bx, f) ?: continue
            // Where this block's reduced pixels fall in the tile.
            val dx = (bx * bw - x0) / f; val dy = (by * bh - y0) / f
            val sx = max(0, -dx); val sy = max(0, -dy)
            val cw = min(r.width - sx, w - max(0, dx)); val ch = min(r.height - sy, h - max(0, dy))
            if (cw > 0 && ch > 0) bmp.setPixels(r.pixels, sy * r.width + sx, r.width, max(0, dx), max(0, dy), cw, ch)
        }
        return bmp
    }

    /** Whether the pyramid was built before (then every level is ready at once). */
    val ready get() = done.exists()

    /**
     * Builds the reduced levels, region by region (the coarse view fills in as it goes), saving each
     * tile; a build that was stopped carries on from what is on disk. [onDone] gets the overview.
     */
    fun buildPyramid(onDone: (Bitmap?) -> Unit) {
        scope.launch {
            if (!done.exists()) {
                val total = (DIRECT + 1..topLevel).sumOf { tilesAcross(it).toLong() * tilesDown(it) }.coerceAtLeast(1)
                var count = 0L
                suspend fun build(k: Int, tx: Int, ty: Int): Bitmap? {
                    if (tx >= tilesAcross(k) || ty >= tilesDown(k)) return null
                    val file = File(dir, "$k-$tx-$ty.jpg")
                    if (file.exists()) return BitmapDecode.decodeFile(file.path, config)
                    coroutineContext.ensureActive()
                    // The four finer tiles: read from the file in parallel at the bottom, one after the
                    // other above it, so only a handful of tiles are held at a time.
                    val quads = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
                    val children = if (k - 1 <= DIRECT) coroutineScope {
                        quads.map { (dx, dy) -> async(workers) { direct(k - 1, 2 * tx + dx, 2 * ty + dy) } }.awaitAll()
                    } else quads.map { (dx, dy) -> build(k - 1, 2 * tx + dx, 2 * ty + dy) }
                    val w = min(TILE, ceil((width shr k).coerceAtLeast(1).toDouble() - tx * TILE).toInt().coerceAtLeast(1))
                    val h = min(TILE, ceil((height shr k).coerceAtLeast(1).toDouble() - ty * TILE).toInt().coerceAtLeast(1))
                    val bmp = createBitmap(w, h, config)
                    val c = Canvas(bmp); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    children.forEachIndexed { i, child ->
                        child ?: return@forEachIndexed
                        val left = (i % 2) * TILE / 2; val top = (i / 2) * TILE / 2
                        c.drawBitmap(child, Rect(0, 0, child.width, child.height), Rect(left, top, left + (child.width + 1) / 2, top + (child.height + 1) / 2), paint)
                        // direct() tiles are not cached. Coarser ones are stored by their own build() call.
                        if (k - 1 <= DIRECT) child.recycle()
                    }
                    val tmp = File(dir, "$k-$tx-$ty.tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    tmp.renameTo(file)
                    memory.put("$k/$tx/$ty", Tile(bmp, bmp.asImageBitmap()))
                    count++
                    if (count % 8 == 0L) withContext(Dispatchers.Main) { built = (count.toFloat() / total).coerceAtMost(0.99f); version++ }
                    return bmp
                }
                build(topLevel, 0, 0)
                done.writeText("1")
            }
            val overview = overview()
            withContext(Dispatchers.Main) { built = 1f; version++; onDone(overview) }
        }
    }

    /** The whole picture at most [maxSize] pixels across, from the pyramid, for the 3D view. */
    fun overview(maxSize: Int = 2048): Bitmap? {
        var k = topLevel
        while (k > DIRECT + 1 && max(width shr (k - 1), height shr (k - 1)) <= maxSize) k--
        val w = (width shr k).coerceAtLeast(1); val h = (height shr k).coerceAtLeast(1)
        val bmp = createBitmap(w, h, config)
        val c = Canvas(bmp)
        for (ty in 0 until tilesDown(k)) for (tx in 0 until tilesAcross(k)) {
            val f = File(dir, "$k-$tx-$ty.jpg")
            val t = BitmapDecode.decodeFile(f.path, config) ?: continue
            c.drawBitmap(t, (tx * TILE).toFloat(), (ty * TILE).toFloat(), null)
            t.recycle()
        }
        return bmp
    }

    /**
     * The part of the picture over [area] at the finest level with at most [maxPixels] pixels and
     * [maxSide] across, and its plan extent; for the 3D view, which only needs the picture under
     * the surfaces. Null when it does not touch [area], or when the level it needs is in the
     * pyramid and that is not built yet.
     */
    fun region(area: Bounds, maxPixels: Long, maxSide: Int): Pair<Bitmap, Bounds>? {
        val px = bounds.width / width; val py = bounds.height / height
        val c0 = floor((area.minX - bounds.minX) / px).toInt().coerceIn(0, width)
        val c1 = ceil((area.maxX - bounds.minX) / px).toInt().coerceIn(0, width)
        val r0 = floor((bounds.maxY - area.maxY) / py).toInt().coerceIn(0, height)
        val r1 = ceil((bounds.maxY - area.minY) / py).toInt().coerceIn(0, height)
        if (c1 <= c0 || r1 <= r0) return null
        var k = 0
        fun w(k: Int) = ((c1 - 1) shr k) - (c0 shr k) + 1
        fun h(k: Int) = ((r1 - 1) shr k) - (r0 shr k) + 1
        while (k < topLevel && (w(k).toLong() * h(k) > maxPixels || max(w(k), h(k)) > maxSide)) k++
        if (k > DIRECT && !ready) return null
        val x0 = c0 shr k; val y0 = r0 shr k; val wk = w(k); val hk = h(k)
        val bmp = createBitmap(wk, hk, config)
        val canvas = Canvas(bmp)
        for (ty in y0 / TILE..(y0 + hk - 1) / TILE) for (tx in x0 / TILE..(x0 + wk - 1) / TILE) {
            val cached = memory.get("$k/$tx/$ty")?.bitmap
            val t = cached ?: runCatching {
                if (k <= DIRECT) direct(k, tx, ty)
                else File(dir, "$k-$tx-$ty.jpg").takeIf { it.exists() }?.let { BitmapDecode.decodeFile(it.path, config) }
            }.getOrNull() ?: continue
            canvas.drawBitmap(t, (tx * TILE - x0).toFloat(), (ty * TILE - y0).toFloat(), null)
            // Tiles decoded only for this composite are not the ones the plan is drawing.
            if (cached == null) t.recycle()
        }
        val plan = Bounds(
            bounds.minX + (x0.toLong() shl k) * px, bounds.maxY - ((y0 + hk).toLong() shl k) * py,
            bounds.minX + ((x0 + wk).toLong() shl k) * px, bounds.maxY - (y0.toLong() shl k) * py,
        )
        return bmp to plan
    }

    /**
     * Each GPU tile of the picture over [area] at pyramid level [k] (0 is native), no side longer
     * than [tileSide]. One bitmap exists at a time; it is recycled after [accept] returns. False
     * when a bitmap cannot be allocated. Levels 0, 1 and 2 are read from the file, not the pyramid.
     */
    fun eachGpuTile(area: Bounds, k: Int, tileSide: Int, accept: (Bitmap, Bounds) -> Unit): Boolean {
        val level = k.coerceIn(0, topLevel)
        val side = tileSide.coerceAtLeast(1)
        val px = bounds.width / width
        val py = bounds.height / height
        if (px <= 0.0 || py <= 0.0 || width <= 0 || height <= 0) return true
        val c0 = floor((area.minX - bounds.minX) / px).toInt().coerceIn(0, width)
        val c1 = ceil((area.maxX - bounds.minX) / px).toInt().coerceIn(0, width)
        val r0 = floor((bounds.maxY - area.maxY) / py).toInt().coerceIn(0, height)
        val r1 = ceil((bounds.maxY - area.minY) / py).toInt().coerceIn(0, height)
        if (c1 <= c0 || r1 <= r0) return true
        fun spanW(n: Int) = ((c1 - 1) shr n) - (c0 shr n) + 1
        fun spanH(n: Int) = ((r1 - 1) shr n) - (r0 shr n) + 1
        val x0 = c0 shr level
        val y0 = r0 shr level
        val wk = spanW(level)
        val hk = spanH(level)
        if (wk <= 0 || hk <= 0) return true
        var oy = 0
        while (oy < hk) {
            val row = min(side, hk - oy)
            var ox = 0
            while (ox < wk) {
                val col = min(side, wk - ox)
                val bmp = try {
                    createBitmap(col, row, config)
                } catch (_: OutOfMemoryError) {
                    return false
                }
                try {
                    val canvas = Canvas(bmp)
                    val originX = x0 + ox
                    val originY = y0 + oy
                    val tx0 = originX / TILE
                    val tx1 = (originX + col - 1) / TILE
                    val ty0 = originY / TILE
                    val ty1 = (originY + row - 1) / TILE
                    for (ty in ty0..ty1) for (tx in tx0..tx1) {
                        val t = runCatching {
                            if (level <= DIRECT) direct(level, tx, ty)
                            else File(dir, "$level-$tx-$ty.jpg").takeIf { it.exists() }?.let { BitmapDecode.decodeFile(it.path, config) }
                        }.getOrNull() ?: continue
                        try {
                            canvas.drawBitmap(t, (tx * TILE - originX).toFloat(), (ty * TILE - originY).toFloat(), null)
                        } finally {
                            if (!t.isRecycled) t.recycle()
                        }
                    }
                    val fileX = originX.toLong() shl level
                    val fileY = originY.toLong() shl level
                    val plan = Bounds(
                        bounds.minX + fileX * px,
                        bounds.maxY - (fileY + (row.toLong() shl level)) * py,
                        bounds.minX + (fileX + (col.toLong() shl level)) * px,
                        bounds.maxY - fileY * py,
                    )
                    accept(bmp, plan)
                } catch (_: OutOfMemoryError) {
                    return false
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
                if (ox + col >= wk) break
                ox += (col - GlTexture.OVERLAP).coerceAtLeast(1)
            }
            if (oy + row >= hk) break
            oy += (row - GlTexture.OVERLAP).coerceAtLeast(1)
        }
        return true
    }

    fun close() {
        scope.cancel()
        memory.evictAll()
        runCatching { fd.close() }
    }
}
