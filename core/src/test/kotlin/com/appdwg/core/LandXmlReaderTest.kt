package com.appdwg.core

import org.kxml2.io.KXmlParser
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandXmlReaderTest {

    private fun load(name: String): LandXmlProject {
        val stream = javaClass.classLoader.getResourceAsStream(name)!!
        val parser = KXmlParser()
        parser.setInput(stream, null)
        return LandXmlReader(parser).read()
    }

    private val project by lazy { load("exemplo.xml") }

    @Test
    fun readsHeader() {
        assertEquals("meter", project.linearUnit)
        assertEquals("31983", project.coordinateSystem?.epsgCode)
    }

    @Test
    fun readsSurfaceWithEastingNorthingOrder() {
        val s = project.surfaces.single()
        assertEquals("TERRENO", s.name)
        assertEquals(63, s.vertexCount)
        assertEquals(96, s.triangleCount)
        val b = s.bounds!!
        assertEquals(950.0, b.minX, 1e-9)
        assertEquals(1950.0, b.minY, 1e-9)
    }

    @Test
    fun interpolatesSurfaceElevation() {
        val s = project.surfaces.single()
        // The sample terrain is the plane z = 500 + 0.01 (E - 1000) + 0.02 (N - 2000).
        val z = s.elevationAt(Point2(1123.4, 2077.7))!!
        assertEquals(500 + 0.01 * 123.4 + 0.02 * 77.7, z, 1e-3)
        assertNull(s.elevationAt(Point2(0.0, 0.0)))
    }

    @Test
    fun readsAlignmentAndResolvesPointReference() {
        val a = project.alignments.single()
        assertEquals("EIXO 1", a.name)
        assertEquals(3, a.elements.size)
        assertEquals(100 + 50 * PI + 200, a.length, 1e-3)
        val last = a.vertices.last()
        assertEquals(1200.0, last.x, 1e-9)
        assertEquals(2300.0, last.y, 1e-9)
    }

    @Test
    fun computesStationAndOffset() {
        val a = project.alignments.single()

        // On the first tangent, 5 m north of the axis while heading east: left side.
        val onTangent = a.stationOffset(Point2(1050.0, 2005.0))!!
        assertEquals(50.0, onTangent.station, 1e-6)
        assertEquals(-5.0, onTangent.offset, 1e-6)

        // Middle of the 90 degree arc, 10 m outside it (right side when turning left).
        val mid = Math.toRadians(-45.0)
        val p = Point2(1100 + 110 * Math.cos(mid), 2100 + 110 * Math.sin(mid))
        val onArc = a.stationOffset(p)!!
        assertEquals(100 + 25 * PI, onArc.station, 0.01)
        assertEquals(10.0, onArc.offset, 0.01)
        assertTrue(!onArc.outside)
    }

    @Test
    fun pointAtStationFollowsTheAxis() {
        val a = project.alignments.single()
        val p = a.pointAt(100 + 50 * PI + 50)!!
        assertEquals(1200.0, p.x, 1e-6)
        assertEquals(2150.0, p.y, 1e-6)
    }

    @Test
    fun readsDesignProfile() {
        val profile = assertNotNull(project.alignments.single().profile)
        assertEquals("GREIDE", profile.name)
        assertEquals(3, profile.pvis.size)
        // Tangent before the vertical curve.
        assertEquals(501.0, profile.elevationAt(50.0)!!, 1e-9)
        // Middle of the 100 m parabola between +2 % and -2 %.
        assertEquals(503.5, profile.elevationAt(200.0)!!, 1e-4)
        // Tangent after the curve.
        assertEquals(504 - 0.02 * 100, profile.elevationAt(300.0)!!, 1e-3)
        assertNull(profile.elevationAt(500.0))
    }

    @Test
    fun readsCgPoints() {
        assertEquals(listOf("PV1", "PV2"), project.cgPoints.map { it.name })
        assertEquals(Point3(1000.0, 2000.0, 500.0), project.cgPoints.first().position)
    }

    @Test
    fun queriesPointForStationElevationAndCutFill() {
        val info = PointQuery.at(project, Point2(1050.0, 1995.0))
        assertEquals("TERRENO", info.surfaceName)
        assertEquals(500 + 0.5 - 0.1, info.terrainElevation!!, 1e-6)
        val a = assertNotNull(info.alignment)
        assertEquals("EIXO 1", a.name)
        assertEquals(50.0, a.stationOffset.station, 1e-6)
        assertEquals(5.0, a.stationOffset.offset, 1e-6)
        // Axis point (1050, 2000): terrain 500.5, design 501.0, so 0.5 m of fill.
        assertEquals(500.5, a.terrainOnAxis!!, 1e-6)
        assertEquals(501.0, a.designElevation!!, 1e-6)
        assertEquals(0.5, a.cutFill!!, 1e-6)
    }

    @Test
    fun rasterizesSurfaceForDrawing() {
        val grid = project.surfaces.single().rasterize(maxSize = 40)!!
        assertEquals(30, grid.cols)
        assertEquals(40, grid.rows)
        // Top-left cell centre is (955, 2345) on the plane z = 500 + 0.01 (E - 1000) + 0.02 (N - 2000).
        assertEquals(500 - 0.45 + 6.9, grid[0, 0].toDouble(), 1e-3)
        assertTrue(grid.values.none { it.isNaN() })
    }

    @Test
    fun readsTextPointIds() {
        val xml = """
            <LandXML><Surfaces><Surface name="S"><Definition surfType="TIN">
              <Pnts><P id="a">0 0 1</P><P id="b">0 10 2</P><P id="c">10 0 3</P></Pnts>
              <Faces><F>a b c</F><F>a b missing</F></Faces>
            </Definition></Surface></Surfaces></LandXML>
        """.trimIndent()
        val parser = KXmlParser()
        parser.setInput(xml.reader())
        val p = LandXmlReader(parser).read()
        val s = p.surfaces.single()
        assertEquals(1, s.triangleCount)
        assertEquals(1, p.warnings.size)
        assertEquals(1.0, s.elevationAt(Point2(0.0, 0.0))!!, 1e-9)
    }
}
