package com.appdwg.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlin.math.min

/**
 * Splits a drape into GPU tiles and picks a pixel format Mali can upload.
 *
 * RGB_565 with an odd width SIGSEGVs inside glTexImage2D on Mali. Opaque tiles with an even
 * width go up as RGB_565 and alignment 2. An odd width, or a picture with alpha, goes up as
 * ARGB_8888. No tile is larger than the real texture limit, and never larger than [TILE_CAP].
 * The full picture is never copied into a second bitmap: one tile is built, uploaded, recycled.
 */
internal object GlTexture {
    const val TILE_CAP = 4096
    const val OVERLAP = 1

    enum class PixelFormat { RGB_565, ARGB_8888 }

    data class Tile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val format: PixelFormat,
        val unpackAlignment: Int,
    )

    /** Tiles covering [width] x [height], or null when there is nothing to upload. */
    fun plan(width: Int, height: Int, hasAlpha: Boolean, maxTextureSize: Int): List<Tile>? {
        if (width <= 0 || height <= 0) return null
        val side = tileSide(maxTextureSize)
        val tiles = ArrayList<Tile>()
        var y = 0
        while (y < height) {
            val row = min(side, height - y)
            var x = 0
            while (x < width) {
                val col = min(side, width - x)
                val format = if (hasAlpha || col % 2 != 0) PixelFormat.ARGB_8888 else PixelFormat.RGB_565
                val align = if (format == PixelFormat.RGB_565) 2 else 4
                tiles += Tile(x, y, col, row, format, align)
                if (x + col >= width) break
                x += col - OVERLAP
            }
            if (y + row >= height) break
            y += row - OVERLAP
        }
        return tiles
    }

    fun tileSide(maxTextureSize: Int) = min(maxTextureSize.coerceAtLeast(1), TILE_CAP)

    /** One quality step down: highest, high, normal. Null when normal already failed. */
    fun nextQuality(quality: Int): Int? = when (quality) {
        2 -> 1
        1 -> 0
        else -> null
    }

    /**
     * World rect of one tile: left, top, width, height. Top is the northern edge, matching the
     * drape shader. The rect belongs to this tile, not to whatever picture is current later.
     */
    fun placed(
        boundsMinX: Double, boundsMaxY: Double, boundsWidth: Double, boundsHeight: Double,
        imageW: Int, imageH: Int, tile: Tile,
    ): DoubleArray {
        val px = if (imageW > 0) boundsWidth / imageW else 0.0
        val py = if (imageH > 0) boundsHeight / imageH else 0.0
        return doubleArrayOf(
            boundsMinX + tile.x * px,
            boundsMaxY - tile.y * py,
            tile.width * px,
            tile.height * py,
        )
    }

    /** A private copy of one tile. The caller uploads it and recycles it immediately after. */
    fun tileBitmap(source: Bitmap, tile: Tile): Bitmap? {
        if (source.isRecycled || tile.width <= 0 || tile.height <= 0) return null
        if (tile.x < 0 || tile.y < 0 || tile.x + tile.width > source.width || tile.y + tile.height > source.height) return null
        val config = if (tile.format == PixelFormat.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val bitmap = try {
            createBitmap(tile.width, tile.height, config)
        } catch (_: Throwable) {
            return null
        }
        return try {
            Canvas(bitmap).drawBitmap(
                source,
                Rect(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height),
                Rect(0, 0, tile.width, tile.height),
                null,
            )
            bitmap
        } catch (_: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }
}
