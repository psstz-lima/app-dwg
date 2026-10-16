package com.appdwg.core.drainage

import com.appdwg.core.Point2
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drawn solids keep a real width and height. The quantity sweep is covered by [SweepTest]. */
class DeviceSolidTest {
    private val catalog = CatalogLoader.bundled()

    @Test
    fun representativeDevicesHaveWidthAndHeight() {
        linear("VPCG 160-30", minCross = 1.5, minHeight = 0.25)
        val concrete = linear("VPCC 160-30", minCross = 1.5, minHeight = 0.25)
        assertTrue(concrete.innerWidthM > 0.5, "inner ${concrete.innerWidthM}")
        assertTrue(concrete.innerWidthM < concrete.widthM, "inner ${concrete.innerWidthM} outer ${concrete.widthM}")
        linear("STC 100-20", minCross = 0.9, minHeight = 0.15)
        linear("MFC 01", minCross = 0.12, minHeight = 0.25, channel = false)
        linear("DCD 100-50", minCross = 0.9, minHeight = 0.4)
        linear("BSTC 100", minCross = 0.9, minHeight = 0.9)
        linear("BDTC 100", minCross = 1.8, minHeight = 0.9)
        point("CCS 250-60 A", minMajor = 1.4, minMinor = 1.2, minHeight = 2.0)
        point("DES 100-300", minMajor = 2.5, minMinor = 1.5, minHeight = 0.2)
    }

    @Test
    fun surfaceTopSitsOnTheTerrainAndSurvivesReopen() {
        val placed = line("VPCG 160-30")
        val built = scene(listOf(placed))
        val mesh = built.triangles
        assertTrue(mesh.size >= 9, "scene triangles ${mesh.size}")
        assertTrue(minZ(mesh) >= 100.0, "minZ ${minZ(mesh)}")
        val glyph = built.glyphs.single()
        assertTrue(glyph.widthM >= 1.5, "width ${glyph.widthM}")
        assertTrue(glyph.footprint.size >= 4)
        assertTrue(glyph.channel.size >= 4)
        val again = scene(DrainageIo.fromJson(DrainageIo.toJson(listOf(placed))))
        assertTrue(again.triangles.size >= 9)
        assertTrue(again.glyphs.single().widthM >= 1.5)
        assertTrue(minZ(again.triangles) >= 100.0)
    }

    @Test
    fun skewedBoxKeepsItsPlan() {
        val device = catalog.device("CCS 250-60 A")
        assertNotNull(device)
        val mesh = DeviceBody.point(
            device, emptyMap(), 10.0, 20.0, 25.0, 15.0,
            GradeSpec(GradeMode.SURFACE_TOP), 100.0, null,
        )
        assertNotNull(mesh)
        val (major, minor) = spans(mesh.footprint)
        assertTrue(major >= 1.4 && minor >= 1.0, "plan $major x $minor")
        assertTrue(zSpan(mesh.triangles) >= 2.0, "height ${zSpan(mesh.triangles)}")
        assertTrue(minZ(mesh.triangles) >= 100.0)
        assertTrue(solidCount(mesh.triangles) > 12)
    }

    private fun linear(code: String, minCross: Double, minHeight: Double, channel: Boolean = true): SweepMesh {
        val device = catalog.device(code)
        assertNotNull(device, code)
        val mesh = DeviceBody.linear(
            device, emptyMap(), Sweep.polyline(doubleArrayOf(0.0, 20.0), doubleArrayOf(0.0, 0.0), null, null),
            0.0, GradeSpec(GradeMode.SURFACE_TOP), { _, _ -> 100.0 }, { null },
        )
        assertNotNull(mesh, code)
        val (major, minor) = spans(mesh.footprint)
        assertTrue(minor >= minCross, "$code cross $minor")
        assertTrue(major >= 10.0, "$code length $major")
        assertTrue(zSpan(mesh.triangles) >= minHeight, "$code height ${zSpan(mesh.triangles)}")
        assertTrue(minZ(mesh.triangles) >= 100.0, "$code minZ ${minZ(mesh.triangles)}")
        assertTrue(solidCount(mesh.triangles) > 12, "$code triangles")
        if (channel) assertTrue(mesh.channel.size >= 4 || mesh.channels.any { it.size >= 4 }, "$code channel")
        val built = scene(listOf(line(code)))
        assertTrue(built.triangles.size >= 9, "$code scene")
        assertTrue(built.glyphs.single().widthM >= minCross, "$code glyph ${built.glyphs.single().widthM}")
        return mesh
    }

    private fun point(code: String, minMajor: Double, minMinor: Double, minHeight: Double) {
        val device = catalog.device(code)
        assertNotNull(device, code)
        val mesh = DeviceBody.point(device, emptyMap(), 0.0, 0.0, 0.0, 0.0, GradeSpec(GradeMode.SURFACE_TOP), 100.0, null)
        assertNotNull(mesh, code)
        val (major, minor) = spans(mesh.footprint)
        assertTrue(major >= minMajor, "$code major $major")
        assertTrue(minor >= minMinor, "$code minor $minor")
        assertTrue(zSpan(mesh.triangles) >= minHeight, "$code height ${zSpan(mesh.triangles)}")
        assertTrue(minZ(mesh.triangles) >= 100.0, "$code minZ ${minZ(mesh.triangles)}")
        assertTrue(solidCount(mesh.triangles) > 12, "$code triangles")
        assertTrue(mesh.channel.size >= 4, "$code channel ${mesh.channel.size}")
        val built = scene(listOf(PlacedDevice.Point("p", code, 0.0, 0.0, 0.0, 0.0, GradeSpec(GradeMode.SURFACE_TOP), null, null)))
        assertTrue(built.triangles.size >= 9, "$code scene")
        assertTrue(built.glyphs.single().footprint.size >= 4, "$code footprint")
    }

    private fun line(code: String) = PlacedDevice.Linear(
        "d", code, PathSource.POLYLINE, null, listOf(0.0, 20.0), listOf(0.0, 0.0),
        DeviceSide.AXIS, 0.0, null, null, GradeSpec(GradeMode.SURFACE_TOP), null,
    )

    private fun scene(placed: List<PlacedDevice>) = DrainageScene.build(
        placed,
        { catalog.device(it) },
        { _, _ -> 100.0 },
        { null },
    )

    private fun spans(points: List<Point2>): Pair<Double, Double> {
        val dx = points.maxOf { it.x } - points.minOf { it.x }
        val dy = points.maxOf { it.y } - points.minOf { it.y }
        return max(dx, dy) to min(dx, dy)
    }

    private fun zSpan(triangles: DoubleArray): Double {
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        var i = 2
        while (i < triangles.size) {
            low = min(low, triangles[i])
            high = max(high, triangles[i])
            i += 3
        }
        return high - low
    }

    private fun minZ(triangles: DoubleArray): Double {
        var low = Double.POSITIVE_INFINITY
        var i = 2
        while (i < triangles.size) {
            low = min(low, triangles[i])
            i += 3
        }
        return low
    }

    private fun solidCount(triangles: DoubleArray): Int {
        var good = 0
        var i = 0
        while (i + 8 < triangles.size) {
            val ux = triangles[i + 3] - triangles[i]
            val uy = triangles[i + 4] - triangles[i + 1]
            val uz = triangles[i + 5] - triangles[i + 2]
            val vx = triangles[i + 6] - triangles[i]
            val vy = triangles[i + 7] - triangles[i + 1]
            val vz = triangles[i + 8] - triangles[i + 2]
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            if (cx * cx + cy * cy + cz * cz > 1e-10) good++
            i += 9
        }
        return good
    }
}
