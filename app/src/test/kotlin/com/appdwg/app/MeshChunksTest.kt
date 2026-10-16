package com.appdwg.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A picture tile draws only the triangles under it, and every triangle is still drawn once. */
class MeshChunksTest {
    /** A grid of n × n squares, two triangles each, one metre apart, nine floats per vertex. */
    private fun grid(n: Int): Pair<FloatArray, IntArray> {
        val side = n + 1
        val vertices = FloatArray(side * side * 9)
        for (y in 0 until side) for (x in 0 until side) {
            val v = (y * side + x) * 9
            vertices[v] = x.toFloat()
            vertices[v + 1] = y.toFloat()
        }
        val indices = ArrayList<Int>()
        for (y in 0 until n) for (x in 0 until n) {
            val a = y * side + x
            indices += listOf(a, a + 1, a + side, a + 1, a + side + 1, a + side)
        }
        return vertices to indices.toIntArray()
    }

    @Test
    fun reorderedIndicesKeepEveryTriangle() {
        val (vertices, indices) = grid(300)
        val chunks = MeshChunks.build(vertices, 9, indices)
        assertTrue(chunks.cells > 1)
        assertEquals(indices.size, chunks.indices.size)
        val before = indices.toList().chunked(3).map { it.sorted() }.sortedBy { it.toString() }
        val after = chunks.indices.toList().chunked(3).map { it.sorted() }.sortedBy { it.toString() }
        assertEquals(before, after)
        // The whole surface is one range.
        assertArrayEquals(intArrayOf(0, indices.size), chunks.ranges(-1f, 301f, 302f, 302f))
    }

    @Test
    fun aSmallTileDrawsAFewCellsAndMissesNothingUnderIt() {
        val (vertices, indices) = grid(300)
        val chunks = MeshChunks.build(vertices, 9, indices)
        // A 10 m tile with its top-left corner at (100, 160).
        val ranges = chunks.ranges(100f, 160f, 10f, 10f)
        val drawn = (0 until ranges.size step 2).sumOf { ranges[it + 1] }
        assertTrue("drew $drawn of ${indices.size}", drawn < indices.size / 10)
        val covered = HashSet<List<Int>>()
        for (r in 0 until ranges.size step 2) {
            for (i in ranges[r] until ranges[r] + ranges[r + 1] step 3) {
                covered += chunks.indices.slice(i until i + 3).sorted()
            }
        }
        // Every triangle with a corner inside the tile is drawn.
        for (t in 0 until indices.size / 3) {
            val corners = (0 until 3).map { indices[t * 3 + it] }
            val inside = corners.any { v ->
                val x = vertices[v * 9]
                val y = vertices[v * 9 + 1]
                x in 100f..110f && y in 150f..160f
            }
            if (inside) assertTrue(covered.contains(corners.sorted()))
        }
        assertEquals(0, chunks.ranges(1000f, 1000f, 5f, 5f).size)
    }

    @Test
    fun aSmallMeshIsOneCell() {
        val (vertices, indices) = grid(4)
        val chunks = MeshChunks.build(vertices, 9, indices)
        assertEquals(1, chunks.cells)
        assertArrayEquals(intArrayOf(0, indices.size), chunks.ranges(0f, 1f, 1f, 1f))
        assertEquals(0, MeshChunks.build(FloatArray(0), 9, IntArray(0)).ranges(0f, 0f, 1f, 1f).sumOf { it })
    }
}
