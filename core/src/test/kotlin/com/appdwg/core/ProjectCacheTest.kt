package com.appdwg.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectCacheTest {
    private fun roundTrip(p: LandXmlProject): LandXmlProject {
        val out = ByteArrayOutputStream(); ProjectCache.write(p, out)
        return assertNotNull(ProjectCache.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun keepsWhatWasRead() {
        val s = Surface("TN", doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0), doubleArrayOf(1.0, 2.0, 3.0, 4.0), intArrayOf(0, 1, 2, 0, 2, 3))
        val a = Alignment("EIXO", 100.0, listOf(AlignmentElement.Line(Point2(0.0, 0.0), Point2(5.0, 0.0)),
            AlignmentElement.Arc(Point2(5.0, 0.0), Point2(10.0, 5.0), Point2(5.0, 5.0), false)), Profile("G", listOf(Profile.Pvi(100.0, 10.0), Profile.Pvi(120.0, 12.0, 4.0))))
        val p = LandXmlProject("meter", CoordinateSystemInfo(null, "31983", null), listOf(s), listOf(a), listOf(CgPoint("1", "TN", Point3(1.0, 2.0, 3.0))),
            listOf("aviso"), listOf(Polyline("L", doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), false, 0xFF00FF00.toInt(), false, doubleArrayOf(5.0, 6.0))),
            listOf(PlanText("T", null, true, 1.0, 2.0, 0.5, 0.1, "olá\nmundo", 1, 2)), emptyList(), emptyList(), emptyList(), setOf("L"), emptySet(), mapOf("L" to null, "T" to 7))
        val q = roundTrip(p)
        assertEquals(p.coordinateSystem, q.coordinateSystem)
        assertEquals(s.zs.toList(), q.surfaces[0].zs.toList())
        assertEquals(s.elevationAt(Point2(6.0, 3.0)), q.surfaces[0].elevationAt(Point2(6.0, 3.0)))
        assertEquals(a.elements, q.alignments[0].elements)
        assertEquals(a.profile!!.pvis, q.alignments[0].profile!!.pvis)
        assertEquals("olá\nmundo", q.texts[0].text)
        assertEquals(listOf(5.0, 6.0), q.polylines[0].zs!!.toList())
        assertEquals(p.layerColors, q.layerColors)
        assertEquals(p.cgPoints, q.cgPoints)
    }

    @Test
    fun ignoresOtherData() = assertNull(ProjectCache.read(ByteArrayInputStream(ByteArray(16))))
}
