package com.appdwg.core.drainage

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkTest {
    private val trench = CrossSection(
        "rect_trench", "m",
        listOf(listOf(SectionPoint(-0.5, 0.0), SectionPoint(-0.5, -1.0), SectionPoint(0.5, -1.0), SectionPoint(0.5, 0.0))),
    )
    private val narrow = CrossSection(
        "rect_trench", "m",
        listOf(listOf(SectionPoint(-0.2, 0.0), SectionPoint(-0.2, -0.4), SectionPoint(0.2, -0.4), SectionPoint(0.2, 0.0))),
    )

    @Test
    fun manningOfAOneMetreTrench() {
        val water = DrainageNetwork.waterway(trench)!!
        assertEquals(1.0, water.area, 1e-9)
        assertEquals(3.0, water.perimeter, 1e-9)
        val q = DrainageNetwork.manning(water.area, water.radius, 0.01, 0.015)
        assertEquals(3.205, q, 0.01)
    }

    @Test
    fun socketsCarryInvertAndDownstreamDirection() {
        val device = line("a", 0.0, 10.0, 5.0, 4.0)
        val graph = DrainageNetwork.analyze(listOf(device), { trench }, { false }, { 0.015 })
        val up = graph.sockets.first { it.role == "montante" }
        val down = graph.sockets.first { it.role == "jusante" }
        assertEquals(5.0, up.invertZ, 1e-6)
        assertEquals(4.0, down.invertZ, 1e-6)
        assertEquals(0.0, abs(up.direction - atan2(0.0, 10.0)), 1e-6)
        assertEquals(up.direction, down.direction, 1e-6)
        assertTrue(graph.issues.any { it.check == NetworkCheck.NO_OUTLET })
    }

    @Test
    fun reverseSlopeDropAndSectionTransition() {
        val uphill = line("up", 0.0, 10.0, 1.0, 2.0)
        val next = line("down", 10.2, 20.0, 0.2, 0.0)
        val graph = DrainageNetwork.analyze(listOf(uphill, next), { if (it.id == "down") narrow else trench }, { false }, { 0.015 })
        assertTrue(graph.issues.any { it.deviceId == "up" && it.check == NetworkCheck.REVERSE_SLOPE })
        val link = graph.links.single()
        assertEquals("up", link.upstreamId)
        assertEquals("down", link.downstreamId)
        assertTrue(link.sectionChange)
        assertTrue(link.drop > DrainageNetwork.MAX_DROP)
        assertTrue(graph.issues.any { it.check == NetworkCheck.DROP })
    }

    @Test
    fun anOutletClearsTheMissingEnd() {
        val device = line("a", 0.0, 10.0, 5.0, 4.0)
        val graph = DrainageNetwork.analyze(listOf(device), { trench }, { true }, { 0.015 })
        assertTrue(graph.issues.none { it.check == NetworkCheck.NO_OUTLET })
    }

    @Test
    fun aTwinLineCarriesTwiceTheSingleBarrel() {
        val catalog = CatalogLoader.bundled()
        val single = catalog.device("BSTC 100")!!
        val twin = catalog.device("BDTC 100")!!
        val device = line("a", 0.0, 20.0, 5.0, 4.8)
        val one = DrainageNetwork.analyze(
            listOf(device), { single.section }, { true }, { 0.015 },
            cellsOf = { DrainageNetwork.parallelCells(single, emptyMap()) },
        ).capacity.getValue("a")
        val two = DrainageNetwork.analyze(
            listOf(device), { twin.section }, { true }, { 0.015 },
            cellsOf = { DrainageNetwork.parallelCells(twin, emptyMap()) },
        ).capacity.getValue("a")
        assertEquals(1, DrainageNetwork.parallelCells(single, emptyMap()))
        assertEquals(2, DrainageNetwork.parallelCells(twin, emptyMap()))
        assertEquals(one * 2.0, two, one * 0.02)
        val box = catalog.device("BDCC 150x150 PM")!!
        assertEquals(2, DrainageNetwork.parallelCells(box, emptyMap()))
    }

    @Test
    fun capacityFlagsAFlowTheSectionCannotCarry() {
        val device = line("a", 0.0, 10.0, 5.0, 4.9)
        val over = DrainageNetwork.analyze(listOf(device), { trench }, { true }, { 0.015 }, designFlow = { 50.0 })
        val issue = over.issues.single { it.check == NetworkCheck.CAPACITY }
        assertEquals(50.0, issue.flow!!, 1e-9)
        assertTrue(issue.capacity!! < 50.0)
        val ok = DrainageNetwork.analyze(listOf(device), { trench }, { true }, { 0.015 }, designFlow = { 0.01 })
        assertTrue(ok.issues.none { it.check == NetworkCheck.CAPACITY })
    }

    @Test
    fun snapMatchesPlanAndLeavesASmallDrop() {
        val upstream = line("a", 0.0, 10.0, 5.0, 4.0)
        val loose = line("b", 11.0, 20.0, 3.0, 2.0)
        val graph = DrainageNetwork.analyze(listOf(upstream, loose), { trench }, { it.id == "b" }, { 0.015 })
        val snapped = DrainageNetwork.snap(loose, graph.sockets)
        val linear = snapped as PlacedDevice.Linear
        assertEquals(10.0, linear.xs.first(), 1e-6)
        assertEquals(4.0 - DrainageNetwork.MATCH_DROP, linear.grade.z0, 1e-6)
    }

    @Test
    fun propagateKeepsTheDropAlongTheChain() {
        val first = line("a", 0.0, 10.0, 5.0, 4.0)
        val second = line("b", 10.0, 20.0, 3.98, 3.0)
        val third = line("c", 20.0, 30.0, 2.96, 2.0)
        val graph = DrainageNetwork.analyze(listOf(first, second, third), { trench }, { it.id == "c" }, { 0.015 })
        val lowered = first.copy(grade = first.grade.copy(z1 = 3.0))
        val next = DrainageNetwork.propagate(listOf(lowered, second, third), graph.links, "a")
        val b = next.first { it.id == "b" } as PlacedDevice.Linear
        val c = next.first { it.id == "c" } as PlacedDevice.Linear
        assertEquals(3.0 - DrainageNetwork.MATCH_DROP, b.grade.z0, 1e-9)
        assertEquals(3.0 - 0.98 - DrainageNetwork.MATCH_DROP, b.grade.z1, 1e-9)
        assertEquals(b.grade.z1 - DrainageNetwork.MATCH_DROP, c.grade.z0, 1e-9)
    }

    @Test
    fun aMoveCarriesTheSnappedDevices() {
        val first = line("a", 0.0, 10.0, 5.0, 4.0)
        val second = line("b", 10.0, 20.0, 3.98, 3.0)
        val alone = line("c", 100.0, 110.0, 1.0, 0.0)
        val graph = DrainageNetwork.analyze(listOf(first, second, alone), { trench }, { true }, { 0.015 })
        val moved = DrainageNetwork.chainTranslate(listOf(first, second, alone), graph.links, "a", 2.0, -1.0)
        val a = moved.first { it.id == "a" } as PlacedDevice.Linear
        val b = moved.first { it.id == "b" } as PlacedDevice.Linear
        val c = moved.first { it.id == "c" } as PlacedDevice.Linear
        assertEquals(2.0, a.xs.first(), 1e-9)
        assertEquals(12.0, b.xs.first(), 1e-9)
        assertEquals(100.0, c.xs.first(), 1e-9)
    }

    private fun line(id: String, x0: Double, x1: Double, z0: Double, z1: Double) = PlacedDevice.Linear(
        id, "VPCG", PathSource.POLYLINE, null, listOf(x0, x1), listOf(0.0, 0.0),
        DeviceSide.AXIS, 0.0, null, null, GradeSpec(GradeMode.FIXED, z0 = z0, z1 = z1), null,
    )
}
