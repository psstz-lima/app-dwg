package com.appdwg.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object StationFormat {
    /**
     * Brazilian notation with 20 m stakes: 245.40 m becomes "12 + 5,40".
     */
    fun estaca(station: Double, stakeLength: Double = 20.0): String {
        val sign = if (station < 0) "-" else ""
        val s = abs(station)
        var whole = floor(s / stakeLength).toLong()
        var rest = s - whole * stakeLength
        if (rest >= stakeLength - 0.005) {
            whole += 1
            rest = 0.0
        }
        return sign + whole + " + " + decimal(rest)
    }

    fun decimal(value: Double, digits: Int = 2): String =
        String.format(PT_BR, "%.${digits}f", value)

    /** Offset with side: "3,25 D" (right) or "3,25 E" (left). */
    fun offset(value: Double): String =
        decimal(abs(value)) + if (value >= 0) " D" else " E"

    private val PT_BR = Locale.forLanguageTag("pt-BR")
}
