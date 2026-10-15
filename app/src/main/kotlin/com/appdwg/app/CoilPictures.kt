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

    fun decode(context: Context, data: Any, maxSide: Int, config: Bitmap.Config): Bitmap? =
        load(context, data, maxSide.coerceAtLeast(1), maxSide.coerceAtLeast(1), config)

    private fun load(context: Context, data: Any, width: Int, height: Int, config: Bitmap.Config): Bitmap? = runBlocking {
        val request = ImageRequest.Builder(context.applicationContext)
            .data(data)
            .size(width.coerceAtLeast(1), height.coerceAtLeast(1))
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

    /**
     * One JPEG tile, decoded through Coil at [reduce] (1, 2, 4…).
     * The request is the header size divided by [reduce]. A tile smaller than that request used to
     * come back full size, and the TIFF reader then kept only its top-left corner.
     */
    fun raster(context: Context, bytes: ByteArray, reduce: Int): Raster? {
        val k = reduce.coerceAtLeast(1)
        val header = ImageHeader.parse(bytes)
        val outW = header?.let { (it.width + k - 1) / k }
        val outH = header?.let { (it.height + k - 1) / k }
        val bitmap = if (outW != null && outH != null) {
            load(context, bytes, outW, outH, Bitmap.Config.RGB_565)
        } else {
            load(context, bytes, 16384, 16384, Bitmap.Config.RGB_565)
        } ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        val width = outW ?: (bitmap.width + k - 1) / k
        val height = outH ?: (bitmap.height + k - 1) / k
        return sample(bitmap, width.coerceAtLeast(1), height.coerceAtLeast(1))
            .also { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    /** Point sample of [bitmap] into [outW] × [outH]. The full image when the sizes already match. */
    private fun sample(bitmap: Bitmap, outW: Int, outH: Int): Raster {
        val src = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(src, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (bitmap.width == outW && bitmap.height == outH) return Raster(outW, outH, src)
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = (y.toLong() * bitmap.height / outH).toInt().coerceIn(0, bitmap.height - 1)
            val row = sy * bitmap.width
            for (x in 0 until outW) {
                val sx = (x.toLong() * bitmap.width / outW).toInt().coerceIn(0, bitmap.width - 1)
                out[y * outW + x] = src[row + sx]
            }
        }
        return Raster(outW, outH, out)
    }
}
