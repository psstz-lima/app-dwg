package com.appdwg.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Survey code ("código de campo"): which point descriptions it takes, the layer and colour of
 * what is drawn from them, and whether they are joined into lines (toe, crest, fence) or stay
 * single points (post, tree). Closed codes (building, pond) close their lines.
 */
data class PointCode(
    val code: String,
    val name: String,
    val aliases: List<String>,
    val layer: String,
    val color: Int,
    val connect: Boolean = true,
    val closed: Boolean = false,
    /** Breakline for the surface (toe, crest, edges); fences and walls are not. */
    val breakline: Boolean = true,
    /** Symbol of the points on the plan: "cruz", "poste", "arvore" or "caixa". */
    val symbol: String = "cruz",
)

/** Library of survey codes, editable as a tab-separated table. */
object PointCodes {
    /** Usual field codes of Brazilian topography; colours follow common drafting practice. */
    val defaults: List<PointCode> = listOf(
        PointCode("PE", "Pé de talude", listOf("PE", "PT", "PESAIA", "PETALUDE", "PEDETALUDE", "SAIA"), "TOPO-PE", 0xFF2E7D32.toInt()),
        PointCode("CR", "Crista de talude", listOf("CR", "CRISTA", "CT", "TOPO", "OFFSET"), "TOPO-CRISTA", 0xFFD32F2F.toInt()),
        PointCode("BO", "Bordo de pista", listOf("BO", "BORDO", "BD", "BP", "BORDA"), "TOPO-BORDO", 0xFF424242.toInt()),
        PointCode("EI", "Eixo", listOf("EI", "EIXO", "CL"), "TOPO-EIXO", 0xFFF9A825.toInt()),
        PointCode("AC", "Acostamento", listOf("AC", "ACOST", "ACOSTAMENTO"), "TOPO-ACOST", 0xFF757575.toInt()),
        PointCode("SA", "Sarjeta", listOf("SA", "SARJETA", "SJ"), "TOPO-SARJETA", 0xFF1565C0.toInt()),
        PointCode("MF", "Meio-fio", listOf("MF", "MEIOFIO", "GUIA"), "TOPO-MEIOFIO", 0xFF616161.toInt()),
        PointCode("VA", "Valeta / canaleta", listOf("VA", "VALETA", "CANALETA", "VL"), "TOPO-VALETA", 0xFF0277BD.toInt()),
        PointCode("FD", "Fundo de vala / talvegue", listOf("FD", "FUNDO", "TALVEGUE", "TV"), "TOPO-TALVEGUE", 0xFF01579B.toInt()),
        PointCode("MA", "Margem / água", listOf("MA", "MARGEM", "AGUA", "NA", "RIO", "LAGO"), "TOPO-AGUA", 0xFF0288D1.toInt()),
        PointCode("CE", "Cerca", listOf("CE", "CERCA", "CC"), "TOPO-CERCA", 0xFF8D6E63.toInt(), breakline = false),
        PointCode("MU", "Muro", listOf("MU", "MURO"), "TOPO-MURO", 0xFF5D4037.toInt(), breakline = false),
        PointCode("ED", "Edificação", listOf("ED", "EDIF", "EDIFICACAO", "CASA", "CONST"), "TOPO-EDIF", 0xFF6A1B9A.toInt(), closed = true, breakline = false),
        PointCode("PO", "Poste", listOf("PO", "POSTE", "PST"), "TOPO-POSTE", 0xFFEF6C00.toInt(), connect = false, breakline = false, symbol = "poste"),
        PointCode("AR", "Árvore", listOf("AR", "ARV", "ARVORE"), "TOPO-ARVORE", 0xFF388E3C.toInt(), connect = false, breakline = false, symbol = "arvore"),
        PointCode("BL", "Boca de lobo / PV", listOf("BL", "BOCADELOBO", "PV", "CX", "CAIXA"), "TOPO-DRENAGEM", 0xFF1976D2.toInt(), connect = false, breakline = false, symbol = "caixa"),
        PointCode("TN", "Terreno natural", listOf("TN", "TERRENO", "PTO", "P"), "TOPO-TN", 0xFF9E9E9E.toInt(), connect = false, breakline = false),
    )

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()

    /**
     * Code of a point description and the line it belongs to: "PE", "PE1", "PE 2", "pé talude 3"
     * read as code PE, lines "", "1", "2" and "3". Null when no code takes the description.
     */
    fun match(description: String, codes: List<PointCode>): Pair<PointCode, String>? {
        val tokens = plain(description).split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val head = tokens[0]
        val letters = head.takeWhile { it.isLetter() }
        val digits = head.drop(letters.length).takeWhile { it.isDigit() }
        // Whole words first ("PE TALUDE"), then the letters of the first one ("PE1").
        val joined = tokens.takeWhile { it.all(Char::isLetter) }.joinToString("")
        val code = codes.firstOrNull { c -> c.aliases.any { plain(it) == joined } }
            ?: codes.firstOrNull { c -> c.aliases.any { plain(it) == letters } } ?: return null
        val line = digits.ifEmpty { tokens.drop(1).firstOrNull { t -> t.all(Char::isDigit) }.orEmpty() }
        return code to line
    }

    fun toTsv(codes: List<PointCode>): String = codes.joinToString("\n") { c ->
        listOf(c.code, c.name, c.aliases.joinToString(","), c.layer, String.format("#%06X", c.color and 0xFFFFFF),
            if (c.connect) "linha" else "ponto", if (c.closed) "fechada" else "aberta", if (c.breakline) "obrigatoria" else "-", c.symbol).joinToString("\t")
    }

    fun fromTsv(text: String): List<PointCode> = text.lines().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 5 || f[0].isBlank()) return@mapNotNull null
        val color = f[4].trim().removePrefix("#").toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: return@mapNotNull null
        PointCode(f[0].trim(), f[1].trim(), f[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(f[0].trim()) }, f[3].trim(), color,
            f.getOrNull(5)?.trim() != "ponto", f.getOrNull(6)?.trim() == "fechada", f.getOrNull(7)?.trim()?.startsWith("obrig") ?: true, f.getOrNull(8)?.trim()?.ifEmpty { null } ?: "cruz")
    }
}

/**
 * Automatic drawing of survey points: points of the same code are joined into 3D polylines,
 * whatever order they were collected in.
 */
object Linework {
    /** One drawn line: its code, line key ("1", "2"… or ""), the points joined in order. */
    class AutoLine(val code: PointCode, val key: String, val points: List<CogoPoint>, val closed: Boolean) {
        fun polyline(): Polyline = Polyline(code.layer, points.map { it.x }.toDoubleArray(), points.map { it.y }.toDoubleArray(), closed, code.color,
            zs = points.map { it.z }.toDoubleArray().takeIf { z -> z.none { it.isNaN() } })
    }

    /**
     * Lines of [points] by their codes. Within a code (and line key), the file order is kept where it
     * already runs point to point; otherwise the closest points are joined two by two into chains,
     * the link weighted by the height difference, never across a gap much longer than the typical
     * spacing of that code.
     */
    fun build(points: List<CogoPoint>, codes: List<PointCode> = PointCodes.defaults): List<AutoLine> {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<CogoPoint>>()
        val byCode = HashMap<String, PointCode>()
        for (p in points) {
            val (c, key) = PointCodes.match(p.description, codes) ?: continue
            if (!c.connect) continue
            byCode[c.code] = c
            groups.getOrPut(c.code to key) { ArrayList() } += p
        }
        val out = ArrayList<AutoLine>()
        for ((k, pts) in groups) {
            val code = byCode.getValue(k.first)
            for (chain in chains(pts)) {
                if (chain.size < 2) continue
                val closed = code.closed && chain.size >= 3
                out += AutoLine(code, k.second, chain, closed)
            }
        }
        return out
    }

    /** Points of one line split into chains. */
    fun chains(pts: List<CogoPoint>): List<List<CogoPoint>> {
        val n = pts.size
        if (n < 2) return listOf(pts)
        fun d(a: Int, b: Int) = hypot(pts[a].x - pts[b].x, pts[a].y - pts[b].y)
        val near = nearestNeighbours(pts, 6)
        // Typical spacing: median distance to the nearest point of the same code.
        val nearest = DoubleArray(n) { i -> near[i].firstOrNull()?.let { d(i, it) } ?: 0.0 }
        val typical = nearest.sorted()[n / 2].coerceAtLeast(1e-6)
        val maxLink = typical * 4
        // File order kept when it already runs point to point.
        val inOrder = (1 until n).count { d(it - 1, it) <= maxLink }
        if (inOrder >= 0.8 * (n - 1) && (1 until n).all { d(it - 1, it) <= typical * 8 }) {
            val out = ArrayList<List<CogoPoint>>(); var cur = arrayListOf(pts[0])
            for (i in 1 until n) { if (d(i - 1, i) > maxLink) { out += cur; cur = arrayListOf() }; cur += pts[i] }
            out += cur
            return out
        }
        // Otherwise: shortest links first, each point joined to at most two, no loops.
        class Link(val a: Int, val b: Int, val cost: Double)
        val links = HashMap<Long, Link>()
        for (i in 0 until n) for (j in near[i]) {
            val dist = d(i, j)
            if (dist > maxLink) continue
            val dz = if (pts[i].z.isNaN() || pts[j].z.isNaN()) 0.0 else abs(pts[i].z - pts[j].z)
            val a = minOf(i, j); val b = maxOf(i, j)
            links[a.toLong() * n + b] = Link(a, b, dist * (1 + 0.5 * dz / max(dist, 1e-6)))
        }
        val parent = IntArray(n) { it }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val nx = parent[y]; parent[y] = r; y = nx }; return r }
        val degree = IntArray(n)
        val adj = Array(n) { ArrayList<Int>(2) }
        for (l in links.values.sortedBy { it.cost }) {
            if (degree[l.a] >= 2 || degree[l.b] >= 2) continue
            val ra = root(l.a); val rb = root(l.b)
            if (ra == rb) continue
            parent[ra] = rb; degree[l.a]++; degree[l.b]++; adj[l.a] += l.b; adj[l.b] += l.a
        }
        // Walk each chain from one end; chains start where the file has their first point.
        val seen = BooleanArray(n)
        val out = ArrayList<List<CogoPoint>>()
        for (s in 0 until n) {
            if (seen[s] || degree[s] > 1) continue
            val chain = ArrayList<CogoPoint>()
            var prev = -1; var cur = s
            while (cur >= 0 && !seen[cur]) {
                seen[cur] = true; chain += pts[cur]
                val next = adj[cur].firstOrNull { it != prev && !seen[it] } ?: -1
                prev = cur; cur = next
            }
            out += chain
        }
        return out
    }

    /** Up to [k] nearest other points of each point, closest first, found through a grid. */
    private fun nearestNeighbours(pts: List<CogoPoint>, k: Int): Array<List<Int>> {
        val n = pts.size
        val x0 = pts.minOf { it.x }; val y0 = pts.minOf { it.y }
        val w = max(pts.maxOf { it.x } - x0, 1e-6); val h = max(pts.maxOf { it.y } - y0, 1e-6)
        // Cells about one point each; a long thin spread (points along a line) uses its length.
        val cell = max(max(kotlin.math.sqrt(w * h / n), max(w, h) / n), 1e-6)
        val cols = (w / cell).toInt() + 1
        val rows = (h / cell).toInt() + 1
        val gx = IntArray(n) { ((pts[it].x - x0) / cell).toInt() }
        val gy = IntArray(n) { ((pts[it].y - y0) / cell).toInt() }
        val grid = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) grid.getOrPut(gy[i] * cols + gx[i]) { ArrayList() }.add(i)
        fun ring(i: Int, r: Int, into: MutableList<Int>) {
            for (yy in gy[i] - r..gy[i] + r) for (xx in gx[i] - r..gx[i] + r) {
                if (maxOf(abs(yy - gy[i]), abs(xx - gx[i])) != r || yy < 0 || xx < 0 || yy >= rows || xx >= cols) continue
                grid[yy * cols + xx]?.forEach { if (it != i) into.add(it) }
            }
        }
        return Array(n) { i ->
            val found = ArrayList<Int>()
            var r = 0
            while (found.size < k && r <= maxOf(cols, rows)) { ring(i, r, found); r++ }
            // One ring more: a nearer point may sit just across the last one.
            ring(i, r, found)
            found.sortedBy { hypot(pts[it].x - pts[i].x, pts[it].y - pts[i].y) }.take(k)
        }
    }

    /**
     * Extra points along [lines] every [step] metres, heights interpolated, so a triangulation
     * of the survey keeps its edges on the breaklines instead of cutting across them.
     */
    fun densify(lines: List<Polyline>, step: Double): List<CogoPoint> {
        val out = ArrayList<CogoPoint>()
        for (l in lines) {
            val zs = l.zs ?: continue
            val n = l.xs.size
            val segs = if (l.closed) n else n - 1
            for (i in 0 until segs) {
                val j = (i + 1) % n
                val len = hypot(l.xs[j] - l.xs[i], l.ys[j] - l.ys[i])
                val k = (len / step).toInt()
                for (s in 1 until k + 1) {
                    val t = s.toDouble() / (k + 1)
                    out += CogoPoint("", "", l.xs[i] + (l.xs[j] - l.xs[i]) * t, l.ys[i] + (l.ys[j] - l.ys[i]) * t, zs[i] + (zs[j] - zs[i]) * t)
                }
            }
        }
        return out
    }
}
