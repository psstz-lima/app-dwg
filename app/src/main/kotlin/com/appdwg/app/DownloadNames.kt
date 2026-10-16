package com.appdwg.app

/** File names for copies saved into the public Downloads folder. */
internal object DownloadNames {
    /** [name] when it is free, otherwise "name (2).ext", "name (3).ext", and so on. */
    fun unique(name: String, taken: Set<String>): String {
        val clean = name.trim().ifBlank { "arquivo" }.replace('/', '_').replace('\\', '_')
        if (clean !in taken) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var n = 2
        while ("$base ($n)$ext" in taken) n++
        return "$base ($n)$ext"
    }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        "xml" -> "text/xml"
        "dxf" -> "image/vnd.dxf"
        "txt", "csv" -> "text/plain"
        "json", "geojson" -> "application/json"
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "ifc" -> "application/x-step"
        "gmt" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    fun shouldAsk(unexported: Int): Boolean = unexported > 0
}
