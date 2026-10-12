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

    /**
     * Reads a station typed as "12 + 5,40" or "12+5.40" (20 m stakes) or as plain metres ("245,4").
     * Null when the text is neither.
     */
    fun parse(text: String, stakeLength: Double = 20.0): Double? {
        val t = text.trim().replace(" ", "").replace(',', '.')
        if (t.isEmpty()) return null
        val plus = t.indexOf('+', startIndex = 1)
        if (plus < 0) return t.toDoubleOrNull()
        val whole = t.substring(0, plus).toDoubleOrNull() ?: return null
        val rest = t.substring(plus + 1).ifEmpty { "0" }.toDoubleOrNull() ?: return null
        return if (whole < 0) whole * stakeLength - rest else whole * stakeLength + rest
    }

    fun decimal(value: Double, digits: Int = 2): String =
        String.format(PT_BR, "%.${digits}f", value)

    /** Offset in metres with side: "3,25 m D" (right) or "3,25 m E" (left). */
    fun offset(value: Double): String =
        decimal(abs(value)) + if (value >= 0) " m D" else " m E"

    private val PT_BR = Locale.forLanguageTag("pt-BR")
}
