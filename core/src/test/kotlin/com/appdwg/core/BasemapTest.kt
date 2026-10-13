package com.appdwg.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasemapTest {
    @Test
    fun tilePositionRoundTrips() {
        assertEquals(1.0 to 1.0, WebTiles.tileXY(0.0, 0.0, 1))
        val (x, y) = WebTiles.tileXY(-19.9167, -43.9345, 15)
        val (la, lo) = WebTiles.latLon(x, y, 15)
        assertEquals(-19.9167, la, 1e-9); assertEquals(-43.9345, lo, 1e-9)
        // Belo Horizonte, zoom 10: tile 387 east, 569 south.
        assertEquals(387, x.toInt() shr 5); assertEquals(569, y.toInt() shr 5)
    }

    @Test
    fun cornersAreInProjectUtm() {
        val e = 609_000.0; val n = 7_797_000.0
        val (la, lo) = Utm.toGeographic(e, n, 23, true)
        val (tx, ty) = WebTiles.tileXY(la, lo, 18)
        val c = WebTiles.corners(TileId(18, tx.toInt(), ty.toInt()), U23)
        assertTrue(c.nw.x <= e && e <= c.ne.x && c.sw.y <= n && n <= c.nw.y, "ponto dentro do tile")
        // About 0.6 m per pixel at zoom 18 near 20° S: a tile is ~147 m wide.
        assertEquals(256 * WebTiles.metersPerPixel(la, 18), c.ne.x - c.nw.x, 0.5)
    }

    @Test
    fun coveringAndZoom() {
        val b = Bounds(609_000.0, 7_797_000.0, 610_000.0, 7_798_000.0)
        val tiles = WebTiles.covering(b, U23, 16)
        assertTrue(tiles.size in 4..30, "tiles: ${tiles.size}")
        assertTrue(tiles.all { t -> val c = WebTiles.corners(t, U23); c.ne.x > b.minX && c.nw.x < b.maxX && c.nw.y > b.minY && c.sw.y < b.maxY })
        assertEquals(18, WebTiles.zoomFor(WebTiles.metersPerPixel(-20.0, 18), -20.0))
        assertTrue(abs(WebTiles.pyramid(b, U23, 10, 12).size - 3) <= 6)
        assertEquals("https://b.t/16/5/7.png", WebTiles.url("https://{s}.t/{z}/{x}/{y}.png", TileId(16, 5, 7)).replace("c.t", "b.t").replace("a.t", "b.t"))
    }
}
private val U23 = CrsCatalog.byEpsg("31983")!!
