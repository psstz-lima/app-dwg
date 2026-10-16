package com.appdwg.core.drainage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every album device is a closed solid swept from its own section.
 * Where the catalog publishes a ring, that waterway (or the trench) is one of the drawn rings.
 */
class VolumetricCatalogTest {
    private val catalog = CatalogLoader.bundled()

    @Test
    fun everyDeviceMeshIsVolumetric() {
        assertTrue(catalog.devices.size >= 390, "devices ${catalog.devices.size}")
        val failures = ArrayList<String>()
        for (device in catalog.devices) {
            val mesh = try {
                DeviceBody.reference(device)
            } catch (error: Throwable) {
                failures += "${device.code} threw ${error.javaClass.simpleName}: ${error.message}"
                continue
            }
            if (mesh == null) {
                failures += "${device.code} has no mesh"
                continue
            }
            if (mesh.simplified) failures += "${device.code} is a placeholder"
            if (mesh.volume <= 1e-4) failures += "${device.code} volume ${mesh.volume}"
            val box = bounds(mesh.triangles)
            if (box.dx <= 0.02 || box.dy <= 0.02 || box.dz <= 0.02) {
                failures += "${device.code} span ${box.dx} x ${box.dy} x ${box.dz}"
            }
            val area = device.section?.areaM2
            if (area != null && area > 1e-4) {
                val rings = DeviceBody.previewRings(device)
                val best = rings.map { ringArea(it) }.minOfOrNull { abs(it - area) / area }
                if (best == null || best > 0.25) {
                    failures += "${device.code} section ${best ?: "none"} vs $area"
                }
                if (device.category != DeviceCategory.POINT && mesh.length > 0.5) {
                    val perMetre = mesh.volume / mesh.length
                    if (perMetre <= area * 0.01 || perMetre >= area * 6.0) {
                        failures += "${device.code} ${perMetre} m3/m vs section $area"
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.take(16).joinToString("\n") + " (${failures.size})")
    }

    @Test
    fun boxCulvertUsesTheAlbumWallAndHaunch() {
        val device = catalog.device("BSCC 150x150 PM")
        assertNotNull(device)
        assertEquals(15.0, device.parameters.first { it.key == "espessura_cm" }.number)
        assertEquals(20.0, device.parameters.first { it.key == "misula_cm" }.number)
        val mesh = DeviceBody.reference(device)
        assertNotNull(mesh)
        assertEquals(1.80, mesh.widthM, 0.05)
        assertEquals(1.50, mesh.innerWidthM, 0.05)
        // Outer 1.80×1.80 minus the chamfered cell is the tabulated 1.07 m³/m.
        assertEquals(1.07, mesh.volume / mesh.length, 0.08)
        val double = catalog.device("BDCC 150x150 PM")
        assertNotNull(double)
        val both = DeviceBody.reference(double)
        assertNotNull(both)
        assertTrue(both.widthM > mesh.widthM + 1.0, "double width ${both.widthM}")
        assertTrue(both.channels.size >= 2, "bores ${both.channels.size}")
    }

    private data class Box(val dx: Double, val dy: Double, val dz: Double)

    private fun bounds(triangles: DoubleArray): Box {
        var x0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        var z0 = Double.POSITIVE_INFINITY
        var z1 = Double.NEGATIVE_INFINITY
        var i = 0
        while (i + 2 < triangles.size) {
            x0 = min(x0, triangles[i])
            x1 = max(x1, triangles[i])
            y0 = min(y0, triangles[i + 1])
            y1 = max(y1, triangles[i + 1])
            z0 = min(z0, triangles[i + 2])
            z1 = max(z1, triangles[i + 2])
            i += 3
        }
        return Box(x1 - x0, y1 - y0, z1 - z0)
    }

    private fun ringArea(points: List<SectionPoint>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }
}
