package com.appdwg.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TinMeasureTest {

    @Test
    fun verticalRayHitsTheTriangleItPointsAt() {
        val s = square(z = { _, _ -> 5.0 })
        val hit = s.rayHit(5.0, 5.0, 20.0, 0.0, 0.0, -1.0)
        assertNotNull(hit)
        assertEquals(5.0, hit.x, 1e-6)
        assertEquals(5.0, hit.y, 1e-6)
        assertEquals(5.0, hit.z, 1e-6)
    }

    @Test
    fun rayThatMissesTheTinReturnsNull() {
        val s = square(z = { _, _ -> 0.0 })
        assertNull(s.rayHit(5.0, 5.0, 10.0, 1.0, 0.0, 0.0))
        assertNull(s.rayHit(-5.0, -5.0, 10.0, -1.0, 0.0, -1.0))
    }

    @Test
    fun obliqueRayHitsAcrossTheGridAndTheNearerSurfaceWins() {
        val wide = grid(n = 8, size = 80.0, z = { _, _ -> 0.0 })
        // Enters the 80 m grid from the west and meets z = 0 at x = 40.
        val hit = wide.rayHit(-10.0, 40.0, 10.0, 1.0, 0.0, -0.2)
        assertNotNull(hit)
        assertEquals(40.0, hit.x, 1e-4)
        assertEquals(40.0, hit.y, 1e-4)
        assertEquals(0.0, hit.z, 1e-4)

        val low = square(z = { _, _ -> 0.0 }, name = "baixo")
        val high = square(z = { _, _ -> 5.0 }, name = "alto")
        val nearest = TinMeasure.nearestHit(listOf(low, high), Ray(5.0, 5.0, 20.0, 0.0, 0.0, -1.0))
        assertNotNull(nearest)
        assertEquals(5.0, nearest.z, 1e-6)
        assertEquals(15.0, nearest.t, 1e-6)
    }

    @Test
    fun centreOfTheScreenLooksAtTheTargetWithExaggerationUndone() {
        val ray = ViewRay.world(
            screenX = 100f, screenY = 100f, width = 200, height = 200,
            yaw = (-Math.PI / 2).toFloat(), pitch = 0.6f, distance = 30f,
            tx = 1f, ty = 1f, tz = 8f, zScale = 2f,
            fovDeg = 45f, radius = 20f,
            originX = 0.0, originY = 0.0, originZ = 0.0,
        )
        assertNotNull(ray)
        assertTrue(distanceToRay(ray, 1.0, 1.0, 4.0) < 1e-4, "ray misses the un-exaggerated target")
        val s = square(z = { _, _ -> 4.0 })
        val hit = s.rayHit(ray.ox, ray.oy, ray.oz, ray.dx, ray.dy, ray.dz)
        assertNotNull(hit)
        assertEquals(1.0, hit.x, 1e-3)
        assertEquals(1.0, hit.y, 1e-3)
        assertEquals(4.0, hit.z, 1e-3)
    }

    @Test
    fun flatSquareHasTheSamePlanAndSurfaceArea() {
        val s = square(z = { _, _ -> 5.0 })
        val poly = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val a = TinMeasure.area(s, poly, geometry = true)
        assertNotNull(a)
        assertEquals(100.0, a.planArea, 1e-6)
        assertEquals(100.0, a.surfaceArea, 1e-6)
        assertEquals(40.0, a.perimeterPlan, 1e-6)
        assertEquals(40.0, a.perimeter3d, 1e-4)
        assertEquals(5.0, a.minElevation, 1e-6)
        assertEquals(5.0, a.maxElevation, 1e-6)
        assertEquals(5.0, a.averageElevation, 1e-6)
        assertEquals(0.0, a.averageSlopePercent, 1e-6)
        assertTrue(a.facets.isNotEmpty())
    }

    @Test
    fun slopeSquareGrowsByTheSecantAndReportsTheGrade() {
        // z = y over a 10 m square: 45°, surface area = 100 * √2, grade = 100 %.
        val s = square(z = { _, y -> y })
        val poly = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val a = TinMeasure.area(s, poly)
        assertNotNull(a)
        assertEquals(100.0, a.planArea, 1e-6)
        assertEquals(100.0 * sqrt(2.0), a.surfaceArea, 1e-4)
        assertEquals(0.0, a.minElevation, 1e-6)
        assertEquals(10.0, a.maxElevation, 1e-6)
        assertEquals(5.0, a.averageElevation, 1e-4)
        assertEquals(100.0, a.averageSlopePercent, 1e-3)
        val half = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 5.0), Point2(0.0, 5.0))
        val h = TinMeasure.area(s, half)
        assertNotNull(h)
        assertEquals(50.0, h.planArea, 1e-6)
        assertEquals(50.0 * sqrt(2.0), h.surfaceArea, 1e-4)
    }

    @Test
    fun groundLengthFollowsTheTinAndDiffersFromTheChordOnARidge() {
        val slope = square(z = { _, y -> y })
        val along = TinMeasure.ground(slope, listOf(Point2(5.0, 0.0), Point2(5.0, 10.0)), closed = false)
        assertNotNull(along)
        assertEquals(10.0 * sqrt(2.0), along.length, 1e-4)

        val ridge = ridge()
        val path = TinMeasure.ground(ridge, listOf(Point2(0.0, 5.0), Point2(10.0, 5.0)), closed = false)
        assertNotNull(path)
        assertEquals(2.0 * sqrt(125.0), path.length, 1e-3)
        val chord = 10.0
        assertTrue(path.length > chord + 1.0)
    }

    @Test
    fun cutAndFillInsideThePolygonAreThePrismBetweenTheSurfaces() {
        val base = square(z = { _, _ -> 0.0 }, name = "terreno")
        val other = square(z = { _, _ -> 3.0 }, name = "projeto")
        val poly = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val flat = TinMeasure.cutFill(base, other, poly)
        assertNotNull(flat)
        assertEquals(0.0, flat.first, 1e-4)
        assertEquals(300.0, flat.second, 1e-3)

        // Other goes from z = -2 at y = 0 to z = +2 at y = 10. Half the volume is cut, half fill.
        val sloped = square(z = { _, y -> -2.0 + 0.4 * y }, name = "projeto")
        val both = TinMeasure.cutFill(base, sloped, poly)
        assertNotNull(both)
        assertEquals(50.0, both.first, 1e-2)
        assertEquals(50.0, both.second, 1e-2)

        val inside = listOf(Point2(2.0, 2.0), Point2(6.0, 2.0), Point2(6.0, 6.0), Point2(2.0, 6.0))
        val part = TinMeasure.area(base, inside, other = other)
        assertNotNull(part)
        assertEquals(16.0, part.planArea, 1e-6)
        assertEquals(0.0, part.cut!!, 1e-3)
        assertEquals(48.0, part.fill!!, 1e-2)
        assertEquals("terreno", part.baseName)
        assertEquals("projeto", part.otherName)
    }

    @Test
    fun screenSnapUsesTheSameTargetsInsideThePixelRadius() {
        val targets = listOf(Point3(0.0, 0.0, 0.0), Point3(10.0, 0.0, 0.0))
        val hit = SnapTargets.nearest(targets, sx = 3.0, sy = 1.0, radiusPx = 5.0) { p ->
            if (p.x == 0.0) 0.0 to 0.0 else 40.0 to 0.0
        }
        assertEquals(0.0, hit!!.x, 0.0)
        assertNull(SnapTargets.nearest(targets, sx = 20.0, sy = 20.0, radiusPx = 5.0) { p ->
            if (p.x == 0.0) 0.0 to 0.0 else 40.0 to 0.0
        })
    }

    private fun distanceToRay(ray: Ray, x: Double, y: Double, z: Double): Double {
        val vx = x - ray.ox; val vy = y - ray.oy; val vz = z - ray.oz
        val d2 = ray.dx * ray.dx + ray.dy * ray.dy + ray.dz * ray.dz
        val t = (vx * ray.dx + vy * ray.dy + vz * ray.dz) / d2
        val cx = ray.ox + t * ray.dx - x
        val cy = ray.oy + t * ray.dy - y
        val cz = ray.oz + t * ray.dz - z
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    /** Two triangles covering the square (0,0)–(10,10). [z] is the height at a plan position. */
    private fun square(z: (x: Double, y: Double) -> Double, name: String = "s") = Surface(
        name,
        doubleArrayOf(0.0, 10.0, 10.0, 0.0),
        doubleArrayOf(0.0, 0.0, 10.0, 10.0),
        doubleArrayOf(z(0.0, 0.0), z(10.0, 0.0), z(10.0, 10.0), z(0.0, 10.0)),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )

    /** [n] by [n] quads over [size] metres, two triangles each. */
    private fun grid(n: Int, size: Double, z: (ix: Int, iy: Int) -> Double): Surface {
        val step = size / n
        val nx = n + 1
        val xs = DoubleArray(nx * nx)
        val ys = DoubleArray(nx * nx)
        val zs = DoubleArray(nx * nx)
        for (iy in 0..n) for (ix in 0..n) {
            val i = iy * nx + ix
            xs[i] = ix * step
            ys[i] = iy * step
            zs[i] = z(ix, iy)
        }
        val tris = IntArray(n * n * 6)
        var k = 0
        for (iy in 0 until n) for (ix in 0 until n) {
            val a = iy * nx + ix
            val b = a + 1
            val c = a + nx
            val d = c + 1
            tris[k++] = a; tris[k++] = b; tris[k++] = d
            tris[k++] = a; tris[k++] = d; tris[k++] = c
        }
        return Surface("g", xs, ys, zs, tris)
    }

    /** A ridge: z = 0 on the border, z = 10 at the centre. */
    private fun ridge() = Surface(
        "crista",
        doubleArrayOf(0.0, 10.0, 10.0, 0.0, 5.0),
        doubleArrayOf(0.0, 0.0, 10.0, 10.0, 5.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 10.0),
        intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4),
    )
}
