package com.appdwg.core.drainage

/**
 * Reads a drainage library. The bundled file is DNIT IPR-736 (2018 album plus Emendas 2, 3 and 4).
 * A user library is the same schema and is merged on top; adding a device does not need a code change.
 */
object CatalogLoader {
    private val bundledCatalog: DeviceCatalog by lazy {
        val stream = CatalogLoader::class.java.getResourceAsStream("ipr736.json")
            ?: throw CatalogFormatException(CatalogFormatException.Kind.SHAPE, "ipr736.json")
        stream.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    }

    fun bundled(): DeviceCatalog = bundledCatalog

    /** Parses [json] and merges it onto [base]. The bundled album is the base when omitted. */
    fun importUser(json: String, base: DeviceCatalog = bundled()): DeviceCatalog = base.merge(parse(json))

    fun parse(json: String): DeviceCatalog {
        val root = parseJv(json).obj() ?: throw CatalogFormatException(CatalogFormatException.Kind.SHAPE, "root")
        val schema = schemaOf(root)
        val origin = root.str("origin")?.takeIf { it.isNotBlank() } ?: "biblioteca"
        val devices = root.fields["devices"] as? Jv.Arr ?: Jv.Arr(emptyList())
        return DeviceCatalog(
            schema = schema,
            origin = origin,
            title = root.str("catalog")?.takeIf { it.isNotBlank() } ?: origin,
            generated = root.str("generated") ?: "",
            unitsNote = root.str("units_note") ?: "",
            chapters = readChapters(root.fields["chapters"]),
            sources = readSources(root.fields["sources"]),
            devices = devices.items.map { readDevice(it, origin) },
        )
    }

    private fun schemaOf(root: Jv.Obj): Int = when (val schema = root.fields["schema"]) {
        null -> DRAINAGE_SCHEMA
        is Jv.Num -> {
            val version = schema.n.toInt()
            if (version != DRAINAGE_SCHEMA) throw CatalogFormatException(CatalogFormatException.Kind.SCHEMA, version.toString())
            version
        }
        else -> throw CatalogFormatException(CatalogFormatException.Kind.SCHEMA, "")
    }

    private fun readDevice(value: Jv, origin: String): DeviceDef {
        val obj = value.obj() ?: throw CatalogFormatException(CatalogFormatException.Kind.SHAPE, "device")
        val code = obj.str("code")?.trim().orEmpty()
        if (code.isEmpty()) throw CatalogFormatException(CatalogFormatException.Kind.SHAPE, "code")
        val params = obj.child("params")
        val geometry = obj.child("geometry")
        val page = readPage(obj.fields["page"]) ?: SourcePage("", 0, "")
        return DeviceDef(
            code = code,
            name = obj.str("name")?.takeIf { it.isNotBlank() } ?: code,
            family = obj.str("family") ?: "",
            chapter = obj.num("chapter")?.toInt() ?: 0,
            category = category(obj.str("category")),
            subtype = obj.str("subtype") ?: "",
            position = obj.str("position"),
            parameters = readParameters(params, geometry),
            section = crossSection(geometry, bindings(params, geometry)),
            materials = readMaterials(obj.fields["materials"]),
            quantities = readQuantities(obj),
            connections = readConnections(obj.fields["connections"]),
            adaptableTo = readStrings(obj.fields["adaptable_to"]),
            page = page,
            reviewNotes = readNotes(obj.fields["needs_review"]),
            note = readText(obj.fields["notes"]),
            variantsNote = obj.str("variants_note"),
            origin = obj.str("origin")?.takeIf { it.isNotBlank() } ?: origin,
            outline = outlineOf(geometry),
            outlineUnits = geometry?.str("units") ?: "cm",
            outlineType = geometry?.str("type") ?: "",
        )
    }

    private fun outlineOf(geometry: Jv.Obj?): List<String> {
        if (geometry == null) return emptyList()
        val keys = LinkedHashSet<String>()
        keys.add("vertices")
        keys.add("inner")
        keys.add("outer")
        keys.addAll(geometry.fields.keys)
        for (key in keys) {
            val array = geometry.fields[key] as? Jv.Arr ?: continue
            val texts = array.items.mapNotNull { (it as? Jv.Str)?.text }
            if (texts.size >= 3 && texts.any { '(' in it && ',' in it }) return texts
        }
        return emptyList()
    }

    private fun category(text: String?): DeviceCategory = when (text?.trim()?.lowercase()) {
        "linear" -> DeviceCategory.LINEAR
        "point" -> DeviceCategory.POINT
        else -> DeviceCategory.OTHER
    }

    private fun readParameters(params: Jv.Obj?, geometry: Jv.Obj?): List<Parameter> {
        val out = ArrayList<Parameter>()
        val seen = HashSet<String>()
        if (params != null) {
            for ((key, value) in params.fields) {
                if (!seen.add(key)) continue
                out += parameter(key, value)
            }
        }
        if (geometry != null) {
            for ((key, value) in geometry.fields) {
                if (value is Jv.Num && seen.add(key)) out += Parameter(key, value.n, null)
            }
        }
        return out
    }

    private fun parameter(key: String, value: Jv): Parameter = when (value) {
        is Jv.Num -> Parameter(key, value.n, null)
        is Jv.Str -> Parameter(key, null, value.text)
        Jv.Yes -> Parameter(key, null, "true")
        Jv.No -> Parameter(key, null, "false")
        else -> Parameter(key, null, null)
    }

    private fun readQuantities(device: Jv.Obj): Quantities {
        val items = ArrayList<Quantity>()
        fun add(obj: Jv.Obj, method: String?, perMetre: Boolean) {
            for ((name, value) in obj.fields) {
                val group = value as? Jv.Obj ?: continue
                val amount = group.num("value")
                if (amount != null) {
                    items += Quantity(name, amount, group.str("unit") ?: "", method, perMetre)
                } else if (method == null) {
                    add(group, name, perMetre)
                }
            }
        }
        device.child("quantities_per_m")?.let { add(it, null, true) }
        device.child("quantities_per_unit")?.let { add(it, null, false) }
        return Quantities(items)
    }

    private fun readConnections(value: Jv?): List<ConnectionPoint> {
        val obj = value as? Jv.Obj ?: return emptyList()
        return obj.fields.mapNotNull { (role, entry) ->
            val accepts = when (entry) {
                is Jv.Arr -> entry.items.mapNotNull { (it as? Jv.Str)?.text }.filter { it.isNotBlank() }
                is Jv.Str -> listOf(entry.text).filter { it.isNotBlank() }
                else -> emptyList()
            }
            if (accepts.isEmpty()) null else ConnectionPoint(role, accepts)
        }
    }

    private fun readMaterials(value: Jv?): List<Material> {
        val array = value as? Jv.Arr ?: return emptyList()
        return array.items.mapNotNull { item ->
            when (item) {
                is Jv.Str -> Material(item.text)
                is Jv.Obj -> (item.str("name") ?: item.str("material"))?.let { Material(it) }
                else -> null
            }
        }
    }

    private fun readNotes(value: Jv?): List<ReviewNote> {
        val array = value as? Jv.Arr ?: return emptyList()
        return array.items.map { item ->
            when (item) {
                is Jv.Str -> ReviewNote("", item.text, null)
                is Jv.Obj -> ReviewNote(item.str("field") ?: "", item.str("issue") ?: "", readPage(item.fields["page"]))
                else -> ReviewNote("", "", null)
            }
        }
    }

    private fun readPage(value: Jv?): SourcePage? {
        val obj = value as? Jv.Obj ?: return null
        return SourcePage(obj.str("source") ?: "", obj.num("pdf_page")?.toInt() ?: 0, obj.str("desenho") ?: "")
    }

    private fun readText(value: Jv?): String? = when (value) {
        is Jv.Str -> value.text.ifBlank { null }
        is Jv.Arr -> value.items.mapNotNull { (it as? Jv.Str)?.text }.joinToString("\n").ifBlank { null }
        else -> null
    }

    private fun readStrings(value: Jv?): List<String> = when (value) {
        is Jv.Arr -> value.items.mapNotNull { (it as? Jv.Str)?.text }
        is Jv.Str -> listOf(value.text)
        else -> emptyList()
    }

    private fun readChapters(value: Jv?): Map<Int, String> {
        val obj = value as? Jv.Obj ?: return emptyMap()
        val map = LinkedHashMap<Int, String>()
        for ((key, entry) in obj.fields) {
            val number = key.toIntOrNull() ?: continue
            val title = (entry as? Jv.Str)?.text ?: continue
            map[number] = title
        }
        return map
    }

    private fun readSources(value: Jv?): Map<String, CatalogSource> {
        val obj = value as? Jv.Obj ?: return emptyMap()
        val map = LinkedHashMap<String, CatalogSource>()
        for ((key, entry) in obj.fields) {
            val source = entry as? Jv.Obj ?: continue
            map[key] = CatalogSource(source.str("file") ?: "", source.str("title") ?: key)
        }
        return map
    }
}
