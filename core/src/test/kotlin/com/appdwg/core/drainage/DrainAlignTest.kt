package com.appdwg.core.drainage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrainAlignTest {
    @Test
    fun aSecondReachAppendsAndStationContinues() {
        val runs = DrainAlignments.build(listOf(line("a", 0.0, 10.0), line("b", 10.0, 25.0)))
        assertEquals(1, runs.size)
        assertEquals(listOf("a", "b"), runs[0].deviceIds)
        assertEquals(25.0, runs[0].length, 1e-6)
        assertEquals(25.0, runs[0].nodes.last().station, 1e-6)
        assertNull(runs[0].junctionId)
        assertTrue(runs[0].name.startsWith("ED-01"))
    }

    @Test
    fun aPointAtTheEndExtendsTheAlignment() {
        val runs = DrainAlignments.build(listOf(line("a", 0.0, 10.0), point("box", 10.4, 0.2)))
        assertEquals(1, runs.size)
        val structure = runs[0].nodes.find { it.structure }
        assertNotNull(structure)
        assertEquals("box", structure.deviceId)
        assertTrue(runs[0].length > 10.0)
        assertNull(runs[0].junctionId)
    }

    @Test
    fun parallelRunsStaySeparate() {
        val runs = DrainAlignments.build(listOf(
            line("left", 0.0, 30.0, y = 0.0, side = DeviceSide.LEFT),
            line("right", 0.0, 30.0, y = 6.0, side = DeviceSide.RIGHT),
        ))
        assertEquals(2, runs.size)
        assertTrue(runs.all { it.junctionId == null })
        assertTrue(runs.map { it.deviceIds.single() }.toSet() == setOf("left", "right"))
        assertTrue(runs.any { it.name.endsWith("LE") })
        assertTrue(runs.any { it.name.endsWith("LD") })
    }

    @Test
    fun aConfluenceEndsBothIncomingRuns() {
        val runs = DrainAlignments.build(listOf(
            line("b", 0.0, 10.0, y = 0.0),
            line("c", 0.0, 10.0, y0 = 8.0, y1 = 0.0),
            line("a", 10.0, 22.0),
        ))
        assertEquals(3, runs.size)
        val incoming = runs.filter { it.junctionId == "a" }
        assertEquals(2, incoming.size)
        assertTrue(incoming.all { it.deviceIds.size == 1 })
        assertEquals(listOf("a"), runs.first { "a" in it.deviceIds }.deviceIds)
    }

    @Test
    fun aBranchStartsNewAlignments() {
        val runs = DrainAlignments.build(listOf(
            line("a", 0.0, 10.0),
            line("b", 10.0, 20.0, y0 = 0.0, y1 = 3.0),
            line("c", 10.0, 20.0, y0 = 0.0, y1 = -3.0),
        ))
        assertEquals(3, runs.size)
        val parent = runs.single { "a" in it.deviceIds }
        assertEquals(listOf("a"), parent.deviceIds)
        assertNotNull(parent.junctionId)
        assertTrue(runs.none { it.deviceIds.size > 1 })
    }

    @Test
    fun profileFallsDownhillAndTheSectionShowsTheTrench() {
        val devices = listOf(line("a", 0.0, 40.0))
        val terrain: (Double, Double) -> Double? = { x, _ -> 20.0 - x * 0.002 }
        val runs = DrainAlignments.build(devices, terrain = terrain)
        val metrics = { _: String -> DrainMetrics(0.30, 1.0, 0.30, buried = false) }
        val profile = DrainAlignments.profile(runs.single(), devices, terrain, metrics)
        assertTrue(profile.samples.size >= 3)
        for (i in 1 until profile.samples.size) {
            assertTrue(profile.samples[i].station + 1e-6 >= profile.samples[i - 1].station)
            if (!profile.samples[i].structure && !profile.samples[i - 1].structure) {
                assertTrue(profile.samples[i].invert <= profile.samples[i - 1].invert + 1e-6, "invert rose")
            }
        }
        val section = DrainAlignments.sectionAt(runs.single(), profile, 10.0, terrain, metrics)
        val deviceWidth = section.device.maxOf { it.x } - section.device.minOf { it.x }
        val trenchWidth = section.trench.maxOf { it.x } - section.trench.minOf { it.x }
        assertTrue(trenchWidth > deviceWidth + 0.4, "trench $trenchWidth device $deviceWidth")
        assertTrue(section.terrain.any { abs(it.x) < 1e-6 })
        assertTrue(section.device.minOf { it.y } <= section.terrain.first { abs(it.x) < 1e-6 }.y)
    }

    private fun line(
        id: String,
        x0: Double,
        x1: Double,
        y: Double = 0.0,
        y0: Double = y,
        y1: Double = y,
        side: DeviceSide = DeviceSide.AXIS,
    ) = PlacedDevice.Linear(
        id, "VPCG", PathSource.POLYLINE, null, listOf(x0, x1), listOf(y0, y1),
        side, 0.0, null, null,
        GradeSpec(GradeMode.HYDRAULIC, minSlope = 0.005, buried = false, flowAuto = true),
        null,
    )

    private fun point(id: String, x: Double, y: Double) = PlacedDevice.Point(
        id, "CCS", x, y, 0.0, 0.0, GradeSpec(GradeMode.HYDRAULIC), null, null,
    )
}
