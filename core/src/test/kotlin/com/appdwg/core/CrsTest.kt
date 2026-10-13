package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reference values computed with PROJ 9 (pyproj) using the same IBGE parameters. */
class CrsTest {
    private fun close(e: Double, n: Double, p: Point2, tol: Double = 0.002) {
        assertEquals(e, p.x, tol); assertEquals(n, p.y, tol)
    }

    @Test
    fun sirgasUtm() {
        val c = assertNotNull(CrsCatalog.byEpsg("31983"))
        close(612610.4363, 7795970.8015, c.fromWgs84(-19.9294, -43.9240))
        close(186142.5171, 8253205.3296, c.fromWgs84(-15.7801, -47.9292))
        val (la, lo) = c.toWgs84(612610.4363, 7795970.8015)
        assertEquals(-19.9294, la, 1e-8); assertEquals(-43.9240, lo, 1e-8)
    }

    @Test
    fun sad69AndCorregoAlegreWithIbgeParameters() {
        val sad = assertNotNull(CrsCatalog.byEpsg("29193"))
        close(612655.0973, 7796015.8995, sad.fromWgs84(-19.9294, -43.9240), 0.01)
        close(186188.1232, 8253249.5244, sad.fromWgs84(-15.7801, -47.9292), 0.01)
        val ca = assertNotNull(CrsCatalog.byEpsg("22523"))
        close(612637.0072, 7795978.5595, ca.fromWgs84(-19.9294, -43.9240), 0.01)
        // Back to SIRGAS within millimetres.
        val (la, lo) = ca.toWgs84(612637.0072, 7795978.5595)
        assertEquals(-19.9294, la, 1e-7); assertEquals(-43.9240, lo, 1e-7)
    }

    @Test
    fun ltmAndOriginLatitude() {
        close(207956.6803, 2795458.6616, CrsCatalog.ltm(-44.0).fromWgs84(-19.9294, -43.9240))
        val tm = Crs("t", "t", null, Datum.SIRGAS2000, Projection.TransverseMercator(-20.0, -45.0, 1.0, 150_000.0, 250_000.0))
        close(262655.4985, 257455.0911, tm.fromWgs84(-19.9294, -43.9240))
        val (la, lo) = tm.toWgs84(262655.4985, 257455.0911)
        assertEquals(-19.9294, la, 1e-8); assertEquals(-43.9240, lo, 1e-8)
    }

    @Test
    fun sevenParameters() {
        val d = Datum("x", "X", Ellipsoid.INTERNATIONAL, Helmert(10.0, -20.0, 30.0, 0.5, -0.3, 0.2, 1.5))
        val g = Crs("g", "g", null, d, Projection.Geographic).fromWgs84(-19.9294, -43.9240)
        assertEquals(-43.924041070, g.x, 2e-8); assertEquals(-19.930210953, g.y, 2e-8)
    }

    @Test
    fun searchAndPrj() {
        assertTrue(CrsCatalog.search("sad 69 23s").any { it.epsg == "29193" })
        assertTrue(CrsCatalog.search("31983").single().name.contains("SIRGAS"))
        val prj = CrsCatalog.byEpsg("31983")!!.prj()
        assertTrue("Central_Meridian\",-45.0" in prj && "False_Northing\",1.0E7" in prj || "False_Northing\",10000000.0" in prj)
    }
}

/** New catalogue entries and Lambert, against PROJ 9. */
class CrsWorldTest {
    private fun check(code: String, lon: Double, lat: Double, e: Double, n: Double, tol: Double = 0.01) {
        val c = assertNotNull(CrsCatalog.byEpsg(code), code)
        val p = c.fromWgs84(lat, lon)
        assertEquals(e, p.x, tol, "$code E"); assertEquals(n, p.y, tol, "$code N")
        val (la, lo) = c.toWgs84(p.x, p.y)
        assertEquals(lat, la, 1e-7, "$code lat"); assertEquals(lon, lo, 1e-7, "$code lon")
    }

    @Test fun lambert93() = check("2154", 2.35, 48.85, 652301.5648, 6861302.7259)
    @Test fun mexicoLcc() = check("6372", -99.13, 19.43, 2800504.0738, 828777.3050)
    @Test fun britishNationalGrid() = check("27700", -0.1276, 51.5072, 530043.1950, 180358.2086, 0.05)
    @Test fun posgarFromThePole() = check("5345", -64.18, -31.42, 3673064.6647, 6522991.4944)
    @Test fun colombiaCtm12() = check("9377", -74.07, 4.71, 4881375.8196, 2078540.4422)
    @Test fun portugalTm06() = check("3763", -9.14, 38.72, -87567.1229, -104793.5554)
    @Test fun nztm() = check("2193", 174.76, -36.85, 1756911.9449, 5920321.8182)
    @Test fun mga55() = check("7855", 144.96, -37.81, 320422.8203, 5813305.2226)

    @Test
    fun lambertOneParallelFromProj4() {
        val c = Proj4.parse("9999", "Teste / LCC 1SP", "+proj=lcc +lat_1=18 +lat_0=18 +lon_0=-61 +k_0=0.9998 +x_0=400000 +y_0=200000 +ellps=intl +towgs84=0,0,0 +units=m +no_defs")
        val p = c.fromWgs84(15.0, -60.0)
        assertEquals(507678.3768, p.x, 0.01); assertEquals(-131744.9004, p.y, 0.01)
    }

    @Test
    fun proj4OfTheCatalogueMatches() {
        val c = Proj4.parse("31983", "SIRGAS 2000 / UTM zone 23S", "+proj=utm +zone=23 +south +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs +type=crs")
        val a = c.fromWgs84(-19.9294, -43.9240); val b = CrsCatalog.byEpsg("31983")!!.fromWgs84(-19.9294, -43.9240)
        assertEquals(b.x, a.x, 1e-6); assertEquals(b.y, a.y, 1e-6)
        assertTrue(runCatching { Proj4.parse("3857", "Pseudo-Mercator", "+proj=merc +a=6378137 +b=6378137") }.exceptionOrNull() is Proj4.Unsupported)
    }

    @Test
    fun ownRegionFirst() {
        assertEquals(CrsCatalog.FRANCE, CrsCatalog.regionOrder("FR").first())
        assertTrue(CrsCatalog.all.map { it.id }.let { it.size == it.toSet().size })
    }
}
