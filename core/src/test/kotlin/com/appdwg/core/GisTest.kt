package com.appdwg.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GisTest {

    private val pts = listOf(CogoPoint("P1", "MARCO", 612000.0, 7795000.0, 850.25), CogoPoint("P2", "", 612010.5, 7795020.0, Double.NaN))
    private val lines = listOf(
        Polyline("CERCA", doubleArrayOf(612000.0, 612100.0, 612150.0), doubleArrayOf(7795000.0, 7795050.0, 7795000.0), false, zs = doubleArrayOf(1.0, 2.0, 3.0)),
        Polyline("LOTE", doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0), true),
    )

    @Test
    fun shapefileRoundTrip() {
        val out = ByteArrayOutputStream()
        Gis.writeShapefileZip(pts, lines, Gis.sirgasUtmPrj(23, true), out)
        val names = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("pontos.shp" in names && "linhas.dbf" in names && "poligonos.prj" in names)
        // The first shapefile in the ZIP is the points one.
        val (shp, dbf, prj) = Gis.unzipShapefile(out.toByteArray())!!
        assertEquals("31983", Gis.prjEpsg(prj))
        val f = Gis.readShapefile(shp, dbf) { x, y -> Point2(x, y) }
        assertEquals(2, f.size)
        val p = f[0] as GisFeature.Point
        assertEquals(612000.0, p.p.x, 1e-9); assertEquals(850.25, p.p.z, 1e-9)
        assertEquals("P1", p.attributes["NOME"]); assertEquals("MARCO", p.attributes["CODIGO"])
        val proj = Gis.toProject(f, "shape", "31983")
        assertEquals(listOf("P1", "P2"), proj.cgPoints.map { it.name })
    }

    @Test
    fun linesAndPolygonsFromTheZip() {
        val out = ByteArrayOutputStream()
        Gis.writeShapefileZip(emptyList(), lines, null, out)
        val (shp, dbf, _) = Gis.unzipShapefile(out.toByteArray())!!
        val f = Gis.readShapefile(shp, dbf) { x, y -> Point2(x, y) }
        val l = f.single() as GisFeature.Line
        assertEquals(3, l.pts.size); assertEquals(3.0, l.pts[2].z, 1e-9); assertEquals("CERCA", l.attributes["CAMADA"])
    }

    @Test
    fun geoJsonRoundTripThroughLongitudeLatitude() {
        val sb = StringBuilder()
        Gis.writeGeoJson(pts, lines.take(1), { x, y -> Utm.toGeographic(x, y, 23, true).let { (lat, lon) -> lon to lat } }, sb)
        val text = sb.toString()
        val (xs, ys) = Gis.geoJsonExtent(text)
        assertTrue(Gis.looksGeographic(xs, ys))
        val f = Gis.readGeoJson(text) { lon, lat -> UtmForward.fromGeographic(lat, lon, 23) }
        assertEquals(3, f.size)
        val p = f[0] as GisFeature.Point
        assertEquals(612000.0, p.p.x, 0.01); assertEquals(7795000.0, p.p.y, 0.01); assertEquals(850.25, p.p.z, 1e-9)
        val proj = Gis.toProject(f, "geo", null)
        assertEquals("CERCA", proj.polylines.single().layer)
        assertEquals("MARCO", proj.cgPoints.first().code)
    }

    @Test
    fun jsonParser() {
        val v = Json.parse("""{"a":[1,2.5,-3e2],"b":"x\"yé","c":null,"d":true}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"]); assertEquals("x\"yé", v["b"]); assertEquals(true, v["d"])
    }
}

class GisZipTest {
    @Test
    fun everyShapefileOfTheZip() {
        val out = java.io.ByteArrayOutputStream()
        Gis.writeShapefileZip(listOf(CogoPoint("A", "", 1.0, 2.0, 3.0)),
            listOf(Polyline("L", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), false), Polyline("P", doubleArrayOf(0.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0), true)), null, out)
        assertEquals(listOf("linhas", "poligonos", "pontos"), Gis.unzipShapefiles(out.toByteArray()).map { it.name })
    }
}
