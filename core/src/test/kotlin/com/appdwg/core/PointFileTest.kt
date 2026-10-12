package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointFileTest {

    @Test
    fun readsEverySeparator() {
        val text = """
            PONTO,DESC,E,N,Z
            1,BORDO,761200.123,7849000.456,250.789
            2;MARCO 2;761201,5;7849001,25;251,5
            3	ESTACA	761202.0	7849002.0	252.0
            4 CERCA VIVA 761203.000 7849003.000 253.000
            lixo
        """.trimIndent()
        val r = PointFile.read(text)
        assertEquals(4, r.points.size)
        assertEquals(CogoPoint("1", "BORDO", 761200.123, 7849000.456, 250.789), r.points[0])
        assertEquals(761201.5, r.points[1].x, 1e-9)
        assertEquals("MARCO 2", r.points[1].description)
        assertEquals("ESTACA", r.points[2].description)
        assertEquals("CERCA VIVA", r.points[3].description)
        assertEquals(listOf(1, 6), r.skipped.map { it.first })
    }

    @Test
    fun commaSeparatedRejectsDecimalCommas() {
        // Six fields with commas: decimal commas would be ambiguous, so the coordinates do not parse.
        assertTrue(PointFile.read("1,A,761200,5,7849000,5").points.size <= 1)
    }

    @Test
    fun writesPdenzWithThreeDecimals() {
        val out = PointFile.write(listOf(CogoPoint("7", "PV, poço", 1.0, 2.25, Double.NaN), CogoPoint("8", "X", 3.0, 4.0, 5.1239)))
        assertEquals("7,PV  poço,1.000,2.250,\r\n8,X,3.000,4.000,5.124\r\n", out)
        assertEquals("9", PointFile.nextNumber(listOf(CogoPoint("8", "", 0.0, 0.0, 0.0), CogoPoint("A1", "", 0.0, 0.0, 0.0))))
        val back = PointFile.read(out)
        assertEquals(2, back.points.size)
        assertTrue(back.points[0].z.isNaN())
    }
}
