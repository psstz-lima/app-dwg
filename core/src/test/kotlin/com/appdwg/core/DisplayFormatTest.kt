package com.appdwg.core

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFormatTest {
    @AfterTest
    fun reset() { Display.locale = Locale.forLanguageTag("pt-BR"); Display.units = UnitSystem.METRIC }

    @Test
    fun separatorsFollowTheLocale() {
        assertEquals("1234,57", StationFormat.decimal(1234.567))
        assertEquals("12", StationFormat.estacaWhole(240.0))
        assertEquals("1.234,57", StationFormat.grouped(1234.567))
        Display.locale = Locale.US
        assertEquals("1234.57", StationFormat.decimal(1234.567))
        assertEquals("1,234.57", StationFormat.grouped(1234.567))
        assertEquals("12 + 5.40", StationFormat.estaca(245.4))
        assertEquals("3.25 m R", StationFormat.offset(3.25))
    }

    @Test
    fun typedNumbersInEitherConvention() {
        assertEquals(1234.56, StationFormat.number("1.234,56"))
        assertEquals(1234.56, StationFormat.number("1,234.56"))
        assertEquals(12.5, StationFormat.number("12,5"))
        assertEquals(12.5, StationFormat.number("12.5"))
        Display.locale = Locale.US
        assertEquals(1234.0, StationFormat.number("1,234"))
        assertEquals(12.5, StationFormat.number("12,5"))
    }

    @Test
    fun imperialConvertsOnlyForShow() {
        Display.units = UnitSystem.IMPERIAL
        Display.locale = Locale.US
        assertEquals("10.00 ft", StationFormat.length(3.048))
        assertEquals(3.048, StationFormat.parseLength("10")!!, 1e-12)
        assertEquals("1,076.39 ft²", StationFormat.area(100.0))
        assertEquals("1.000 ac", StationFormat.landArea(StationFormat.ACRE, 3))
        assertEquals("1.3 yd³", StationFormat.volume(1.0))
        // 100 ft stations: 245.4 m = 805.12 ft.
        assertEquals("8+05.12", StationFormat.estaca(245.4))
        assertEquals(245.4, StationFormat.parse("8+05.12")!!, 0.001)
        assertEquals("10.66 ft L", StationFormat.offset(-3.25))
        assertEquals("8+00", StationFormat.estacaWhole(800 * StationFormat.FOOT))
        Display.locale = java.util.Locale.forLanguageTag("pt-BR")
        assertEquals("8+00", StationFormat.estacaWhole(800 * StationFormat.FOOT))
        assertEquals("10,00 ft", StationFormat.length(3.048))
    }
}
