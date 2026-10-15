package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceEdgesTest {
    // Two triangles sharing the diagonal of a unit square.
    private val square = Surface("s", doubleArrayOf(0.0, 1.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0), DoubleArray(4), intArrayOf(0, 1, 2, 0, 2, 3))

    @Test
    fun countsEachEdgeOnce() = assertEquals(5, SurfaceEdges.all(square).size / 2)

    @Test
    fun boundaryLeavesOutSharedEdges() {
        val b = SurfaceEdges.boundary(square)
        assertEquals(4, b.size / 2)
        val pairs = (0 until b.size / 2).map { setOf(b[2 * it], b[2 * it + 1]) }
        assert(setOf(0, 2) !in pairs)
    }
}
