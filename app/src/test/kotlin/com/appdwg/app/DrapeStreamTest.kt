package com.appdwg.app

import com.appdwg.core.RandomInput
import com.appdwg.core.TiffReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D drape has to show the overview on the first frame, then refine without rereading the
 * whole TIFF or rebuilding the tile set every frame.
 */
class DrapeStreamTest {
    @Test
    fun theFirstFrameHasADrapeAndTheCoverConvergesWithoutThrash() {
        val budget = DrapeLod.budgetBytes(4L shl 30, 192)
        val cover = DrapeLod.cover(20_000, 15_000, topLevel = 7, tileSide = DrapeLod.STREAM_TILE)
        val covered = DrapeLod.settle(cover, budget, ::bytesOf, slots = DrapeLod.DECODES_IN_FLIGHT)
        assertTrue(covered.hasDrapeOnFirst)
        assertTrue(covered.converged)
        assertTrue(covered.readded.isEmpty())
        assertTrue(covered.steps <= cover.size + 2)

        val plan = ortho(budget, finest = 0)
        val settled = DrapeLod.settle(plan.tiles, budget, ::bytesOf, slots = DrapeLod.DECODES_IN_FLIGHT)
        assertTrue(settled.hasDrapeOnFirst)
        assertTrue(settled.converged)
        assertTrue(settled.readded.isEmpty())
        assertTrue(settled.steps <= plan.tiles.size + 2)
        // Nothing is resident before the first step, and the base is still a drape.
        val first = DrapeLod.tick(baseReady = true, residents = emptySet(), plan = plan.tiles, queued = emptySet(), slots = 2)
        assertTrue(first.hasDrape)
        assertTrue(first.refining)
        assertTrue(first.request.isNotEmpty())
    }

    @Test
    fun aSmallCameraMoveDoesNotRebuildThePlanAndMaliUploadsOneTile() {
        val still = floatArrayOf(-1.2f, 0.6f, 40f, 10f, 20f, 0f)
        val jitter = floatArrayOf(-1.19f, 0.61f, 42f, 10.4f, 20.1f, 0.2f)
        assertTrue(DrapeLod.cameraSettled(still, jitter))
        val zoom = floatArrayOf(-1.2f, 0.6f, 80f, 10f, 20f, 0f)
        assertFalse(DrapeLod.cameraSettled(still, zoom))
        val orbit = floatArrayOf(0.4f, 0.6f, 40f, 10f, 20f, 0f)
        assertFalse(DrapeLod.cameraSettled(still, orbit))

        assertEquals(1, DrapeLod.uploadsPerFrame("Mali-G57 MC2"))
        assertEquals(1, DrapeLod.uploadsPerFrame("Mali-G615"))
        assertEquals(2, DrapeLod.uploadsPerFrame("Adreno (TM) 730"))
        assertEquals(2, DrapeLod.uploadsPerFrame(null))
        assertTrue(DrapeLod.mipmap(2048, 1536, base = true))
        assertFalse(DrapeLod.mipmap(1024, 1024, base = false))
        assertTrue(DrapeLod.mipmap(512, 512, base = false))
        // 8 GB still uses the heap cap, the same one a 6 GB device of the same class gets.
        assertEquals(DrapeLod.budgetBytes(6L shl 30, 256), DrapeLod.budgetBytes(8L shl 30, 256))
    }

    @Test
    fun planningALargeOrthophotoStaysOffTheFrameBudget() {
        val budget = DrapeLod.budgetBytes(8L shl 30, 256)
        val started = System.nanoTime()
        val plan = DrapeLod.plan(
            imageWidth = 26_000,
            imageHeight = 21_000,
            boundsMinX = 0.0,
            boundsMaxY = 2_100.0,
            boundsWidth = 2_600.0,
            boundsHeight = 2_100.0,
            cameraX = 1_300.0,
            cameraY = 1_050.0,
            cameraZ = 40.0,
            groundZ = 0.0,
            targetDistance = 40.0,
            worldPerPixel = 0.03,
            finestLevel = 0,
            topLevel = 7,
            tileSide = DrapeLod.STREAM_TILE,
            budgetBytes = budget,
            bytesPerPixel = 2,
        )
        val ms = (System.nanoTime() - started) / 1_000_000.0
        assertTrue("plan took ${ms} ms", ms < 100.0)
        assertTrue(plan.bytes <= budget)
        assertTrue(plan.tiles.isNotEmpty())
    }

    @Test
    fun aOnePointSixGigabyteTiffIsOpenedWithoutReadingItsPixels() {
        // 26,000 × 21,000 RGB is 1.64 GB uncompressed: the size of Paulo's file.
        val width = 26_000
        val height = 21_000
        val virtual = width.toLong() * height * 3
        assertTrue(virtual > 1_600_000_000L)
        val started = System.nanoTime()
        for (shape in listOf(true to false, true to true, false to false, false to true)) {
            val (tiled, jpeg) = shape
            val head = tiff(width, height, tiled, jpeg)
            val input = Counting(head, virtual)
            val blocks = TiffReader(input).blocks()
            assertEquals(width, blocks.width)
            assertEquals(height, blocks.height)
            val opened = input.bytes
            assertTrue(opened < 2_000_000L)
            val first = DrapeLod.tick(
                baseReady = true,
                residents = emptySet(),
                plan = DrapeLod.cover(width, height, topLevel = 7, tileSide = DrapeLod.STREAM_TILE),
                queued = emptySet(),
                slots = 0,
            )
            assertTrue(first.hasDrape)
            assertEquals(opened, input.bytes)
        }
        val ms = (System.nanoTime() - started) / 1_000_000.0
        assertTrue("open took ${ms} ms", ms < 2_000.0)
    }

    @Test
    fun theGlFrameDoesNotPlanOrDecode() {
        val view = File(root(), "app/src/main/kotlin/com/appdwg/app/View3D.kt").readText()
        val stream = view.substringAfter("private fun streamDrape()").substringBefore("private fun schedulePlan")
        assertFalse(stream.contains("decodeWindow"))
        assertFalse(stream.contains("DrapeLod.plan("))
        assertFalse(stream.contains("buildPyramid"))
        assertTrue(view.contains("baseTiles"))
        assertTrue(view.contains("DrapeLod.cameraSettled"))
        assertTrue(view.contains("DrapeLod.tick"))
        assertTrue(view.contains("DrapeLod.mipmap"))
        assertTrue(view.contains("gpuTiles = built"))
        val picture = File(root(), "app/src/main/kotlin/com/appdwg/app/TiledPicture.kt").readText()
        assertTrue(picture.contains("fun pyramidTile"))
        assertTrue(picture.contains("readRaw"))
        assertTrue(picture.contains("pyramid2"))
    }

    private fun bytesOf(node: DrapeLod.Node): Long {
        val (w, h) = DrapeLod.outputSize(node)
        return DrapeLod.textureBytes(w, h, 2)
    }

    /** 300 MP ortho over 2 km × 1.5 km, camera 40 m above the middle, 3 cm per screen pixel. */
    private fun ortho(budget: Long, finest: Int) = DrapeLod.plan(
        imageWidth = 20_000,
        imageHeight = 15_000,
        boundsMinX = 0.0,
        boundsMaxY = 1_500.0,
        boundsWidth = 2_000.0,
        boundsHeight = 1_500.0,
        cameraX = 1_000.0,
        cameraY = 750.0,
        cameraZ = 40.0,
        groundZ = 0.0,
        targetDistance = 40.0,
        worldPerPixel = 0.03,
        finestLevel = finest,
        topLevel = 7,
        tileSide = DrapeLod.STREAM_TILE,
        budgetBytes = budget,
        bytesPerPixel = 2,
    )

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }

    /**
     * Classic TIFF header for a [width]×[height] RGB file. Pixel bytes are a few samples at the
     * end of the header; the caller's input reports the real uncompressed size.
     */
    private fun tiff(width: Int, height: Int, tiled: Boolean, jpeg: Boolean): ByteArray {
        val tile = 256
        val rows = 256
        val across = (width + tile - 1) / tile
        val down = (height + tile - 1) / tile
        val n = if (tiled) across * down else (height + rows - 1) / rows
        val entries = 10
        val ifdAt = 8
        val ifdBytes = 2 + entries * 12 + 4
        val bitsAt = ifdAt + ifdBytes
        val tableAt = bitsAt + 6
        val payloadAt = tableAt + n * 4 + n * 4
        val payload = if (jpeg) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()) else ByteArray(16)
        val out = ByteArray(payloadAt + payload.size)
        fun u16(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun u32(at: Int, v: Long) {
            val i = v.toInt()
            u16(at, i and 0xFFFF)
            u16(at + 2, (i ushr 16) and 0xFFFF)
        }
        out[0] = 'I'.code.toByte()
        out[1] = 'I'.code.toByte()
        u16(2, 42)
        u32(4, ifdAt.toLong())
        u16(ifdAt, entries)
        var e = ifdAt + 2
        fun entry(tag: Int, type: Int, count: Int, value: Long) {
            u16(e, tag); u16(e + 2, type); u32(e + 4, count.toLong()); u32(e + 8, value); e += 12
        }
        entry(256, 4, 1, width.toLong())
        entry(257, 4, 1, height.toLong())
        entry(258, 3, 3, bitsAt.toLong())
        entry(259, 3, 1, if (jpeg) 7L else 1L)
        entry(262, 3, 1, 2L)
        entry(277, 3, 1, 3L)
        if (tiled) {
            entry(322, 3, 1, tile.toLong())
            entry(323, 3, 1, tile.toLong())
            entry(324, 4, n, if (n == 1) payloadAt.toLong() else tableAt.toLong())
            entry(325, 4, n, if (n == 1) payload.size.toLong() else (tableAt + n * 4).toLong())
        } else {
            entry(273, 4, n, if (n == 1) payloadAt.toLong() else tableAt.toLong())
            entry(278, 3, 1, rows.toLong())
            entry(279, 4, n, if (n == 1) payload.size.toLong() else (tableAt + n * 4).toLong())
        }
        u16(bitsAt, 8); u16(bitsAt + 2, 8); u16(bitsAt + 4, 8)
        if (n > 1) {
            for (i in 0 until n) {
                u32(tableAt + i * 4, payloadAt.toLong())
                u32(tableAt + n * 4 + i * 4, payload.size.toLong())
            }
        }
        payload.copyInto(out, payloadAt)
        return out
    }

    private class Counting(private val head: ByteArray, override val size: Long) : RandomInput {
        var bytes = 0L
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            bytes += length
            for (i in 0 until length) {
                val p = position + i
                buffer[offset + i] = if (p >= 0 && p < head.size) head[p.toInt()] else 0
            }
        }
    }
}
