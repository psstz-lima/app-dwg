package com.appdwg.core.drainage

import com.appdwg.core.Idf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowTest {
    private val idf = Idf(k = 100.0, a = 0.0, b = 0.0, c = 0.0)

    @Test
    fun oneSquareKilometreAtHalfRunoffIsAboutFourteenCubicMetres() {
        val flow = DrainageFlow.peak(1_000_000.0, 0.5, idf, 10.0, 15.0)
        assertEquals(100.0, flow.intensity, 1e-9)
        assertEquals(13.8889, flow.local, 1e-3)
        assertEquals(13.8889, flow.accumulated, 1e-3)
    }

    @Test
    fun durationNeverDropsBelowTenMinutes() {
        val flow = DrainageFlow.peak(1_000.0, 1.0, Idf(1000.0, 0.2, 10.0, 0.7), 10.0, 1.0)
        assertEquals(10.0, flow.minutes, 1e-9)
    }

    @Test
    fun aBasinOverTwoSquareKilometresIsFlagged() {
        val flow = DrainageFlow.peak(3_000_000.0, 0.4, idf, 25.0, 20.0)
        assertTrue(flow.tooLarge)
    }

    @Test
    fun flowAddsUpDownstreamAndALoopIsCountedOnce() {
        val links = listOf(
            NetworkLink("a", "c", 0.02, 0.0, false),
            NetworkLink("b", "c", 0.02, 0.0, false),
            NetworkLink("c", "d", 0.02, 0.0, false),
        )
        val sum = DrainageFlow.accumulate(links, mapOf("a" to 1.0, "b" to 2.0, "c" to 0.5, "d" to 0.0))
        assertEquals(1.0, sum.getValue("a"), 1e-9)
        assertEquals(2.0, sum.getValue("b"), 1e-9)
        assertEquals(3.5, sum.getValue("c"), 1e-9)
        assertEquals(3.5, sum.getValue("d"), 1e-9)

        val loop = listOf(
            NetworkLink("a", "b", 0.0, 0.0, false),
            NetworkLink("b", "a", 0.0, 0.0, false),
        )
        val stuck = DrainageFlow.accumulate(loop, mapOf("a" to 1.0, "b" to 2.0))
        assertEquals(1.0, stuck.getValue("a"), 1e-9)
        assertEquals(2.0, stuck.getValue("b"), 1e-9)
    }

    @Test
    fun accumulatedFlowOverTheManningCapacityIsReported() {
        val graph = DrainageGraph(
            emptyList(), emptyList(), emptyList(),
            mapOf("a" to 1.0, "b" to 5.0),
        )
        val flows = mapOf(
            "a" to ReachFlow(10.0, 100.0, 2.0, 2.0, false),
            "b" to ReachFlow(10.0, 100.0, 1.0, 1.0, false),
        )
        val next = DrainageFlow.applyCapacity(graph, flows)
        val issue = next.issues.single()
        assertEquals("a", issue.deviceId)
        assertEquals(NetworkCheck.CAPACITY, issue.check)
        assertEquals(2.0, issue.flow!!, 1e-9)
        assertEquals(1.0, issue.capacity!!, 1e-9)
    }
}
