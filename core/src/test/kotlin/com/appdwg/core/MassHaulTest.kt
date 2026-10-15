package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MassHaulTest {

    /** Rows from per-interval cut and fill (no swell), stations every 20 m. */
    private fun rows(vararg cf: Pair<Double, Double>): List<VolumeRow> {
        var acc = 0.0
        return listOf(VolumeRow(0.0, 0.0, 0.0, 0.0)) + cf.mapIndexed { i, (c, f) -> acc += c - f; VolumeRow(20.0 * (i + 1), c, f, acc) }
    }

    @Test
    fun cutThenFillIsOneWave() {
        // 100 m³ cut in the first 20 m, 100 m³ fill in the 20 m after: triangle 100 high, 40 m wide.
        val m = MassHaul.of(rows(100.0 to 0.0, 0.0 to 100.0))
        assertEquals(1, m.waves.size)
        assertEquals(100.0, m.hauled, 1e-9)
        assertEquals(100.0 * 40 / 2, m.moment, 1e-9)
        assertEquals(20.0, m.dmt, 1e-9)
        assertEquals(0.0, m.spoil, 1e-9); assertEquals(0.0, m.borrow, 1e-9)
    }

    @Test
    fun leftoverCutGoesToSpoil() {
        // 300 cut, 100 fill: 100 balanced, 200 to spoil.
        val m = MassHaul.of(rows(300.0 to 0.0, 0.0 to 100.0))
        assertEquals(100.0, m.hauled, 1e-9)
        assertEquals(200.0, m.spoil, 1e-9)
        assertEquals(0.0, m.borrow, 1e-9)
        // Above the 200 level: triangle 100 high over 20 m on each side → 100·(20/3+20)/2? check the area directly.
        assertEquals(100.0 * (20.0 / 3 + 20.0) / 2, m.moment, 1e-6)
    }

    @Test
    fun twoWavesBothSides() {
        val m = MassHaul.of(rows(50.0 to 0.0, 0.0 to 50.0, 0.0 to 80.0, 80.0 to 0.0))
        assertEquals(2, m.waves.size)
        assertEquals(true, m.waves[0].forward); assertEquals(false, m.waves[1].forward)
        assertEquals(130.0, m.hauled, 1e-9)
        assertEquals(1, m.bands.size)
        assertEquals(130.0, m.bands.single().volume, 1e-9)
    }

    @Test
    fun lateralCompensation() {
        val m = MassHaul.of(listOf(VolumeRow(0.0, 0.0, 0.0, 0.0), VolumeRow(20.0, 30.0, 10.0, 20.0)))
        assertEquals(10.0, m.lateral, 1e-9)
    }
}
