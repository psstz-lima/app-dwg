package com.appdwg.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import java.io.InputStream
import kotlinx.coroutines.runBlocking

/**
 * JPEG, PNG and WebP for a drape or a drawing image. Coil subsamples to [maxSide] instead of
 * decoding the stream with [BitmapFactory] at full size.
 */
internal object CoilPictures {
    @Volatile private var loader: ImageLoader? = null

    private fun loader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .allowHardware(false)
                .build()
                .also { loader = it }
        }

    /** Width, height and MIME, without allocating the pixels. */
    fun sizeOf(open: () -> InputStream?): Triple<Int, Int, String?>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return Triple(bounds.outWidth, bounds.outHeight, bounds.outMimeType)
    }

    fun decode(context: Context, data: Any, maxSide: Int, config: Bitmap.Config): Bitmap? = runBlocking {
        val request = ImageRequest.Builder(context.applicationContext)
            .data(data)
            .size(maxSide.coerceAtLeast(1))
            .precision(Precision.INEXACT)
            .bitmapConfig(config)
            .allowHardware(false)
            .allowRgb565(config == Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        when (val result = loader(context).execute(request)) {
            is SuccessResult -> {
                val raw = when (val image = result.image) {
                    is BitmapImage -> image.bitmap
                    else -> image.toBitmap()
                }
                if (raw.isRecycled) null else raw.copy(if (raw.hasAlpha()) Bitmap.Config.ARGB_8888 else config, false) ?: raw
            }
            is ErrorResult -> null
        }
    }
}
