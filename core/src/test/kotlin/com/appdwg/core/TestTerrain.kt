package com.appdwg.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic hilly terrain for tests: a valley running north with hills on both sides. */
object TestTerrain {
    fun height(x: Double, y: Double) = 100 + 0.02 * kotlin.math.abs(x - 500) * 3 + 0.01 * y + 4 * sin(x / 60) * cos(y / 80)

    /** TIN of [n] random points plus the corners over [size] x [size] metres, in UTM-like coordinates. */
    fun surface(n: Int, size: Double = 1000.0, x0: Double = 0.0, y0: Double = 0.0, seed: Int = 1, z: (Double, Double) -> Double = ::height): Surface {
        val rnd = Random(seed)
        val xs = DoubleArray(n + 4); val ys = DoubleArray(n + 4)
        val corners = listOf(0.0 to 0.0, size to 0.0, size to size, 0.0 to size)
        for (i in 0 until n + 4) {
            val (x, y) = if (i < 4) corners[i] else rnd.nextDouble() * size to rnd.nextDouble() * size
            xs[i] = x; ys[i] = y
        }
        val coords = DoubleArray(2 * xs.size) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
        val t = Delaunay(coords).triangles
        return Surface("T", DoubleArray(xs.size) { xs[it] + x0 }, DoubleArray(ys.size) { ys[it] + y0 }, DoubleArray(xs.size) { z(xs[it], ys[it]) }, t)
    }
}
