package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AreaDataTest {
    @Test
    fun tileNames() {
        assertEquals("Copernicus_DSM_COG_10_S20_00_W044_00_DEM", CopernicusDem.tileName(-20, -44))
        assertEquals("Copernicus_DSM_COG_10_N45_00_E007_00_DEM", CopernicusDem.tileName(45, 7))
        assertEquals(listOf(-20 to -44, -20 to -43), CopernicusDem.tiles(-19.9, -43.5, -19.8, -42.9))
    }

    @Test
    fun flatGroundNeedsOnlyCorners() {
        val g = GeoGrid(-19.0, -44.0, 0.001, 0.001, 33, 33, FloatArray(33 * 33) { 800f })
        val s = assertNotNull(GridSurface.build("T", g, 0.5, { lat, lon -> Point2(lon * 100000, lat * 100000) }))
        assertEquals(4, s.vertexCount)
    }

    @Test
    fun keepsDetailWhereTheGroundChanges() {
        val n = 65
        // A ridge along one column: the TIN must follow it within the tolerance.
        val g = GeoGrid(-19.0, -44.0, 0.001, 0.001, n, n, FloatArray(n * n) { i -> if (i % n == 40) 830f else 800f })
        val s = assertNotNull(GridSurface.build("T", g, 0.5, { lat, lon -> Point2(lon * 100000, lat * 100000) }))
        assertTrue(s.vertexCount in 5 until n * n)
        val z = s.elevationAt(Point2(g.lon(40) * 100000, g.lat(20) * 100000))
        assertEquals(830.0, assertNotNull(z), 0.5)
    }

    @Test
    fun overpassWaysBecomeLayeredGeoJson() {
        val answer = """{"elements":[
            {"type":"way","id":1,"tags":{"highway":"primary","name":"Av. \"A\""},"geometry":[{"lat":-19.9,"lon":-43.9},{"lat":-19.91,"lon":-43.91}]},
            {"type":"way","id":2,"tags":{"building":"yes"},"geometry":[{"lat":-19.9,"lon":-43.9},{"lat":-19.9,"lon":-43.89},{"lat":-19.89,"lon":-43.89},{"lat":-19.9,"lon":-43.9}]},
            {"type":"way","id":3,"tags":{"amenity":"bench"},"geometry":[{"lat":-19.9,"lon":-43.9},{"lat":-19.91,"lon":-43.91}]}]}"""
        val (json, count) = Overpass.toGeoJson(listOf(answer, answer))
        assertEquals(2, count)
        val features = Gis.readGeoJson(json) { x, y -> Point2(x, y) }
        val p = Gis.toProject(features, "OSM", null)
        assertEquals(setOf("OSM_RUAS_PRINCIPAIS", "OSM_CONSTRUCOES"), p.polylines.map { it.layer }.toSet())
        assertEquals(0xFFE8743B.toInt(), p.polylines.first { it.layer == "OSM_RUAS_PRINCIPAIS" }.color)
        assertTrue("(-19.91" in Overpass.query(-19.91, -43.91, -19.89, -43.89, setOf(Overpass.Kind.ROADS)).replace(",", "("))
    }

    @Test
    fun gridWrittenAsGeoTiffReadsBack() {
        val g = GeoGrid(-19.0, -44.0, 0.01, 0.01, 4, 5, FloatArray(20) { 800f + it })
        val out = java.io.ByteArrayOutputStream(); GeoTiffWriter.writeFloat(g, out)
        val bytes = out.toByteArray()
        val r = TiffReader(object : RandomInput {
            override val size = bytes.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = System.arraycopy(bytes, position.toInt(), buffer, offset, length)
        })
        assertEquals(g.values.toList(), r.readFloats(0, 0, 0, 5, 4).toList())
        val e = assertNotNull(r.extent())
        assertEquals(-44.005, e.minX, 1e-9); assertEquals(-18.995, e.maxY, 1e-9)
    }

    @Test
    fun sentinelScenesMustCoverTheArea() {
        fun item(id: String, lon0: Double) = """{"id":"$id","properties":{"datetime":"2026-10-0${id.last()}T13:00:00Z","eo:cloud_cover":3.5,"proj:epsg":32723},
            "geometry":{"type":"Polygon","coordinates":[[[$lon0,-20.0],[${lon0 + 1},-20.0],[${lon0 + 1},-19.0],[$lon0,-19.0],[$lon0,-20.0]]]},
            "assets":{"visual":{"href":"https://x/$id/TCI.tif","proj:transform":[10,0,600000,0,-10,7900000],"proj:shape":[10980,10980]},"scl":{"href":"https://x/$id/SCL.tif"}}}"""
        val json = """{"features":[${item("A1", -44.0)},${item("B2", -43.94)}]}"""
        val s = Sentinel2.scenes(json, -19.94, -43.95, -19.91, -43.92)
        assertEquals(listOf("A1"), s.map { it.id })
        assertEquals("32723", s[0].epsg); assertEquals(600000.0, s[0].originX); assertEquals(10.0, s[0].pixel)
        assertEquals("Contains modified Copernicus Sentinel data 2026", Sentinel2.attribution(s[0].year))
        assertEquals("https://x/A1/SCL.tif", s[0].scl)
    }

    @Test
    fun cloudShareCountsCloudsAndShadowsOverValidPixels() {
        // 0 no data, 4 vegetation, 5 bare soil, 3 shadow, 8/9 cloud, 10 cirrus.
        assertEquals(0.5, Sentinel2.cloudShare(intArrayOf(0, 0, 4, 5, 3, 9))!!, 1e-9)
        assertEquals(0.0, Sentinel2.cloudShare(intArrayOf(4, 4, 6))!!, 1e-9)
        assertEquals(null, Sentinel2.cloudShare(intArrayOf(0, 0)))
        assertEquals(1.0, Sentinel2.cloudShare(intArrayOf(8, 10))!!, 1e-9)
    }

    @Test
    fun autoContrastStretches() {
        val px = IntArray(1000) { i -> (0xFF shl 24) or ((60 + i % 40) shl 16) or ((60 + i % 40) shl 8) or (60 + i % 40) }
        Sentinel2.autoContrast(px)
        assertTrue(px.minOf { (it shr 16) and 0xFF } <= 10 && px.maxOf { (it shr 16) and 0xFF } >= 245)
    }
}
