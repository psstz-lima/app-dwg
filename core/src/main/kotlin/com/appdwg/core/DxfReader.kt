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
 * objects. It keeps what can be shown in plan from model space:
 *
 * - 3DFACE entities and polyface meshes become TIN surfaces, one per layer, so tapping the plan
 *   gives terrain elevations. Faces that are all at one elevation (frames, legends) are drawn as lines.
 * - LINE, LWPOLYLINE, POLYLINE, CIRCLE, ARC, ELLIPSE and SPLINE become plan lines.
 * - TEXT and MTEXT become texts; HATCH becomes a filled area; POINT becomes a point.
 * - INSERT draws its block, moved, scaled and rotated.
 *
 * Everything keeps its CAD layer and color (entity color, else the layer's). Layer states (off,
 * frozen) and colors are returned too, so the app starts with the drawing's layer settings.
 *
 * Civil 3D objects (alignments, surfaces) stored as custom AECC objects cannot be read from DXF.
 */
class DxfReader(private val input: BufferedReader) {

    private val polylines = ArrayList<Polyline>()
    private val texts = ArrayList<PlanText>()
    private val hatches = ArrayList<Hatch>()
    private val points = ArrayList<CgPoint>()
    /** Triangle corners per layer, three points per triangle. */
    private val faces = LinkedHashMap<String, ArrayList<Point3>>()
    private val skipped = sortedMapOf<String, Int>()
    private var linearUnit: String? = null
    /** Layer colors (ARGB, null for the foreground color) and the layers that are off or frozen. */
    private val layerColors = HashMap<String, Int?>()
    private val layersOff = LinkedHashSet<String>()
    private val layersFrozen = LinkedHashSet<String>()

    private val modelSpace = ArrayList<Entity>()
    private val blocks = HashMap<String, Block>()

    fun read(): LandXmlProject {
        parse()
        draw(modelSpace, Xf.IDENTITY, Context("0", null, true), 0)
        return build()
    }

    // ---- Parsing: group codes into entities ----

    private fun parse() {
        var section: String? = null
        var expectSectionName = false
        var headerVariable: String? = null
        var pending: Entity? = null
        var block: Block? = null

        fun finish() {
            val e = pending ?: return
            pending = null
            when (section) {
                "TABLES" -> if (e.type == "LAYER") addLayer(e)
                "BLOCKS" -> when (e.type) {
                    "BLOCK" -> block = Block(e.d(10), e.d(20)).also { b -> e.s(2)?.let { blocks[it] = b } }
                    "ENDBLK" -> block = null
                    else -> block?.entities?.add(e)
                }
                // Group 67 = 1 marks paper space (layouts), which is not part of the plan.
                "ENTITIES" -> if (e.i(67) != 1) modelSpace += e
            }
        }

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
                finish()
                when (value) {
                    "SECTION" -> expectSectionName = true
                    "ENDSEC" -> section = null
                    "EOF" -> break
                    else -> if (section == "ENTITIES" || section == "TABLES" || section == "BLOCKS") pending = Entity(value)
                }
                continue
            }
            when (section) {
                "ENTITIES", "TABLES", "BLOCKS" -> pending?.add(code, value)
                "HEADER" -> when {
                    code == 9 -> headerVariable = value
                    headerVariable == "\$INSUNITS" && code == 70 -> linearUnit = INSUNITS[value.toIntOrNull()]
                }
            }
        }
        finish()
    }

    private fun addLayer(e: Entity) {
        val name = e.s(2) ?: return
        val aci = e.i(62)
        // A negative color means the layer is off; flag 1 means frozen.
        if (aci < 0) layersOff += name
        if (e.i(70) and 1 != 0) layersFrozen += name
        layerColors[name] = e.s(420)?.toIntOrNull()?.let { trueColor(it) } ?: aciColor(abs(aci))
    }

    // ---- Drawing: entities to plan geometry ----

    /** Layer and color an INSERT passes to block entities on layer 0 or colored "by block". */
    private class Context(val layer: String, val color: Int?, val byLayer: Boolean)

    private fun draw(entities: List<Entity>, xf: Xf, ctx: Context, depth: Int) {
        var k = 0
        while (k < entities.size) {
            val e = entities[k++]
            val layer = if (e.layer == "0") ctx.layer else e.layer
            val (color, byLayer) = colorOf(e, layer, ctx)
            val out = Out(layer, color, byLayer, xf)
            when (e.type) {
                "LINE" -> out.line3(listOf(Point3(e.d(10), e.d(20), e.d(30)), Point3(e.d(11), e.d(21), e.d(31))), false)
                "LWPOLYLINE" -> readLwPolyline(e, out)
                "POLYLINE" -> {
                    val vertices = ArrayList<Entity>()
                    while (k < entities.size && entities[k].type == "VERTEX") vertices += entities[k++]
                    if (k < entities.size && entities[k].type == "SEQEND") k++
                    readPolyline(e, vertices, out)
                }
                "CIRCLE" -> readArc(e, 0.0, 2 * PI, out)
                "ARC" -> readArc(e, Math.toRadians(e.d(50)), Math.toRadians(e.d(51)), out)
                "ELLIPSE" -> out.line(ellipse(e.d(10), e.d(20), e.d(11), e.d(21), e.d(40), e.d(41), e.s(42)?.toDoubleOrNull() ?: (2 * PI), true), false)
                "SPLINE" -> {
                    // Fit points lie on the curve; without them the control polygon is a close enough outline.
                    out.line(e.points(11, 21).ifEmpty { e.points(10, 20) }, e.i(70) and 1 != 0)
                }
                "3DFACE" -> {
                    val c = (0..3).map { Point3(e.d(10 + it), e.d(20 + it), e.d(30 + it)) }
                    out.face(if (c[3] == c[2]) c.subList(0, 3) else c)
                }
                "POINT" -> out.point(e.d(10), e.d(20), e.d(30))
                "TEXT" -> readText(e, out)
                "MTEXT" -> readMText(e, out)
                "HATCH" -> readHatch(e, out)
                "INSERT" -> readInsert(e, xf, Context(layer, color, byLayer), depth)
                "VERTEX", "SEQEND", "VIEWPORT" -> {}
                else -> skipped.merge(e.type, 1, Int::plus)
            }
        }
    }

    private fun readInsert(e: Entity, xf: Xf, ctx: Context, depth: Int) {
        val block = blocks[e.s(2)] ?: return
        if (depth > 8) return
        val sx = e.s(41)?.toDoubleOrNull() ?: 1.0
        val sy = e.s(42)?.toDoubleOrNull() ?: 1.0
        val sz = e.s(43)?.toDoubleOrNull() ?: 1.0
        val rot = Math.toRadians(e.d(50))
        val cols = max(1, e.i(70))
        val rows = max(1, e.i(71))
        val ocs = Ocs.of(e)
        for (r in 0 until rows) for (c in 0 until cols) {
            // Array inserts (MINSERT) repeat the block along the rotated rows and columns.
            val ox = e.d(10) + c * e.d(44) * cos(rot) - r * e.d(45) * sin(rot)
            val oy = e.d(20) + c * e.d(44) * sin(rot) + r * e.d(45) * cos(rot)
            val local = Xf.translate(ox, oy, e.d(30))
                .times(Xf.rotate(rot))
                .times(Xf.scale(sx, sy, sz))
                .times(Xf.translate(-block.baseX, -block.baseY, 0.0))
            draw(block.entities, xf.times(ocs.asXf()).times(local), ctx, depth + 1)
        }
    }

    private fun readLwPolyline(e: Entity, out: Out) {
        val ocs = Ocs.of(e)
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
        out.line3(local.map { ocs.toWorld(it.x, it.y, e.d(38)) }, closed)
    }

    private fun readPolyline(p: Entity, vertices: List<Entity>, out: Out) {
        val flags = p.i(70)
        // Spline frame control points (vertex flag 16) are not on the curve.
        val curve = vertices.filter { it.i(70) and 16 == 0 }
        when {
            flags and 64 != 0 -> {
                val corners = ArrayList<Point3>()
                for (v in vertices) {
                    val vf = v.i(70)
                    if (vf and 128 != 0 && vf and 64 == 0) {
                        // Face record: 1-based corner indices, negative when the edge is hidden.
                        out.face(listOf(71, 72, 73, 74).map { abs(v.i(it)) }.filter { it > 0 }.mapNotNull { corners.getOrNull(it - 1) })
                    } else {
                        corners += Point3(v.d(10), v.d(20), v.d(30))
                    }
                }
            }
            flags and 16 != 0 -> skipped.merge("POLYLINE (malha)", 1, Int::plus)
            flags and 8 != 0 -> out.line3(curve.map { Point3(it.d(10), it.d(20), it.d(30)) }, flags and 1 != 0)
            else -> {
                val ocs = Ocs.of(p)
                val closed = flags and 1 != 0
                val local = withBulges(curve.map { Point2(it.d(10), it.d(20)) }, curve.map { it.d(42) }, closed)
                out.line3(local.map { ocs.toWorld(it.x, it.y, p.d(30)) }, closed)
            }
        }
    }

    private fun readArc(e: Entity, start: Double, end: Double, out: Out) {
        val r = e.d(40)
        if (r <= 0) return
        val ocs = Ocs.of(e)
        out.line3(arc(e.d(10), e.d(20), r, start, end, true).map { ocs.toWorld(it.x, it.y, e.d(30)) }, false)
    }

    private fun readText(e: Entity, out: Out) {
        val text = decodeText(e.s(1) ?: return)
        val h = e.i(72)
        val v = e.i(73)
        // Justified texts are placed by the second alignment point.
        val aligned = h != 0 || v != 0
        val ocs = Ocs.of(e)
        val p = if (aligned) ocs.toPlan(e.d(11), e.d(21), e.d(31)) else ocs.toPlan(e.d(10), e.d(20), e.d(30))
        val hAlign = when (h) { 1, 4 -> 1; 2 -> 2; else -> 0 }
        val vAlign = when {
            h == 4 -> PlanText.MIDDLE
            v == 1 -> PlanText.BOTTOM
            v == 2 -> PlanText.MIDDLE
            v == 3 -> PlanText.TOP
            else -> PlanText.BASELINE
        }
        out.text(p, e.d(40), Math.toRadians(e.d(50)), text, hAlign, vAlign)
    }

    private fun readMText(e: Entity, out: Out) {
        // Long contents are split into 3-group chunks followed by the final 1 group.
        val raw = buildString {
            e.forEach { code, value -> if (code == 3) append(value) }
            append(e.s(1) ?: "")
        }
        val text = decodeMText(raw).trim()
        if (text.isEmpty()) return
        val rotation = if (e.s(11) != null) atan2(e.d(21), e.d(11)) else Math.toRadians(e.d(50))
        val attach = e.i(71).coerceIn(1, 9)
        val vAlign = when ((attach - 1) / 3) { 0 -> PlanText.TOP; 1 -> PlanText.MIDDLE; else -> PlanText.BOTTOM }
        out.text(Point2(e.d(10), e.d(20)), e.d(40), rotation, text, (attach - 1) % 3, vAlign)
    }

    private fun readHatch(e: Entity, out: Out) {
        val ocs = Ocs.of(e)
        val loops = HatchCursor(e).loops().map { loop -> loop.map { ocs.toPlan(it.x, it.y, e.d(30)) } }
        out.hatch(loops.filter { it.size >= 3 })
    }

    /** Reads hatch boundary loops from the group codes, which follow a fixed order. */
    private class HatchCursor(private val e: Entity) {
        private var k = 0

        private fun next(code: Int): String? {
            while (k < e.size) {
                if (e.code(k) == code) return e.value(k++)
                k++
            }
            return null
        }

        private fun nextD(code: Int) = next(code)?.toDoubleOrNull() ?: 0.0
        private fun nextI(code: Int) = next(code)?.trim()?.toIntOrNull() ?: 0

        fun loops(): List<List<Point2>> {
            val count = nextI(91)
            val loops = ArrayList<List<Point2>>()
            repeat(count) {
                val flags = nextI(92)
                if (flags and 2 != 0) {
                    val hasBulge = nextI(72) != 0
                    nextI(73) // closed flag; hatch boundaries always close
                    val n = nextI(93)
                    val pts = ArrayList<Point2>()
                    val bulges = ArrayList<Double>()
                    repeat(n) {
                        pts += Point2(nextD(10), nextD(20))
                        bulges += if (hasBulge && k < e.size && e.code(k) == 42) e.value(k++).toDouble() else 0.0
                    }
                    loops += withBulges(pts, bulges, true)
                } else {
                    val pts = ArrayList<Point2>()
                    repeat(nextI(93)) {
                        when (nextI(72)) {
                            1 -> { pts += Point2(nextD(10), nextD(20)); pts += Point2(nextD(11), nextD(21)) }
                            2 -> {
                                val cx = nextD(10); val cy = nextD(20); val r = nextD(40)
                                val s = Math.toRadians(nextD(50)); val en = Math.toRadians(nextD(51))
                                pts += arc(cx, cy, r, s, en, nextI(73) != 0)
                            }
                            3 -> {
                                val cx = nextD(10); val cy = nextD(20); val mx = nextD(11); val my = nextD(21)
                                val ratio = nextD(40)
                                val s = Math.toRadians(nextD(50)); val en = Math.toRadians(nextD(51))
                                pts += ellipse(cx, cy, mx, my, ratio, s, en, nextI(73) != 0)
                            }
                            4 -> {
                                // Spline edge: the control polygon is drawn.
                                nextI(94)
                                val knots = nextI(95)
                                val ctrl = nextI(96)
                                repeat(knots) { next(40) }
                                repeat(ctrl) { pts += Point2(nextD(10), nextD(20)) }
                            }
                        }
                    }
                    loops += pts
                }
                // Source boundary objects, skipped.
                repeat(nextI(97)) { next(330) }
            }
            return loops
        }
    }

    /** Sink for one entity's geometry: applies the block transform and records layer and color. */
    private inner class Out(val layer: String, val color: Int?, val byLayer: Boolean, val xf: Xf) {
        /** Line with elevations; they are kept only when not all zero, so 2D drawings stay flat. */
        fun line3(pts: List<Point3>, closed: Boolean) {
            if (pts.size < 2) return
            val w = pts.map { xf.apply(it) }
            val zs = if (w.any { it.z != 0.0 }) DoubleArray(w.size) { w[it].z } else null
            polylines += Polyline(layer, DoubleArray(w.size) { w[it].x }, DoubleArray(w.size) { w[it].y }, closed, color, byLayer, zs)
        }

        fun line(pts: List<Point2>, closed: Boolean) {
            if (pts.size < 2) return
            val w = pts.map { xf.apply(it) }
            polylines += Polyline(layer, DoubleArray(w.size) { w[it].x }, DoubleArray(w.size) { w[it].y }, closed, color, byLayer)
        }

        fun face(c: List<Point3>) {
            if (c.size < 3) return
            val list = faces.getOrPut(layer) { ArrayList() }
            val w = c.map { xf.apply(it) }
            // Fan triangulation; DXF faces are triangles or convex quads.
            for (k in 1 until w.size - 1) {
                list += w[0]; list += w[k]; list += w[k + 1]
            }
        }

        fun point(x: Double, y: Double, z: Double) {
            points += CgPoint("", layer, xf.apply(Point3(x, y, z)))
        }

        fun text(p: Point2, height: Double, rotation: Double, text: String, hAlign: Int, vAlign: Int) {
            if (height <= 0 || text.isBlank()) return
            val w = xf.apply(p)
            texts += PlanText(layer, color, byLayer, w.x, w.y, height * xf.scale, rotation + xf.rotation, text, hAlign, vAlign)
        }

        fun hatch(loops: List<List<Point2>>) {
            if (loops.isEmpty()) return
            hatches += Hatch(layer, color, byLayer, loops.map { l ->
                val w = l.map { xf.apply(it) }
                Polyline(layer, DoubleArray(w.size) { w[it].x }, DoubleArray(w.size) { w[it].y }, true, color)
            })
        }
    }

    /**
     * Entity color and whether it follows the layer: true color (420), else ACI (62), where 256 is
     * "by layer" and 0 "by block" (the insert's color).
     */
    private fun colorOf(e: Entity, layer: String, ctx: Context): Pair<Int?, Boolean> {
        e.s(420)?.toIntOrNull()?.let { return trueColor(it) to false }
        return when (val aci = e.s(62)?.trim()?.toIntOrNull() ?: 256) {
            256 -> layerColors[layer] to true
            0 -> ctx.color to (ctx.byLayer && layer == ctx.layer)
            else -> aciColor(abs(aci)) to false
        }
    }

    private fun build(): LandXmlProject {
        val surfaces = ArrayList<Surface>()
        for ((layer, corners) in faces) {
            if (corners.isEmpty()) continue
            val minZ = corners.minOf { it.z }
            val maxZ = corners.maxOf { it.z }
            if (maxZ - minZ < 1e-6) {
                // Flat faces are drawing elements (frames, boxes), not terrain.
                val out = Out(layer, layerColors[layer], true, Xf.IDENTITY)
                for (t in corners.indices step 3) out.line(corners.subList(t, t + 3).map { Point2(it.x, it.y) }, true)
            } else {
                surfaces += toSurface(layer, corners)
            }
        }
        val warnings = skipped.map { (type, n) -> "Não exibidos: $n ${SKIPPED_NAMES[type] ?: type}" }
        return LandXmlProject(
            linearUnit = linearUnit,
            coordinateSystem = null,
            surfaces = surfaces,
            alignments = emptyList(),
            cgPoints = points,
            warnings = warnings,
            polylines = polylines,
            texts = texts,
            hatches = hatches,
            layersOff = layersOff,
            layersFrozen = layersFrozen,
            layerColors = layerColors,
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

    private class Block(val baseX: Double, val baseY: Double) {
        val entities = ArrayList<Entity>()
    }

    /** One DXF entity: its type and its group codes in file order. */
    private class Entity(val type: String) {
        private val codes = ArrayList<Int>()
        private val values = ArrayList<String>()

        fun add(code: Int, value: String) { codes += code; values += value }

        val size get() = codes.size
        fun code(k: Int) = codes[k]
        fun value(k: Int) = values[k]

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

    /** Plan transform of a block insert: x' = a·x + b·y + c, y' = d·x + e·y + f, z' = sz·z + tz. */
    private class Xf(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double,
        val sz: Double = 1.0, val tz: Double = 0.0,
    ) {
        fun apply(p: Point2) = Point2(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
        fun apply(p: Point3) = Point3(a * p.x + b * p.y + c, d * p.x + e * p.y + f, sz * p.z + tz)

        fun times(o: Xf) = Xf(
            a * o.a + b * o.d, a * o.b + b * o.e, a * o.c + b * o.f + c,
            d * o.a + e * o.d, d * o.b + e * o.e, d * o.c + e * o.f + f,
            sz * o.sz, sz * o.tz + tz,
        )

        val scale get() = hypot(a, d)
        val rotation get() = atan2(d, a)

        companion object {
            val IDENTITY = Xf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            fun translate(x: Double, y: Double, z: Double) = Xf(1.0, 0.0, x, 0.0, 1.0, y, 1.0, z)
            fun scale(x: Double, y: Double, z: Double) = Xf(x, 0.0, 0.0, 0.0, y, 0.0, z, 0.0)
            fun rotate(r: Double) = Xf(cos(r), -sin(r), 0.0, sin(r), cos(r), 0.0)
        }
    }

    /**
     * Object coordinate system of 2D entities (DXF "arbitrary axis algorithm"). Polylines, circles,
     * arcs, texts, hatches and inserts store their points in it; [toPlan] returns the world X/Y.
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

        fun toWorld(x: Double, y: Double, z: Double) = Point3(
            x * ax[0] + y * ay[0] + z * n[0], x * ax[1] + y * ay[1] + z * n[1], x * ax[2] + y * ay[2] + z * n[2],
        )

        fun toPlan(x: Double, y: Double, z: Double) =
            Point2(x * ax[0] + y * ay[0] + z * n[0], x * ax[1] + y * ay[1] + z * n[1])

        /** The plan part of this system, for block inserts (their elevation is ignored). */
        fun asXf() = if (this === WORLD) Xf.IDENTITY else Xf(ax[0], ay[0], 0.0, ax[1], ay[1], 0.0)

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

        private val SKIPPED_NAMES = mapOf(
            "DIMENSION" to "cotas (DIMENSION)",
            "IMAGE" to "imagens (IMAGE)",
            "LEADER" to "chamadas (LEADER)",
            "MULTILEADER" to "chamadas (MULTILEADER)",
        )

        private fun trueColor(rgb: Int) = (0xFF shl 24) or (rgb and 0xFFFFFF)

        /**
         * AutoCAD Color Index to ARGB. Color 7 (white on dark, black on light backgrounds) is null so
         * the app can use its foreground color.
         */
        fun aciColor(aci: Int): Int? {
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

        /** TEXT control codes: %%d degree, %%c diameter, %%p plus-minus; underline/overline toggles dropped. */
        internal fun decodeText(s: String): String = s
            .replace("%%d", "°", ignoreCase = true)
            .replace("%%c", "Ø", ignoreCase = true)
            .replace("%%p", "±", ignoreCase = true)
            .replace("%%u", "", ignoreCase = true)
            .replace("%%o", "", ignoreCase = true)
            .replace("%%%", "%")

        /** Plain text of MTEXT contents: paragraph breaks become new lines and formatting is removed. */
        internal fun decodeMText(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '\\' && i + 1 < s.length -> {
                        val code = s[i + 1]
                        i += 2
                        when (code) {
                            'P' -> out.append('\n')
                            '~' -> out.append(' ')
                            '\\', '{', '}' -> out.append(code)
                            'S' -> {
                                // Stacked fraction "\Sa^b;" or "\Sa/b;" shown as a/b.
                                val end = s.indexOf(';', i).let { if (it < 0) s.length else it }
                                out.append(s.substring(i, end).replace('^', '/').replace('#', '/'))
                                i = end + 1
                            }
                            'L', 'l', 'O', 'o', 'K', 'k', 'N' -> {}
                            'U' -> if (s.startsWith("+", i) && i + 5 <= s.length) {
                                s.substring(i + 1, i + 5).toIntOrNull(16)?.let { out.append(it.toChar()) }
                                i += 5
                            }
                            else -> {
                                // \F, \H, \C, \T, \Q, \W, \A, \p and similar take a value up to ';'.
                                val end = s.indexOf(';', i)
                                if (end >= 0) i = end + 1
                            }
                        }
                    }
                    ch == '{' || ch == '}' -> i++
                    else -> { out.append(ch); i++ }
                }
            }
            return decodeText(out.toString())
        }

        /** True when [head], the first bytes of a file, look like an ASCII DXF drawing. */
        fun looksLikeDxf(head: String): Boolean {
            val lines = head.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
            return lines == listOf("0", "SECTION")
        }

        /** Arc points from [start] to [end] (radians), counter-clockwise, or clockwise as hatch edges store them. */
        internal fun arc(cx: Double, cy: Double, r: Double, start: Double, end: Double, ccw: Boolean): List<Point2> {
            var s = start
            var e = end
            if (!ccw) { s = -start; e = -end }
            if (ccw) { while (e <= s) e += 2 * PI } else { while (e >= s) e -= 2 * PI }
            val sweep = e - s
            val n = max(8, ceil(abs(sweep) / ARC_STEP).toInt())
            return (0..n).map { k ->
                val a = s + sweep * k / n
                Point2(cx + r * cos(a), cy + r * sin(a))
            }
        }

        /** Ellipse points; ([mx], [my]) is the major axis end relative to the centre. */
        internal fun ellipse(
            cx: Double, cy: Double, mx: Double, my: Double, ratio: Double,
            start: Double, end: Double, ccw: Boolean,
        ): List<Point2> {
            val unit = arc(0.0, 0.0, 1.0, start, end, ccw)
            return unit.map { Point2(cx + mx * it.x - my * ratio * it.y, cy + my * it.x + mx * ratio * it.y) }
        }

        /**
         * Points along a polyline with arc segments. [bulges] holds, per vertex, the tangent of a
         * quarter of the arc angle to the next vertex (0 for a straight segment).
         */
        internal fun withBulges(pts: List<Point2>, bulges: List<Double>, closed: Boolean): List<Point2> {
            if (pts.isEmpty() || bulges.all { it == 0.0 }) return pts
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
