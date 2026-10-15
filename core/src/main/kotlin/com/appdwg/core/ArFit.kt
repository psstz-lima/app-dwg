package com.appdwg.core

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point tapped in the camera and the project point the user named for it. */
data class ArControl(val camera: Point3, val project: Point3)

/**
 * Places the camera in the drawing. Two points give a rigid fit (no scale) plus one height
 * offset. Three or more give a 2D similarity by least squares, plus the same height offset.
 */
class ArFit private constructor(
    private val mx: Double, private val my: Double,
    private val cx: Double, private val cy: Double,
    private val a: Double, private val b: Double,
    val heightOffset: Double,
    val controls: List<ArControl>,
) {
    val scale get() = hypot(a, b)
    val rotationRad get() = atan2(b, a)

    /** Project position of a point measured in the camera frame. */
    fun toProject(p: Point3): Point3 {
        val dx = p.x - mx
        val dy = p.y - my
        return Point3(cx + a * dx - b * dy, cy + b * dx + a * dy, p.z + heightOffset)
    }

    /** Camera-frame position of a project point. */
    fun toCamera(p: Point3): Point3 {
        val dx = p.x - cx
        val dy = p.y - cy
        val s2 = a * a + b * b
        return Point3(mx + (a * dx + b * dy) / s2, my + (-b * dx + a * dy) / s2, p.z - heightOffset)
    }

    fun residual(c: ArControl): Point3 {
        val q = toProject(c.camera)
        return Point3(c.project.x - q.x, c.project.y - q.y, c.project.z - q.z)
    }

    val horizontalRms: Double get() {
        if (controls.isEmpty()) return 0.0
        return sqrt(controls.sumOf { val r = residual(it); r.x * r.x + r.y * r.y } / controls.size)
    }

    companion object {
        /**
         * Null with fewer than two points, or when the camera points sit on top of each other.
         */
        fun solve(controls: List<ArControl>): ArFit? {
            if (controls.size < 2) return null
            val mx = controls.map { it.camera.x }.average()
            val my = controls.map { it.camera.y }.average()
            val cx = controls.map { it.project.x }.average()
            val cy = controls.map { it.project.y }.average()
            var sxx = 0.0
            var sab = 0.0
            var sba = 0.0
            for (p in controls) {
                val x = p.camera.x - mx
                val y = p.camera.y - my
                val u = p.project.x - cx
                val v = p.project.y - cy
                sxx += x * x + y * y
                sab += x * u + y * v
                sba += x * v - y * u
            }
            if (sxx < 1e-6) return null
            var a = sab / sxx
            var b = sba / sxx
            if (controls.size == 2) {
                val s = hypot(a, b)
                if (s < 1e-9) return null
                a /= s
                b /= s
            }
            val dz = controls.map { it.project.z - it.camera.z }.average()
            return ArFit(mx, my, cx, cy, a, b, dz, controls)
        }
    }
}

/** Pinhole camera. A point in front of the lens has positive z; x is right and y is up. */
object ArCamera {
    fun project(
        x: Double, y: Double, z: Double,
        fovXDeg: Double, fovYDeg: Double,
        width: Int, height: Int,
    ): Pair<Double, Double>? {
        if (z <= 1e-4 || width < 1 || height < 1) return null
        if (fovXDeg <= 0 || fovYDeg <= 0 || fovXDeg >= 170 || fovYDeg >= 170) return null
        val fx = (width / 2.0) / tan(Math.toRadians(fovXDeg) / 2.0)
        val fy = (height / 2.0) / tan(Math.toRadians(fovYDeg) / 2.0)
        return (width / 2.0 + fx * x / z) to (height / 2.0 - fy * y / z)
    }

    /** Horizontal and vertical field of view, in degrees, from the lens and the sensor. */
    fun fov(focalMm: Double, sensorWidthMm: Double, sensorHeightMm: Double): Pair<Double, Double>? {
        if (focalMm <= 0 || sensorWidthMm <= 0 || sensorHeightMm <= 0) return null
        val hx = Math.toDegrees(2 * atan(sensorWidthMm / (2 * focalMm)))
        val hy = Math.toDegrees(2 * atan(sensorHeightMm / (2 * focalMm)))
        return hx to hy
    }

    /**
     * Camera-frame coordinates of a project point.
     * [headingRad] is clockwise from north, [pitchRad] above the horizon, [rollRad] right-down.
     * The camera looks along negative device Z.
     */
    fun observe(
        east: Double, north: Double, up: Double,
        headingRad: Double, pitchRad: Double, rollRad: Double,
    ): Point3 {
        // Heading is clockwise from north. Pitch raises the lens. Roll is clockwise.
        val forwardE = sin(headingRad)
        val forwardN = cos(headingRad)
        val rightE = cos(headingRad)
        val rightN = -sin(headingRad)
        val cp = cos(pitchRad)
        val sp = sin(pitchRad)
        val lookE = cp * forwardE
        val lookN = cp * forwardN
        val lookU = sp
        val upE = -sp * forwardE
        val upN = -sp * forwardN
        val upU = cp
        val x = east * rightE + north * rightN
        val y = east * upE + north * upN + up * upU
        val z = east * lookE + north * lookN + up * lookU
        val cr = cos(rollRad)
        val sr = sin(rollRad)
        return Point3(cr * x + sr * y, -sr * x + cr * y, z)
    }
}

/** Keeps an augmented-reality overlay inside a distance and a vertex budget. */
object ArScene {
    const val RANGE_M = 300.0
    const val MAX_VERTICES = 4000

    fun take(originX: Double, originY: Double, points: List<Point3>, range: Double = RANGE_M, cap: Int = MAX_VERTICES): List<Point3> {
        if (cap <= 0 || range <= 0) return emptyList()
        val r2 = range * range
        val out = ArrayList<Point3>(minOf(cap, points.size))
        for (p in points) {
            val dx = p.x - originX
            val dy = p.y - originY
            if (dx * dx + dy * dy > r2) continue
            out += p
            if (out.size >= cap) break
        }
        return out
    }
}
