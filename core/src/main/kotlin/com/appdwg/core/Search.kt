package com.appdwg.core

/** Something found by name on the plan. */
data class SearchHit(val kind: String, val label: String, val position: Point2)

object Search {
    /**
     * Points (COGO and _POINTS), texts and alignments whose name or description contains [query],
     * ignoring case and accents; exact point numbers first. At most [limit] hits.
     */
    fun find(project: LandXmlProject, query: String, limit: Int = 50): List<SearchHit> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val exact = ArrayList<SearchHit>()
        val hits = ArrayList<SearchHit>()
        for (p in project.points) {
            val hit = SearchHit(tr("Ponto"), p.number + if (p.description.isNotEmpty()) " · ${p.description}" else "", Point2(p.x, p.y))
            when {
                fold(p.number) == q -> exact += hit
                q in fold(p.number) || q in fold(p.description) -> hits += hit
            }
        }
        for (c in project.cgPoints) {
            if (c.name.isEmpty()) continue
            val hit = SearchHit(tr("Ponto COGO"), c.name + (c.code?.let { " · $it" } ?: ""), Point2(c.position.x, c.position.y))
            when {
                fold(c.name) == q -> exact += hit
                q in fold(c.name) || (c.code != null && q in fold(c.code)) -> hits += hit
            }
        }
        for (a in project.alignments) {
            if (q in fold(a.name)) a.pointAt(a.startStation)?.let { hits += SearchHit(tr("Alinhamento"), a.name, it) }
        }
        for (t in project.texts) {
            if (hits.size + exact.size >= limit) break
            if (q in fold(t.text)) hits += SearchHit(tr("Texto"), t.text.lines().first().take(60), Point2(t.x, t.y))
        }
        return (exact + hits).take(limit)
    }

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
