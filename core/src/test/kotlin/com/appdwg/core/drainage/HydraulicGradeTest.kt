package com.appdwg.core.drainage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydraulicGradeTest {
    @Test
    fun invertNeverRisesAndHoldsTheMinimumSlope() {
        val distances = doubleArrayOf(0.0, 10.0, 20.0)
        val terrain = doubleArrayOf(10.0, 12.0, 11.0)
        val grade = HydraulicGrade.design(
            distances, terrain, deviceHeight = 0.2, deviceWidth = 1.0, deviceArea = 0.2,
            buried = false, minSlope = 0.005, bedding = 0.0,
        )
        val order = if (grade.downstreamAtStart) (2 downTo 0).toList() else (0..2).toList()
        for (k in 0 until order.size - 1) {
            val ds = abs(distances[order[k + 1]] - distances[order[k]])
            val drop = grade.samples[order[k]].invert - grade.samples[order[k + 1]].invert
            assertTrue(drop >= -1e-9, "rose ${grade.samples.map { it.invert }}")
            assertTrue(drop / ds + 1e-9 >= 0.005, "slope ${drop / ds}")
        }
        val steep = (grade.samples[1].invert - grade.samples[0].invert) / 10.0
        assertTrue(steep > 0.02, "grade break $steep")
    }

    @Test
    fun flatGroundUsesTheMinimumSlope() {
        val grade = HydraulicGrade.design(
            doubleArrayOf(0.0, 10.0), doubleArrayOf(100.0, 100.0),
            deviceHeight = 0.3, deviceWidth = 1.0, deviceArea = 0.3,
            buried = false, minSlope = 0.005, bedding = 0.0,
        )
        assertEquals(false, grade.downstreamAtStart)
        assertEquals(99.7, grade.samples[0].invert, 1e-9)
        assertEquals(99.65, grade.samples[1].invert, 1e-9)
        assertEquals(0.005, grade.samples[0].slopeDown, 1e-9)
        assertTrue(grade.samples[1].invert <= grade.samples[0].invert)
    }

    @Test
    fun trenchBackfillAndSpoilUseAverageEndArea() {
        val grade = HydraulicGrade.design(
            doubleArrayOf(0.0, 10.0), doubleArrayOf(100.0, 100.0),
            deviceHeight = 0.3, deviceWidth = 1.0, deviceArea = 0.3,
            buried = false, minSlope = 0.005, bedding = 0.0, clearance = 0.30,
        )
        assertEquals(5.2, grade.excavation, 1e-6)
        assertEquals(2.2, grade.backfill, 1e-6)
        assertEquals(0.0, grade.bedding, 1e-9)
        assertEquals(3.0, grade.spoil, 1e-6)
        assertEquals(false, grade.shoring)
        assertEquals(false, grade.tooDeep)
    }

    @Test
    fun aDeepCutWarnsAndNotesShoring() {
        val grade = HydraulicGrade.design(
            doubleArrayOf(0.0, 40.0), doubleArrayOf(50.0, 50.0),
            deviceHeight = 0.4, deviceWidth = 1.0, deviceArea = 0.4,
            buried = true, minSlope = 0.02, minCover = 0.6, maxDepth = 1.0, bedding = 0.10,
        )
        assertTrue(grade.tooDeep, "cut ${grade.maxCut}")
        assertTrue(grade.shoring)
        assertTrue(grade.excavation > grade.backfill)
        assertTrue(grade.bedding > 0.0)
        assertTrue(grade.samples.last().invert < grade.samples.first().invert)
    }
}
