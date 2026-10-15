package com.appdwg.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelTest {
    private val cores = Parallel.threads

    @AfterTest
    fun restore() { Parallel.threads = cores }

    private fun <T> both(block: () -> T): Pair<T, T> {
        Parallel.threads = 1
        val one = block()
        Parallel.threads = 4
        return one to block()
    }

    @Test
    fun chunksCoverEveryIndexOnceAndNest() {
        Parallel.threads = 4
        val hits = IntArray(100_000)
        assertTrue(Parallel.forChunks(hits.size, 100) { a, b ->
            for (i in a until b) hits[i]++
            // A nested call runs in the calling thread.
            Parallel.forChunks(10) { _, _ -> true }
        })
        assertTrue(hits.all { it == 1 })
        assertEquals((0 until 1000).map { it * 2 }, Parallel.map(1000) { it * 2 })
    }

    @Test
    fun stopsWhenAPieceSaysSoAndPassesErrorsOn() {
        Parallel.threads = 4
        assertFalse(Parallel.forChunks(10_000, 10) { a, _ -> a < 5000 })
        val e = runCatching { Parallel.forChunks(10_000, 10) { a, _ -> if (a >= 5000) error("boom"); true } }.exceptionOrNull()
        assertEquals("boom", e?.message)
    }

    @Test
    fun rasterContoursAndCorridorMatchOneThread() {
        val s = TestTerrain.surface(60_000, x0 = 760_000.0, y0 = 7_850_000.0)
        val (g1, g4) = both { s.rasterize(512)!! }
        var differ = 0
        for (i in g1.values.indices) {
            val a = g1.values[i]; val b = g4.values[i]
            if (a.isNaN() != b.isNaN() || (!a.isNaN() && kotlin.math.abs(a - b) > 1e-3)) differ++
        }
        assertEquals(0, differ)
        val region = Bounds(760_200.0, 7_850_200.0, 760_700.0, 7_850_600.0)
        val (r1, r4) = both { s.rasterize(region, 300, 200)!!.values.count { !it.isNaN() } }
        assertEquals(r1, r4)

        val (c1, c4) = both { Contours.segments(s, 1.0)!! }
        assertEquals(c1.map { it.elevation }, c4.map { it.elevation })
        for (k in c1.indices) assertContentEquals(c1[k].segments, c4[k].segments)

        val axis = AlignmentBuilder.fromPis("E", listOf(Pi(Point2(760_100.0, 7_850_100.0)), Pi(Point2(760_500.0, 7_850_500.0), radius = 150.0), Pi(Point2(760_500.0, 7_850_900.0))),
            profile = Profile("G", listOf(Profile.Pvi(0.0, 105.0), Profile.Pvi(900.0, 110.0))))
        val (k1, k4) = both { Corridor.build(axis, s, listOf(Region(0.0, 1e9, Template())), step = 10.0) }
        assertEquals(k1.sections.map { it.station }, k4.sections.map { it.station })
        assertEquals(k1.totalCut, k4.totalCut, 1e-9)
        assertEquals(k1.totalFill, k4.totalFill, 1e-9)
        assertEquals(k1.unresolved, k4.unresolved)
    }

    /** PARALLEL_BENCH=1: one thread against all cores on a large terrain. */
    @Test
    fun bench() {
        System.getenv("PARALLEL_BENCH") ?: return
        val s = TestTerrain.surface(1_000_000)
        fun time(label: String, block: () -> Unit) {
            for (t in listOf(1, Runtime.getRuntime().availableProcessors())) {
                Parallel.threads = t
                block()
                val t0 = System.nanoTime()
                repeat(3) { block() }
                println("BENCH $label, $t threads: ${(System.nanoTime() - t0) / 3_000_000} ms")
            }
        }
        time("raster 2048") { s.rasterize(2048) }
        time("curvas 0,5 m") { Contours.segments(s, 0.5) }
        time("volume 1M células") { Volumes.of(s, null, VolumeBase.Elevation(110.0)) }
    }
}
