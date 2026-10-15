package com.appdwg.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appdwg.core.Bounds
import com.appdwg.core.LandXmlProject
import com.appdwg.core.ProjectCache
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Large files, once read, are kept on the device in [ProjectCache]'s binary form with their terrain
 * pictures, so opening them again skips parsing, triangulation, indexing and shading. A cache entry
 * is found by the file's size and modification date plus a sample of its first and last bytes, so
 * a changed file is read afresh. The oldest entries go when the cache passes [MAX_BYTES].
 */
class OpenCache(private val context: Context) {
    companion object {
        /** Files smaller than this open fast enough without a cache. */
        const val MIN_SOURCE_BYTES = 2L * 1024 * 1024
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val SAMPLE = 64 * 1024
    }

    private val dir = File(context.cacheDir, "opened").apply { mkdirs() }

    /** Identity of the file's content as it is now, or null when it is too small or unreadable. */
    fun keyOf(uri: Uri, size: Long, open: () -> java.io.InputStream?): String? {
        if (size < MIN_SOURCE_BYTES) return null
        val modified = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${ProjectCache.VERSION}|$uri|$size|$modified".toByteArray())
        // First and last bytes: cheap, and they change when the file is saved again.
        runCatching {
            open()?.use { s ->
                val head = ByteArray(SAMPLE); val n = s.readNBytesCompat(head); md.update(head, 0, n)
                val skip = size - SAMPLE - n
                if (skip > 0) { var left = skip; while (left > 0) { val k = s.skip(left); if (k <= 0) break; left -= k } }
                val tail = ByteArray(SAMPLE); val m = s.readNBytesCompat(tail); md.update(tail, 0, m)
            }
        }.getOrElse { return null }
        return md.digest().joinToString("") { "%02x".format(it) }.take(40)
    }

    /** The project and terrain pictures saved under [key], or null. */
    fun load(key: String): Pair<LandXmlProject, List<TerrainImage>>? {
        val f = File(dir, "$key.bin")
        if (!f.exists()) return null
        return runCatching {
            f.setLastModified(System.currentTimeMillis())
            f.inputStream().buffered(1 shl 16).use { input ->
                val p = ProjectCache.read(input) ?: return null
                val d = DataInputStream(input)
                val images = List(d.readInt()) {
                    val name = d.readUTF(); val b = Bounds(d.readDouble(), d.readDouble(), d.readDouble(), d.readDouble())
                    val w = d.readInt(); val h = d.readInt()
                    val bytes = ByteArray(w * h * 4); d.readFully(bytes)
                    val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
                    TerrainImage(name, b, bmp.asImageBitmap())
                }
                p to images
            }
        }.getOrElse { f.delete(); null }
    }

    /** Saves a freshly read project; written to a temporary file first so a failure leaves nothing half done. */
    fun save(key: String, project: LandXmlProject, images: List<TerrainImage>) {
        val f = File(dir, "$key.bin"); val tmp = File(dir, "$key.tmp")
        runCatching {
            tmp.outputStream().buffered(1 shl 16).use { out ->
                ProjectCache.write(project, out)
                val d = DataOutputStream(out)
                d.writeInt(images.size)
                for (t in images) {
                    val bmp = t.image.asAndroidBitmap()
                    d.writeUTF(t.name); d.writeDouble(t.bounds.minX); d.writeDouble(t.bounds.minY); d.writeDouble(t.bounds.maxX); d.writeDouble(t.bounds.maxY)
                    d.writeInt(bmp.width); d.writeInt(bmp.height)
                    val buf = ByteBuffer.allocate(bmp.byteCount); bmp.copyPixelsToBuffer(buf); d.write(buf.array())
                }
                d.flush()
            }
            if (!tmp.renameTo(f)) tmp.delete()
            trim()
        }.onFailure { tmp.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.extension == "bin" }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) { total += f.length(); if (total > MAX_BYTES) f.delete() }
    }
}

/** Reads until [buffer] is full or the stream ends; returns how many bytes were read. */
private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val r = read(buffer, total, buffer.size - total)
        if (r < 0) break
        total += r
    }
    return total
}
