package com.appdwg.core.drainage

import java.text.Normalizer

/**
 * Schema of a drainage library. Version 1 is the IPR-736 album and any user library
 * (DER, a city, a company) with the same fields. A higher [schema] is refused rather than guessed.
 */
const val DRAINAGE_SCHEMA: Int = 1

class CatalogFormatException(val kind: Kind, val detail: String = "") : IllegalArgumentException(
    if (detail.isEmpty()) kind.name else "${kind.name}: $detail",
) {
    enum class Kind { SYNTAX, SCHEMA, SHAPE }
}

enum class DeviceCategory { LINEAR, POINT, OTHER }

/** What a caller may do with a device. Review blocks first; Pro is the second gate. */
enum class DeviceAccess { OPEN, NEEDS_REVIEW, NEEDS_PRO }

data class Parameter(val key: String, val number: Double?, val text: String?)

data class Material(val name: String)

/** One consumption from the album, per metre or per unit, optionally split by construction method. */
data class Quantity(
    val name: String,
    val value: Double,
    val unit: String,
    val method: String?,
    val perMetre: Boolean,
)

data class Quantities(val items: List<Quantity>) {
    fun excavationPerMetre(): Double? = items.firstOrNull { it.name == "escavacao" && it.perMetre }?.value

    /** Scales every consumption. Per-metre units lose the `/m`; per-unit units lose the `/un`. */
    fun times(factor: Double) = Quantities(items.map { it.scaled(factor) })
}

private fun Quantity.scaled(factor: Double) = copy(
    value = value * factor,
    unit = when {
        perMetre && unit.endsWith("/m") -> unit.removeSuffix("/m")
        !perMetre && unit.endsWith("/un") -> unit.removeSuffix("/un")
        else -> unit
    },
)

/** A named socket: upstream, downstream, barrel, wing, or whatever the library calls it. */
data class ConnectionPoint(val role: String, val accepts: List<String>)

data class SourcePage(val source: String, val pdfPage: Int, val drawing: String)

data class ReviewNote(val field: String, val issue: String, val page: SourcePage?)

data class DeviceReview(val by: String, val date: String)

data class CatalogSource(val file: String, val title: String)

/**
 * One device. The engine does not know the code: family, section, materials, quantities and
 * connections all come from the library JSON.
 */
data class DeviceDef(
    val code: String,
    val name: String,
    val family: String,
    val chapter: Int,
    val category: DeviceCategory,
    val subtype: String,
    val position: String?,
    val parameters: List<Parameter>,
    val section: CrossSection?,
    val materials: List<Material>,
    val quantities: Quantities,
    val connections: List<ConnectionPoint>,
    val adaptableTo: List<String>,
    val page: SourcePage,
    val reviewNotes: List<ReviewNote>,
    val note: String?,
    val variantsNote: String?,
    val origin: String,
    val review: DeviceReview? = null,
) {
    /** Visible in the library, but placement and calculations stay closed until someone signs the review. */
    val needsReview: Boolean get() = reviewNotes.isNotEmpty() && review == null

    val placeable: Boolean get() = !needsReview

    val calculable: Boolean get() = !needsReview

    fun access(isPro: Boolean): DeviceAccess = when {
        needsReview -> DeviceAccess.NEEDS_REVIEW
        !isPro -> DeviceAccess.NEEDS_PRO
        else -> DeviceAccess.OPEN
    }

    /** Returns a copy signed by [by] on [date], or null when either is blank. */
    fun reviewed(by: String, date: String): DeviceDef? {
        val name = by.trim()
        val day = date.trim()
        if (name.isEmpty() || day.isEmpty()) return null
        return copy(review = DeviceReview(name, day))
    }

    /** Catalog consumptions times a length (m) or a count. Null while review or Pro blocks the device. */
    fun quantitiesFor(factor: Double, isPro: Boolean): Quantities? {
        if (access(isPro) != DeviceAccess.OPEN) return null
        if (!factor.isFinite() || factor < 0.0) return null
        return quantities.times(factor)
    }
}

data class DeviceCatalog(
    val schema: Int,
    val origin: String,
    val title: String,
    val generated: String,
    val unitsNote: String,
    val chapters: Map<Int, String>,
    val sources: Map<String, CatalogSource>,
    val devices: List<DeviceDef>,
) {
    fun device(code: String): DeviceDef? = devices.find { it.code == code }

    /**
     * User devices are appended. The same code replaces the bundled one, and the replacement
     * keeps the user library's origin.
     */
    fun merge(user: DeviceCatalog): DeviceCatalog {
        val map = LinkedHashMap<String, DeviceDef>()
        for (device in devices) map[device.code] = device
        for (device in user.devices) map[device.code] = device
        return copy(
            devices = map.values.toList(),
            chapters = chapters + user.chapters,
            sources = sources + user.sources,
        )
    }

    fun withReview(code: String, by: String, date: String): DeviceCatalog {
        val next = device(code)?.reviewed(by, date) ?: return this
        return copy(devices = devices.map { if (it.code == code) next else it })
    }

    /** Code or name, ignoring case and accents. Chapter, category and family narrow the list. */
    fun query(text: String, chapter: Int?, category: DeviceCategory?, family: String?): List<DeviceDef> {
        val q = fold(text.trim())
        return devices.filter { device ->
            (chapter == null || device.chapter == chapter) &&
                (category == null || device.category == category) &&
                (family == null || device.family == family) &&
                (q.isEmpty() || fold(device.code).contains(q) || fold(device.name).contains(q))
        }
    }
}

private val combiningMarks = Regex("\\p{M}+")

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()
