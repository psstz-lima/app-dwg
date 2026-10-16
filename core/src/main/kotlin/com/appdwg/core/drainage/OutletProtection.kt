package com.appdwg.core.drainage

/** Soil the outlet discharges onto. [limit] is the permissible mean velocity, m/s. */
enum class OutletSoil(val limit: Double) {
    SILT(0.60),
    SAND(0.75),
    CLAY(1.20),
    GRAVEL(1.50),
    ROCK(4.00),
}

/** Velocity at an outlet and the dumped-rock size that stays put on [soil]. */
data class OutletAdvice(
    val velocity: Double,
    val limit: Double,
    val d50: Double,
    val dissipator: Boolean,
)

/**
 * Compares the outlet velocity with the soil limit and sizes dumped riprap by Isbash.
 * D50 = V² / (2 g (Ss − 1) C²), with Ss = 2.65 and C = 0.86.
 */
object OutletProtection {
    const val G = 9.80665
    const val SPECIFIC_GRAVITY = 2.65
    const val DUMPED_ROCK = 0.86

    /** Mean velocity Q/A, m/s. Zero when there is no flow or no section. */
    fun velocity(discharge: Double, area: Double): Double {
        if (discharge <= 0.0 || area <= 0.0 || !discharge.isFinite() || !area.isFinite()) return 0.0
        return discharge / area
    }

    /** Median stone size, metres. Zero when the water is still. */
    fun d50(velocity: Double): Double {
        if (velocity <= 0.0 || !velocity.isFinite()) return 0.0
        val denominator = 2.0 * G * (SPECIFIC_GRAVITY - 1.0) * DUMPED_ROCK * DUMPED_ROCK
        return velocity * velocity / denominator
    }

    fun advise(discharge: Double, area: Double, soil: OutletSoil): OutletAdvice {
        val speed = velocity(discharge, area)
        return OutletAdvice(speed, soil.limit, d50(speed), speed > soil.limit + 1e-9)
    }
}
