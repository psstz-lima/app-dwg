package com.appdwg.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes a picture that is already a file on this device, such as a pyramid tile in the cache.
 * Network bytes and content streams go through Coil instead.
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
}
