package com.appdwg.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Shared decoding options for the large pictures this app shows: drawing images tiled from
 * orthophotos, and the satellite / street tiles draped under the plan.
 *
 * Opaque pictures (JPEG, and the map tiles) are decoded as [Bitmap.Config.RGB_565], which is
 * half the memory of the default ARGB_8888. Pictures that can carry transparency stay ARGB.
 * [BitmapFactory.Options.inScaled] is off so a content stream is not resampled to the screen
 * density on top of the sample size the caller already chose.
 */
internal object BitmapDecode {
    fun options(config: Bitmap.Config, sampleSize: Int = 1): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inPreferredConfig = config
            inSampleSize = sampleSize.coerceAtLeast(1)
            inScaled = false
        }

    /** JPEG has no alpha. Anything else may, so it keeps 32-bit pixels. */
    fun configForMime(mime: String?): Bitmap.Config =
        if (mime == "image/jpeg") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    fun decodeFile(path: String, config: Bitmap.Config, sampleSize: Int = 1): Bitmap? =
        BitmapFactory.decodeFile(path, options(config, sampleSize))

    fun decodeBytes(bytes: ByteArray, config: Bitmap.Config, sampleSize: Int = 1): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(config, sampleSize))

    fun decodeStream(
        stream: java.io.InputStream,
        config: Bitmap.Config,
        sampleSize: Int = 1,
    ): Bitmap? = BitmapFactory.decodeStream(stream, null, options(config, sampleSize))
}
