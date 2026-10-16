package com.appdwg.core.drainage

/** Construction phase of one placed device. Phase 0 means the device is always shown. */
data class DevicePhase(val deviceId: String, val phase: Int)

object ConstructionPhase {
    /**
     * Devices drawn for a view that shows work through [through].
     * Zero and missing phases stay visible. [through] at or below zero shows every device.
     */
    fun visible(placed: List<PlacedDevice>, phases: List<DevicePhase>, through: Int): List<PlacedDevice> {
        if (through <= 0) return placed
        val assigned = HashMap<String, Int>()
        for (phase in phases) if (phase.phase > 0) assigned[phase.deviceId] = phase.phase
        return placed.filter { device ->
            val phase = assigned[device.id] ?: 0
            phase == 0 || phase <= through
        }
    }
}
