package com.appdwg.app

import kotlin.math.max
import kotlin.math.sqrt

/**
 * How large a picture the plan may decode in one bitmap.
 * At most [PIXELS] pixels of RGB_565 (about 128 MB). A TIFF past [TILED_FROM] on a side, or past
 * the budget, is shown from its own tiles so a zoomed-in view stays sharp.
 */
internal object RasterBudget {
    const val PIXELS = 64_000_000L
    const val TILED_FROM = 4096

    /** Longest side to ask the decoder for. Full resolution when the picture fits the budget. */
    fun maxSide(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val pixels = width.toLong() * height.toLong()
        val longest = max(width, height)
        if (pixels <= PIXELS) return longest
        val scale = sqrt(pixels.toDouble() / PIXELS.toDouble())
        return max(1, (longest / scale).toInt())
    }

    fun tileTiff(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return max(width, height) > TILED_FROM || width.toLong() * height.toLong() > PIXELS
    }
}
