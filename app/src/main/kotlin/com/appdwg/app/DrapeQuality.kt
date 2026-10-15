package com.appdwg.app

import kotlin.math.max

/**
 * What Normal, Alta and Máxima mean, in pixels.
 *
 * Máxima is the file: reduce 1, pyramid level 0. Alta is half on each side (reduce 2, level 1).
 * Normal is a quarter (reduce 4, level 2). A 20,000 × 15,000 orthophoto is therefore
 * 20,000 × 15,000, 10,000 × 7,500 or 5,000 × 3,750. The 3D view used to stop Máxima at
 * 8,192 pixels or 40 million pixels, so that same file became level 2 (5,000 × 3,750)
 * before it ever reached the GPU.
 */
internal object DrapeQuality {
    /** One file pixel per output pixel, or 2, or 4. */
    fun reduce(quality: Int): Int = when (quality) {
        2 -> 1
        1 -> 2
        else -> 4
    }

    /** Pyramid level read from the file. 0 is native. */
    fun pyramidLevel(quality: Int): Int = when (quality) {
        2 -> 0
        1 -> 1
        else -> 2
    }

    /** Output size of a [width] × [height] file at [quality]. Zero when the file size is empty. */
    fun outputSize(width: Int, height: Int, quality: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val k = reduce(quality)
        return (width + k - 1) / k to (height + k - 1) / k
    }

    /**
     * Pyramid level for the plan. [filePixelsPerScreenPixel] is how many file pixels sit on one
     * screen pixel. Máxima uses the level the screen needs, and level 0 once the view is zoomed
     * past the file. Alta and Normal stay one and two steps coarser than that.
     */
    fun level(filePixelsPerScreenPixel: Double, quality: Int, topLevel: Int): Int {
        val ratio = when {
            !filePixelsPerScreenPixel.isFinite() -> Double.MAX_VALUE
            else -> filePixelsPerScreenPixel
        }
        val top = topLevel.coerceAtLeast(0)
        return (stepsOfTwo(max(ratio, 1.0)) + pyramidLevel(quality)).coerceIn(0, top)
    }

    /** How many times [ratio] can be halved before it drops under 2. Exact on powers of two. */
    private fun stepsOfTwo(ratio: Double): Int {
        var value = ratio
        var steps = 0
        while (value >= 2.0 && steps < 30) {
            value /= 2.0
            steps++
        }
        return steps
    }
}
