package com.appdwg.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Writes a file into the public Downloads folder.
 * API 29 and newer use [MediaStore.Downloads], which needs no storage permission.
 * Older versions write the public directory when the process may, and otherwise the app's
 * own external Downloads directory. No broad storage permission is requested.
 */
internal object PublicDownloads {
    fun save(context: Context, name: String, mime: String, source: File, taken: MutableSet<String>): Boolean {
        if (!source.isFile) return false
        val finalName = DownloadNames.unique(name, taken)
        val ok = if (Build.VERSION.SDK_INT >= 29) mediaStore(context, finalName, mime, source) else legacy(context, finalName, source)
        if (ok) taken += finalName
        return ok
    }

    @RequiresApi(29)
    private fun mediaStore(context: Context, name: String, mime: String, source: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun legacy(context: Context, name: String, source: File): Boolean {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (publicDir != null && (publicDir.isDirectory || publicDir.mkdirs()) && publicDir.canWrite()) publicDir
        else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        return try {
            source.inputStream().use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            true
        } catch (_: Exception) {
            false
        }
    }
}
