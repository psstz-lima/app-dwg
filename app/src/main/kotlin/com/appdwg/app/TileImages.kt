package com.appdwg.app

import android.content.Context
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Map tiles through Coil 3: the network fetch, the downsample and both caches live in the
 * image loader. Opaque tiles are RGB_565 (half the memory of 32-bit). A picture that still
 * carries alpha is kept as ARGB_8888. The app's own `filesDir/tiles` copy stays the offline
 * store (empty files mark tiles the server has no picture for).
 */
internal class TileImages(context: Context) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val loader: ImageLoader = ImageLoader.Builder(app)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowHardware(false)
        .allowRgb565(true)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(app, 0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(app.cacheDir, "coil-tiles").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()

    /**
     * Decodes [data] (a URL or a file already on disk) to at most [size] pixels on a side.
     * [opaque] asks for RGB_565; if the bitmap still has alpha it is copied to ARGB_8888 so
     * transparency is not flattened.
     */
    suspend fun decode(data: Any, cacheKey: String, size: Int, opaque: Boolean, disk: Boolean, memory: Boolean): Bitmap? {
        val config = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val request = ImageRequest.Builder(app)
            .data(data)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .size(size)
            .precision(Precision.INEXACT)
            .bitmapConfig(config)
            .allowHardware(false)
            .allowRgb565(opaque)
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCachePolicy(if (disk) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
        return when (val result = loader.execute(request)) {
            is SuccessResult -> copyForDraw(result.image, config)
            is ErrorResult -> null
        }
    }

    /** Copies the encoded bytes Coil cached for [cacheKey] into [dest]. False when there is no entry. */
    fun exportDisk(cacheKey: String, dest: File): Boolean {
        val cache = loader.diskCache ?: return false
        val snapshot = cache.openSnapshot(cacheKey) ?: return false
        snapshot.use { snap ->
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            snap.data.toFile().inputStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (tmp.length() == 0L) { tmp.delete(); return false }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
        return dest.isFile && dest.length() > 0
    }

    fun evict(cacheKey: String) {
        loader.memoryCache?.remove(MemoryCache.Key(cacheKey))
        loader.diskCache?.remove(cacheKey)
    }

    private fun copyForDraw(image: coil3.Image, preferred: Bitmap.Config): Bitmap {
        val raw = when (image) {
            is BitmapImage -> image.bitmap
            else -> image.toBitmap()
        }
        val config = if (raw.hasAlpha()) Bitmap.Config.ARGB_8888 else preferred
        // A private copy: Coil may reuse the cached bitmap after this request returns.
        return raw.copy(config, false) ?: raw
    }
}
