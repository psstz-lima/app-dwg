package com.appdwg.core.drainage

/** Result of checking a catalog without leaving the device. */
data class CatalogReport(
    val devices: Int,
    val placeable: Int,
    val sectioned: Int,
    val problems: List<String>,
)

/**
 * Runs the drainage calculations on every placeable device in a catalog that is already on the device.
 * A problem is a non-finite area, a Manning result that is not a number, or a quantity that will not scale.
 */
object CatalogAudit {
    fun check(catalog: DeviceCatalog): CatalogReport {
        val problems = ArrayList<String>()
        var placeable = 0
        var sectioned = 0
        for (device in catalog.devices) {
            if (!device.placeable) continue
            placeable++
            val section = device.section
            if (section != null && section.points.size >= 3) {
                sectioned++
                val area = section.areaM2
                if (area == null || !area.isFinite() || area < 0.0) problems += device.code
                val water = DrainageNetwork.waterway(section)
                if (water != null) {
                    val discharge = DrainageNetwork.manning(water.area, water.radius, 0.01, DrainageNetwork.roughnessOf(device))
                    if (!discharge.isFinite() || discharge < 0.0) problems += device.code
                }
            }
            val scaled = device.quantitiesFor(1.0, isPro = true)
            if (scaled == null || scaled.items.any { !it.value.isFinite() }) problems += device.code
            Subsurface.of(device, 1.0)
        }
        Culvert.size(0.5, 15.0, 0.01, 0.0, 2.0)
        return CatalogReport(catalog.devices.size, placeable, sectioned, problems.distinct())
    }
}
