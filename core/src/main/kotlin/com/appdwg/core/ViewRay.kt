package com.appdwg.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** A ray in world coordinates: origin + t · direction, t > 0. Direction is not normalised. */
data class Ray(val ox: Double, val oy: Double, val oz: Double, val dx: Double, val dy: Double, val dz: Double)

/** First hit of a ray with one triangle of a TIN. [t] is the ray parameter. */
data class RayHit(val x: Double, val y: Double, val z: Double, val t: Double, val triangle: Int)

/**
 * The 3D camera, matching the view's perspective and look-at, with the vertical exaggeration
 * undone so a hit is in the same coordinates as the TIN.
 */
object ViewRay {
    fun world(
        screenX: Float, screenY: Float, width: Int, height: Int,
        yaw: Float, pitch: Float, distance: Float,
        tx: Float, ty: Float, tz: Float, zScale: Float,
        fovDeg: Float, radius: Float,
        originX: Double, originY: Double, originZ: Double,
    ): Ray? {
        val cam = matrices(width, height, yaw, pitch, distance, tx, ty, tz, zScale, fovDeg, radius, originX, originY, originZ) ?: return null
        val ndcX = 2.0 * screenX / width - 1.0
        val ndcY = 1.0 - 2.0 * screenY / height
        val near = transform(cam.inv, ndcX, ndcY, -1.0) ?: return null
        val far = transform(cam.inv, ndcX, ndcY, 1.0) ?: return null
        val s = cam.zScale
        return Ray(
            near[0] + originX, near[1] + originY, near[2] / s + originZ,
            far[0] - near[0], far[1] - near[1], (far[2] - near[2]) / s,
        )
    }

    /** Screen pixel of a world point, or null when it is behind the camera. */
    fun project(
        x: Double, y: Double, z: Double,
        width: Int, height: Int,
        yaw: Float, pitch: Float, distance: Float,
        tx: Float, ty: Float, tz: Float, zScale: Float,
        fovDeg: Float, radius: Float,
        originX: Double, originY: Double, originZ: Double,
    ): Pair<Double, Double>? {
        val cam = matrices(width, height, yaw, pitch, distance, tx, ty, tz, zScale, fovDeg, radius, originX, originY, originZ) ?: return null
        val sx = x - originX
        val sy = y - originY
        val sz = (z - originZ) * cam.zScale
        val clip = mulPoint(cam.mvp, sx, sy, sz) ?: return null
        if (clip[3] <= 1e-8) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        return (width * (ndcX + 1.0) / 2.0) to (height * (1.0 - ndcY) / 2.0)
    }

    private class Cam(val mvp: DoubleArray, val inv: DoubleArray, val zScale: Double)

    private fun matrices(
        width: Int, height: Int,
        yaw: Float, pitch: Float, distance: Float,
        tx: Float, ty: Float, tz: Float, zScale: Float,
        fovDeg: Float, radius: Float,
        originX: Double, originY: Double, originZ: Double,
    ): Cam? {
        if (width < 1 || height < 1) return null
        val near = max(distance * 0.01, 0.05).toDouble()
        val far = (distance * 4 + radius * 8).toDouble()
        if (far <= near) return null
        val proj = perspective(fovDeg.toDouble(), width.toDouble() / height, near, far)
        val ex = tx + distance * cos(pitch) * cos(yaw)
        val ey = ty + distance * cos(pitch) * sin(yaw)
        val ez = tz + distance * sin(pitch)
        val view = lookAt(ex.toDouble(), ey.toDouble(), ez.toDouble(), tx.toDouble(), ty.toDouble(), tz.toDouble())
        val mvp = multiply(proj, view)
        val inv = invert(mvp) ?: return null
        val s = zScale.toDouble().takeIf { it != 0.0 } ?: 1.0
        // origin is applied by the caller; referenced so a future camera offset stays in this signature.
        if (originX.isNaN() || originY.isNaN() || originZ.isNaN()) return null
        return Cam(mvp, inv, s)
    }

    private fun perspective(fovyDeg: Double, aspect: Double, near: Double, far: Double): DoubleArray {
        val m = DoubleArray(16)
        val f = 1.0 / tan(fovyDeg * Math.PI / 360.0)
        val rec = 1.0 / (near - far)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) * rec
        m[11] = -1.0
        m[14] = 2.0 * far * near * rec
        return m
    }

    /** Column-major look-at, the same arrangement as android.opengl.Matrix.setLookAtM. */
    private fun lookAt(eyeX: Double, eyeY: Double, eyeZ: Double, cx: Double, cy: Double, cz: Double): DoubleArray {
        var fx = cx - eyeX; var fy = cy - eyeY; var fz = cz - eyeZ
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 0 } ?: 1.0
        fx /= fl; fy /= fl; fz /= fl
        val upX = 0.0; val upY = 0.0; val upZ = 1.0
        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz).takeIf { it > 0 } ?: 1.0
        sx /= sl; sy /= sl; sz /= sl
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx
        val m = doubleArrayOf(
            sx, ux, -fx, 0.0,
            sy, uy, -fy, 0.0,
            sz, uz, -fz, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        translate(m, -eyeX, -eyeY, -eyeZ)
        return m
    }

    private fun translate(m: DoubleArray, x: Double, y: Double, z: Double) {
        m[12] += m[0] * x + m[4] * y + m[8] * z
        m[13] += m[1] * x + m[5] * y + m[9] * z
        m[14] += m[2] * x + m[6] * y + m[10] * z
        m[15] += m[3] * x + m[7] * y + m[11] * z
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (col in 0..3) for (row in 0..3) {
            var s = 0.0
            for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = s
        }
        return r
    }

    private fun transform(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray? {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (kotlin.math.abs(w) < 1e-12) return null
        return doubleArrayOf(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[4 + 1] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w,
        )
    }

    private fun mulPoint(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray? {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        return doubleArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
            w,
        )
    }

    private fun invert(m: DoubleArray): DoubleArray? {
        val a = Array(4) { r -> DoubleArray(4) { c -> m[c * 4 + r] } }
        val inv = Array(4) { DoubleArray(4) }
        for (i in 0..3) inv[i][i] = 1.0
        for (col in 0..3) {
            var pivot = col
            var best = kotlin.math.abs(a[col][col])
            for (row in col + 1..3) {
                val v = kotlin.math.abs(a[row][col])
                if (v > best) { best = v; pivot = row }
            }
            if (best < 1e-12) return null
            if (pivot != col) {
                val row = a[col]; a[col] = a[pivot]; a[pivot] = row
                val irow = inv[col]; inv[col] = inv[pivot]; inv[pivot] = irow
            }
            val div = a[col][col]
            for (k in 0..3) { a[col][k] /= div; inv[col][k] /= div }
            for (row in 0..3) if (row != col) {
                val f = a[row][col]
                if (f == 0.0) continue
                for (k in 0..3) {
                    a[row][k] -= f * a[col][k]
                    inv[row][k] -= f * inv[col][k]
                }
            }
        }
        val out = DoubleArray(16)
        for (c in 0..3) for (r in 0..3) out[c * 4 + r] = inv[r][c]
        return out
    }
}
