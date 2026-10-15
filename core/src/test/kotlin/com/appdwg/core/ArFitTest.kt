package com.appdwg.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArFitTest {
    @Test
    fun twoPointsRecoverARigidMoveAndAHeightOffset() {
        val fit = ArFit.solve(listOf(
            control(0.0, 0.0, 10.0, 100.0, 200.0, 15.0),
            control(10.0, 0.0, 10.0, 100.0, 210.0, 16.0),
        ))
        assertNotNull(fit)
        assertEquals(1.0, fit.scale, 1e-9)
        assertEquals(5.5, fit.heightOffset, 1e-9)
        val back = fit.toProject(Point3(0.0, 0.0, 10.0))
        assertEquals(100.0, back.x, 1e-6)
        assertEquals(200.0, back.y, 1e-6)
        assertEquals(15.5, back.z, 1e-6)
        assertTrue(fit.horizontalRms < 1e-6)
    }

    @Test
    fun threePointsRecoverASimilarityIncludingScale() {
        val local = listOf(Point3(0.0, 0.0, 0.0), Point3(10.0, 0.0, 0.0), Point3(0.0, 10.0, 1.0))
        val fitKnown = known(local, scale = 2.0, rotationDeg = 90.0, tx = 50.0, ty = -20.0, dz = 4.0)
        val solved = ArFit.solve(fitKnown)
        assertNotNull(solved)
        assertEquals(2.0, solved.scale, 1e-6)
        assertEquals(4.0, solved.heightOffset, 1e-6)
        for (c in fitKnown) {
            val q = solved.toProject(c.camera)
            assertEquals(c.project.x, q.x, 1e-4)
            assertEquals(c.project.y, q.y, 1e-4)
            assertEquals(c.project.z, q.z, 1e-4)
            val back = solved.toCamera(c.project)
            assertEquals(c.camera.x, back.x, 1e-4)
            assertEquals(c.camera.y, back.y, 1e-4)
        }
    }

    @Test
    fun noiseStaysInTheResidualsAndDegenerateInputIsRejected() {
        val clean = known(
            listOf(Point3(0.0, 0.0, 0.0), Point3(20.0, 0.0, 0.0), Point3(0.0, 20.0, 0.0), Point3(20.0, 20.0, 2.0)),
            scale = 1.0, rotationDeg = 15.0, tx = 1000.0, ty = 2000.0, dz = 3.0,
        )
        val noisy = clean.mapIndexed { i, c ->
            val wobble = if (i == 3) 0.2 else 0.0
            c.copy(project = Point3(c.project.x + wobble, c.project.y - wobble, c.project.z))
        }
        val fit = ArFit.solve(noisy)
        assertNotNull(fit)
        assertTrue(fit.horizontalRms < 0.3)
        assertNull(ArFit.solve(listOf(control(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))))
        assertNull(ArFit.solve(listOf(
            control(5.0, 5.0, 0.0, 0.0, 0.0, 0.0),
            control(5.0, 5.0, 1.0, 10.0, 0.0, 1.0),
        )))
    }

    @Test
    fun pinholeKeepsAPointAheadAndDropsOneBehind() {
        val center = ArCamera.project(0.0, 0.0, 10.0, 90.0, 90.0, 200, 100)
        assertNotNull(center)
        assertEquals(100.0, center.first, 1e-6)
        assertEquals(50.0, center.second, 1e-6)
        assertNull(ArCamera.project(0.0, 0.0, -1.0, 90.0, 90.0, 200, 100))
        val fov = ArCamera.fov(focalMm = 4.0, sensorWidthMm = 4.0, sensorHeightMm = 3.0)
        assertNotNull(fov)
        assertEquals(53.13, fov.first, 0.1)
        val ahead = ArCamera.observe(0.0, 10.0, 0.0, headingRad = 0.0, pitchRad = 0.0, rollRad = 0.0)
        assertTrue(ahead.z > 0.0)
        assertEquals(0.0, ahead.x, 1e-9)
    }

    @Test
    fun overlayDropsPointsPastTheRangeAndCapsTheRest() {
        val far = Point3(10_000.0, 0.0, 0.0)
        val near = (0 until 20).map { Point3(it.toDouble(), 0.0, 1.0) }
        val kept = ArScene.take(0.0, 0.0, listOf(far) + near, range = 15.0, cap = 5)
        assertEquals(5, kept.size)
        assertTrue(kept.none { it.x > 15.0 })
    }

    private fun control(cx: Double, cy: Double, cz: Double, px: Double, py: Double, pz: Double) =
        ArControl(Point3(cx, cy, cz), Point3(px, py, pz))

    private fun known(local: List<Point3>, scale: Double, rotationDeg: Double, tx: Double, ty: Double, dz: Double): List<ArControl> {
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val s = sin(r)
        return local.map { p ->
            ArControl(p, Point3(tx + scale * (c * p.x - s * p.y), ty + scale * (s * p.x + c * p.y), p.z + dz))
        }
    }
}
