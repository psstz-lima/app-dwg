package com.appdwg.core.drainage

import com.appdwg.core.Alignment
import com.appdwg.core.AlignmentElement
import com.appdwg.core.Point2
import com.appdwg.core.Profile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SweepTest {
    private val trench = CrossSection(
        "rect_trench", "m",
        listOf(listOf(SectionPoint(-1.0, 0.0), SectionPoint(-1.0, -1.0), SectionPoint(1.0, -1.0), SectionPoint(1.0, 0.0))),
    )

    @Test
    fun vpcgOverOneHundredMetresIsThirtyNineCubicMetres() {
        val device = CatalogLoader.bundled().device("VPCG 160-30")
        assertNotNull(device)
        val section = device.section
        assertNotNull(section)
        val mesh = Sweep.sweep(
            section,
            Sweep.polyline(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 0.0), null, null),
            0.0,
            GradeSpec(GradeMode.FIXED, z0 = 0.0, z1 = 0.0),
        )
        assertNotNull(mesh)
        val expected = (section.areaM2 ?: 0.0) * mesh.length
        assertEquals(0.39, section.areaM2!!, 1e-6)
        assertEquals(39.0, expected, 1e-6)
        assertEquals(39.0, mesh.volume, 0.05)
    }

    @Test
    fun eachElevationMode() {
        val nodes = Sweep.polyline(doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), null, null)
        fun mesh(grade: GradeSpec, terrain: Double?, profile: Double?) = Sweep.sweep(
            trench, nodes, 0.0, grade,
            terrain = { _, _ -> terrain },
            profileAt = { profile },
        )

        val top = mesh(GradeSpec(GradeMode.SURFACE_TOP), 50.0, null)
        assertNotNull(top)
        assertEquals(50.0, top.frames.first().topZ, 1e-6)
        assertEquals(49.0, top.frames.first().invertZ, 1e-6)

        val invert = mesh(GradeSpec(GradeMode.SURFACE_INVERT, depth = 0.4), 50.0, null)
        assertNotNull(invert)
        assertEquals(49.6, invert.frames.first().invertZ, 1e-6)
        assertEquals(50.6, invert.frames.first().topZ, 1e-6)

        val fixed = mesh(GradeSpec(GradeMode.FIXED, z0 = 10.0, z1 = 12.0), null, null)
        assertNotNull(fixed)
        assertEquals(10.0, fixed.frames.first().invertZ, 1e-6)
        assertEquals(12.0, fixed.frames.last().invertZ, 1e-6)

        val slope = mesh(GradeSpec(GradeMode.SLOPE, z0 = 100.0, slope = 0.02), null, null)
        assertNotNull(slope)
        assertEquals(100.0, slope.frames.first().invertZ, 1e-6)
        assertEquals(100.2, slope.frames.last().invertZ, 1e-4)

        val profile = mesh(GradeSpec(GradeMode.PROFILE), null, 12.0)
        assertNotNull(profile)
        assertEquals(12.0, profile.frames.first().topZ, 1e-6)
        assertEquals(11.0, profile.frames.first().invertZ, 1e-6)
    }

    @Test
    fun aCurveKeepsTheSectionPerpendicular() {
        val radius = 10.0
        val nodes = ArrayList<PathNode>()
        val steps = 8
        for (i in 0..steps) {
            val angle = PI / 2.0 * i / steps
            val length = radius * angle
            nodes += PathNode(radius * cos(angle), radius * sin(angle), length, length, corner = false)
        }
        val mesh = Sweep.sweep(trench, nodes, 0.0, GradeSpec(GradeMode.FIXED))
        assertNotNull(mesh)
        val mid = mesh.frames[mesh.frames.size / 2]
        val radial = hypot(mid.x, mid.y)
        val along = (mid.x * mid.rightX + mid.y * mid.rightY) / radial
        val tangentX = -mid.y / radial
        val tangentY = mid.x / radial
        val across = mid.rightX * tangentX + mid.rightY * tangentY
        assertEquals(1.0, along, 0.05)
        assertEquals(0.0, across, 0.05)
        assertEquals(1.0, mid.miter, 1e-6)
    }

    @Test
    fun aRightAngleMitersTheJoint() {
        val nodes = listOf(
            PathNode(0.0, 0.0, 0.0, 0.0, corner = true),
            PathNode(10.0, 0.0, 10.0, 10.0, corner = true),
            PathNode(10.0, 10.0, 20.0, 20.0, corner = true),
        )
        val mesh = Sweep.sweep(trench, nodes, 0.0, GradeSpec(GradeMode.FIXED))
        assertNotNull(mesh)
        val joint = mesh.frames.minBy { abs(it.distance - 10.0) }
        assertEquals(kotlin.math.sqrt(2.0), joint.miter, 0.02)
        val outward = hypot(joint.rightX - kotlin.math.sqrt(0.5), joint.rightY + kotlin.math.sqrt(0.5))
        assertEquals(0.0, outward, 0.05)
    }

    @Test
    fun tenKilometresStaysInsideTheSampleBudget() {
        val mesh = Sweep.sweep(
            trench,
            Sweep.polyline(doubleArrayOf(0.0, 10_000.0), doubleArrayOf(0.0, 0.0), null, null),
            0.0,
            GradeSpec(GradeMode.FIXED, z0 = 1.0, z1 = 1.0),
        )
        assertNotNull(mesh)
        assertTrue(mesh.frames.size <= Sweep.MAX_SAMPLES + 2, "samples ${mesh.frames.size}")
        assertTrue(mesh.triangles.size / 9 <= Sweep.MAX_TRIANGLES)
        assertEquals(10_000.0, mesh.length, 1e-6)
    }

    @Test
    fun anAlignmentCurveAndProfile() {
        val arc = AlignmentElement.Arc(Point2(10.0, 0.0), Point2(0.0, 10.0), Point2(0.0, 0.0), clockwise = false)
        val profile = Profile("G", listOf(Profile.Pvi(0.0, 5.0), Profile.Pvi(arc.length, 7.0)))
        val alignment = Alignment("Eixo", 0.0, listOf(arc), profile, step = 1.0)
        val nodes = Sweep.alignment(alignment, null, null)
        assertTrue(nodes.size >= 3)
        assertTrue(nodes.drop(1).dropLast(1).none { it.corner })
        val mesh = Sweep.sweep(
            trench, nodes, 0.0, GradeSpec(GradeMode.PROFILE),
            profileAt = { alignment.profile?.elevationAt(it) },
        )
        assertNotNull(mesh)
        assertEquals(5.0, mesh.frames.first().topZ, 0.05)
        assertEquals(7.0, mesh.frames.last().topZ, 0.05)
    }

    @Test
    fun roundTrip() {
        val linear = PlacedDevice.Linear(
            "a", "VPCG 160-30", PathSource.ALIGNMENT, "Eixo 1",
            listOf(0.0, 10.0), listOf(0.0, 5.0), DeviceSide.LEFT, 2.5, 10.0, 80.0,
            GradeSpec(GradeMode.SLOPE, z0 = 100.0, slope = -0.01), "Greide",
            mapOf("H_cm" to 30.0),
        )
        val point = PlacedDevice.Point(
            "b", "DAD 170-35", 12.5, -3.0, 15.0, 30.0,
            GradeSpec(GradeMode.SURFACE_INVERT, depth = 0.35), "Eixo 1", null,
        )
        val back = DrainageIo.fromJson(DrainageIo.toJson(listOf(linear, point)))
        assertEquals(listOf(linear, point), back)
    }

    @Test
    fun aMissingListAndAnUnknownKindAreEmpty() {
        assertEquals(emptyList(), DrainageIo.fromJson("[]"))
        assertEquals(emptyList(), DrainageIo.fromJson("{\"kind\":\"future\"}"))
        val kept = DrainageIo.fromJson("[{\"id\":\"a\",\"kind\":\"nope\",\"code\":\"X\"},{\"id\":\"b\",\"kind\":\"point\",\"code\":\"Y\",\"x\":1,\"y\":2}]")
        assertEquals(1, kept.size)
        assertEquals("b", kept.first().id)
    }
}
