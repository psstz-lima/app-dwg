package com.appdwg.core

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** One control point: where GNSS puts it (UTM and height) and its known local coordinates. */
data class CalibrationPair(
    val name: String,
    val measured: Point3,
    val local: Point3,
    /** Off: kept in the list but left out of the fit (a doubtful point). */
    val useHorizontal: Boolean = true,
    val useVertical: Boolean = true,
)

/**
 * Site calibration (localization) from GNSS to the local grid:
 * horizontal 2D similarity (Helmert: shift, rotation, scale) by least squares, or a plain shift
 * with one point; vertical shift, or an inclined plane with three points or more.
 */
class Calibration private constructor(
    // Horizontal: local = c + [a −b; b a] (measured − m), around the centroids m and c.
    private val mx: Double, private val my: Double,
    private val cx: Double, private val cy: Double,
    private val a: Double, private val b: Double,
    // Vertical: dz = v0 + vx (x − mx) + vy (y − my), x and y measured.
    private val v0: Double, private val vx: Double, private val vy: Double,
    val pairs: List<CalibrationPair>,
    val horizontalCount: Int,
    val verticalCount: Int,
) {
    val scale get() = hypot(a, b)
    /** Rotation from GNSS grid to local grid, degrees counterclockwise. */
    val rotationDeg get() = Math.toDegrees(atan2(b, a))
    val shiftE get() = cx - mx
    val shiftN get() = cy - my
    val verticalShift get() = v0
    /** Slope of the vertical plane, in m/m along E and N (0 without a plane). */
    val slopeE get() = vx
    val slopeN get() = vy

    fun apply(p: Point3): Point3 {
        val dx = p.x - mx; val dy = p.y - my
        return Point3(cx + a * dx - b * dy, cy + b * dx + a * dy, p.z + v0 + vx * dx + vy * dy)
    }

    fun applyHorizontal(p: Point2): Point2 = apply(Point3(p.x, p.y, 0.0)).let { Point2(it.x, it.y) }

    /** Local minus calibrated: (dE, dN, dZ) of each pair. */
    fun residual(p: CalibrationPair): Point3 {
        val c = apply(p.measured)
        return Point3(p.local.x - c.x, p.local.y - c.y, p.local.z - c.z)
    }

    val horizontalRms: Double get() {
        val r = pairs.filter { it.useHorizontal }.map { residual(it) }
        return if (r.isEmpty()) 0.0 else sqrt(r.sumOf { it.x * it.x + it.y * it.y } / r.size)
    }
    val verticalRms: Double get() {
        val r = pairs.filter { it.useVertical }.map { residual(it) }
        return if (r.isEmpty()) 0.0 else sqrt(r.sumOf { it.z * it.z } / r.size)
    }

    companion object {
        /** Null without any point used horizontally. */
        fun fit(pairs: List<CalibrationPair>, fixScale: Boolean = false): Calibration? {
            val h = pairs.filter { it.useHorizontal }
            if (h.isEmpty()) return null
            val mx = h.map { it.measured.x }.average(); val my = h.map { it.measured.y }.average()
            val cx = h.map { it.local.x }.average(); val cy = h.map { it.local.y }.average()
            var a = 1.0; var b = 0.0
            if (h.size >= 2) {
                var sxx = 0.0; var sab = 0.0; var sba = 0.0
                for (p in h) {
                    val x = p.measured.x - mx; val y = p.measured.y - my
                    val u = p.local.x - cx; val v = p.local.y - cy
                    sxx += x * x + y * y; sab += x * u + y * v; sba += x * v - y * u
                }
                if (sxx > 1e-9) { a = sab / sxx; b = sba / sxx }
                if (fixScale) { val s = hypot(a, b); if (s > 0) { a /= s; b /= s } }
            }
            val vPairs = pairs.filter { it.useVertical }
            var v0 = 0.0; var vx = 0.0; var vy = 0.0
            if (vPairs.isNotEmpty()) {
                val dz = vPairs.map { it.local.z - it.measured.z }
                v0 = dz.average()
                if (vPairs.size >= 3) {
                    // Least-squares plane in the measured position around the horizontal centroid.
                    val xs = vPairs.map { it.measured.x - mx }; val ys = vPairs.map { it.measured.y - my }
                    val n = vPairs.size.toDouble()
                    val sx = xs.sum(); val sy = ys.sum(); val sxx = xs.sumOf { it * it }; val syy = ys.sumOf { it * it }
                    val sxy = xs.indices.sumOf { xs[it] * ys[it] }
                    val sz = dz.sum(); val sxz = xs.indices.sumOf { xs[it] * dz[it] }; val syz = ys.indices.sumOf { ys[it] * dz[it] }
                    val m = arrayOf(doubleArrayOf(n, sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
                    solve3(m, doubleArrayOf(sz, sxz, syz))?.let { v0 = it[0]; vx = it[1]; vy = it[2] }
                }
            }
            return Calibration(mx, my, cx, cy, a, b, v0, vx, vy, pairs, h.size, vPairs.size)
        }

        /** Cramer's rule; null when the points are (nearly) in a line. */
        private fun solve3(m: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
            fun det(q: Array<DoubleArray>) = q[0][0] * (q[1][1] * q[2][2] - q[1][2] * q[2][1]) -
                q[0][1] * (q[1][0] * q[2][2] - q[1][2] * q[2][0]) + q[0][2] * (q[1][0] * q[2][1] - q[1][1] * q[2][0])
            val d = det(m)
            val scale = m[1][1] * m[2][2]
            if (scale <= 0 || kotlin.math.abs(d) < 1e-9 * scale * m[0][0]) return null
            return DoubleArray(3) { k -> det(Array(3) { i -> DoubleArray(3) { j -> if (j == k) r[i] else m[i][j] } }) / d }
        }
    }
}
