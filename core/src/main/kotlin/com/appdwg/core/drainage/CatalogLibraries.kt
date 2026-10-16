package com.appdwg.core.drainage

/**
 * User libraries saved with a project. Each import is kept as JSON and merged back onto the
 * bundled album when the project opens. Older projects simply have no list.
 */
object CatalogLibraries {
    fun append(stored: String?, json: String): String {
        val item = quote(json.trim())
        val current = stored?.trim().orEmpty()
        if (current.isEmpty()) return "[$item]"
        if (current.startsWith("[")) {
            val body = current.removeSuffix("]").trimEnd()
            return "$body,$item]"
        }
        return "[${quote(current)},$item]"
    }

    fun apply(base: DeviceCatalog, stored: String): DeviceCatalog {
        val texts = texts(stored)
        var catalog = base
        for (text in texts) catalog = CatalogLoader.importUser(text, catalog)
        return catalog
    }

    private fun texts(stored: String): List<String> {
        val root = parseJv(stored)
        return when (root) {
            is Jv.Arr -> root.items.mapNotNull { (it as? Jv.Str)?.text }
            is Jv.Str -> listOf(root.text)
            else -> emptyList()
        }
    }

    private fun quote(json: String) = buildString {
        append('"')
        for (c in json) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
