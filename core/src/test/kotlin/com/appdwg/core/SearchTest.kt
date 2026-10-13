package com.appdwg.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private val project = LandXmlProject(
        null, null, emptyList(), emptyList(), listOf(CgPoint("PV1", "MARCO", Point3(1.0, 2.0, 3.0))), emptyList(),
        texts = listOf(PlanText("T", null, true, 5.0, 6.0, 1.0, 0.0, "Ponte sobre o córrego", 0, 0)),
        points = listOf(CogoPoint("10", "BORDO", 7.0, 8.0, 9.0), CogoPoint("101", "CERCA", 1.0, 1.0, 1.0)),
    )

    @Test
    fun exactNumberComesFirstAndAccentsAreIgnored() {
        val r = Search.find(project, "10")
        assertEquals("10 · BORDO", r.first().label)
        assertEquals(2, r.size)
        assertEquals("Texto", Search.find(project, "CORREGO").single().kind)
        assertEquals(Point2(1.0, 2.0), Search.find(project, "marco").single().position)
    }
}
