package com.appdwg.app

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlin.math.max

/**
 * Prepares the picture draped over a surface before [android.opengl.GLUtils.texImage2D].
 *
 * On Mali (Redmi / mt6789, Android 16) that call SIGSEGVs in memcpy when the bitmap is RGB_565
 * with an odd width, or larger than the GPU's texture limit. The upload is always a new
 * ARGB_8888 bitmap no bigger than that limit. The drape's own bitmap is not recycled here.
 */
internal object GlTexture {
    const val CONFIG = "ARGB_8888"

    data class Plan(val width: Int, val height: Int, val config: String = CONFIG)

    /** Size to upload, or null when there is nothing safe to send to the GPU. */
    fun plan(width: Int, height: Int, recycled: Boolean, maxTextureSize: Int): Plan? {
        if (recycled || width <= 0 || height <= 0) return null
        val max = maxTextureSize.coerceAtLeast(1)
        if (width <= max && height <= max) return Plan(width, height)
        val f = max.toFloat() / max(width, height)
        return Plan(
            (width * f).toInt().coerceAtLeast(1).coerceAtMost(max),
            (height * f).toInt().coerceAtLeast(1).coerceAtMost(max),
        )
    }

    /**
     * A private ARGB_8888 copy of [source], scaled down to [maxTextureSize].
     * The caller uploads it on the GL thread and recycles it only after [android.opengl.GLUtils.texImage2D] returns.
     */
    fun copy(source: Bitmap, maxTextureSize: Int): Bitmap? {
        val plan = plan(source.width, source.height, source.isRecycled, maxTextureSize) ?: return null
        val scaled = if (plan.width == source.width && plan.height == source.height) source
            else try {
                source.scale(plan.width, plan.height, true)
            } catch (_: Throwable) {
                return null
            }
        if (scaled.isRecycled) return null
        val argb = try {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        }
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        if (argb == null || argb.isRecycled || argb.config != Bitmap.Config.ARGB_8888) {
            if (argb != null && argb !== source && !argb.isRecycled) argb.recycle()
            return null
        }
        return argb
    }
}
