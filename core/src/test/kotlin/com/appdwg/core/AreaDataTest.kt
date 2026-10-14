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
}
