package com.appdwg.core.drainage

import com.appdwg.core.Display
import com.appdwg.core.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tablet findings from 0.1.9 on the Xiaomi: ground, budget, earthwork, grade, boxes, IFC. */
class TabletFixesTest {
    private val catalog = CatalogLoader.bundled()

    @Test
    fun missingGroundIsInterpolatedAndNeverZero() {
        val (filled, missed) = GroundCover.fill(arrayOf(100.0, null, 90.0))
        assertTrue(missed)
        assertEquals(95.0, filled[1], 1e-9)
        val (none, allMissed) = GroundCover.fill(arrayOf(null, null))
        assertTrue(allMissed)
        assertTrue(none.all { it.isNaN() })
        val unknown = HydraulicGrade.design(
            doubleArrayOf(0.0, 10.0), doubleArrayOf(Double.NaN, 100.0),
            deviceHeight = 0.3, deviceWidth = 1.0, deviceArea = 0.2,
            buried = true,
        )
        assertTrue(unknown.offSurface)
        assertEquals(0.0, unknown.excavation, 1e-9)
        assertTrue(unknown.samples.isEmpty())
    }

    @Test
    fun aGapInTheTinDoesNotDropTheInvert() {
        val built = scene("VPCG 160-30", 20.0) { x, _ -> if (x < 1.0 || x > 19.0) 100.0 else null }
        val grade = built.profiles.single().hydraulic
        assertTrue(grade.offSurface)
        assertTrue(grade.samples.all { it.invert > 90.0 }, "invert ${grade.samples.map { it.invert }}")
        assertTrue(grade.actualSlope >= 0.0)
        assertEquals(0.0, grade.excavation, 1e-9)
        val gone = scene("VPCG 160-30", 20.0) { _, _ -> null }
        assertTrue(gone.glyphs.isNotEmpty())
        assertTrue(gone.profiles.isEmpty())
        assertEquals(0.0, gone.takeoff.single().trench, 1e-9)
        assertTrue(gone.takeoff.single().invertUp.isNaN())
    }

    @Test
    fun eachDeviceKeepsItsOwnTriangles() {
        val placed = listOf(line("a", "VPCG 160-30", 0.0, 80.0), line("b", "STC 100-20", 8.0, 80.0))
        val built = DrainageScene.build(placed, { catalog.device(it) }, { _, _ -> 100.0 }, { null }, triangleBudget = 40)
        assertEquals(2, built.glyphs.size)
        assertEquals(2, built.takeoff.size)
        for (row in built.takeoff) {
            assertTrue(row.drawnTriangles in 1..40, "${row.code} tris ${row.drawnTriangles}")
            assertTrue(row.length > 10.0, row.code)
        }
        assertTrue(built.colored.isNotEmpty())
    }

    @Test
    fun surfaceDevicesKeepCatalogEarthAndTheRealGrade() {
        val built = scene("STC 100-20", 20.0) { x, _ -> 100.0 - x * 0.5 }
        val row = built.takeoff.single()
        assertEquals(0.0, row.trench, 1e-9)
        assertEquals(0.0, row.backfill, 1e-9)
        assertEquals(false, row.shoring)
        assertEquals(false, row.tooDeep)
        assertTrue(row.gradeSlope > 0.2, "slope ${row.gradeSlope}")
        val perM = catalog.device("STC 100-20")?.quantities?.excavationPerMetre()
        assertNotNull(perM)
        assertTrue(abs(row.excavation - perM * row.length) < 1e-6, "catalog ${perM * row.length} row ${row.excavation}")
        assertTrue(built.trench.isEmpty())
        assertTrue(built.colored.any { it.first == DeviceLook.rgb(DeviceFinish.CONCRETE) })
    }

    @Test
    fun aCatchBasinReportsThePitAndARealGrate() {
        val device = catalog.device("CCT 250-100 A")
        assertNotNull(device)
        val mesh = DeviceBody.point(device, emptyMap(), 0.0, 0.0, 0.0, 0.0, GradeSpec(GradeMode.HYDRAULIC, buried = true), 100.0, null)
        assertNotNull(mesh)
        assertTrue(mesh.triangles.size / 9 > 60, "tris ${mesh.triangles.size / 9}")
        val dx = mesh.footprint.maxOf { it.x } - mesh.footprint.minOf { it.x }
        val dy = mesh.footprint.maxOf { it.y } - mesh.footprint.minOf { it.y }
        assertTrue(dx > 1.0 && dy > 1.0, "plan $dx x $dy")
        val placed = PlacedDevice.Point("p", "CCT 250-100 A", 0.0, 0.0, 0.0, 0.0, GradeSpec(GradeMode.HYDRAULIC, buried = true), null, null)
        val built = DrainageScene.build(listOf(placed), { catalog.device(it) }, { _, _ -> 100.0 }, { null })
        val row = built.takeoff.single()
        assertEquals(0.0, row.length, 1e-9)
        assertTrue(row.excavation > 1.0, "pit ${row.excavation}")
        assertTrue(row.drawnTriangles > 60)
    }

    @Test
    fun totalsKeepAFiniteCut() {
        val deep = takeoff(cut = 1.4, tris = 10)
        val blank = takeoff(cut = Double.NaN, tris = 4)
        val sum = blank + deep
        assertEquals(1.4, sum.cutDepth, 1e-9)
        assertEquals(14, sum.drawnTriangles)
        assertTrue((blank + blank).cutDepth.isNaN())
    }

    @Test
    fun portugueseAndBrazilStayMetric() {
        assertEquals(UnitSystem.METRIC, Display.defaultUnits(Locale.forLanguageTag("pt-BR")))
        assertEquals(UnitSystem.METRIC, Display.defaultUnits(Locale.forLanguageTag("pt-US")))
        assertEquals(UnitSystem.METRIC, Display.defaultUnits(Locale.forLanguageTag("en-BR")))
        assertEquals(UnitSystem.IMPERIAL, Display.defaultUnits(Locale.US))
        assertEquals(UnitSystem.METRIC, Display.defaultUnits(Locale.UK))
    }

    @Test
    fun everyCatalogDeviceHasAFinishAndTheRightMouth() {
        assertTrue(catalog.devices.size >= 390, "devices ${catalog.devices.size}")
        for (family in listOf("VPCG", "VPAG", "SZG", "STG", "STCG")) {
            val found = catalog.devices.filter { it.code.substringBefore(' ') == family }
            assertTrue(found.isNotEmpty(), family)
            for (device in found) {
                assertEquals(DeviceFinish.GRASS, DeviceLook.finish(device), device.code)
                assertTrue(DeviceLook.openMouth(device), "${device.code} ${device.outlineType}")
            }
        }
        for (family in listOf("STC", "STCC", "SZC", "SZCC", "VPAC", "VPCC")) {
            val found = catalog.devices.filter { it.code.substringBefore(' ') == family }
            assertTrue(found.isNotEmpty(), family)
            for (device in found) {
                assertEquals(DeviceFinish.CONCRETE, DeviceLook.finish(device), device.code)
                assertTrue(DeviceLook.openMouth(device), "${device.code} ${device.outlineType}")
            }
        }
        for (device in catalog.devices) {
            DeviceLook.finish(device)
            val type = device.outlineType
            if (type == "circular_barrels" || type == "box" || type == "flared_apron" || type.startsWith("slab")) {
                assertTrue(!DeviceLook.openMouth(device), "${device.code} $type")
            }
        }
        val grass = DeviceBody.linear(
            catalog.device("VPCG 160-30")!!, emptyMap(),
            Sweep.polyline(doubleArrayOf(0.0, 20.0), doubleArrayOf(0.0, 0.0), null, null),
            0.0, GradeSpec(GradeMode.SURFACE_TOP), { _, _ -> 100.0 }, { null },
        )
        val concrete = DeviceBody.linear(
            catalog.device("VPCC 160-30")!!, emptyMap(),
            Sweep.polyline(doubleArrayOf(0.0, 20.0), doubleArrayOf(0.0, 0.0), null, null),
            0.0, GradeSpec(GradeMode.SURFACE_TOP), { _, _ -> 100.0 }, { null },
        )
        assertNotNull(grass)
        assertNotNull(concrete)
        assertTrue(grass.widthM - grass.innerWidthM < 0.12, "grass skin ${grass.widthM - grass.innerWidthM}")
        assertTrue(concrete.widthM - concrete.innerWidthM > grass.widthM - grass.innerWidthM)
        assertEquals(0x3E8F45, DeviceLook.rgb(DeviceFinish.GRASS))
        assertEquals(0xC2C2BA, DeviceLook.rgb(DeviceFinish.CONCRETE))
    }

    @Test
    fun ifcUsesCompressedIdsGeometryAndKinds() {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$"
        val site = IfcDrainage.guid("site-rel:obra")
        assertEquals(22, site.length)
        assertTrue(site.all { it in alphabet })
        assertEquals(site, IfcDrainage.guid("site-rel:obra"))
        val text = StringBuilder()
        IfcDrainage.write(
            "obra",
            listOf(
                ifc("g", "VPCG 160-30", "Valeta de proteção de corte em grama", "valeta"),
                ifc("c", "CCT 250-100 A", "Caixa coletora de talvegue", "caixa"),
                ifc("b", "BSTC 100", "Bueiro tubular de concreto", "bueiro"),
            ),
            text,
        )
        val step = text.toString()
        assertTrue("IFCEXTRUDEDAREASOLID" in step)
        assertTrue(".GUTTER." in step)
        assertTrue(".SUMP." in step)
        assertTrue(".CULVERT." in step)
        assertTrue("site-rel" !in step)
        assertTrue("contains" !in step || "IFCRELCONTAINEDINSPATIALSTRUCTURE" in step)
        val defined = Regex("#(\\d+)=").findAll(step).map { it.groupValues[1] }.toSet()
        val referred = Regex("#(\\d+)(?!=)").findAll(step).map { it.groupValues[1] }.toSet()
        assertTrue(referred.all { it in defined }, "dangling ${referred - defined}")
        assertTrue(Regex("'([0-9A-Za-z_$]{22})'").findAll(step).count() >= 6)
    }

    private fun ifc(id: String, code: String, name: String, classification: String) = IfcDevice(
        id, code, name, classification, 12.0, 100.0, 99.5, 0.04, 1, 10.0, 20.0, 100.0, "",
    )

    private fun scene(code: String, length: Double, terrain: (Double, Double) -> Double?) =
        DrainageScene.build(listOf(line("d", code, 0.0, length)), { catalog.device(it) }, terrain, { null })

    private fun line(id: String, code: String, y: Double, length: Double) = PlacedDevice.Linear(
        id, code, PathSource.POLYLINE, null, listOf(0.0, length), listOf(y, y),
        DeviceSide.AXIS, 0.0, null, null, GradeSpec(GradeMode.HYDRAULIC, buried = false), null,
    )

    private fun takeoff(cut: Double, tris: Int) = DeviceTakeoff(
        "a", "X", 1.0, 0.0, 0.0, 0.0, false, cutDepth = cut, drawnTriangles = tris,
    )
}
