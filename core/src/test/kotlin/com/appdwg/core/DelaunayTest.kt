package com.appdwg.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DelaunayTest {

    @Test
    fun gridTriangulates() {
        // 4 x 4 grid: 9 cells, 18 triangles.
        val coords = DoubleArray(32)
        for (i in 0 until 16) { coords[2 * i] = (i % 4).toDouble(); coords[2 * i + 1] = (i / 4).toDouble() }
        assertEquals(18, Delaunay(coords).triangles.size / 3)
    }

    @Test
    fun randomPointsSatisfyEmptyCircle() {
        val r = Random(1)
        val n = 2000
        val coords = DoubleArray(2 * n) { r.nextDouble() * 1000 }
        val t = Delaunay(coords).triangles
        // Euler: a triangulation of n points with h on the hull has 2n - 2 - h triangles.
        assertTrue(t.size / 3 in (2 * n - 2 - 100)..(2 * n - 5))
        // No point strictly inside any circumcircle (checked on a sample).
        for (k in 0 until t.size / 3 step 37) {
            val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
            for (p in 0 until n) {
                if (p == a || p == b || p == c) continue
                val ax = coords[2 * a] - coords[2 * p]; val ay = coords[2 * a + 1] - coords[2 * p + 1]
                val bx = coords[2 * b] - coords[2 * p]; val by = coords[2 * b + 1] - coords[2 * p + 1]
                val cx = coords[2 * c] - coords[2 * p]; val cy = coords[2 * c + 1] - coords[2 * p + 1]
                val det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) + (cx * cx + cy * cy) * (ax * by - bx * ay)
                // The sign of the in-circle test depends on the triangle's winding.
                val ccw = (coords[2 * b] - coords[2 * a]) * (coords[2 * c + 1] - coords[2 * a + 1]) -
                    (coords[2 * b + 1] - coords[2 * a + 1]) * (coords[2 * c] - coords[2 * a])
                assertTrue(det * kotlin.math.sign(ccw) <= 1e-6, "triangle $k has point $p inside")
            }
        }
    }

    @Test
    fun readsPointCacheIntoSurface() {
        // Plane z = 100 + x / 10 sampled on a 20 x 20 grid at UTM-like coordinates, plus a bounding-box record.
        val records = ArrayList<ByteArray>()
        fun rec(flag: Int, x: Double, y: Double, z: Double) = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(flag).putInt(records.size).putDouble(x).putDouble(y).putDouble(z).array().also { records += it }
        rec(0x3B3FF440, 761000.0, 7848000.0, 0.0)
        for (i in 0 until 20) for (j in 0 until 20) rec(0x80, 761000.0 + i * 10, 7848000.0 + j * 10, 100.0 + i)
        val bytes = records.reduce { a, b -> a + b }
        assertTrue(AeccPointCache.looksLikePointCache(bytes.copyOf(8), bytes.size.toLong()))
        val p = AeccPointCache.read(ByteArrayInputStream(bytes), bytes.size.toLong(), "TIN")
        val s = p.surfaces.single()
        assertEquals(400, s.vertexCount)
        assertEquals(2 * 19 * 19, s.triangleCount)
        val z = assertNotNull(s.elevationAt(Point2(761055.0, 7848033.0)))
        assertEquals(105.5, z, 1e-6)
    }
}
