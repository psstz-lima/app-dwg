package com.appdwg.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteDesignTest {
    /** A 60 m ridge along x = 1000 with a saddle down to 5 m at y = 700. */
    private fun ridge(x: Double, y: Double): Double {
        val across = exp(-((x - 1000) / 150) * ((x - 1000) / 150))
        val saddle = 1 - 0.92 * exp(-((y - 700) / 120) * ((y - 700) / 120))
        return 100 + 60 * across * saddle
    }
    private val terrain = TestTerrain.surface(40_000, size = 2000.0, z = ::ridge)

    @Test
    fun routesCrossTheRidgeAndKeepTheirCurves() {
        val start = Point2(300.0, 1000.0); val end = Point2(1700.0, 1000.0)
        val rules = RouteDesign.RouteRules(maxGrade = 6.0, speed = 50.0)
        val routes = RouteDesign.suggest(start, end, terrain, Template(), rules)
        assertTrue(routes.isNotEmpty())
        val rmin = RouteDesign.minRadius(50.0, 8.0)
        for (r in routes) {
            assertEquals(start, r.pis.first().position); assertEquals(end, r.pis.last().position)
            assertTrue(r.pis.drop(1).dropLast(1).all { it.radius >= rmin - 1e-6 }, "radii ${r.pis.map { it.radius }}")
            // The alignment really ends at the end point.
            val e = r.alignment.pointAt(r.alignment.endStation)!!
            assertTrue(hypot(e.x - end.x, e.y - end.y) < 1.0, "ends at $e")
        }
        val shortest = routes.first()
        val gentlest = routes.last()
        assertTrue(shortest.length <= gentlest.length + 1)
        assertTrue(routes.size >= 2, "routes ${routes.map { it.kind }}")
        run {
            // The gentle route detours to the saddle: it passes lower over the ridge.
            fun crest(r: RouteSuggestion): Double {
                var best = Double.MAX_VALUE
                var s = r.alignment.startStation
                while (s <= r.alignment.endStation) {
                    val p = r.alignment.pointAt(s)!!
                    if (abs(p.x - 1000) < 15) best = minOf(best, abs(p.y - 700))
                    s += 5.0
                }
                return best
            }
            assertTrue(crest(gentlest) < crest(shortest), "saddle ${crest(gentlest)} vs ${crest(shortest)}")
            val gs = shortest.grade!!; val gg = gentlest.grade!!
            assertTrue(gg.cut + gg.fill < gs.cut + gs.fill, "earthwork ${gg.cut + gg.fill} vs ${gs.cut + gs.fill}")
        }
    }

    @Test
    fun avoidsBlockedAreas() {
        val start = Point2(300.0, 1000.0); val end = Point2(1700.0, 1000.0)
        // A wall from y = 500 to 1500 at x = 600 to 650: the route goes round it.
        val wall = listOf(Point2(600.0, 500.0), Point2(650.0, 500.0), Point2(650.0, 1500.0), Point2(600.0, 1500.0))
        val routes = RouteDesign.suggest(start, end, terrain, Template(), RouteDesign.RouteRules(maxGrade = 6.0, speed = 40.0, avoid = listOf(wall)))
        assertTrue(routes.isNotEmpty())
        val a = routes.first().alignment
        var s = a.startStation
        while (s <= a.endStation) { val p = a.pointAt(s)!!; assertTrue(!SurfaceEdit.inside(p, wall), "crosses the wall at $p"); s += 2.0 }
    }
}
