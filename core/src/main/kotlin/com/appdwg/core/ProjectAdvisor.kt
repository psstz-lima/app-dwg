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
                out += Advice(Advice.Kind.CORRIDOR, tr("Calcular o corredor de {0}", a.name), tr("O eixo tem greide e passa sobre {0}.", surface.name), alignment = i)
            else
                out += Advice(Advice.Kind.GRADE, tr("Sugerir o greide de {0}", a.name), tr("O eixo não tem greide e passa sobre {0}.", surface.name), alignment = i)
        }
        if (p.alignments.isEmpty()) {
            // An exploded drawing keeps the axis as a line on its own layer.
            val axis = p.polylines.withIndex().filter { (_, l) -> !l.closed && l.xs.size >= 2 && AXIS.containsMatchIn(l.layer) && onTerrain(l.xs, l.ys) }
                .maxByOrNull { (_, l) -> length(l) }
            if (axis != null) out += Advice(Advice.Kind.AXIS_FROM_LINE, tr("Usar a linha da camada {0} como eixo", axis.value.layer),
                tr("O desenho tem a linha do eixo, mas não o alinhamento; o app ajusta PIs e curvas a ela."), line = axis.index)
        }
        val pad = p.polylines.withIndex().filter { (_, l) -> l.closed && l.xs.size >= 3 && PLATFORM.containsMatchIn(l.layer) && onTerrain(l.xs, l.ys) }
            .maxByOrNull { (_, l) -> kotlin.math.abs(area(l)) }
        if (pad != null) out += Advice(Advice.Kind.PLATFORM_FROM_OUTLINE, tr("Estudar o platô da camada {0}", pad.value.layer),
            tr("Há um contorno fechado de {0}; o app sugere as formas da plataforma.", StationFormat.area(kotlin.math.abs(area(pad.value)), 0)), line = pad.index)
        if (p.alignments.isNotEmpty() || out.any { it.kind == Advice.Kind.AXIS_FROM_LINE })
            out += Advice(Advice.Kind.CULVERTS, tr("Achar os bueiros do eixo"), tr("Onde os talvegues do terreno cruzam o eixo, com a bacia de cada um."))
        if (p.alignments.isEmpty() && out.none { it.kind == Advice.Kind.AXIS_FROM_LINE })
            out += Advice(Advice.Kind.ROUTE, tr("Traçar uma estrada"), tr("Marque a saída e a chegada; o app sugere o traçado e o greide."))
        out += Advice(Advice.Kind.BASINS, tr("Bacias e pontos de lançamento"), tr("Escoamento de {0}, com a vazão de pico de cada bacia.", surface.name))
        if (terrainSurfaces.size >= 2)
            out += Advice(Advice.Kind.COMPARE, tr("Comparar {0} com {1}", terrainSurfaces[0].name, terrainSurfaces[1].name), tr("Corte e aterro entre as duas superfícies."))
        out += Advice(Advice.Kind.SLOPES, tr("Mapa de declividades"), tr("Classes de relevo de {0}.", surface.name))
        return out
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
    fun pisFromLine(l: Polyline, speed: Double = 60.0, emax: Double = 8.0, minTangent: Double = 30.0): List<Pi> {
        val pts = ArrayList<Point2>()
        for (i in l.xs.indices) { val p = Point2(l.xs[i], l.ys[i]); if (pts.isEmpty() || pts.last() != p) pts += p }
        if (pts.size < 2) return emptyList()
        // Runs of collinear chords.
        class Run(val a: Int, val b: Int) { val len get() = kotlin.math.hypot(pts[b].x - pts[a].x, pts[b].y - pts[a].y) }
        fun heading(i: Int) = kotlin.math.atan2(pts[i + 1].y - pts[i].y, pts[i + 1].x - pts[i].x)
        val runs = ArrayList<Run>()
        var a = 0
        for (i in 1 until pts.size - 1) {
            var d = kotlin.math.abs(heading(i) - heading(i - 1)); if (d > Math.PI) d = 2 * Math.PI - d
            if (d > Math.toRadians(0.2)) { runs += Run(a, i); a = i }
        }
        runs += Run(a, pts.size - 1)
        val tangents = runs.filterIndexed { k, r -> r.len >= minTangent || k == 0 || k == runs.size - 1 }
        if (tangents.size < 2) {
            val fitted = RouteDesign.fitPis(pts, RouteDesign.minRadius(speed, emax), 0.0, 1.0)
            return fitted ?: listOf(Pi(pts.first()), Pi(pts.last()))
        }
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
