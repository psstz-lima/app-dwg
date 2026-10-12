package com.appdwg.core

import java.io.BufferedReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reader for ASCII DXF drawings, such as a Civil 3D drawing exported or exploded to plain AutoCAD
 * objects. It keeps what can be shown in plan from the ENTITIES section:
 *
 * - 3DFACE entities and polyface meshes become TIN surfaces, one per layer, so tapping the plan
 *   gives terrain elevations. Faces that are all at one elevation (frames, legends) are drawn as lines.
 * - LINE, LWPOLYLINE, POLYLINE, CIRCLE, ARC and SPLINE become plan lines.
 * - POINT becomes a point.
 *
 * Lines keep their CAD color (entity color, else the layer's). Objects on layers that are off or
 * frozen are left out, as in the CAD program.
 *
 * Blocks (INSERT), texts, hatches and images are not drawn; the reader lists them in the warnings.
 * Civil 3D objects (alignments, surfaces) stored as custom AECC objects cannot be read from DXF.
 */
class DxfReader(private val input: BufferedReader) {

    private val polylines = ArrayList<Polyline>()
    private val points = ArrayList<CgPoint>()
    /** Triangle corners per layer, three points per triangle. */
    private val faces = LinkedHashMap<String, ArrayList<Point3>>()
    private val skipped = sortedMapOf<String, Int>()
    private var linearUnit: String? = null
    /** Layer colors (ARGB, null for the foreground color) and the layers that are off or frozen. */
    private val layerColors = HashMap<String, Int?>()
    private val hiddenLayers = HashSet<String>()
    private var hiddenCount = 0

    private var pending: Entity? = null
    private var polyline: Entity? = null
    private val vertices = ArrayList<Entity>()

    fun read(): LandXmlProject {
        var section: String? = null
        var expectSectionName = false
        var headerVariable: String? = null
        while (true) {
            val codeLine = input.readLine() ?: break
            val value = input.readLine()?.trim() ?: break
            val code = codeLine.trim().toIntOrNull() ?: error("DXF inválido perto de \"${codeLine.take(40)}\"")

            if (expectSectionName) {
                expectSectionName = false
                if (code == 2) {
                    section = value
                    continue
                }
            }
            if (code == 0) {
                if (section == "ENTITIES") finishEntity()
                if (section == "TABLES") finishTableEntry()
                when (value) {
                    "SECTION" -> expectSectionName = true
                    "ENDSEC" -> section = null
                    "EOF" -> break
                    else -> if (section == "ENTITIES" || section == "TABLES") pending = Entity(value)
                }
                continue
            }
            when (section) {
                "ENTITIES", "TABLES" -> pending?.add(code, value)
                "HEADER" -> when {
                    code == 9 -> headerVariable = value
                    headerVariable == "\$INSUNITS" && code == 70 -> linearUnit = INSUNITS[value.toIntOrNull()]
                }
            }
        }
        finishEntity()
        finishPolyline()
        return build()
    }

    private fun finishTableEntry() {
        val e = pending ?: return
        pending = null
        if (e.type != "LAYER") return
        val name = e.s(2) ?: return
        val aci = e.i(62)
        // A negative color means the layer is off; flag 1 means frozen.
        if (aci < 0 || e.i(70) and 1 != 0) hiddenLayers += name
        layerColors[name] = e.s(420)?.toIntOrNull()?.let { trueColor(it) } ?: aciColor(abs(aci))
    }

    private fun finishEntity() {
        val e = pending ?: return
        pending = null
        if (e.type != "VERTEX" && e.type != "SEQEND" && e.layer in hiddenLayers) hiddenCount++
        if (polyline != null) {
            when (e.type) {
                "VERTEX" -> { vertices += e; return }
                "SEQEND" -> { finishPolyline(); return }
                else -> finishPolyline()
            }
        }
        when (e.type) {
            "LINE" -> addLine(e, listOf(Point2(e.d(10), e.d(20)), Point2(e.d(11), e.d(21))), false)
            "LWPOLYLINE" -> readLwPolyline(e)
            "POLYLINE" -> { polyline = e; vertices.clear() }
            "CIRCLE" -> readArc(e, 0.0, 2 * PI)
            "ARC" -> readArc(e, Math.toRadians(e.d(50)), Math.toRadians(e.d(51)))
            "SPLINE" -> readSpline(e)
            "3DFACE" -> readFace(e)
            "POINT" -> points += CgPoint("", e.layer, Point3(e.d(10), e.d(20), e.d(30)))
            else -> skipped.merge(e.type, 1, Int::plus)
        }
    }

    private fun readLwPolyline(e: Entity) {
        val ocs = Ocs.of(e)
        val elevation = e.d(38)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val bulges = ArrayList<Double>()
        e.forEach { code, value ->
            when (code) {
                10 -> { xs += value.toDouble(); bulges += 0.0 }
                20 -> ys += value.toDouble()
                42 -> if (bulges.isNotEmpty()) bulges[bulges.size - 1] = value.toDouble()
            }
        }
        val closed = e.i(70) and 1 != 0
        val local = withBulges(xs.indices.map { Point2(xs[it], ys.getOrElse(it) { 0.0 }) }, bulges, closed)
        addLine(e, local.map { ocs.toPlan(it.x, it.y, elevation) }, closed)
    }

    private fun finishPolyline() {
        val p = polyline ?: return
        polyline = null
        val flags = p.i(70)
        // Spline frame control points (vertex flag 16) are not on the curve.
        val curve = vertices.filter { it.i(70) and 16 == 0 }
        when {
            flags and 64 != 0 -> readPolyfaceMesh(p)
            flags and 16 != 0 -> skipped.merge("POLYLINE (malha)", 1, Int::plus)
            flags and 8 != 0 -> addLine(p, curve.map { Point2(it.d(10), it.d(20)) }, flags and 1 != 0)
            else -> {
                val ocs = Ocs.of(p)
                val closed = flags and 1 != 0
                val local = withBulges(curve.map { Point2(it.d(10), it.d(20)) }, curve.map { it.d(42) }, closed)
                addLine(p, local.map { ocs.toPlan(it.x, it.y, p.d(30)) }, closed)
            }
        }
        vertices.clear()
    }

    private fun readPolyfaceMesh(p: Entity) {
        val corners = ArrayList<Point3>()
        val list = faces.getOrPut(p.layer) { ArrayList() }
        for (v in vertices) {
            val flags = v.i(70)
            if (flags and 128 != 0 && flags and 64 == 0) {
                // Face record: 1-based corner indices, negative when the edge is hidden.
                val idx = listOf(71, 72, 73, 74).map { abs(v.i(it)) }.filter { it > 0 }
                    .mapNotNull { corners.getOrNull(it - 1) }
                addPolygon(list, idx)
            } else {
                corners += Point3(v.d(10), v.d(20), v.d(30))
            }
        }
    }

    private fun readFace(e: Entity) {
        val c = (0..3).map { Point3(e.d(10 + it), e.d(20 + it), e.d(30 + it)) }
        addPolygon(faces.getOrPut(e.layer) { ArrayList() }, if (c[3] == c[2]) c.subList(0, 3) else c)
    }

    private fun addPolygon(list: ArrayList<Point3>, c: List<Point3>) {
        if (c.size < 3) return
        // Fan triangulation; DXF faces are triangles or convex quads.
        for (k in 1 until c.size - 1) {
            list += c[0]; list += c[k]; list += c[k + 1]
        }
    }

    private fun readArc(e: Entity, start: Double, endAngle: Double) {
        val r = e.d(40)
        if (r <= 0) return
        val ocs = Ocs.of(e)
        var end = endAngle
        while (end <= start) end += 2 * PI
        val sweep = end - start
        val n = max(8, ceil(sweep / ARC_STEP).toInt())
        val cx = e.d(10)
        val cy = e.d(20)
        val pts = (0..n).map { k ->
            val a = start + sweep * k / n
            ocs.toPlan(cx + r * cos(a), cy + r * sin(a), e.d(30))
        }
        addLine(e, pts, false)
    }

    private fun readSpline(e: Entity) {
        // Fit points lie on the curve; without them the control polygon is a close enough outline.
        val fit = e.points(11, 21)
        addLine(e, fit.ifEmpty { e.points(10, 20) }, e.i(70) and 1 != 0)
    }

    private fun addLine(e: Entity, pts: List<Point2>, closed: Boolean) = addLine(e.layer, colorOf(e), pts, closed)

    private fun addLine(layer: String, color: Int?, pts: List<Point2>, closed: Boolean) {
        if (pts.size < 2 || layer in hiddenLayers) return
        polylines += Polyline(layer, DoubleArray(pts.size) { pts[it].x }, DoubleArray(pts.size) { pts[it].y }, closed, color)
    }

    /** Entity color: true color (420), else ACI (62), where 256 is "by layer" and 0 "by block". */
    private fun colorOf(e: Entity): Int? {
        e.s(420)?.toIntOrNull()?.let { return trueColor(it) }
        val aci = e.s(62)?.trim()?.toIntOrNull() ?: 256
        return if (aci == 256 || aci == 0) layerColors[e.layer] else aciColor(abs(aci))
    }

    private fun build(): LandXmlProject {
        val surfaces = ArrayList<Surface>()
        for ((layer, corners) in faces) {
            if (corners.isEmpty() || layer in hiddenLayers) continue
            val minZ = corners.minOf { it.z }
            val maxZ = corners.maxOf { it.z }
            if (maxZ - minZ < 1e-6) {
                // Flat faces are drawing elements (frames, boxes), not terrain.
                for (t in corners.indices step 3) {
                    addLine(layer, layerColors[layer], corners.subList(t, t + 3).map { Point2(it.x, it.y) }, true)
                }
            } else {
                surfaces += toSurface(layer, corners)
            }
        }
        val warnings = skipped.filterKeys { it !in IGNORED }.map { (type, n) -> "Não exibidos: $n ${SKIPPED_NAMES[type] ?: type}" } +
            listOfNotNull(if (hiddenCount > 0) "Não exibidos: $hiddenCount objetos em camadas desligadas ou congeladas" else null)
        return LandXmlProject(
            linearUnit = linearUnit,
            coordinateSystem = null,
            surfaces = surfaces,
            alignments = emptyList(),
            cgPoints = points.filter { it.code !in hiddenLayers },
            warnings = warnings,
            polylines = polylines,
        )
    }

    private fun toSurface(layer: String, corners: List<Point3>): Surface {
        val index = HashMap<Point3, Int>()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val zs = ArrayList<Double>()
        val triangles = IntArray(corners.size) { k ->
            index.getOrPut(corners[k]) {
                xs += corners[k].x; ys += corners[k].y; zs += corners[k].z
                xs.size - 1
            }
        }
        return Surface(layer, xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray(), triangles)
    }

    /** One DXF entity: its type and its group codes in file order. */
    private class Entity(val type: String) {
        private val codes = ArrayList<Int>()
        private val values = ArrayList<String>()

        fun add(code: Int, value: String) { codes += code; values += value }

        val layer get() = s(8) ?: "0"

        fun s(code: Int): String? = codes.indexOf(code).let { if (it < 0) null else values[it] }
        fun d(code: Int): Double = s(code)?.toDoubleOrNull() ?: 0.0
        fun i(code: Int): Int = s(code)?.trim()?.toIntOrNull() ?: 0

        inline fun forEach(action: (Int, String) -> Unit) {
            for (k in codes.indices) action(codes[k], values[k])
        }

        fun points(xCode: Int, yCode: Int): List<Point2> {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            forEach { code, value ->
                if (code == xCode) xs += value.toDouble()
                if (code == yCode) ys += value.toDouble()
            }
            return (0 until minOf(xs.size, ys.size)).map { Point2(xs[it], ys[it]) }
        }
    }

    /**
     * Object coordinate system of 2D entities (DXF "arbitrary axis algorithm"). Polylines, circles
     * and arcs store their points in it; [toPlan] returns the world X/Y.
     */
    private class Ocs(nx: Double, ny: Double, nz: Double) {
        private val n: DoubleArray
        private val ax: DoubleArray
        private val ay: DoubleArray

        init {
            n = normalize(doubleArrayOf(nx, ny, nz))
            ax = normalize(
                if (abs(n[0]) < 1.0 / 64 && abs(n[1]) < 1.0 / 64) cross(doubleArrayOf(0.0, 1.0, 0.0), n)
                else cross(doubleArrayOf(0.0, 0.0, 1.0), n),
            )
            ay = normalize(cross(n, ax))
        }

        fun toPlan(x: Double, y: Double, z: Double) =
            Point2(x * ax[0] + y * ay[0] + z * n[0], x * ax[1] + y * ay[1] + z * n[1])

        companion object {
            private val WORLD = Ocs(0.0, 0.0, 1.0)

            fun of(e: Entity): Ocs {
                val z = e.s(230)?.toDoubleOrNull() ?: return WORLD
                val x = e.d(210)
                val y = e.d(220)
                return if (x == 0.0 && y == 0.0 && z > 0) WORLD else Ocs(x, y, z)
            }

            private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
                a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
            )

            private fun normalize(v: DoubleArray): DoubleArray {
                val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                return if (len == 0.0) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
            }
        }
    }

    companion object {
        /** Arc segments are at most this many radians, about 5 degrees. */
        private const val ARC_STEP = PI / 36

        private val INSUNITS = mapOf(1 to "inch", 2 to "foot", 4 to "millimeter", 5 to "centimeter", 6 to "meter", 7 to "kilometer")

        /** Entities with nothing to show in plan, left out of the warnings. */
        private val IGNORED = setOf("VIEWPORT", "SEQEND", "VERTEX")

        private val SKIPPED_NAMES = mapOf(
            "INSERT" to "blocos (INSERT)",
            "TEXT" to "textos (TEXT)",
            "MTEXT" to "textos (MTEXT)",
            "HATCH" to "hachuras (HATCH)",
            "DIMENSION" to "cotas (DIMENSION)",
            "IMAGE" to "imagens (IMAGE)",
        )

        private fun trueColor(rgb: Int) = (0xFF shl 24) or (rgb and 0xFFFFFF)

        /**
         * AutoCAD Color Index to ARGB. Color 7 (white on dark, black on light backgrounds) is null so
         * the app can use its foreground color.
         */
        internal fun aciColor(aci: Int): Int? {
            val base = intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF)
            val grays = intArrayOf(0x333333, 0x505050, 0x696969, 0x828282, 0xBEBEBE, 0xFFFFFF)
            return when (aci) {
                in 1..6 -> trueColor(base[aci - 1])
                7 -> null
                8 -> trueColor(0x808080)
                9 -> trueColor(0xC0C0C0)
                in 10..249 -> {
                    // Hue steps of 15 degrees; within each group of ten, darker shades with even
                    // entries saturated and odd entries pale.
                    val hue = (aci / 10 - 1) * 15f
                    val shade = aci % 10
                    val value = floatArrayOf(1f, 0.65f, 0.5f, 0.3f, 0.15f)[shade / 2]
                    val sat = if (shade % 2 == 0) 1f else 0.5f
                    hsv(hue, sat, value)
                }
                in 250..255 -> trueColor(grays[aci - 250])
                else -> null
            }
        }

        private fun hsv(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1 - abs((h / 60f) % 2 - 1))
            val m = v - c
            val (r, g, b) = when ((h / 60f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun byte(f: Float) = ((f + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
            return trueColor((byte(r) shl 16) or (byte(g) shl 8) or byte(b))
        }

        /** True when [head], the first bytes of a file, look like an ASCII DXF drawing. */
        fun looksLikeDxf(head: String): Boolean {
            val lines = head.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
            return lines == listOf("0", "SECTION")
        }

        /**
         * Points along a polyline with arc segments. [bulges] holds, per vertex, the tangent of a
         * quarter of the arc angle to the next vertex (0 for a straight segment).
         */
        internal fun withBulges(pts: List<Point2>, bulges: List<Double>, closed: Boolean): List<Point2> {
            if (bulges.all { it == 0.0 }) return pts
            val out = ArrayList<Point2>()
            val segments = if (closed) pts.size else pts.size - 1
            for (k in 0 until segments) {
                val a = pts[k]
                val b = pts[(k + 1) % pts.size]
                out += a
                val bulge = bulges.getOrElse(k) { 0.0 }
                val chord = hypot(b.x - a.x, b.y - a.y)
                if (bulge == 0.0 || chord == 0.0) continue
                val sweep = 4 * atan(bulge)
                // The centre lies on the chord's perpendicular bisector, to the left for a positive bulge.
                val d = chord * (1 - bulge * bulge) / (4 * bulge)
                val cx = (a.x + b.x) / 2 - (b.y - a.y) / chord * d
                val cy = (a.y + b.y) / 2 + (b.x - a.x) / chord * d
                val r = hypot(a.x - cx, a.y - cy)
                val start = atan2(a.y - cy, a.x - cx)
                val n = max(2, ceil(abs(sweep) / ARC_STEP).toInt())
                for (i in 1 until n) {
                    val t = start + sweep * i / n
                    out += Point2(cx + r * cos(t), cy + r * sin(t))
                }
            }
            if (!closed) out += pts.last()
            return out
        }
    }
}
