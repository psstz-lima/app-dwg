package com.appdwg.app

import java.io.InputStream

/**
 * Width, height and MIME of a JPEG, PNG or WebP, read from the header.
 * No bitmap is allocated. TIFF is recognised by the caller before this runs.
 */
internal object ImageHeader {
    data class Info(val width: Int, val height: Int, val mime: String)

    fun parse(open: () -> InputStream?): Info? = open()?.use { parse(it) }

    fun parse(stream: InputStream): Info? {
        val buf = ByteArray(256 * 1024)
        var n = 0
        while (n < buf.size) {
            val r = stream.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
            if (n >= 32) {
                parse(buf, n)?.let { return it }
            }
        }
        return if (n > 0) parse(buf, n) else null
    }

    fun parse(data: ByteArray, length: Int = data.size): Info? {
        if (length >= 24 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()) {
            val w = u32(data, 16)
            val h = u32(data, 20)
            if (w > 0 && h > 0) return Info(w, h, "image/png")
        }
        if (length >= 12 && ascii(data, 0, 4) == "RIFF" && ascii(data, 8, 4) == "WEBP") {
            webp(data, length)?.let { return it }
        }
        if (length >= 4 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            jpeg(data, length)?.let { return it }
        }
        return null
    }

    private fun jpeg(data: ByteArray, length: Int): Info? {
        var i = 2
        while (i + 9 < length) {
            if (data[i] != 0xFF.toByte()) { i++; continue }
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9) { i += 2; continue }
            if (i + 3 >= length) return null
            val size = u16(data, i + 2)
            if (size < 2) return null
            val sof = marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF
            if (sof && i + 8 < length) {
                val h = u16(data, i + 5)
                val w = u16(data, i + 7)
                if (w > 0 && h > 0) return Info(w, h, "image/jpeg")
            }
            i += 2 + size
        }
        return null
    }

    private fun webp(data: ByteArray, length: Int): Info? {
        if (length >= 30 && ascii(data, 12, 4) == "VP8X") {
            val w = 1 + (data[24].toInt() and 0xFF) + ((data[25].toInt() and 0xFF) shl 8) + ((data[26].toInt() and 0xFF) shl 16)
            val h = 1 + (data[27].toInt() and 0xFF) + ((data[28].toInt() and 0xFF) shl 8) + ((data[29].toInt() and 0xFF) shl 16)
            if (w > 0 && h > 0) return Info(w, h, "image/webp")
        }
        if (length >= 30 && ascii(data, 12, 4) == "VP8 " && data[23] == 0x9D.toByte()) {
            val w = (data[26].toInt() and 0xFF) + ((data[27].toInt() and 0xFF) shl 8)
            val h = (data[28].toInt() and 0xFF) + ((data[29].toInt() and 0xFF) shl 8)
            if (w > 0 && h > 0) return Info(w and 0x3FFF, h and 0x3FFF, "image/webp")
        }
        if (length >= 25 && ascii(data, 12, 4) == "VP8L") {
            val b0 = data[21].toInt() and 0xFF
            val b1 = data[22].toInt() and 0xFF
            val b2 = data[23].toInt() and 0xFF
            val b3 = data[24].toInt() and 0xFF
            val w = 1 + (b0 or ((b1 and 0x3F) shl 8))
            val h = 1 + (((b1 and 0xC0) shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
            if (w > 0 && h > 0) return Info(w, h, "image/webp")
        }
        return null
    }

    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    private fun u32(data: ByteArray, at: Int) =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun ascii(data: ByteArray, at: Int, n: Int) = if (at + n > data.size) "" else String(data, at, n, Charsets.ISO_8859_1)
}
