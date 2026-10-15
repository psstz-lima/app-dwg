package com.appdwg.app

import com.appdwg.core.RandomInput
import com.appdwg.core.tr
import okhttp3.Request
import java.io.IOException

/** Identifies the app to the servers it downloads from, as their usage policies ask. */
const val USER_AGENT = "Gromatic/1.0 (Android; survey and earthworks)"

/**
 * A file on a web server read at any position with HTTP range requests, in chunks kept in memory,
 * so a Cloud Optimized GeoTIFF gives just the part of it that is needed.
 * Bytes that are not an image stay on OkHttp. JPEG tiles of a remote TIFF are decoded by Coil.
 */
class HttpRandomInput private constructor(private val url: String, override val size: Long, private val cancelled: () -> Boolean) : RandomInput {
    companion object {
        private const val CHUNK = 256 * 1024

        /** The file at [url], or null when the server has none (404/403: no tile there). */
        fun open(url: String, cancelled: () -> Boolean = { false }): HttpRandomInput? {
            val call = Net.client.newCall(Request.Builder().url(url).head().build())
            call.execute().use { response ->
                return when (response.code) {
                    200 -> HttpRandomInput(url, response.header("Content-Length")?.toLongOrNull() ?: response.body?.contentLength() ?: -1L, cancelled)
                    403, 404 -> null
                    else -> throw IOException(tr("resposta {0} do servidor", response.code))
                }
            }
        }
    }

    private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 64
    }
    /** Bytes received so far, for progress. */
    var received = 0L; private set

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var pos = position; var off = offset; var left = length
        while (left > 0) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException()
            val start = pos / CHUNK * CHUNK
            // Long reads (whole tiles) go straight through; small ones (headers) come in chunks.
            if (pos == start && left >= CHUNK) {
                fetch(pos, buffer, off, left); return
            }
            val chunk = chunks.getOrPut(start) { ByteArray(minOf(CHUNK.toLong(), size - start).toInt()).also { fetch(start, it, 0, it.size) } }
            val inChunk = (pos - start).toInt()
            val n = minOf(left, chunk.size - inChunk)
            if (n <= 0) throw IOException(tr("fim inesperado do arquivo"))
            System.arraycopy(chunk, inChunk, buffer, off, n)
            pos += n; off += n; left -= n
        }
    }

    private fun fetch(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var attempt = 0
        while (true) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$position-${position + length - 1}")
                    .build()
                Net.client.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) throw IOException(tr("resposta {0} do servidor", response.code))
                    (response.body ?: throw IOException(tr("resposta {0} do servidor", response.code))).byteStream().use { s ->
                        var o = offset; var left = length
                        while (left > 0) {
                            if (cancelled()) throw kotlinx.coroutines.CancellationException()
                            val k = s.read(buffer, o, left)
                            if (k < 0) throw IOException(tr("fim inesperado do arquivo"))
                            o += k; left -= k; received += k
                        }
                    }
                }
                return
            } catch (e: IOException) {
                // Mobile connections drop now and then: a few tries before giving up.
                if (++attempt >= 3) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }
}
