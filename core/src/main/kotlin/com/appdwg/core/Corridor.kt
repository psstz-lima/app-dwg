package com.appdwg.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Point of intersection of the horizontal alignment, with its curve radius and spiral length (0 = none). */
data class Pi(val position: Point2, val radius: Double = 0.0, val spiral: Double = 0.0)

object AlignmentBuilder {
    /**
     * Alignment through [pis]: tangents joined at each inner PI by a circular curve of its radius,
     * with symmetric clothoids of its spiral length when given. The first and last PIs are the ends.
     */
    fun fromPis(name: String, pis: List<Pi>, startStation: Double = 0.0, profile: Profile? = null): Alignment {
        require(pis.size >= 2) { "o eixo precisa de 2 pontos ou mais" }
        val elements = ArrayList<AlignmentElement>()
        var cursor = pis.first().position
        for (i in 1 until pis.size - 1) {
            val a = pis[i - 1].position; val v = pis[i]; val b = pis[i + 1].position
            val tin = unit(a, v.position); val tout = unit(v.position, b)
            val cross = tin.x * tout.y - tin.y * tout.x
            val delta = acos((tin.x * tout.x + tin.y * tout.y).coerceIn(-1.0, 1.0))
            if (v.radius <= 0 || delta < 1e-9) {
                elements += AlignmentElement.Line(cursor, v.position); cursor = v.position; continue
            }
            val left = cross > 0
            val r = v.radius
            var ls = v.spiral.coerceAtLeast(0.0)
            // Spirals cannot take more than the whole deflection.
            if (ls / r > delta) ls = delta * r
            val thS = ls / (2 * r)
            val xs = ls * (1 - thS * thS / 10 + thS * thS * thS * thS / 216)
            val ys = ls * (thS / 3 - thS * thS * thS / 42)
            val p = if (ls > 0) ys - r * (1 - cos(thS)) else 0.0
            val k = if (ls > 0) xs - r * sin(thS) else 0.0
            val t = (r + p) * tan(delta / 2) + k
            val ts = Point2(v.position.x - tin.x * t, v.position.y - tin.y * t)
            val st = Point2(v.position.x + tout.x * t, v.position.y + tout.y * t)
            val nin = if (left) Point2(-tin.y, tin.x) else Point2(tin.y, -tin.x)
            val nout = if (left) Point2(-tout.y, tout.x) else Point2(tout.y, -tout.x)
            elements += AlignmentElement.Line(cursor, ts)
            if (ls > 0) {
                val sc = Point2(ts.x + tin.x * xs + nin.x * ys, ts.y + tin.y * xs + nin.y * ys)
                val cs = Point2(st.x - tout.x * xs + nout.x * ys, st.y - tout.y * xs + nout.y * ys)
                // Long tangent of the spiral: from its straight end to where both of its tangents meet.
                val lt = xs - ys / tan(thS).let { if (abs(it) < 1e-12) 1e-12 else it }
                val piIn = Point2(ts.x + tin.x * lt, ts.y + tin.y * lt)
                val piOut = Point2(st.x - tout.x * lt, st.y - tout.y * lt)
                val headSc = rotate(tin, if (left) thS else -thS)
                val nSc = if (left) Point2(-headSc.y, headSc.x) else Point2(headSc.y, -headSc.x)
                val center = Point2(sc.x + nSc.x * r, sc.y + nSc.y * r)
                elements += AlignmentElement.Spiral(ts, sc, piIn, ls, Double.POSITIVE_INFINITY, r, !left)
                elements += AlignmentElement.Arc(sc, cs, center, !left)
                elements += AlignmentElement.Spiral(cs, st, piOut, ls, r, Double.POSITIVE_INFINITY, !left)
            } else {
                val center = Point2(ts.x + nin.x * r, ts.y + nin.y * r)
                elements += AlignmentElement.Arc(ts, st, center, !left)
            }
            cursor = st
        }
        elements += AlignmentElement.Line(cursor, pis.last().position)
        return Alignment(name, startStation, elements.filter { it.length > 1e-9 }, profile)
    }

    private fun unit(a: Point2, b: Point2): Point2 { val l = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0 } ?: 1.0; return Point2((b.x - a.x) / l, (b.y - a.y) / l) }
    private fun rotate(v: Point2, a: Double) = Point2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))

    /** PVIs with curve lengths from K values: L = K × |g2 − g1| in percent. */
    fun curvesFromK(pvis: List<Profile.Pvi>, k: Double): List<Profile.Pvi> = pvis.mapIndexed { i, p ->
        if (i == 0 || i == pvis.size - 1) p else {
            val g1 = (p.elevation - pvis[i - 1].elevation) / (p.station - pvis[i - 1].station) * 100
            val g2 = (pvis[i + 1].elevation - p.elevation) / (pvis[i + 1].station - p.station) * 100
            p.copy(curveLength = k * abs(g2 - g1))
        }
    }
}

/** One side of the typical section, from the axis outward. */
data class SideTemplate(
    val laneWidth: Double = 3.5,
    /** Cross slope in percent; negative falls away from the axis. */
    val laneSlope: Double = -2.0,
    val shoulderWidth: Double = 2.5,
    val shoulderSlope: Double = -3.0,
    /** Curb and gutter ("sarjeta"): width and drop. */
    val gutterWidth: Double = 0.0,
    val gutterDepth: Double = 0.0,
    /** Ditch in cut ("valeta"): width at the top and depth (V shaped). */
    val ditchWidth: Double = 1.0,
    val ditchDepth: Double = 0.5,
)

/** Typical section ("seção tipo") with both sides, slopes and pavement thickness. */
data class Template(
    val left: SideTemplate = SideTemplate(),
    val right: SideTemplate = SideTemplate(),
    val cut: SlopeRule = SlopeRule(1.0, 8.0, 3.0, 5.0),
    val fill: SlopeRule = SlopeRule(1.5, 8.0, 3.0, 5.0),
    /** Pavement thickness: the earthwork (datum) surface lies this far below lanes and shoulders. */
    val pavement: Double = 0.5,
)

/** Template used between two stations. */
data class Region(val from: Double, val to: Double, val template: Template)

/** Superelevation at a station, percent rising to the right (negative: left side up). */
data class Superelevation(val station: Double, val percent: Double)

/** Point of a cross section: offset from the axis (right positive), elevation and what it marks. */
data class SectionPoint(val offset: Double, val z: Double, val code: String)

/** Designed cross section at one station, with its areas against the terrain. */
class RoadSection(
    val station: Double,
    val center: Point2,
    val right: Point2,
    /** Finished surface points, left to right. */
    val top: List<SectionPoint>,
    /** Earthwork (datum) points, left to right. */
    val datum: List<SectionPoint>,
    val grade: Double,
    val terrainAxis: Double?,
    val cutArea: Double,
    val fillArea: Double,
) {
    fun pointAt(offset: Double) = Point2(center.x + right.x * offset, center.y + right.y * offset)
    fun xyz(p: SectionPoint) = pointAt(p.offset).let { Point3(it.x, it.y, p.z) }
}

/** Volumes between two consecutive sections (average end areas) and the running mass balance. */
data class VolumeRow(val station: Double, val cut: Double, val fill: Double, val accumulated: Double)

class CorridorResult(
    val alignment: Alignment,
    val sections: List<RoadSection>,
    val top: Surface,
    val datum: Surface,
    /** Longitudinal lines by code (edges, shoulders, crest, toe…), each as 3D points along the road. */
    val features: Map<String, List<List<Point3>>>,
    val volumes: List<VolumeRow>,
    val unresolved: Int,
) {
    val totalCut get() = volumes.sumOf { it.cut }
    val totalFill get() = volumes.sumOf { it.fill }
}

object Corridor {
    /**
     * Corridor along [alignment] (with its design profile) over [terrain]: a section every [step]
     * metres and at the element ends and PVI curve ends, built from the region's template, with
     * superelevation from [superelevation] (interpolated; none = normal crown). [swell] multiplies the
     * fill volume in the mass balance (compaction/swell factor).
     */
    fun build(
        alignment: Alignment,
        terrain: Surface,
        regions: List<Region>,
        superelevation: List<Superelevation> = emptyList(),
        step: Double = 20.0,
        swell: Double = 1.0,
        name: String = "Corredor",
    ): CorridorResult {
        val profile = alignment.profile ?: error("o eixo precisa de greide (perfil com PVIs)")
        require(regions.isNotEmpty())
        val stations = sortedSetOf<Double>()
        var s = ceil(alignment.startStation / step) * step
        while (s < alignment.endStation) { stations += s; s += step }
        stations += alignment.startStation; stations += alignment.endStation
        var acc = alignment.startStation
        for (e in alignment.elements) { acc += e.length; stations += acc }
        for (p in profile.pvis) { stations += p.station - p.curveLength / 2; stations += p.station + p.curveLength / 2; stations += p.station }
        val stas = stations.filter { it >= alignment.startStation - 1e-6 && it <= alignment.endStation + 1e-6 && profile.elevationAt(it) != null }

        var unresolved = 0
        val sections = stas.mapNotNull { sta ->
            val tpl = regions.lastOrNull { sta >= it.from - 1e-6 && sta <= it.to + 1e-6 }?.template ?: regions.first().template
            val se = interpolateSe(superelevation, sta)
            section(alignment, profile, terrain, tpl, sta, se)?.also { if (it.second) unresolved++ }?.first
        }
        require(sections.size >= 2) { "o eixo não tem greide em pelo menos duas seções" }

        val top = surface(name, sections) { it.top }
        val datum = surface("$name (terraplenagem)", sections) { it.datum }
        val features = LinkedHashMap<String, MutableList<List<Point3>>>()
        val codes = sections.flatMap { sec -> sec.top.map { it.code } }.distinct().filter { it.isNotEmpty() && it != "talude" }
        for (code in codes) {
            for (side in listOf(-1, 1)) {
                var run = ArrayList<Point3>()
                for (sec in sections) {
                    val p = sec.top.filter { it.code == code && it.offset * side >= 0 }.let { l -> if (side < 0) l.firstOrNull() else l.lastOrNull() }
                    if (p != null) run += sec.xyz(p) else { if (run.size > 1) features.getOrPut(name(code, side)) { ArrayList() } += run; run = ArrayList() }
                }
                if (run.size > 1) features.getOrPut(name(code, side)) { ArrayList() } += run
            }
        }
        val volumes = ArrayList<VolumeRow>()
        var balance = 0.0
        volumes += VolumeRow(sections[0].station, 0.0, 0.0, 0.0)
        for (i in 1 until sections.size) {
            val a = sections[i - 1]; val b = sections[i]
            val d = b.station - a.station
            val cut = (a.cutArea + b.cutArea) / 2 * d
            val fill = (a.fillArea + b.fillArea) / 2 * d
            balance += cut - fill * swell
            volumes += VolumeRow(b.station, cut, fill, balance)
        }
        return CorridorResult(alignment, sections, top, datum, features, volumes, unresolved)
    }

    private fun name(code: String, side: Int) = code + if (side < 0) " E" else " D"

    private fun interpolateSe(table: List<Superelevation>, sta: Double): Double? {
        if (table.isEmpty()) return null
        val t = table.sortedBy { it.station }
        if (sta <= t.first().station) return t.first().percent
        if (sta >= t.last().station) return t.last().percent
        val i = t.indexOfLast { it.station <= sta }
        val a = t[i]; val b = t[i + 1]
        return a.percent + (b.percent - a.percent) * (sta - a.station) / (b.station - a.station)
    }

    /** Section at [sta]; the flag is true when a slope never met the terrain. */
    fun section(alignment: Alignment, profile: Profile, terrain: Surface, tpl: Template, sta: Double, se: Double?): Pair<RoadSection, Boolean>? {
        val c = alignment.pointAt(sta) ?: return null
        val a = alignment.pointAt(sta - 0.5)!!; val b = alignment.pointAt(sta + 0.5)!!
        val len = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0 } ?: return null
        val right = Point2((b.y - a.y) / len, -(b.x - a.x) / len)
        val grade = profile.elevationAt(sta) ?: return null
        var unresolved = false
        fun side(t: SideTemplate, sign: Int): Pair<List<SectionPoint>, List<SectionPoint>> {
            val top = ArrayList<SectionPoint>(); val datum = ArrayList<SectionPoint>()
            // Lanes rotate about the axis with superelevation: one plane across both sides.
            val laneSlope = se?.let { it * sign } ?: t.laneSlope
            var o = 0.0; var z = grade
            fun add(dw: Double, dz: Double, code: String, paved: Boolean) {
                o += dw * sign; z += dz
                top += SectionPoint(o, z, code)
                datum += SectionPoint(o, if (paved) z - tpl.pavement else z, code)
            }
            add(t.laneWidth, t.laneWidth * laneSlope / 100, "bordo", true)
            if (t.shoulderWidth > 0) add(t.shoulderWidth, t.shoulderWidth * (se?.let { s -> if (s * sign > 0) s * sign else t.shoulderSlope } ?: t.shoulderSlope) / 100, "acostamento", true)
            if (t.gutterWidth > 0) add(t.gutterWidth, -t.gutterDepth, "sarjeta", false)
            // Hinge: cut or fill decided against the terrain here.
            val hinge = Point2(c.x + right.x * o, c.y + right.y * o)
            val ground = terrain.elevationAt(hinge)
            if (ground != null && ground > z && t.ditchWidth > 0) {
                add(t.ditchWidth / 2, -t.ditchDepth, "valeta", false)
                add(t.ditchWidth / 2, t.ditchDepth, "valeta borda", false)
            }
            val start = Point3(c.x + right.x * o, c.y + right.y * o, z)
            val dir = Point2(right.x * sign, right.y * sign)
            val (pts, miss) = Platform.profile(start, dir, tpl.cut, tpl.fill, 300.0, terrain)
            if (miss) unresolved = true
            val isCut = ground != null && ground > z
            for ((k, p) in pts.withIndex()) {
                if (k == 0) continue
                val off = hypot(p.x - c.x, p.y - c.y) * sign
                val code = if (k == pts.size - 1 && !miss) (if (isCut) "crista" else "pé") else "talude"
                top += SectionPoint(off, p.z, code); datum += SectionPoint(off, p.z, code)
            }
            return top to datum
        }
        val (lt, ld) = side(tpl.left, -1)
        val (rt, rd) = side(tpl.right, 1)
        val axis = SectionPoint(0.0, grade, "eixo")
        val axisDatum = SectionPoint(0.0, grade - tpl.pavement, "eixo")
        val top = lt.reversed() + axis + rt
        val datum = ld.reversed() + axisDatum + rd
        val terrainAxis = terrain.elevationAt(c)
        val (cutA, fillA) = areas(datum, c, right, terrain)
        return RoadSection(sta, c, right, top, datum, grade, terrainAxis, cutA, fillA) to unresolved
    }

    /** Cut and fill areas between the datum line and the terrain, sampled every 0,25 m across. */
    private fun areas(datum: List<SectionPoint>, c: Point2, right: Point2, terrain: Surface): Pair<Double, Double> {
        val o0 = datum.first().offset; val o1 = datum.last().offset
        if (o1 <= o0) return 0.0 to 0.0
        val n = max(2, ceil((o1 - o0) / 0.25).toInt())
        var cut = 0.0; var fill = 0.0
        val dw = (o1 - o0) / n
        for (i in 0 until n) {
            val o = o0 + (i + 0.5) * dw
            val k = datum.indexOfLast { it.offset <= o }.coerceIn(0, datum.size - 2)
            val p = datum[k]; val q = datum[k + 1]
            val zd = if (q.offset == p.offset) p.z else p.z + (q.z - p.z) * (o - p.offset) / (q.offset - p.offset)
            val zt = terrain.elevationAt(Point2(c.x + right.x * o, c.y + right.y * o)) ?: continue
            if (zt > zd) cut += (zt - zd) * dw else fill += (zd - zt) * dw
        }
        return cut to fill
    }

    /** Strips between consecutive sections, pairing points by position across. */
    private fun surface(name: String, sections: List<RoadSection>, pts: (RoadSection) -> List<SectionPoint>): Surface {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>(); val tri = ArrayList<Int>()
        val ids = sections.map { sec -> pts(sec).map { p -> val w = sec.xyz(p); xs += w.x; ys += w.y; zs += w.z; xs.size - 1 } }
        val axisIdx = sections.map { sec -> pts(sec).indexOfFirst { it.code == "eixo" } }
        for (i in 1 until sections.size) {
            val a = ids[i - 1]; val b = ids[i]
            val ca = axisIdx[i - 1]; val cb = axisIdx[i]
            // Each side zipped from the axis outward, so lanes meet lanes and slopes meet slopes.
            for (side in listOf(-1, 1)) {
                val la = if (side < 0) (ca downTo 0).map { a[it] } else (ca until a.size).map { a[it] }
                val lb = if (side < 0) (cb downTo 0).map { b[it] } else (cb until b.size).map { b[it] }
                val k = max(la.size, lb.size)
                for (j in 0 until k - 1) {
                    val a0 = la[min(j, la.size - 1)]; val a1 = la[min(j + 1, la.size - 1)]
                    val b0 = lb[min(j, lb.size - 1)]; val b1 = lb[min(j + 1, lb.size - 1)]
                    if (a0 != a1) { tri += a0; tri += a1; tri += b0 }
                    if (b0 != b1) { tri += a1; tri += b1; tri += b0 }
                }
            }
        }
        return Surface(name, xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray(), tri.toIntArray())
    }
}
