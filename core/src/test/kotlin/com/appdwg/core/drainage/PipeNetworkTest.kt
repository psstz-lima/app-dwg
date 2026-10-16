package com.appdwg.core.drainage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeNetworkTest {
    private val trench = CrossSection(
        "rect_trench", "m",
        listOf(listOf(SectionPoint(-0.5, 0.0), SectionPoint(-0.5, -1.0), SectionPoint(0.5, -1.0), SectionPoint(0.5, 0.0))),
    )
    private val box = CrossSection(
        "box", "m",
        listOf(listOf(SectionPoint(-0.75, 0.0), SectionPoint(-0.75, 1.0), SectionPoint(0.75, 1.0), SectionPoint(0.75, 0.0))),
    )

    @Test
    fun twoReachesShareTheConnectionAndKeepTheSlope() {
        val first = line("a", 0.0, 10.0, 5.0, 4.0)
        val second = line("b", 10.0, 20.0, 3.98, 3.0)
        val graph = DrainageNetwork.analyze(listOf(first, second), { trench }, { false }, { 0.015 })
        val xml = buildString { PipeNetworkWriter.write("Drenagem", graph, out = this) }
        assertTrue(xml.contains("<PipeNetworks>"))
        assertTrue(xml.contains("name=\"Drenagem\""))
        assertEquals(3, Regex("<Struct ").findAll(xml).count())
        assertEquals(2, Regex("<Pipe ").findAll(xml).count())
        assertTrue(xml.contains("slope=\"0.100\""))
        assertTrue(xml.contains("slope=\"0.098\""))
        assertTrue(xml.contains("<RectPipe width=\"1.000\" height=\"1.000\"/>"))
        assertTrue(!xml.contains("CircPipe"))
        assertTrue(xml.contains("<Center>0.000 0.000</Center>"))
        val names = Regex("Struct name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        val refs = Regex("ref(?:Start|End)=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(4, refs.size)
        assertTrue(refs.all { it in names })
    }

    @Test
    fun aSquareBoxStaysRectangularAndACircleStaysRound() {
        val square = CrossSection(
            "box_culvert", "m",
            listOf(listOf(SectionPoint(0.0, 0.0), SectionPoint(1.5, 0.0), SectionPoint(1.5, 1.5), SectionPoint(0.0, 1.5))),
        )
        val device = line("box", 0.0, 12.0, 2.0, 1.4)
        val graph = DrainageNetwork.analyze(listOf(device), { square }, { true }, { 0.015 })
        val rectangular = buildString { PipeNetworkWriter.write("Drenagem", graph, out = this) }
        assertTrue(rectangular.contains("<RectPipe width=\"1.500\" height=\"1.500\"/>"))
        assertTrue(!rectangular.contains("CircPipe"))
        val round = buildString { PipeNetworkWriter.write("Drenagem", graph, { true }, this) }
        assertTrue(round.contains("<CircPipe diameter=\"1.500\"/>"))
    }

    @Test
    fun aBendIsSplitInsteadOfExportedAsTheChord() {
        val device = PlacedDevice.Linear(
            "bend", "BSCC 150x150 PM", PathSource.POLYLINE, null,
            listOf(0.0, 0.0, 10.0), listOf(0.0, 10.0, 10.0),
            DeviceSide.AXIS, 0.0, null, null, GradeSpec(GradeMode.FIXED, z0 = 5.0, z1 = 4.0), null,
        )
        val section = CrossSection(
            "box_culvert", "m",
            listOf(listOf(SectionPoint(0.0, 0.0), SectionPoint(1.5, 0.0), SectionPoint(1.5, 1.5), SectionPoint(0.0, 1.5))),
        )
        val graph = DrainageNetwork.analyze(listOf(device), { section }, { true }, { 0.015 })
        val path = PipeNetworkWriter.vertices(device, section)
        assertEquals(3, path.size)
        val xml = buildString { PipeNetworkWriter.write("Drenagem", graph, out = this, bends = mapOf(device.id to path)) }
        assertEquals(2, Regex("<Pipe ").findAll(xml).count())
        assertEquals(3, Regex("<Struct ").findAll(xml).count())
        val lengths = Regex("length=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(20.0, lengths.sum(), 0.05)
        assertTrue(lengths.all { it < 14.0 })
    }

    @Test
    fun aBoxWritesARectangularPipe() {
        val device = line("box", 0.0, 12.0, 2.0, 1.4)
        val graph = DrainageNetwork.analyze(listOf(device), { box }, { true }, { 0.015 })
        val xml = buildString { PipeNetworkWriter.write("Drenagem", graph, out = this) }
        assertTrue(xml.contains("<RectPipe width=\"1.500\" height=\"1.000\"/>"))
        assertTrue(xml.contains("slope=\"0.050\""))
    }

    @Test
    fun anEmptyNetworkIsStillLandXml() {
        val xml = buildString { PipeNetworkWriter.write("Drenagem", DrainageGraph(emptyList(), emptyList(), emptyList(), emptyMap()), out = this) }
        assertTrue(xml.contains("<PipeNetworks>"))
        assertTrue(xml.contains("</LandXML>"))
        assertEquals(0, Regex("<Pipe ").findAll(xml).count())
    }

    private fun line(id: String, x0: Double, x1: Double, z0: Double, z1: Double) = PlacedDevice.Linear(
        id, "VPCG", PathSource.POLYLINE, null, listOf(x0, x1), listOf(0.0, 0.0),
        DeviceSide.AXIS, 0.0, null, null, GradeSpec(GradeMode.FIXED, z0 = z0, z1 = z1), null,
    )
}
