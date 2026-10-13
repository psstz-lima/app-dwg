package com.appdwg.core

import org.kxml2.io.KXmlParser
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportersTest {

    private val surface = Surface(
        "TERRENO", doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0), intArrayOf(0, 1, 2, 0, 2, 3),
    )

    private val project = LandXmlProject(
        linearUnit = "meter", coordinateSystem = null, surfaces = listOf(surface), alignments = emptyList(),
        cgPoints = emptyList(), warnings = emptyList(),
        polylines = listOf(Polyline("BORDO", doubleArrayOf(0.0, 5.0, 5.0), doubleArrayOf(0.0, 0.0, 5.0), false)),
        texts = listOf(PlanText("TEXTO", null, true, 1.0, 2.0, 0.5, 0.0, "OLA", 0, 0)),
        points = listOf(CogoPoint("7", "MARCO", 3.0, 4.0, 5.5)),
    )

    @Test
    fun dxfReadsBackIntoTheApp() {
        val out = StringWriter()
        DxfWriter.write(project, out, extra = listOf(ExportLine("_MEDICAO", listOf(Point2(0.0, 0.0), Point2(10.0, 10.0)), false, 4)),
            includeSurfaces = true)
        val back = DxfReader(StringReader(out.toString()).buffered()).read()
        assertTrue(back.polylines.any { it.layer == "BORDO" && it.xs.size == 3 })
        assertTrue(back.polylines.any { it.layer == "_MEDICAO" })
        assertTrue(back.texts.any { it.text == "OLA" })
        assertTrue(back.texts.any { it.layer == "_POINTS" && it.text == "7 MARCO 5.500" })
        assertEquals(2, back.surfaces.sumOf { it.triangleCount })
        assertEquals(3.0, back.surfaces.first().elevationAt(Point2(10.0, 10.0))!!, 1e-6)
    }

    @Test
    fun landXmlReadsBack() {
        val out = StringWriter()
        LandXmlWriter.write(listOf(surface), project.points, emptyList(), out, epsg = "31983",
            sources = mapOf("TERRENO" to SurfaceSource(listOf(listOf(Point3(0.0, 0.0, 1.0), Point3(10.0, 10.0, 3.0))), listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0)))))
        assertTrue("<Breakline brkType=\"standard\"><PntList3D>0.000 0.000 1.000 10.000 10.000 3.000</PntList3D>" in out.toString())
        val parser = KXmlParser()
        parser.setInput(StringReader(out.toString()))
        val back = LandXmlReader(parser).read()
        assertEquals("31983", back.coordinateSystem?.epsgCode)
        val s = back.surfaces.single()
        assertEquals(2, s.triangleCount)
        assertEquals(2.0, s.elevationAt(Point2(5.0, 5.0))!!, 1e-6)
        val p = back.cgPoints.single()
        assertEquals(3.0, p.position.x, 1e-9)
        assertEquals(4.0, p.position.y, 1e-9)
    }

    @Test
    fun utmToGeographicOnTheEquator() {
        val (lat0, lon0) = Utm.toGeographic(500_000.0, 10_000_000.0, 23, south = true)
        assertEquals(0.0, lat0, 1e-9)
        assertEquals(-45.0, lon0, 1e-9)
        // 3° east of the central meridian on the equator is 333 978,557 m east of the false easting.
        val (lat, lon) = Utm.toGeographic(833_978.557, 10_000_000.0, 23, south = true)
        assertEquals(0.0, lat, 1e-7)
        assertEquals(-42.0, lon, 1e-6)
        assertEquals(23, Utm.zoneOfEpsg("31983"))
    }

    @Test
    fun aciPicksTheClosestColor() {
        assertEquals(1, DxfWriter.aci(0xFFFF0000.toInt()))
        assertEquals(5, DxfWriter.aci(0xFF0000FF.toInt()))
    }

    /** Krüger forward series (latitude/longitude to UTM), to check the inverse away from the equator. */
    private fun forward(latDeg: Double, lonDeg: Double, zone: Int): Pair<Double, Double> {
        val f = 1 / 298.257222101
        val a = 6378137.0
        val n = f / (2 - f)
        val aa = a / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
        val al = doubleArrayOf(n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16, 13 * n * n / 48 - 3 * n * n * n / 5, 61 * n * n * n / 240)
        val e = Math.sqrt(f * (2 - f))
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg - (zone * 6 - 183))
        val t = Math.sinh(atanh(Math.sin(phi)) - e * atanh(e * Math.sin(phi)))
        val xi = Math.atan2(t, Math.cos(lam))
        val eta = atanh(Math.sin(lam) / Math.sqrt(1 + t * t))
        var x = eta
        var y = xi
        for (j in 1..3) {
            x += al[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta)
            y += al[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta)
        }
        return 500000 + 0.9996 * aa * x to 10_000_000 + 0.9996 * aa * y
    }

    private fun atanh(v: Double) = 0.5 * Math.log((1 + v) / (1 - v))

    @Test
    fun utmInverseMatchesTheForwardSeries() {
        for ((lat, lon) in listOf(-19.9191 to -43.9386, -15.78 to -47.93, -23.55 to -46.63)) {
            val zone = ((lon + 180) / 6).toInt() + 1
            val (e, n) = forward(lat, lon, zone)
            val (lat2, lon2) = Utm.toGeographic(e, n, zone, south = true)
            assertEquals(lat, lat2, 1e-8)
            assertEquals(lon, lon2, 1e-8)
        }
    }
}
