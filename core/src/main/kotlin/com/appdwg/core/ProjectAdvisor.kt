package com.appdwg.core

/** Something the app suggests doing with what the opened files contain. */
data class Advice(
    val kind: Kind,
    /** What to do, in a few words, in the display language. */
    val title: String,
    /** Why the app suggests it: what it found in the files. */
    val reason: String,
    /** Alignment the advice is about (index in the project), or −1. */
    val alignment: Int = -1,
    /** Line the advice is about (index in the project's polylines), or −1. */
    val line: Int = -1,
) {
    enum class Kind { CORRIDOR, GRADE, AXIS_FROM_LINE, PLATFORM_FROM_OUTLINE, CULVERTS, BASINS, ROUTE, COMPARE, SLOPES }
}

/**
 * Reads what the opened files hold (surfaces, alignments with or without grade, axis and
 * platform lines of an exploded Civil 3D drawing) and suggests the design tools that fit, most
 * useful first.
 */
object ProjectAdvisor {
    /** Layer names that usually hold an axis or a platform outline. */
    private val AXIS = Regex("EIXO|AXIS|ALINH|CL[-_ ]|CENTERLINE", RegexOption.IGNORE_CASE)
    private val PLATFORM = Regex("PLAT|PLATO|PLATÔ|PATIO|PÁTIO|PAD", RegexOption.IGNORE_CASE)

    fun advise(p: LandXmlProject, terrainSurfaces: List<Surface> = p.surfaces): List<Advice> {
        val out = ArrayList<Advice>()
        val surface = terrainSurfaces.firstOrNull() ?: return out
        val sb = surface.bounds
        fun inside(x: Double, y: Double) = sb != null && x in sb.minX..sb.maxX && y in sb.minY..sb.maxY
        fun onTerrain(xs: DoubleArray, ys: DoubleArray) = xs.indices.any { inside(xs[it], ys[it]) }

        p.alignments.forEachIndexed { i, a ->
            if (a.vertices.none { inside(it.x, it.y) }) return@forEachIndexed
            if (a.profile != null && a.profile.pvis.size >= 2)
                out += Advice(Advice.Kind.CORRIDOR, tr("Calcular o corredor de {0}", a.name), tr("O eixo tem greide e passa sobre {0}.", surface.name.substringAfterLast('|')), alignment = i)
            else
                out += Advice(Advice.Kind.GRADE, tr("Sugerir o greide de {0}", a.name), tr("O eixo não tem greide e passa sobre {0}.", surface.name.substringAfterLast('|')), alignment = i)
        }
        if (p.alignments.isEmpty()) {
            // An exploded drawing keeps the axis as a line on its own layer, often next to its
            // offsets: of the long lines there, the middle one in length is the axis.
            val lines = p.polylines.withIndex().filter { (_, l) -> !l.closed && l.xs.size >= 2 && AXIS.containsMatchIn(l.layer) && onTerrain(l.xs, l.ys) }
            val longest = lines.maxOfOrNull { length(it.value) } ?: 0.0
            val long = lines.filter { length(it.value) >= 0.5 * longest }.sortedBy { length(it.value) }
            val axis = long.getOrNull(long.size / 2).takeIf { long.size != 2 } ?: long.lastOrNull()
            if (axis != null) out += Advice(Advice.Kind.AXIS_FROM_LINE, tr("Usar a linha da camada {0} como eixo", axis.value.layer),
                tr("O desenho tem a linha do eixo, mas não o alinhamento; o app ajusta PIs e curvas a ela."), line = axis.index)
        }
        // A platform outline: closed, or ending close to where it starts.
        val pad = p.polylines.withIndex().filter { (_, l) -> l.xs.size >= 3 && PLATFORM.containsMatchIn(l.layer) && onTerrain(l.xs, l.ys) && looksClosed(l) && kotlin.math.abs(area(l)) >= 200 }
            .maxByOrNull { (_, l) -> kotlin.math.abs(area(l)) }
        if (pad != null) out += Advice(Advice.Kind.PLATFORM_FROM_OUTLINE, tr("Estudar o platô da camada {0}", pad.value.layer),
            tr("Há um contorno fechado de {0}; o app sugere as formas da plataforma.", StationFormat.area(kotlin.math.abs(area(pad.value)), 0)), line = pad.index)
        if (p.alignments.isNotEmpty() || out.any { it.kind == Advice.Kind.AXIS_FROM_LINE })
            out += Advice(Advice.Kind.CULVERTS, tr("Achar os bueiros do eixo"), tr("Onde os talvegues do terreno cruzam o eixo, com a bacia de cada um."))
        if (p.alignments.isEmpty() && out.none { it.kind == Advice.Kind.AXIS_FROM_LINE })
            out += Advice(Advice.Kind.ROUTE, tr("Traçar uma estrada"), tr("Marque a saída e a chegada; o app sugere o traçado e o greide."))
        out += Advice(Advice.Kind.BASINS, tr("Bacias e pontos de lançamento"), tr("Escoamento de {0}, com a vazão de pico de cada bacia.", surface.name.substringAfterLast('|')))
        if (terrainSurfaces.size >= 2)
            out += Advice(Advice.Kind.COMPARE, tr("Comparar {0} com {1}", terrainSurfaces[0].name.substringAfterLast('|'), terrainSurfaces[1].name.substringAfterLast('|')), tr("Corte e aterro entre as duas superfícies."))
        out += Advice(Advice.Kind.SLOPES, tr("Mapa de declividades"), tr("Classes de relevo de {0}.", surface.name.substringAfterLast('|')))
        return out
    }

    private fun looksClosed(l: Polyline): Boolean {
        if (l.closed) return true
        val gap = kotlin.math.hypot(l.xs.last() - l.xs.first(), l.ys.last() - l.ys.first())
        return gap <= 0.15 * length(l)
    }

    private fun length(l: Polyline): Double {
        var s = 0.0
        for (i in 1 until l.xs.size) s += kotlin.math.hypot(l.xs[i] - l.xs[i - 1], l.ys[i] - l.ys[i - 1])
        return s
    }

    private fun area(l: Polyline): Double {
        var s = 0.0
        for (i in l.xs.indices) { val j = (i + 1) % l.xs.size; s += l.xs[i] * l.ys[j] - l.xs[j] * l.ys[i] }
        return s / 2
    }

    /**
     * PIs of an axis drawn as a plain line [l] (an exploded alignment, arcs as short chords): the
     * long straight runs are the tangents, each PI is where two of them meet, and the radius is
     * the one whose curve passes where the line does (external distance E = R (sec Δ/2 − 1)).
     * A line without straight runs gets curves fitted with the minimum radius for [speed] km/h.
     */
    fun pisFromLine(l: Polyline, speed: Double = 60.0, emax: Double = 8.0, minTangent: Double = 20.0): List<Pi> {
        val pts = ArrayList<Point2>()
        for (i in l.xs.indices) { val p = Point2(l.xs[i], l.ys[i]); if (pts.isEmpty() || pts.last() != p) pts += p }
        if (pts.size < 2) return emptyList()
        // Runs of chords that stay on one straight line (within 1 cm), as long as they go: arcs drawn
        // as chords leave that line within a few metres, tangents stay on it.
        class Run(val a: Int, val b: Int) { val len get() = kotlin.math.hypot(pts[b].x - pts[a].x, pts[b].y - pts[a].y) }
        fun straight(a: Int, b: Int): Boolean {
            val dx = pts[b].x - pts[a].x; val dy = pts[b].y - pts[a].y
            val len = kotlin.math.hypot(dx, dy).takeIf { it > 0 } ?: return false
            for (i in a + 1 until b) if (kotlin.math.abs((pts[i].x - pts[a].x) * dy - (pts[i].y - pts[a].y) * dx) / len > 0.01) return false
            return true
        }
        // A chord between two equal bends of one hand is part of an arc drawn with long chords,
        // not a tangent (a tangent between two curves is much longer than their chords).
        val chords = pts.size - 1
        fun chordLen(j: Int) = kotlin.math.hypot(pts[j + 1].x - pts[j].x, pts[j + 1].y - pts[j].y)
        fun turn(v: Int): Double {
            if (v <= 0 || v >= pts.size - 1) return 0.0
            var t = kotlin.math.atan2(pts[v + 1].y - pts[v].y, pts[v + 1].x - pts[v].x) - kotlin.math.atan2(pts[v].y - pts[v - 1].y, pts[v].x - pts[v - 1].x)
            while (t > Math.PI) t -= 2 * Math.PI
            while (t < -Math.PI) t += 2 * Math.PI
            return t
        }
        val arcChord = BooleanArray(chords) { j ->
            val t0 = turn(j); val t1 = turn(j + 1)
            val near = maxOf(if (j > 0) chordLen(j - 1) else 0.0, if (j + 1 < chords) chordLen(j + 1) else 0.0)
            kotlin.math.abs(t0) > Math.toRadians(0.05) && kotlin.math.abs(t1) > Math.toRadians(0.05) && t0 * t1 > 0 &&
                kotlin.math.abs(t1 / t0) in 0.4..2.5 && chordLen(j) <= 2 * near
        }
        val runs = ArrayList<Run>()
        var a = 0
        while (a < chords) {
            var b = a + 1
            if (!arcChord[a]) while (b < chords && !arcChord[b] && straight(a, b + 1)) b++
            if (!arcChord[a]) runs += Run(a, b)
            a = b
        }
        val tangents = runs.filter { r -> r.len >= minTangent || r.a == 0 || r.b == pts.size - 1 }
        fun dist(p: Point2, from: Int, to: Int): Double {
            var best = Double.MAX_VALUE
            for (i in from until to) {
                val u = pts[i]; val v = pts[i + 1]
                val dx = v.x - u.x; val dy = v.y - u.y; val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((p.x - u.x) * dx + (p.y - u.y) * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, kotlin.math.hypot(p.x - (u.x + dx * t), p.y - (u.y + dy * t)))
            }
            return best
        }
        val out = arrayListOf(Pi(pts[tangents.first().a]))
        for (k in 0 until tangents.size - 1) {
            val t1 = tangents[k]; val t2 = tangents[k + 1]
            val p1 = pts[t1.a]; val d1 = Point2(pts[t1.b].x - p1.x, pts[t1.b].y - p1.y)
            val p2 = pts[t2.a]; val d2 = Point2(pts[t2.b].x - p2.x, pts[t2.b].y - p2.y)
            val den = d1.x * d2.y - d1.y * d2.x
            val l1 = kotlin.math.hypot(d1.x, d1.y); val l2 = kotlin.math.hypot(d2.x, d2.y)
            val delta = kotlin.math.acos(((d1.x * d2.x + d1.y * d2.y) / (l1 * l2)).coerceIn(-1.0, 1.0))
            if (kotlin.math.abs(den) < 1e-9 || delta < Math.toRadians(0.1)) continue
            val t = ((p2.x - p1.x) * d2.y - (p2.y - p1.y) * d2.x) / den
            val pi = Point2(p1.x + d1.x * t, p1.y + d1.y * t)
            val e = dist(pi, t1.b, t2.a)
            val r = e / (1 / kotlin.math.cos(delta / 2) - 1)
            out += Pi(pi, if (t2.a > t1.b) r else 0.0)
        }
        out += Pi(pts[tangents.last().b])
        return out
    }
}
