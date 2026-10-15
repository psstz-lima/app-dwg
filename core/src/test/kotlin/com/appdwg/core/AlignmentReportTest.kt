package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AlignmentReportTest {
    @Test
    fun keyPointsAndElementsOfACurveWithSpirals() {
        val a = AlignmentBuilder.fromPis("E", listOf(Pi(Point2(0.0, 0.0)), Pi(Point2(500.0, 0.0), 300.0, 50.0), Pi(Point2(800.0, 400.0))))
        val labels = AlignmentReport.keyPoints(a).map { it.label }
        assertEquals(listOf("PP", "TS", "SC", "CS", "ST", "PF"), labels)
        val h = AlignmentReport.horizontal(a)
        assertEquals(listOf("Tangente", "Espiral", "Curva circular", "Espiral", "Tangente"), h.map { it.kind })
        assertEquals(90.0, h[0].azimuth, 1e-6)
        assertEquals(300.0, h[2].radius!!, 1e-6)
        val plain = AlignmentBuilder.fromPis("C", listOf(Pi(Point2(0.0, 0.0)), Pi(Point2(500.0, 0.0), 300.0), Pi(Point2(800.0, 400.0))))
        assertEquals(listOf("PP", "PC", "PT", "PF"), AlignmentReport.keyPoints(plain).map { it.label })
    }

    @Test
    fun verticalRowsGiveGradesAndK() {
        val p = Profile("G", listOf(Profile.Pvi(0.0, 100.0), Profile.Pvi(100.0, 102.0, 40.0), Profile.Pvi(200.0, 101.0)))
        val v = AlignmentReport.vertical(p)
        assertEquals(2.0, v[1].gradeIn!!, 1e-9); assertEquals(-1.0, v[1].gradeOut!!, 1e-9)
        assertEquals(40.0 / 3.0, v[1].k!!, 1e-9)
        assertEquals(80.0, v[1].pcv!!, 1e-9); assertEquals(120.0, v[1].ptv!!, 1e-9)
    }
}
