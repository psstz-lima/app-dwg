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
        val head = Culvert.headwater(barrel, 1.0, 20.0, 0.01, 0.5, 1.5)
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
    fun aDownhillSlopeKeepsItsMagnitudeAndAnUphillOneIsNotFavorable() {
        assertEquals(0.01, Culvert.favorableSlope(GradeSpec(GradeMode.SLOPE, slope = -0.01), 15.0), 1e-12)
        assertEquals(0.0, Culvert.favorableSlope(GradeSpec(GradeMode.SLOPE, slope = 0.01), 15.0), 1e-12)
        assertEquals(0.02, Culvert.favorableSlope(GradeSpec(GradeMode.FIXED, z0 = 10.0, z1 = 9.7), 15.0), 1e-9)
        assertEquals(0.0, Culvert.favorableSlope(GradeSpec(GradeMode.FIXED, z0 = 9.0, z1 = 10.0), 15.0), 1e-12)
        val barrel = CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 1)
        val onePercent = Culvert.headwater(barrel, 1.5, 40.0, 0.01, 0.0, 2.0)
        val tenth = Culvert.headwater(barrel, 1.5, 40.0, 0.001, 0.0, 2.0)
        assertTrue(tenth.headwater > onePercent.headwater + 0.001)
    }

    @Test
    fun aTwinBarrelAndASquareBoxKeepTheirOwnCodes() {
        assertEquals("BDTC 100", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 2)))
        assertEquals("BTTC 100", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 3)))
        assertEquals("BSTC 60", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 0.40, 0.40, 1)))
        assertEquals("BDTC 150", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 2.0, 2.0, 2)))
        assertEquals(200.0, CulvertCodes.params(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 2.0, 2.0, 2))["DN_cm"]!!, 1e-6)
        assertEquals(2.0, CulvertCodes.params(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 2.0, 2.0, 2))["n_linhas"]!!, 1e-9)
        assertEquals("BSCC 150x150 PM", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.BOX, 1.5, 1.5, 1)))
        assertEquals("BDCC 150x150 PM", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.BOX, 1.5, 1.5, 2)))
        assertEquals("BTCC 200x200 PM", CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.BOX, 2.0, 2.0, 3)))
        assertFalse(CulvertCodes.code(CulvertBarrel(CulvertBarrel.Shape.BOX, 1.5, 1.5, 1)).startsWith("BSTC"))
    }

    @Test
    fun noFlowHasNoHeadwater() {
        val barrel = CulvertBarrel(CulvertBarrel.Shape.CIRCLE, 1.0, 1.0, 1)
        val head = Culvert.headwater(barrel, 0.0, 30.0, 0.02, 0.4, allowable = 2.0)
        assertEquals(0.0, head.headwater, 1e-9)
        assertFalse(head.overtopped)
    }
}
