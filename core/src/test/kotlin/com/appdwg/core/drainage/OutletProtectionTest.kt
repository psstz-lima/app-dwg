package com.appdwg.core.drainage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutletProtectionTest {
    @Test
    fun isbashStoneForTwoMetresPerSecond() {
        val stone = OutletProtection.d50(2.0)
        val expected = 4.0 / (2.0 * 9.80665 * 1.65 * 0.86 * 0.86)
        assertEquals(expected, stone, 1e-9)
        assertEquals(0.167, stone, 1e-3)
    }

    @Test
    fun stillWaterNeedsNoStone() {
        assertEquals(0.0, OutletProtection.velocity(0.0, 1.0), 0.0)
        assertEquals(0.0, OutletProtection.d50(0.0), 0.0)
        assertFalse(OutletProtection.advise(0.0, 1.0, OutletSoil.SILT).dissipator)
    }

    @Test
    fun aFastOutletOnSiltAsksForADissipator() {
        val advice = OutletProtection.advise(1.5, 1.0, OutletSoil.SILT)
        assertEquals(1.5, advice.velocity, 1e-9)
        assertEquals(0.6, advice.limit, 0.0)
        assertTrue(advice.dissipator)
        assertTrue(advice.d50 > 0.09)
    }

    @Test
    fun theSameFlowOnRockStaysUnderTheLimit() {
        val advice = OutletProtection.advise(1.5, 1.0, OutletSoil.ROCK)
        assertFalse(advice.dissipator)
        assertEquals(4.0, advice.limit, 0.0)
    }
}
