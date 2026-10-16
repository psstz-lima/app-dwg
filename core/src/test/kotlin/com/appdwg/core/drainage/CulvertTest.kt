package com.appdwg.core.drainage

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CulvertTest {
    @Test
    fun aOneMetreBoxMatchesInletAndOutletControl() {
        val barrel = CulvertBarrel(CulvertBarrel.Shape.BOX, 1.0, 1.0, 1)
        val head = Culvert.headwater(barrel, 1.0, 20.0, 0.01, 0.5, allowable = 1.5, manningN = 0.013)
        assertEquals(0.95, head.inlet, 0.03)
        assertEquals(0.58, head.outlet, 0.03)
        assertEquals(head.inlet, head.headwater, 1e-9)
        assertFalse(head.overtopped)
        val low = Culvert.headwater(barrel, 1.0, 20.0, 0.01, 0.5, allowable = 0.5)
        assertTrue(low.overtopped)
    }

    @Test
    fun criticalDepthOfAUnitBox() {
        val dc = Culvert.criticalBox(1.0, 1.0)
        assertEquals((1.0 / Culvert.G).pow(1.0 / 3.0), dc, 1e-9)
    }

    @Test
    fun aQuietPipeDoesNotOvertopAndAFloodDoes() {
        val quiet = Culvert.size(0.2, 15.0, 0.01, 0.0, allowable = 1.5)
        assertFalse(quiet.second.overtopped)
        assertEquals(0.40, quiet.first.rise, 1e-9)
        val flood = Culvert.size(40.0, 15.0, 0.001, 2.0, allowable = 1.0)
        assertTrue(flood.second.overtopped)
        assertTrue(flood.second.headwater > 1.0)
    }

    @Test
    fun noFlowHasNoHeadwater() {
        val barrel = CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 1)
        val head = Culvert.headwater(barrel, 0.0, 30.0, 0.02, 0.4, allowable = 2.0)
        assertEquals(0.0, head.headwater, 1e-9)
        assertFalse(head.overtopped)
    }
}
