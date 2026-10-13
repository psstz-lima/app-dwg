package com.appdwg.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Units shown on screen and in reports; the model itself is always in metres. */
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * How numbers are shown: the device's locale for the decimal and thousands separators, and the
 * unit system. Files written for other programs never use this (they always use a dot, in metres).
 */
object Display {
    @Volatile var locale: Locale = Locale.forLanguageTag("pt-BR")
    @Volatile var units: UnitSystem = UnitSystem.METRIC

    /** Imperial by default only in the United States. */
    fun defaultUnits(locale: Locale) = if (locale.country.equals("US", true)) UnitSystem.IMPERIAL else UnitSystem.METRIC

    val imperial get() = units == UnitSystem.IMPERIAL
    /** Portuguese uses E/D for left/right of the axis; other languages L/R. */
    val portuguese get() = locale.language == "pt"
}

object StationFormat {
    /** International foot. */
    const val FOOT = 0.3048
    const val SQ_FOOT = FOOT * FOOT
    const val ACRE = 4046.8564224
    const val CUBIC_YARD = 0.764554857984

    /**
     * Station along an alignment: Brazilian 20 m stakes ("12 + 5,40") in metric, 100 ft stations
     * ("8+05.12") in imperial.
     */
    fun estaca(station: Double, stakeLength: Double = 20.0): String {
        if (Display.imperial) {
            val ft = station / FOOT
            val sign = if (ft < 0) "-" else ""
            val s = abs(ft)
            var whole = floor(s / 100).toLong()
            var rest = s - whole * 100
            if (rest >= 100 - 0.005) { whole += 1; rest = 0.0 }
            return sign + whole + "+" + (if (rest < 10) "0" else "") + decimal(rest)
        }
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

    /** Brazilian 20 m stakes whatever the shown units, for files and sheets that stay metric. */
    fun estacaMetric(station: Double): String {
        val saved = Display.units
        if (saved == UnitSystem.METRIC) return estaca(station)
        val sign = if (station < 0) "-" else ""
        val s = abs(station)
        var whole = floor(s / 20.0).toLong()
        var rest = s - whole * 20.0
        if (rest >= 20.0 - 0.005) { whole += 1; rest = 0.0 }
        return sign + whole + " + " + decimal(rest)
    }

    /** Station label for a chart: whole stakes as "12" (or "8+00" in imperial), others in full. */
    fun estacaWhole(station: Double): String {
        val e = estaca(station)
        if (Display.imperial) {
            val zero = symbols().decimalSeparator + "00"
            return if (e.endsWith(zero)) e.removeSuffix(zero) else e
        }
        val zero = " + " + decimal(0.0)
        return if (e.endsWith(zero)) e.removeSuffix(zero) else e
    }

    /**
     * Reads a station typed as "12 + 5,40" or "12+5.40" (20 m stakes; 100 ft stations in
     * imperial) or as a plain length ("245,4"). Metres back; null when the text is neither.
     */
    fun parse(text: String, stakeLength: Double = 20.0): Double? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val plus = t.indexOf('+', startIndex = 1)
        val unit = if (Display.imperial) FOOT else 1.0
        if (plus < 0) return number(t)?.times(unit)
        val whole = number(t.substring(0, plus)) ?: return null
        val rest = number(t.substring(plus + 1).ifEmpty { "0" }) ?: return null
        val stake = if (Display.imperial) 100.0 else stakeLength
        val v = if (whole < 0) whole * stake - rest else whole * stake + rest
        return v * unit
    }

    private fun symbols() = DecimalFormatSymbols.getInstance(Display.locale)

    /** Plain number in the display locale, without thousands separators (safe to edit and read back). */
    fun decimal(value: Double, digits: Int = 2): String {
        val f = DecimalFormat("0" + if (digits > 0) "." + "0".repeat(digits) else "", symbols())
        f.roundingMode = java.math.RoundingMode.HALF_UP
        return f.format(value + 0.0)
    }

    /** Number with thousands separators ("1.234,56" in pt-BR, "1,234.56" in English). */
    fun grouped(value: Double, digits: Int = 2): String {
        val f = DecimalFormat("#,##0" + if (digits > 0) "." + "0".repeat(digits) else "", symbols())
        return f.format(value + 0.0)
    }

    /**
     * Number typed by the user, in either convention: "1.234,56", "1,234.56", "12,5" or "12.5".
     * With both separators the last one is the decimal; a lone comma is decimal unless it groups
     * thousands in an English locale.
     */
    fun number(text: String): Double? {
        val t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val c = t.lastIndexOf(','); val d = t.lastIndexOf('.')
        val norm = when {
            c >= 0 && d >= 0 -> if (c > d) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            c >= 0 -> if (!Display.portuguese && Regex("^-?\\d{1,3}(,\\d{3})+$").matches(t)) t.replace(",", "") else t.replace(',', '.')
            else -> t
        }
        return norm.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    // ---- Units ----

    val lengthUnit get() = if (Display.imperial) "ft" else "m"
    val areaUnit get() = if (Display.imperial) "ft²" else "m²"
    val volumeUnit get() = if (Display.imperial) "yd³" else "m³"

    /** Metres to the shown length unit, and back. */
    fun toLength(m: Double) = if (Display.imperial) m / FOOT else m
    fun fromLength(v: Double) = if (Display.imperial) v * FOOT else v

    /** A length or a height with its unit: "12,35 m" or "40.52 ft". */
    fun length(m: Double, digits: Int = 2) = decimal(toLength(m), digits) + " " + lengthUnit

    /** Just the number of a length in the shown unit (for coordinates and fields). */
    fun lengthValue(m: Double, digits: Int = 3) = decimal(toLength(m), digits)

    /** Length typed by the user in the shown unit, back in metres. */
    fun parseLength(text: String) = number(text)?.let(::fromLength)

    /** Area with its unit, and hectares or acres added when large. */
    fun area(m2: Double, digits: Int = 2): String =
        if (Display.imperial) grouped(m2 / SQ_FOOT, digits) + " ft²" + if (m2 >= ACRE / 4) " (" + decimal(m2 / ACRE, 3) + " ac)" else ""
        else grouped(m2, digits) + " m²" + if (m2 >= 10_000) " (" + decimal(m2 / 10_000, 4) + " ha)" else ""

    /** Large areas: hectares or acres. */
    fun landArea(m2: Double, digits: Int = 2) =
        if (Display.imperial) decimal(m2 / ACRE, digits) + " ac" else decimal(m2 / 10_000, digits) + " ha"

    fun volume(m3: Double, digits: Int = 1) =
        if (Display.imperial) grouped(m3 / CUBIC_YARD, digits) + " yd³" else grouped(m3, digits) + " m³"

    /** Offset with side: "3,25 m D" (right) or "3,25 m E" (left); "10.66 ft R" / "L" in English. */
    fun offset(value: Double): String {
        val right = if (Display.portuguese) "D" else "R"
        val left = if (Display.portuguese) "E" else "L"
        return length(abs(value)) + " " + if (value >= 0) right else left
    }
}

/**
 * Numbers for a report or a file, in metres ([metric]) or in the shown units. CSV files stay
 * metric (other programs read them); PDFs follow the user's choice. The number format always
 * follows the display locale.
 */
class ReportUnits(val metric: Boolean = !Display.imperial) {
    val len get() = if (metric) "m" else StationFormat.lengthUnit
    val area get() = if (metric) "m²" else StationFormat.areaUnit
    val vol get() = if (metric) "m³" else StationFormat.volumeUnit
    /** Same as [area] and [vol] in plain ASCII, for CSV headers. */
    val areaAscii get() = if (metric) "m2" else "ft2"
    val volAscii get() = if (metric) "m3" else "yd3"

    fun l(m: Double, d: Int = 3) = StationFormat.decimal(if (metric) m else m / StationFormat.FOOT, d)
    fun a(m2: Double, d: Int = 2) = StationFormat.decimal(if (metric) m2 else m2 / StationFormat.SQ_FOOT, d)
    fun v(m3: Double, d: Int = 1) = StationFormat.decimal(if (metric) m3 else m3 / StationFormat.CUBIC_YARD, d)
    fun lu(m: Double, d: Int = 3) = l(m, d) + " " + len
    fun au(m2: Double, d: Int = 2) = StationFormat.grouped(if (metric) m2 else m2 / StationFormat.SQ_FOOT, d) + " " + area
    fun vu(m3: Double, d: Int = 1) = StationFormat.grouped(if (metric) m3 else m3 / StationFormat.CUBIC_YARD, d) + " " + vol
    fun station(m: Double) = if (metric) StationFormat.estacaMetric(m) else StationFormat.estaca(m)
    fun offset(m: Double): String {
        val right = if (Display.portuguese) "D" else "R"
        val left = if (Display.portuguese) "E" else "L"
        return lu(kotlin.math.abs(m), 2) + " " + if (m >= 0) right else left
    }
}
