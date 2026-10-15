package com.appdwg.app

import android.content.Context
import android.graphics.Bitmap
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
import com.appdwg.core.Raster
import java.io.InputStream
import kotlinx.coroutines.runBlocking

/**
 * JPEG, PNG and WebP, including a JPEG tile carried as bytes.
 * Coil subsamples to [maxSide]. The size of a local picture is read from its header.
 */
internal object CoilPictures {
    @Volatile private var loader: ImageLoader? = null
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        loader(context)
    }

    private fun loader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .allowHardware(false)
                .build()
                .also { loader = it }
        }

    /** Width, height and MIME, without allocating the pixels. */
    fun sizeOf(open: () -> InputStream?): ImageHeader.Info? = ImageHeader.parse(open)

    fun decodeInstalled(data: Any, maxSide: Int, config: Bitmap.Config): Bitmap? {
        val context = appContext ?: return null
        return decode(context, data, maxSide, config)
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

    /** One JPEG tile. Uses the context from [install] when the caller has none. */
    fun raster(bytes: ByteArray, reduce: Int): Raster? {
        val context = appContext ?: return null
        return raster(context, bytes, reduce)
    }

    /** One JPEG tile, local or downloaded, decoded through Coil and returned as pixels. */
    fun raster(context: Context, bytes: ByteArray, reduce: Int): Raster? {
        val side = (8192 / reduce.coerceAtLeast(1)).coerceAtLeast(1)
        val bitmap = decode(context, bytes, side, Bitmap.Config.RGB_565) ?: return null
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Raster(bitmap.width, bitmap.height, px).also { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}
