package com.appdwg.core

/**
 * Interface text in the display language. The Portuguese text is the key; other languages come
 * from a catalogue (`i18n/en.tsv`: Portuguese, a tab, the translation). Placeholders are `{0}`,
 * `{1}`… in both, filled from [args]. A text missing from the catalogue stays in Portuguese.
 */
fun tr(pt: String, vararg args: Any?): String {
    val text = if (Display.portuguese) pt else I18n.english[pt] ?: pt
    if (args.isEmpty()) return text
    var out = text
    for (i in args.indices) out = out.replace("{$i}", args[i].toString())
    return out
}

object I18n {
    /** English texts by their Portuguese key, read once from the catalogue. */
    val english: Map<String, String> by lazy { load("i18n/en.tsv") }

    private fun load(name: String): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream(name) ?: return emptyMap()
        val map = HashMap<String, String>(2048)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[unescape(line.substring(0, tab))] = unescape(line.substring(tab + 1))
            }
        }
        return map
    }

    /** `\n`, `\t` and `\\` in the catalogue stand for a line break, a tab and a backslash. */
    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}
