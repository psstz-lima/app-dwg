package com.appdwg.core

/** The edges of a triangulated surface, for drawing its mesh and its outline. */
object SurfaceEdges {
    /** Every edge once, as vertex index pairs (a, b). */
    fun all(s: Surface): IntArray = collect(s, boundaryOnly = false)

    /** Edges used by a single triangle: the outer border and the rims of holes. */
    fun boundary(s: Surface): IntArray = collect(s, boundaryOnly = true)

    private fun collect(s: Surface, boundaryOnly: Boolean): IntArray {
        val t = s.triangles
        val n = s.vertexCount.toLong()
        // Each edge as one sortable number, smaller index first, so shared edges end up side by side.
        val keys = LongArray(t.size)
        for (k in 0 until s.triangleCount) for (e in 0 until 3) {
            val a = t[3 * k + e]; val b = t[3 * k + (e + 1) % 3]
            keys[3 * k + e] = minOf(a, b) * n + maxOf(a, b)
        }
        keys.sort()
        val out = IntArrayList()
        var i = 0
        while (i < keys.size) {
            var j = i + 1
            while (j < keys.size && keys[j] == keys[i]) j++
            if (!boundaryOnly || j - i == 1) { out.add((keys[i] / n).toInt()); out.add((keys[i] % n).toInt()) }
            i = j
        }
        return out.toArray()
    }

    private class IntArrayList {
        private var a = IntArray(64)
        private var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
