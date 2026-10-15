package com.appdwg.core

import java.text.Normalizer

/** A published rain equation (IDF) of a place, i = K·Tᵃ / (t + b)ᶜ in mm/h. */
class IdfEntry(val place: String, val state: String, val idf: Idf, val author: String) {
    val label get() = "$place/$state"
}

/**
 * Rain equations of Brazilian places to pick from, from A. V. Festi's "Coletânea das equações de
 * chuva do Brasil" (ABRH), equations given in mm/min converted to mm/h.
 */
object IdfCatalog {
    val entries: List<IdfEntry> by lazy {
        val stream = IdfCatalog::class.java.getResourceAsStream("idf/brasil.tsv") ?: return@lazy emptyList()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 7) return@mapNotNull null
                val n = f.subList(2, 6).map { it.toDoubleOrNull() ?: return@mapNotNull null }
                IdfEntry(f[0], f[1], Idf(n[0], n[1], n[2], n[3]), f[6].takeIf { it != "—" } ?: "")
            }.toList()
        }
    }

    /** Entries whose place or state contains [query], ignoring case and accents. */
    fun search(query: String): List<IdfEntry> {
        val q = fold(query.trim())
        if (q.isEmpty()) return entries
        return entries.filter { fold(it.label).contains(q) }
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
}
