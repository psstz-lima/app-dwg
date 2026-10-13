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
