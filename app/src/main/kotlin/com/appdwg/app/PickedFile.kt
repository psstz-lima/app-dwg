package com.appdwg.app

/** What to do with a document the user just picked. */
enum class PickRoute {
    /** Replace the screen with this drawing. */
    NEW_PROJECT,
    /** Keep the open project and lay this picture on it. */
    ATTACH_IMAGE,
}

/**
 * Decides whether a picked SAF document is an image to add to the open project, and whether its
 * bytes have to be streamed to a local file first. Google Drive (and other cloud providers) hand
 * back a content URI that often cannot be seeked until the bytes are copied; a local file can.
 */
object PickedFile {
    private val IMAGE_EXT = setOf("tif", "tiff", "jpg", "jpeg", "png", "webp")

    /** Document providers that expose a real file. Anything else is streamed to the cache. */
    private val LOCAL_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        "com.android.providers.media",
    )

    fun route(projectOpen: Boolean, image: Boolean): PickRoute =
        if (projectOpen && image) PickRoute.ATTACH_IMAGE else PickRoute.NEW_PROJECT

    /** True for Drive and any other cloud document. A `file:` URI and on-device providers are false. */
    fun needsStreamedCopy(scheme: String?, authority: String?): Boolean {
        if (scheme == null || scheme == "file") return false
        if (scheme != "content") return true
        val auth = authority ?: return true
        return auth !in LOCAL_AUTHORITIES
    }

    /**
     * An image by its name, its MIME type, or its first bytes. DXF's `image/vnd.dxf` is a drawing.
     * A world file (.tfw and the like) is not an image on its own.
     */
    fun isImage(name: String?, mime: String?, head: ByteArray): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val named = name != null && name.contains('.') && ext in IMAGE_EXT
        if (named) return true
        if (mime != null && mime.startsWith("image/") && !mime.contains("dxf", ignoreCase = true)) return true
        return isImageHead(head)
    }

    /** TIFF (including GeoTIFF), JPEG, PNG or WebP magic. */
    fun isImageHead(head: ByteArray): Boolean {
        if (head.size >= 4) {
            val n = minOf(head.size, 8)
            val text = String(head, 0, n, Charsets.ISO_8859_1)
            if (text.startsWith("II*") || text.startsWith("II+") || text.startsWith("MM\u0000*") || text.startsWith("MM\u0000+")) return true
            if (text.startsWith("\u0089PNG")) return true
        }
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return true
        if (head.size >= 12 &&
            String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        ) return true
        return false
    }
}
