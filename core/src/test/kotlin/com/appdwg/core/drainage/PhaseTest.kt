package com.appdwg.core.drainage

import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseTest {
    private fun device(id: String) = PlacedDevice.Point(
        id, "X", 0.0, 0.0, 0.0, 0.0, GradeSpec(GradeMode.FIXED), null, null,
    )

    @Test
    fun aLaterPhaseStaysOffTheDrawing() {
        val placed = listOf(device("a"), device("b"), device("c"))
        val phases = listOf(DevicePhase("b", 2), DevicePhase("c", 1))
        assertEquals(listOf("a", "c"), ConstructionPhase.visible(placed, phases, 1).map { it.id })
        assertEquals(listOf("a", "b", "c"), ConstructionPhase.visible(placed, phases, 2).map { it.id })
    }

    @Test
    fun zeroShowsEveryPhase() {
        val placed = listOf(device("a"), device("b"))
        val phases = listOf(DevicePhase("b", 3))
        assertEquals(listOf("a", "b"), ConstructionPhase.visible(placed, phases, 0).map { it.id })
    }

    @Test
    fun anUnsetPhaseIsAlwaysVisible() {
        val placed = listOf(device("a"))
        assertEquals(listOf("a"), ConstructionPhase.visible(placed, listOf(DevicePhase("a", 0)), 1).map { it.id })
    }
}
