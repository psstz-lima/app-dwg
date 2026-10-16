package com.appdwg.core.drainage

import kotlin.math.PI

/** A drain trench or a drainage blanket. */
sealed interface SubsurfaceQty

/** French drain: trench volume, filter after the pipe, and pipe length. */
data class DrainTakeoff(
    val excavation: Double,
    val filter: Double,
    val pipe: Double,
) : SubsurfaceQty

/** Drainage blanket in a cut: volume, and the thickness and slope length used. */
data class BlanketTakeoff(
    val volume: Double,
    val thickness: Double,
    val slopeLength: Double,
) : SubsurfaceQty

/**
 * Subsurface quantities. A drain is a trench minus the pipe. A blanket is thickness times
 * the slope length times the run along the cut.
 */
object Subsurface {
    fun french(width: Double, depth: Double, length: Double, pipeDiameter: Double): DrainTakeoff {
        if (width <= 0.0 || depth <= 0.0 || length <= 0.0) return DrainTakeoff(0.0, 0.0, 0.0)
        val excavation = width * depth * length
        val pipeVolume = if (pipeDiameter > 0.0) PI * (pipeDiameter / 2.0) * (pipeDiameter / 2.0) * length else 0.0
        return DrainTakeoff(excavation, (excavation - pipeVolume).coerceAtLeast(0.0), if (pipeDiameter > 0.0) length else 0.0)
    }

    fun blanket(thickness: Double, slopeLength: Double, length: Double): Double {
        if (thickness <= 0.0 || slopeLength <= 0.0 || length <= 0.0) return 0.0
        return thickness * slopeLength * length
    }

    /** Pipe diameter from a material name such as `tubo_pvc_perf_15`, otherwise 100 mm. */
    fun pipeDiameter(device: DeviceDef): Double {
        val text = device.materials.joinToString(" ") { it.name }
        val cm = Regex("(\\d{2,3})").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0.10
        return if (cm in 5.0..100.0) cm / 100.0 else 0.10
    }

    /** Drain or blanket for [device] over [length] metres. Null when it is neither. */
    fun of(device: DeviceDef, length: Double): SubsurfaceQty? {
        if (length <= 0.0 || !length.isFinite()) return null
        val label = (device.subtype + " " + device.name).lowercase()
        if ("camada" in label || "colch" in label) {
            val thickness = metres(device, "espessura_cm", "espessura_m") ?: 0.20
            val slope = metres(device, "talude_m", "talude_cm", "comprimento_talude_m") ?: 1.0
            return BlanketTakeoff(blanket(thickness, slope, length), thickness, slope)
        }
        if (device.chapter !in 2..4 && "dreno" !in label) return null
        val width = metres(device, "largura_cm", "largura_m") ?: return null
        val depth = metres(device, "profundidade_cm", "profundidade_m") ?: return null
        return french(width, depth, length, pipeDiameter(device))
    }

    private fun metres(device: DeviceDef, vararg keys: String): Double? {
        for (key in keys) {
            val number = device.parameters.find { it.key == key }?.number ?: continue
            if (number <= 0.0) continue
            return if (key.endsWith("_cm")) number / 100.0 else number
        }
        return null
    }
}
