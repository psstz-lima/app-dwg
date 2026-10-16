package com.appdwg.core.drainage

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsurfaceTest {
    @Test
    fun frenchDrainSubtractsThePipeFromTheTrench() {
        val qty = Subsurface.french(0.50, 1.50, 10.0, 0.15)
        val pipe = PI * 0.075 * 0.075 * 10.0
        assertEquals(7.5, qty.excavation, 1e-9)
        assertEquals(7.5 - pipe, qty.filter, 1e-9)
        assertEquals(10.0, qty.pipe, 0.0)
    }

    @Test
    fun aZeroTrenchHasNoQuantity() {
        assertEquals(0.0, Subsurface.french(0.0, 1.0, 10.0, 0.1).excavation, 0.0)
        assertEquals(0.0, Subsurface.blanket(0.2, 0.0, 10.0), 0.0)
    }

    @Test
    fun blanketIsThicknessTimesSlopeTimesRun() {
        assertEquals(160.0, Subsurface.blanket(0.20, 8.0, 100.0), 1e-9)
    }

    @Test
    fun aCatalogDeepDrainUsesItsTrench() {
        val device = CatalogLoader.bundled().device("DPS 01")!!
        assertEquals(0.15, Subsurface.pipeDiameter(device), 1e-9)
        val qty = Subsurface.of(device, 10.0) as DrainTakeoff
        assertEquals(7.5, qty.excavation, 1e-9)
        assertTrue(qty.filter < qty.excavation)
    }

    @Test
    fun aDrainageBlanketUsesThicknessAndSlope() {
        val device = CatalogLoader.bundled().device("CDR (camada drenante)")!!
        val qty = Subsurface.of(device, 20.0) as BlanketTakeoff
        assertEquals(0.20, qty.thickness, 0.0)
        assertEquals(1.0, qty.slopeLength, 0.0)
        assertEquals(4.0, qty.volume, 1e-9)
    }

    @Test
    fun aSurfaceDitchIsNotSubsurface() {
        val device = CatalogLoader.bundled().device("VPCG 160-30")!!
        assertNull(Subsurface.of(device, 10.0))
    }
}
