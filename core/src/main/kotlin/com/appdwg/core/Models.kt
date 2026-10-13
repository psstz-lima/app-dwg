package com.appdwg.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** One coloured triangle mesh of a 3D model, in the model's own coordinates (metres after [Model3D.unitScale]). */
class ModelMesh(
    val layer: String,
    val name: String,
    /** x, y, z per vertex. */
    val positions: DoubleArray,
    /** Three vertex indices per triangle. */
    val triangles: IntArray,
    /** RGB colour. */
    val color: Int,
    /** Data shown when the element is tapped (name, type, GlobalId…). */
    val props: Map<String, String> = emptyMap(),
) {
    val triangleCount get() = triangles.size / 3
}

/** Where a model's own origin sits in the project (from IFC map conversion when it has one). */
data class ModelGeoref(val east: Double, val north: Double, val height: Double, val rotationDeg: Double, val scale: Double)

/** Model read from a file. [unitScale] turns its numbers into metres. */
class Model3D(val meshes: List<ModelMesh>, val warnings: List<String>, val unitScale: Double = 1.0, val georef: ModelGeoref? = null) {
    val triangleCount get() = meshes.sumOf { it.triangleCount }
}

/**
 * Placement of a model in the project: scaled by [unitScale] × [scale], rotated [rotationDeg]
 * counterclockwise about the vertical, then moved to ([east], [north], [elevation]).
 */
data class ModelPlacement(
    val east: Double,
    val north: Double,
    val elevation: Double,
    val rotationDeg: Double = 0.0,
    val scale: Double = 1.0,
    val unitScale: Double = 1.0,
) {
    fun apply(m: ModelMesh): ModelMesh {
        val r = Math.toRadians(rotationDeg); val c = cos(r); val s = sin(r)
        val k = unitScale * scale
        val p = m.positions
        val out = DoubleArray(p.size)
        for (i in 0 until p.size / 3) {
            val x = p[3 * i] * k; val y = p[3 * i + 1] * k; val z = p[3 * i + 2] * k
            out[3 * i] = east + x * c - y * s
            out[3 * i + 1] = north + x * s + y * c
            out[3 * i + 2] = elevation + z
        }
        return ModelMesh(m.layer, m.name, out, m.triangles, m.color, m.props)
    }
}

/** Readers for OBJ, STL, PLY, glTF/GLB and IFC models. */
object ModelReader {
    private const val DEFAULT_COLOR = 0xB0BEC5

    /** Reads [bytes] of a file called [name], choosing the format by content and extension. */
    fun read(bytes: ByteArray, name: String, open: (String) -> ByteArray? = { null }): Model3D {
        val ext = name.substringAfterLast('.', "").lowercase()
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        return when {
            head.startsWith("ISO-10303-21") || ext == "ifc" -> Ifc.read(String(bytes, Charsets.UTF_8))
            head.startsWith("glTF") -> Gltf.readGlb(bytes)
            ext == "gltf" -> Gltf.readJson(String(bytes, Charsets.UTF_8), open)
            head.startsWith("ply") -> readPly(bytes)
            ext == "stl" || head.startsWith("solid") && ext != "obj" -> readStl(bytes)
            ext == "obj" -> readObj(String(bytes, Charsets.UTF_8), open)
            ext == "fbx" || ext == "dwg" || ext == "rvt" || ext == "skp" ->
                error("formato $ext não é lido diretamente: exporte o modelo em OBJ, glTF/GLB ou IFC")
            else -> error("formato de modelo não reconhecido (use OBJ, STL, PLY, glTF/GLB ou IFC)")
        }
    }

    // ---- OBJ ----

    fun readObj(text: String, open: (String) -> ByteArray? = { null }): Model3D {
        val v = ArrayList<Double>()
        val colors = HashMap<String, Int>()
        val groups = LinkedHashMap<Pair<String, String>, ArrayList<Int>>()
        var group = "OBJ"; var material = ""
        val warnings = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> if (parts.size >= 4) { v += parts[1].toDouble(); v += parts[2].toDouble(); v += parts[3].toDouble() }
                "o", "g" -> group = parts.drop(1).joinToString(" ").ifEmpty { "OBJ" }
                "usemtl" -> material = parts.getOrElse(1) { "" }
                "mtllib" -> open(parts.drop(1).joinToString(" "))?.let { colors += readMtl(String(it, Charsets.UTF_8)) }
                    ?: warnings.add("Arquivo de materiais ${parts.drop(1).joinToString(" ")} não encontrado: cores padrão.")
                "f" -> {
                    val idx = parts.drop(1).map { tok ->
                        val i = tok.substringBefore('/').toInt()
                        if (i < 0) v.size / 3 + i else i - 1
                    }
                    val list = groups.getOrPut(group to material) { ArrayList() }
                    // Polygons as triangle fans.
                    for (k in 1 until idx.size - 1) { list += idx[0]; list += idx[k]; list += idx[k + 1] }
                }
            }
        }
        val positions = v.toDoubleArray()
        val meshes = groups.map { (key, tri) ->
            compact(key.first, key.first, positions, tri.toIntArray(), colors[key.second] ?: DEFAULT_COLOR, mapOf("Grupo" to key.first) +
                if (key.second.isNotEmpty()) mapOf("Material" to key.second) else emptyMap())
        }
        return Model3D(meshes, warnings)
    }

    private fun readMtl(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        var name = ""
        for (raw in text.lineSequence()) {
            val p = raw.trim().split(Regex("\\s+"))
            when (p[0]) {
                "newmtl" -> name = p.getOrElse(1) { "" }
                "Kd" -> if (p.size >= 4) out[name] = rgb(p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
            }
        }
        return out
    }

    /** Keeps only the vertices [tri] uses, so each mesh carries its own small arrays. */
    private fun compact(layer: String, name: String, all: DoubleArray, tri: IntArray, color: Int, props: Map<String, String>): ModelMesh {
        val map = HashMap<Int, Int>()
        val pos = ArrayList<Double>()
        val out = IntArray(tri.size) { k ->
            map.getOrPut(tri[k]) { pos += all[3 * tri[k]]; pos += all[3 * tri[k] + 1]; pos += all[3 * tri[k] + 2]; map.size }
        }
        return ModelMesh(layer, name, pos.toDoubleArray(), out, color, props)
    }

    // ---- STL ----

    fun readStl(bytes: ByteArray): Model3D {
        val asText = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val binarySize = if (bytes.size >= 84) 84L + 50L * ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() else -1
        val pos = ArrayList<Double>()
        if (asText.startsWith("solid") && binarySize != bytes.size.toLong()) {
            for (line in String(bytes, Charsets.ISO_8859_1).lineSequence()) {
                val t = line.trim()
                if (t.startsWith("vertex")) t.split(Regex("\\s+")).drop(1).take(3).forEach { pos += it.toDouble() }
            }
        } else {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = b.getInt(80)
            for (i in 0 until n) {
                val o = 84 + 50 * i + 12
                for (k in 0 until 9) pos += b.getFloat(o + 4 * k).toDouble()
            }
        }
        val p = pos.toDoubleArray()
        return Model3D(listOf(ModelMesh("STL", "STL", p, IntArray(p.size / 3) { it }, DEFAULT_COLOR)), emptyList())
    }

    // ---- PLY ----

    fun readPly(bytes: ByteArray): Model3D {
        val headerEnd = String(bytes, 0, minOf(bytes.size, 65536), Charsets.ISO_8859_1).indexOf("end_header")
        require(headerEnd > 0) { "PLY sem cabeçalho" }
        val header = String(bytes, 0, headerEnd, Charsets.ISO_8859_1).lines()
        val format = header.first { it.startsWith("format") }.split(" ")[1]
        var vertices = 0; var faces = 0
        val vProps = ArrayList<Pair<String, String>>()
        var faceCountType = "uchar"; var faceIndexType = "int"
        var current = ""
        for (l in header) {
            val p = l.trim().split(Regex("\\s+"))
            when {
                p[0] == "element" && p[1] == "vertex" -> { vertices = p[2].toInt(); current = "vertex" }
                p[0] == "element" && p[1] == "face" -> { faces = p[2].toInt(); current = "face" }
                p[0] == "element" -> current = p[1]
                p[0] == "property" && current == "vertex" && p[1] != "list" -> vProps += p[2] to p[1]
                p[0] == "property" && current == "face" && p[1] == "list" -> { faceCountType = p[2]; faceIndexType = p[3] }
            }
        }
        var start = headerEnd + "end_header".length
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        start++
        val pos = DoubleArray(vertices * 3)
        val tri = ArrayList<Int>()
        val xi = vProps.indexOfFirst { it.first == "x" }; val yi = vProps.indexOfFirst { it.first == "y" }; val zi = vProps.indexOfFirst { it.first == "z" }
        if (format == "ascii") {
            val lines = String(bytes, start, bytes.size - start, Charsets.ISO_8859_1).lineSequence().filter { it.isNotBlank() }.iterator()
            for (i in 0 until vertices) {
                val p = lines.next().trim().split(Regex("\\s+")).map { it.toDouble() }
                pos[3 * i] = p[xi]; pos[3 * i + 1] = p[yi]; pos[3 * i + 2] = p[zi]
            }
            for (f in 0 until faces) {
                val p = lines.next().trim().split(Regex("\\s+")).map { it.toInt() }
                for (k in 2 until p[0]) { tri += p[1]; tri += p[k]; tri += p[k + 1] }
            }
        } else {
            val b = ByteBuffer.wrap(bytes).order(if (format == "binary_big_endian") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            var o = start
            fun read(type: String): Double = when (type) {
                "char", "int8" -> b.get(o).toDouble().also { o += 1 }
                "uchar", "uint8" -> (b.get(o).toInt() and 0xFF).toDouble().also { o += 1 }
                "short", "int16" -> b.getShort(o).toDouble().also { o += 2 }
                "ushort", "uint16" -> (b.getShort(o).toInt() and 0xFFFF).toDouble().also { o += 2 }
                "int", "int32" -> b.getInt(o).toDouble().also { o += 4 }
                "uint", "uint32" -> (b.getInt(o).toLong() and 0xFFFFFFFFL).toDouble().also { o += 4 }
                "float", "float32" -> b.getFloat(o).toDouble().also { o += 4 }
                "double", "float64" -> b.getDouble(o).also { o += 8 }
                else -> error("tipo PLY $type não suportado")
            }
            for (i in 0 until vertices) {
                val vals = vProps.map { read(it.second) }
                pos[3 * i] = vals[xi]; pos[3 * i + 1] = vals[yi]; pos[3 * i + 2] = vals[zi]
            }
            for (f in 0 until faces) {
                val n = read(faceCountType).toInt()
                val idx = IntArray(n) { read(faceIndexType).toInt() }
                for (k in 1 until n - 1) { tri += idx[0]; tri += idx[k]; tri += idx[k + 1] }
            }
        }
        return Model3D(listOf(ModelMesh("PLY", "PLY", pos, tri.toIntArray(), DEFAULT_COLOR)), emptyList())
    }

    fun rgb(r: Double, g: Double, b: Double): Int =
        ((r.coerceIn(0.0, 1.0) * 255).toInt() shl 16) or ((g.coerceIn(0.0, 1.0) * 255).toInt() shl 8) or (b.coerceIn(0.0, 1.0) * 255).toInt()

    /** Fan triangles of a planar polygon given by vertex indices; concave faces go through ear clipping in their plane. */
    internal fun triangulateFace(pos: DoubleArray, idx: IntArray, out: MutableList<Int>) {
        if (idx.size < 3) return
        if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2]; return }
        // Newell normal picks the projection plane.
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in idx.indices) {
            val a = idx[i]; val b = idx[(i + 1) % idx.size]
            nx += (pos[3 * a + 1] - pos[3 * b + 1]) * (pos[3 * a + 2] + pos[3 * b + 2])
            ny += (pos[3 * a + 2] - pos[3 * b + 2]) * (pos[3 * a] + pos[3 * b])
            nz += (pos[3 * a] - pos[3 * b]) * (pos[3 * a + 1] + pos[3 * b + 1])
        }
        val ax = kotlin.math.abs(nx); val ay = kotlin.math.abs(ny); val az = kotlin.math.abs(nz)
        val (u, w) = when { az >= ax && az >= ay -> 0 to 1; ax >= ay -> 1 to 2; else -> 2 to 0 }
        val pts = idx.map { Point2(pos[3 * it + u], pos[3 * it + w]) }
        val t = Platform.earClip(pts)
        if (t.size / 3 < idx.size - 2) { for (k in 1 until idx.size - 1) { out += idx[0]; out += idx[k]; out += idx[k + 1] }; return }
        for (k in t) out += idx[k]
    }

    @Suppress("unused")
    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}

/** glTF 2.0 (text with buffers, or binary GLB): triangle primitives with node transforms and base colours. */
object Gltf {
    fun readGlb(bytes: ByteArray): Model3D {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var o = 12
        var json = ""; var bin: ByteArray? = null
        while (o + 8 <= bytes.size) {
            val len = b.getInt(o); val type = b.getInt(o + 4)
            val data = bytes.copyOfRange(o + 8, o + 8 + len)
            if (type == 0x4E4F534A) json = String(data, Charsets.UTF_8) else if (type == 0x004E4942) bin = data
            o += 8 + len
        }
        return readJson(json) { uri -> if (uri == "") bin else null }.let { it }
    }

    fun readJson(text: String, open: (String) -> ByteArray?): Model3D {
        val root = Json.parse(text) as Map<*, *>
        val buffers = (root["buffers"] as? List<*>).orEmpty().map { buf ->
            val uri = (buf as Map<*, *>)["uri"] as? String
            when {
                uri == null -> open("") ?: error("GLB sem dados binários")
                uri.startsWith("data:") -> java.util.Base64.getDecoder().decode(uri.substringAfter(","))
                else -> open(uri) ?: error("arquivo $uri do glTF não encontrado (escolha o .glb ou junte os arquivos)")
            }
        }
        val views = (root["bufferViews"] as? List<*>).orEmpty().map { it as Map<*, *> }
        val accessors = (root["accessors"] as? List<*>).orEmpty().map { it as Map<*, *> }
        val materials = (root["materials"] as? List<*>).orEmpty().map { m ->
            (((m as Map<*, *>)["pbrMetallicRoughness"] as? Map<*, *>)?.get("baseColorFactor") as? List<*>)?.let { c ->
                ModelReader.rgb((c[0] as Number).toDouble(), (c[1] as Number).toDouble(), (c[2] as Number).toDouble())
            } ?: 0xB0BEC5
        }
        fun read(acc: Int): DoubleArray {
            val a = accessors[acc]
            val view = views[(a["bufferView"] as Number).toInt()]
            val buf = ByteBuffer.wrap(buffers[(view["buffer"] as Number).toInt()]).order(ByteOrder.LITTLE_ENDIAN)
            val count = (a["count"] as Number).toInt()
            val comps = when (a["type"]) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 1 }
            val ct = (a["componentType"] as Number).toInt()
            val size = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val stride = (view["byteStride"] as? Number)?.toInt() ?: (size * comps)
            val base = ((view["byteOffset"] as? Number)?.toInt() ?: 0) + ((a["byteOffset"] as? Number)?.toInt() ?: 0)
            return DoubleArray(count * comps) { k ->
                val o = base + (k / comps) * stride + (k % comps) * size
                when (ct) {
                    5126 -> buf.getFloat(o).toDouble()
                    5125 -> (buf.getInt(o).toLong() and 0xFFFFFFFFL).toDouble()
                    5123 -> (buf.getShort(o).toInt() and 0xFFFF).toDouble()
                    5122 -> buf.getShort(o).toDouble()
                    5121 -> (buf.get(o).toInt() and 0xFF).toDouble()
                    else -> buf.get(o).toDouble()
                }
            }
        }
        val meshes = (root["meshes"] as? List<*>).orEmpty().map { it as Map<*, *> }
        val nodes = (root["nodes"] as? List<*>).orEmpty().map { it as Map<*, *> }
        val out = ArrayList<ModelMesh>()
        val warnings = ArrayList<String>()
        fun visit(n: Int, parent: DoubleArray) {
            val node = nodes[n]
            val m = Mat4.mul(parent, Mat4.ofNode(node))
            (node["mesh"] as? Number)?.toInt()?.let { mi ->
                val mesh = meshes[mi]
                val name = mesh["name"] as? String ?: (node["name"] as? String ?: "mesh $mi")
                for (p in (mesh["primitives"] as List<*>)) {
                    val prim = p as Map<*, *>
                    if (((prim["mode"] as? Number)?.toInt() ?: 4) != 4) { warnings += "Primitiva não triangular ignorada em $name."; continue }
                    val pos = read(((prim["attributes"] as Map<*, *>)["POSITION"] as Number).toInt())
                    val idx = (prim["indices"] as? Number)?.let { read(it.toInt()).map { v -> v.toInt() }.toIntArray() } ?: IntArray(pos.size / 3) { it }
                    // glTF is Y-up; the project is Z-up.
                    val world = DoubleArray(pos.size)
                    for (i in 0 until pos.size / 3) {
                        val q = Mat4.apply(m, pos[3 * i], pos[3 * i + 1], pos[3 * i + 2])
                        world[3 * i] = q[0]; world[3 * i + 1] = -q[2]; world[3 * i + 2] = q[1]
                    }
                    val color = (prim["material"] as? Number)?.toInt()?.let { materials.getOrNull(it) } ?: 0xB0BEC5
                    out += ModelMesh("glTF|$name", name, world, idx, color, mapOf("Malha" to name))
                }
            }
            (node["children"] as? List<*>)?.forEach { visit((it as Number).toInt(), m) }
        }
        val scene = (root["scenes"] as? List<*>)?.getOrNull((root["scene"] as? Number)?.toInt() ?: 0) as? Map<*, *>
        val roots = (scene?.get("nodes") as? List<*>)?.map { (it as Number).toInt() } ?: nodes.indices.toList()
        roots.forEach { visit(it, Mat4.IDENTITY) }
        return Model3D(out, warnings)
    }
}

/** Column-major 4×4 matrices, as glTF stores them. */
internal object Mat4 {
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (c in 0..3) for (rr in 0..3) { var s = 0.0; for (k in 0..3) s += a[k * 4 + rr] * b[c * 4 + k]; r[c * 4 + rr] = s }
        return r
    }

    fun apply(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    /** From an origin and three axes (each scaled as given). */
    fun of(o: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray) =
        doubleArrayOf(x[0], x[1], x[2], 0.0, y[0], y[1], y[2], 0.0, z[0], z[1], z[2], 0.0, o[0], o[1], o[2], 1.0)

    fun ofNode(n: Map<*, *>): DoubleArray {
        (n["matrix"] as? List<*>)?.let { l -> return DoubleArray(16) { (l[it] as Number).toDouble() } }
        val t = (n["translation"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(0.0, 0.0, 0.0)
        val q = (n["rotation"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(0.0, 0.0, 0.0, 1.0)
        val s = (n["scale"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(1.0, 1.0, 1.0)
        val (x, y, z, w) = q
        val rot = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        for (c in 0..2) for (r in 0..2) rot[c * 4 + r] *= s[c]
        rot[12] = t[0]; rot[13] = t[1]; rot[14] = t[2]
        return rot
    }
}

/** Minimal JSON reader for glTF: objects as maps, arrays as lists, numbers as doubles. */
internal object Json {
    fun parse(s: String): Any? = Parser(s).value()

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() }
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } }
                else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}

/** IFC (STEP physical file) geometry: tessellated, faceted and extruded solids placed through their placements. */
object Ifc {
    private class Entity(val type: String, val args: List<Any?>)
    private class Ref(val id: Int)
    private object Unset
    private class Typed(val type: String, val args: List<Any?>)

    fun read(text: String): Model3D {
        val ents = parse(text)
        val warnings = LinkedHashMap<String, Int>()
        fun warn(w: String) { warnings[w] = (warnings[w] ?: 0) + 1 }
        fun e(r: Any?): Entity? = (r as? Ref)?.let { ents[it.id] }
        fun num(a: Any?): Double = when (a) { is Number -> a.toDouble(); is Typed -> num(a.args.firstOrNull()); else -> 0.0 }
        fun list(a: Any?): List<Any?> = a as? List<Any?> ?: emptyList()
        fun str(a: Any?): String? = when (a) { is String -> a; is Typed -> str(a.args.firstOrNull()); else -> null }

        // Units: millimetres are common in IFC.
        var unit = 1.0
        ents.values.filter { it.type == "IFCSIUNIT" && (it.args.getOrNull(1) as? String) == "LENGTHUNIT" }.firstOrNull()?.let { u ->
            unit = when (u.args.getOrNull(2) as? String) { "MILLI" -> 0.001; "CENTI" -> 0.01; "DECI" -> 0.1; "KILO" -> 1000.0; else -> 1.0 }
        }
        ents.values.firstOrNull { it.type == "IFCCONVERSIONBASEDUNIT" && (it.args.getOrNull(1) as? String) == "LENGTHUNIT" }?.let { u ->
            if (str(u.args.getOrNull(2))?.uppercase()?.contains("FOOT") == true) unit = 0.3048
        }
        val georef = ents.values.firstOrNull { it.type == "IFCMAPCONVERSION" }?.let { m ->
            val ax = m.args.getOrNull(5).let { if (it is Number) it.toDouble() else 1.0 }
            val ay = m.args.getOrNull(6).let { if (it is Number) it.toDouble() else 0.0 }
            ModelGeoref(num(m.args[2]), num(m.args[3]), num(m.args[4]), Math.toDegrees(atan2(ay, ax)), m.args.getOrNull(7).let { if (it is Number) it.toDouble() else 1.0 })
        }

        fun point(r: Any?): DoubleArray {
            val c = list(e(r)?.args?.firstOrNull()).map { num(it) }
            return doubleArrayOf(c.getOrElse(0) { 0.0 }, c.getOrElse(1) { 0.0 }, c.getOrElse(2) { 0.0 })
        }
        fun dir(r: Any?, default: DoubleArray): DoubleArray {
            val c = list(e(r)?.args?.firstOrNull()).map { num(it) }
            if (c.isEmpty()) return default
            val v = doubleArrayOf(c[0], c.getOrElse(1) { 0.0 }, c.getOrElse(2) { 0.0 })
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 0 } ?: return default
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun axis3(r: Any?): DoubleArray {
            val p = e(r) ?: return Mat4.IDENTITY
            val o = point(p.args.getOrNull(0))
            if (p.type == "IFCAXIS2PLACEMENT2D") {
                val x = dir(p.args.getOrNull(1), doubleArrayOf(1.0, 0.0, 0.0))
                return Mat4.of(o, x, doubleArrayOf(-x[1], x[0], 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            }
            val z = dir(p.args.getOrNull(1), doubleArrayOf(0.0, 0.0, 1.0))
            var x = dir(p.args.getOrNull(2), doubleArrayOf(1.0, 0.0, 0.0))
            // Ref direction made square to the axis.
            val d = x[0] * z[0] + x[1] * z[1] + x[2] * z[2]
            x = doubleArrayOf(x[0] - d * z[0], x[1] - d * z[1], x[2] - d * z[2]).let { v ->
                val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); if (l < 1e-9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
            }
            return Mat4.of(o, x, cross(z, x), z)
        }
        val placements = HashMap<Int, DoubleArray>()
        fun placement(r: Any?, depth: Int = 0): DoubleArray {
            val ref = r as? Ref ?: return Mat4.IDENTITY
            placements[ref.id]?.let { return it }
            val p = ents[ref.id] ?: return Mat4.IDENTITY
            val m = if (p.type == "IFCLOCALPLACEMENT" && depth < 64) Mat4.mul(placement(p.args.getOrNull(0), depth + 1), axis3(p.args.getOrNull(1))) else Mat4.IDENTITY
            placements[ref.id] = m
            return m
        }

        // Colours of representation items.
        val itemColor = HashMap<Int, Int>()
        for (s in ents.values.filter { it.type == "IFCSTYLEDITEM" }) {
            val item = (s.args.getOrNull(0) as? Ref)?.id ?: continue
            fun findColor(a: Any?, depth: Int = 0): Int? {
                if (depth > 6) return null
                val en = e(a) ?: return list(a).firstNotNullOfOrNull { findColor(it, depth + 1) }
                if (en.type == "IFCCOLOURRGB") return ModelReader.rgb(num(en.args[1]), num(en.args[2]), num(en.args[3]))
                return en.args.firstNotNullOfOrNull { findColor(it, depth + 1) }
            }
            findColor(s.args.getOrNull(1))?.let { itemColor[item] = it }
        }

        // Geometry of one representation item, appended in world coordinates through [m].
        fun item(r: Any?, m: DoubleArray, pos: ArrayList<Double>, tri: ArrayList<Int>, depth: Int = 0) {
            val it = e(r) ?: return
            fun addPoint(p: DoubleArray): Int { val q = Mat4.apply(m, p[0], p[1], p[2]); pos += q[0]; pos += q[1]; pos += q[2]; return pos.size / 3 - 1 }
            fun face(points: List<DoubleArray>) {
                val idx = points.map(::addPoint).toIntArray()
                ModelReader.triangulateFace(pos.toDoubleArray(), idx, tri)
            }
            when (it.type) {
                "IFCTRIANGULATEDFACESET", "IFCTRIANGULATEDIRREGULARNETWORK" -> {
                    val coords = list(e(it.args[0])?.args?.firstOrNull()).map { p -> list(p).map(::num) }
                    val base = pos.size / 3
                    coords.forEach { c -> addPoint(doubleArrayOf(c[0], c.getOrElse(1) { 0.0 }, c.getOrElse(2) { 0.0 })) }
                    for (t in list(it.args[3])) { val i = list(t).map { v -> num(v).toInt() - 1 }; tri += base + i[0]; tri += base + i[1]; tri += base + i[2] }
                }
                "IFCPOLYGONALFACESET" -> {
                    val coords = list(e(it.args[0])?.args?.firstOrNull()).map { p -> list(p).map(::num) }
                    val base = pos.size / 3
                    coords.forEach { c -> addPoint(doubleArrayOf(c[0], c.getOrElse(1) { 0.0 }, c.getOrElse(2) { 0.0 })) }
                    val all = pos.toDoubleArray()
                    for (f in list(it.args[2])) {
                        val idx = list(e(f)?.args?.firstOrNull()).map { v -> base + num(v).toInt() - 1 }.toIntArray()
                        ModelReader.triangulateFace(all, idx, tri)
                    }
                }
                "IFCFACETEDBREP", "IFCFACETEDBREPWITHVOIDS" -> item(it.args[0], m, pos, tri, depth + 1)
                "IFCCLOSEDSHELL", "IFCOPENSHELL", "IFCCONNECTEDFACESET" -> for (f in list(it.args[0])) {
                    val fe = e(f) ?: continue
                    val outer = list(fe.args[0]).map { b -> e(b) }.let { bs -> bs.firstOrNull { b -> b?.type == "IFCFACEOUTERBOUND" } ?: bs.firstOrNull() } ?: continue
                    val loop = e(outer.args[0]) ?: continue
                    if (loop.type != "IFCPOLYLOOP") { warn("Face com contorno ${loop.type}"); continue }
                    val pts = list(loop.args[0]).map(::point)
                    face(if (outer.args.getOrNull(1) == false) pts.reversed() else pts)
                }
                "IFCSHELLBASEDSURFACEMODEL", "IFCFACEBASEDSURFACEMODEL" -> list(it.args[0]).forEach { s -> item(s, m, pos, tri, depth + 1) }
                "IFCEXTRUDEDAREASOLID" -> {
                    val profile = profile(e(it.args[0]), ::point, ::num, ::list, ::e, ::warn) ?: return
                    val mm = Mat4.mul(m, axis3(it.args[1]))
                    val d = dir(it.args[2], doubleArrayOf(0.0, 0.0, 1.0))
                    val depthV = num(it.args[3])
                    val n = profile.size
                    val bottom = profile.map { p -> doubleArrayOf(p.x, p.y, 0.0) }
                    val top = profile.map { p -> doubleArrayOf(p.x + d[0] * depthV, p.y + d[1] * depthV, d[2] * depthV) }
                    val bi = bottom.map { p -> val q = Mat4.apply(mm, p[0], p[1], p[2]); pos += q[0]; pos += q[1]; pos += q[2]; pos.size / 3 - 1 }
                    val ti = top.map { p -> val q = Mat4.apply(mm, p[0], p[1], p[2]); pos += q[0]; pos += q[1]; pos += q[2]; pos.size / 3 - 1 }
                    val caps = Platform.earClip(profile)
                    for (k in caps.indices step 3) {
                        tri += bi[caps[k]]; tri += bi[caps[k + 2]]; tri += bi[caps[k + 1]]
                        tri += ti[caps[k]]; tri += ti[caps[k + 1]]; tri += ti[caps[k + 2]]
                    }
                    for (k in 0 until n) {
                        val j = (k + 1) % n
                        tri += bi[k]; tri += bi[j]; tri += ti[j]
                        tri += bi[k]; tri += ti[j]; tri += ti[k]
                    }
                }
                "IFCMAPPEDITEM" -> {
                    val map = e(it.args[0]) ?: return
                    val target = e(it.args[1])
                    val tm = if (target == null) Mat4.IDENTITY else {
                        val x = dir(target.args.getOrNull(0), doubleArrayOf(1.0, 0.0, 0.0))
                        val y = dir(target.args.getOrNull(1), doubleArrayOf(0.0, 1.0, 0.0))
                        val o = point(target.args.getOrNull(2))
                        val s = target.args.getOrNull(3).let { v -> if (v is Number) v.toDouble() else 1.0 }
                        val z = dir(target.args.getOrNull(4), cross(x, y))
                        Mat4.of(o, x.map { v -> v * s }.toDoubleArray(), y.map { v -> v * s }.toDoubleArray(), z.map { v -> v * s }.toDoubleArray())
                    }
                    val mm = Mat4.mul(Mat4.mul(m, tm), axis3(map.args[0]))
                    if (depth < 16) list(e(map.args[1])?.args?.getOrNull(3)).forEach { sub -> item(sub, mm, pos, tri, depth + 1) }
                }
                "IFCBOOLEANRESULT", "IFCBOOLEANCLIPPINGRESULT" -> {
                    warn("Recorte booleano (mostrado sem o recorte)")
                    item(it.args[1], m, pos, tri, depth + 1)
                }
                else -> warn("Geometria ${it.type} não suportada")
            }
        }

        // Spatial containment gives each element its storey.
        val storeyOf = HashMap<Int, String>()
        for (r in ents.values.filter { it.type == "IFCRELCONTAINEDINSPATIALSTRUCTURE" }) {
            val name = e(r.args.getOrNull(5))?.let { s -> str(s.args.getOrNull(2)) ?: s.type.removePrefix("IFC").lowercase() } ?: continue
            list(r.args.getOrNull(4)).forEach { el -> (el as? Ref)?.let { storeyOf[it.id] = name } }
        }
        val meshes = ArrayList<ModelMesh>()
        for ((id, en) in ents) {
            if (en.args.size < 7 || en.type in SKIP) continue
            val shape = e(en.args[6]) ?: continue
            if (shape.type != "IFCPRODUCTDEFINITIONSHAPE") continue
            val reps = list(shape.args.getOrNull(2)).mapNotNull { e(it) }
            val body = reps.filter { str(it.args.getOrNull(1)) == "Body" }.ifEmpty { reps.filter { str(it.args.getOrNull(1)) != "Axis" && str(it.args.getOrNull(1)) != "FootPrint" } }
            val m = placement(en.args[5])
            val type = en.type.removePrefix("IFC").let { t -> t.substring(0, 1) + t.substring(1).lowercase() }
            val storey = storeyOf[id] ?: "sem pavimento"
            val pos = ArrayList<Double>(); val tri = ArrayList<Int>()
            var color: Int? = null
            for (rep in body) for (it in list(rep.args.getOrNull(3))) {
                (it as? Ref)?.let { r -> color = color ?: itemColor[r.id] }
                item(it, m, pos, tri)
            }
            if (tri.isEmpty()) continue
            meshes += ModelMesh("IFC|$storey|$type", str(en.args.getOrNull(2)) ?: type, pos.toDoubleArray(), tri.toIntArray(),
                color ?: TYPE_COLORS[en.type] ?: 0xB0BEC5,
                listOfNotNull("Tipo" to type, str(en.args.getOrNull(2))?.let { "Nome" to it }, str(en.args.getOrNull(0))?.let { "GlobalId" to it },
                    "Pavimento" to storey).toMap())
        }
        return Model3D(meshes, warnings.map { (w, n) -> if (n > 1) "$w ($n vezes)" else w }, unit, georef)
    }

    private val SKIP = setOf("IFCOPENINGELEMENT", "IFCSPACE", "IFCSITE", "IFCBUILDING", "IFCBUILDINGSTOREY", "IFCPROJECT", "IFCANNOTATION", "IFCGRID")
    private val TYPE_COLORS = mapOf(
        "IFCWALL" to 0xE0E0E0, "IFCWALLSTANDARDCASE" to 0xE0E0E0, "IFCSLAB" to 0xBDBDBD, "IFCROOF" to 0xB71C1C, "IFCBEAM" to 0x8D6E63,
        "IFCCOLUMN" to 0x9E9E9E, "IFCWINDOW" to 0x81D4FA, "IFCDOOR" to 0xA1887F, "IFCSTAIR" to 0xBCAAA4, "IFCRAILING" to 0x607D8B,
    )

    private fun profile(
        p: Entity?, point: (Any?) -> DoubleArray, num: (Any?) -> Double, list: (Any?) -> List<Any?>, e: (Any?) -> Entity?, warn: (String) -> Unit,
    ): List<Point2>? {
        p ?: return null
        fun place(pts: List<Point2>, pos: Any?): List<Point2> {
            val pl = e(pos) ?: return pts
            val o = point(pl.args.getOrNull(0))
            val xd = list(e(pl.args.getOrNull(1))?.args?.firstOrNull()).map(num).takeIf { it.size >= 2 } ?: listOf(1.0, 0.0)
            val l = hypot(xd[0], xd[1]).takeIf { it > 0 } ?: 1.0
            val c = xd[0] / l; val s = xd[1] / l
            return pts.map { q -> Point2(o[0] + q.x * c - q.y * s, o[1] + q.x * s + q.y * c) }
        }
        return when (p.type) {
            "IFCRECTANGLEPROFILEDEF" -> {
                val hx = num(p.args[3]) / 2; val hy = num(p.args[4]) / 2
                place(listOf(Point2(-hx, -hy), Point2(hx, -hy), Point2(hx, hy), Point2(-hx, hy)), p.args[2])
            }
            "IFCCIRCLEPROFILEDEF" -> {
                val r = num(p.args[3])
                place(List(24) { k -> val a = 2 * Math.PI * k / 24; Point2(r * cos(a), r * sin(a)) }, p.args[2])
            }
            "IFCARBITRARYCLOSEDPROFILEDEF", "IFCARBITRARYPROFILEDEFWITHVOIDS" -> {
                val curve = e(p.args[2]) ?: return null
                val pts = when (curve.type) {
                    "IFCPOLYLINE" -> list(curve.args[0]).map { q -> point(q).let { Point2(it[0], it[1]) } }
                    "IFCINDEXEDPOLYCURVE" -> list(e(curve.args[0])?.args?.firstOrNull()).map { q -> list(q).map(num).let { Point2(it[0], it[1]) } }
                    else -> { warn("Perfil com curva ${curve.type}"); return null }
                }
                if (p.type == "IFCARBITRARYPROFILEDEFWITHVOIDS") warn("Perfil com vazios (mostrado cheio)")
                pts.dropLast(if (pts.size > 1 && pts.first() == pts.last()) 1 else 0)
            }
            else -> { warn("Perfil ${p.type} não suportado"); null }
        }
    }

    /** Entities of the DATA section by id. */
    private fun parse(text: String): Map<Int, Entity> {
        val out = HashMap<Int, Entity>()
        val data = text.substringAfter("DATA;", text)
        var i = 0
        val n = data.length
        while (i < n) {
            val hash = data.indexOf('#', i)
            if (hash < 0) break
            val eq = data.indexOf('=', hash)
            if (eq < 0) break
            val id = data.substring(hash + 1, eq).trim().toIntOrNull()
            if (id == null) { i = hash + 1; continue }
            var j = eq + 1
            while (j < n && data[j].isWhitespace()) j++
            val open = data.indexOf('(', j)
            if (open < 0) break
            val type = data.substring(j, open).trim().uppercase()
            val (args, end) = parseList(data, open)
            out[id] = Entity(type, args)
            i = end
        }
        return out
    }

    /** Parses a parenthesised list starting at [start]; returns it and the index after it. */
    private fun parseList(s: String, start: Int): Pair<List<Any?>, Int> {
        val items = ArrayList<Any?>()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c == ')' -> return items to i + 1
                c == '(' -> { val (l, e) = parseList(s, i); items.add(l); i = e }
                c == '\'' -> {
                    val sb = StringBuilder(); i++
                    while (i < s.length) { if (s[i] == '\'') { if (i + 1 < s.length && s[i + 1] == '\'') { sb.append('\''); i += 2; continue } else break }; sb.append(s[i]); i++ }
                    items += decode(sb.toString()); i++
                }
                c == '#' -> { val st = ++i; while (i < s.length && s[i].isDigit()) i++; items += Ref(s.substring(st, i).toInt()) }
                c == '$' || c == '*' -> { items += null; i++ }
                c == '.' && i + 1 < s.length && s[i + 1].isLetter() -> {
                    val end = s.indexOf('.', i + 1); val v = s.substring(i + 1, end)
                    items += when (v) { "T" -> true; "F" -> false; "U" -> null; else -> v }; i = end + 1
                }
                c.isLetter() -> {
                    val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(st, i).uppercase()
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length && s[i] == '(') { val (l, e) = parseList(s, i); items += Typed(name, l); i = e } else items += name
                }
                else -> {
                    val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    items += s.substring(st, i).toDoubleOrNull(); if (i == st) i++
                }
            }
        }
        return items to i
    }

    /** STEP string escapes \\X2\\…\\X0\\ (UTF-16 hex) and \\X\\hh (Latin-1). */
    private fun decode(s: String): String {
        if (!s.contains('\\')) return s
        var r = Regex("\\\\X2\\\\([0-9A-Fa-f]+)\\\\X0\\\\").replace(s) { m ->
            m.groupValues[1].chunked(4).map { it.toInt(16).toChar() }.joinToString("")
        }
        r = Regex("\\\\X\\\\([0-9A-Fa-f]{2})").replace(r) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        return r
    }
}

object ModelPick {
    /** Element whose plan projection contains [p], the highest one where several overlap; null when none. */
    fun at(meshes: List<ModelMesh>, p: Point2, hiddenLayers: Set<String> = emptySet()): ModelMesh? {
        var best: ModelMesh? = null
        var bestZ = Double.NEGATIVE_INFINITY
        for (m in meshes) {
            if (m.layer in hiddenLayers) continue
            val q = m.positions
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (i in 0 until q.size / 3) { minX = minOf(minX, q[3 * i]); maxX = maxOf(maxX, q[3 * i]); minY = minOf(minY, q[3 * i + 1]); maxY = maxOf(maxY, q[3 * i + 1]) }
            if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) continue
            val t = m.triangles
            for (k in 0 until t.size / 3) {
                val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
                val d1 = (q[3 * b] - q[3 * a]) * (p.y - q[3 * a + 1]) - (q[3 * b + 1] - q[3 * a + 1]) * (p.x - q[3 * a])
                val d2 = (q[3 * c] - q[3 * b]) * (p.y - q[3 * b + 1]) - (q[3 * c + 1] - q[3 * b + 1]) * (p.x - q[3 * b])
                val d3 = (q[3 * a] - q[3 * c]) * (p.y - q[3 * c + 1]) - (q[3 * a + 1] - q[3 * c + 1]) * (p.x - q[3 * c])
                val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
                if (neg && pos) continue
                val z = (q[3 * a + 2] + q[3 * b + 2] + q[3 * c + 2]) / 3
                if (z > bestZ) { bestZ = z; best = m }
                break
            }
        }
        return best
    }
}
