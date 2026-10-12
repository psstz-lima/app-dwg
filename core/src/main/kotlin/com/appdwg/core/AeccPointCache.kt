package com.appdwg.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Reader for Civil 3D's "*.xml.aecc.pnt" point caches: the points Civil 3D keeps beside an imported
 * LandXML or point file. The format is undocumented; observed layout is 32-byte little-endian
 * records (flags, id, x, y, z), where a few records with other flags hold the bounding box.
 *
 * The points are triangulated (Delaunay) into a surface. Long, thin triangles that only close the
 * convex hull over areas without points are dropped.
 */
object AeccPointCache {
    private const val RECORD = 32
    private const val POINT_FLAG = 0x80

    fun looksLikePointCache(head: ByteArray, size: Long): Boolean =
        size >= RECORD * 4 && size % RECORD == 0L && head.size >= 4 &&
            ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0).let { it == POINT_FLAG || it == 0x3B3FF440 }

    /** [onStage] is told when reading ends and triangulation starts, which takes most of the time. */
    fun read(input: InputStream, size: Long, name: String, onStage: (String) -> Unit = {}): LandXmlProject {
        val count = (size / RECORD).toInt()
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        val zs = DoubleArray(count)
        var n = 0
        val record = ByteArray(RECORD)
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        input.buffered(1 shl 16).use { s ->
            while (true) {
                var got = 0
                while (got < RECORD) {
                    val r = s.read(record, got, RECORD - got)
                    if (r < 0) break
                    got += r
                }
                if (got < RECORD) break
                if (buffer.getInt(0) != POINT_FLAG) continue
                val z = buffer.getDouble(24)
                if (z.isNaN()) continue
                xs[n] = buffer.getDouble(8); ys[n] = buffer.getDouble(16); zs[n] = z
                n++
            }
        }
        require(n >= 3) { "nenhum ponto encontrado no arquivo" }
        onStage("Triangulando $n pontos")
        return LandXmlProject(
            linearUnit = null,
            coordinateSystem = null,
            surfaces = listOf(triangulate(name, xs.copyOf(n), ys.copyOf(n), zs.copyOf(n))),
            alignments = emptyList(),
            cgPoints = emptyList(),
            warnings = listOf("Superfície triangulada a partir do cache de pontos do Civil 3D ($n pontos); linhas de quebra e contornos do Civil 3D não estão no arquivo."),
        )
    }

    /** Delaunay TIN of the points, without hull triangles much longer than the typical edge. */
    fun triangulate(name: String, xs: DoubleArray, ys: DoubleArray, zs: DoubleArray): Surface {
        val n = xs.size
        // Relative to the first point, so the triangulation keeps full precision with UTM values.
        val ox = xs[0]; val oy = ys[0]
        val coords = DoubleArray(2 * n)
        for (i in 0 until n) { coords[2 * i] = xs[i] - ox; coords[2 * i + 1] = ys[i] - oy }
        val t = Delaunay(coords).triangles

        fun edge(a: Int, b: Int): Double {
            val dx = xs[a] - xs[b]; val dy = ys[a] - ys[b]
            return sqrt(dx * dx + dy * dy)
        }
        val count = t.size / 3
        val longest = DoubleArray(count) { k -> maxOf(edge(t[3 * k], t[3 * k + 1]), edge(t[3 * k + 1], t[3 * k + 2]), edge(t[3 * k + 2], t[3 * k])) }
        val sorted = longest.copyOf().also { it.sort() }
        val limit = if (count == 0) 0.0 else sorted[count / 2] * 20
        var kept = 0
        val out = IntArray(t.size)
        for (k in 0 until count) {
            if (longest[k] > limit) continue
            out[3 * kept] = t[3 * k]; out[3 * kept + 1] = t[3 * k + 1]; out[3 * kept + 2] = t[3 * k + 2]
            kept++
        }
        return Surface(name, xs, ys, zs, out.copyOf(3 * kept))
    }
}
