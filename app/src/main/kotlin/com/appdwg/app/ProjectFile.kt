package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the user set on the open drawing: layers, measurement, cross section, comparison and view.
 * Kept in the view model so it survives rotation and goes into the project file.
 */
class ViewState(project: LandXmlProject) {
    var offLayers by mutableStateOf(project.layersOff.toList())
    var frozenLayers by mutableStateOf(project.layersFrozen.toList())
    /** Layers seen so far; ones that appear later (an xref loaded) start as their drawing saved them. */
    var knownLayers by mutableStateOf(project.layers)
    /** Layer colors changed by the user (ARGB; 0 means the foreground color). */
    var colorOverrides by mutableStateOf<Map<String, Int>>(emptyMap())
    var measuring by mutableStateOf(false)
    var measurePoints by mutableStateOf<List<Point2>>(emptyList())
    /** Measurements kept with "Salvar e nova", drawn on the plan numbered M1, M2… */
    var savedMeasures by mutableStateOf<List<List<Point2>>>(emptyList())
    var showSection by mutableStateOf(false)
    var sectionAlignment by mutableStateOf(0)
    var sectionStation by mutableStateOf(project.alignments.firstOrNull()?.startStation ?: 0.0)
    var sectionHalfWidth by mutableStateOf(30.0)
    var compareBase by mutableStateOf<String?>(null)
    var compareOther by mutableStateOf<String?>(null)
    var showProfile by mutableStateOf(false)
    var profileCursor by mutableStateOf(project.alignments.firstOrNull()?.startStation ?: 0.0)
    var profileRange by mutableStateOf(0.0)
    var showContours by mutableStateOf(false)
    /** Contour interval in metres; 0 picks one from the zoom. */
    var contourInterval by mutableStateOf(0.0)
    // Platform design: outline, parameters, the surface it is designed on and the drawing mode.
    var drawingPlatform by mutableStateOf(false)
    var platformOutline by mutableStateOf<List<Point2>>(emptyList())
    var platformParams by mutableStateOf(PlatformParams())
    var platformTerrain by mutableStateOf<String?>(null)
    var platformBuilt by mutableStateOf(false)
    var roadParams by mutableStateOf(RoadParams())
    var roadBuilt by mutableStateOf(false)
    var drawingRoad by mutableStateOf(false)
    // Drainage basin: rain and runoff settings, and the plan waiting for the outlet to be tapped.
    var basinParams by mutableStateOf(BasinParams())
    var pickingOutlet by mutableStateOf(false)
    var show3d by mutableStateOf(false)
    var exaggeration by mutableStateOf(1)
    /** Detail of the picture over the surfaces in 3D: 0 normal, 1 high, 2 highest. */
    var drapeQuality by mutableStateOf(0)
    // Site calibration of the GNSS position to the local grid.
    var calibrationPairs by mutableStateOf<List<com.appdwg.core.CalibrationPair>>(emptyList())
    var calibrationOn by mutableStateOf(false)
    var calibrationFixScale by mutableStateOf(false)
    // Measurement bulletin (boletim de medição).
    var bulletinItems by mutableStateOf<List<BulletinItem>>(emptyList())
    var bulletinNumber by mutableStateOf("1")
    var bulletinPeriod by mutableStateOf("")
    var sheetParams by mutableStateOf(SheetParams())
    var pointLabels by mutableStateOf(true)
    /** Decimal places of the coordinates in the footer. */
    var coordDigits by mutableStateOf(3)
    /** Coordinate system of the project; null = from the drawing's EPSG, else SIRGAS 2000 / UTM 23S. */
    var crs by mutableStateOf<com.appdwg.core.Crs?>(null)
    /** Area shown while the project has nothing of its own yet (a new project from the map). */
    var area by mutableStateOf<com.appdwg.core.Bounds?>(null)
    /** How each surface is drawn, by name; surfaces not listed use the default style. */
    var surfaceStyles by mutableStateOf<Map<String, SurfaceStyle>>(emptyMap())

    fun styleOf(surface: String) = surfaceStyles[surface] ?: SurfaceStyle()
    fun setStyle(surface: String, style: SurfaceStyle) { surfaceStyles = surfaceStyles + (surface to style) }
}

/** How the elevations of a surface are painted. */
enum class ElevationPaint { COLORS, SHADE, NONE }

/**
 * Display style of a surface, as in Civil 3D: each part on or off on its own, so the mesh can be
 * hidden while the elevations stay visible.
 */
data class SurfaceStyle(
    val triangles: Boolean = true,
    val border: Boolean = false,
    val contours: Boolean = true,
    val elevation: ElevationPaint = ElevationPaint.COLORS,
    /** Elevation legend on the plan (colours mode). */
    val legend: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("triangles", triangles).put("border", border).put("contours", contours)
        .put("elevation", elevation.name).put("legend", legend)

    companion object {
        fun fromJson(o: JSONObject) = SurfaceStyle(
            o.optBoolean("triangles", true), o.optBoolean("border"), o.optBoolean("contours", true),
            ElevationPaint.entries.firstOrNull { it.name == o.optString("elevation") } ?: ElevationPaint.COLORS, o.optBoolean("legend"),
        )
    }
}

/** Coordinate systems as JSON, for the project file and the user's own systems. */
object CrsJson {
    fun toJson(c: com.appdwg.core.Crs): JSONObject = JSONObject().apply {
        put("id", c.id); put("name", c.name); c.epsg?.let { put("epsg", it) }; put("custom", c.custom); put("local", c.local); put("region", c.region)
        val d = c.datum; val e = d.ellipsoid; val h = d.toSirgas
        put("datum", JSONObject().put("id", d.id).put("name", d.name).put("wkt", d.wktName)
            .put("ellipsoid", JSONObject().put("name", e.name).put("a", e.a).put("invF", e.invF))
            .put("toSirgas", JSONArray(listOf(h.dx, h.dy, h.dz, h.rx, h.ry, h.rz, h.ds))))
        when (val p = c.projection) {
            com.appdwg.core.Projection.Geographic -> put("projection", JSONObject().put("type", "geographic"))
            is com.appdwg.core.Projection.TransverseMercator -> put("projection", JSONObject().put("type", "tm").put("lat0", p.lat0).put("lon0", p.lon0)
                .put("k0", p.k0).put("fe", p.falseEasting).put("fn", p.falseNorthing))
            is com.appdwg.core.Projection.LambertConic -> put("projection", JSONObject().put("type", "lcc").put("lat0", p.lat0).put("lon0", p.lon0)
                .put("lat1", p.lat1).apply { p.lat2?.let { put("lat2", it) } }.put("k0", p.k0).put("fe", p.falseEasting).put("fn", p.falseNorthing))
        }
    }

    fun fromJson(o: JSONObject): com.appdwg.core.Crs {
        // Built-in systems come back from the catalog, so a fix to it reaches old projects too.
        com.appdwg.core.CrsCatalog.byId(o.optString("id"))?.takeIf { !o.optBoolean("custom") }?.let { return it }
        val d = o.getJSONObject("datum"); val e = d.getJSONObject("ellipsoid"); val h = d.getJSONArray("toSirgas")
        val datum = com.appdwg.core.Datum(d.getString("id"), d.getString("name"),
            com.appdwg.core.Ellipsoid(e.getString("name"), e.getDouble("a"), e.getDouble("invF")),
            com.appdwg.core.Helmert(h.getDouble(0), h.getDouble(1), h.getDouble(2), h.getDouble(3), h.getDouble(4), h.getDouble(5), h.getDouble(6)),
            d.optString("wkt", d.getString("name")))
        val p = o.getJSONObject("projection")
        val proj = when (p.getString("type")) {
            "geographic" -> com.appdwg.core.Projection.Geographic
            "lcc" -> com.appdwg.core.Projection.LambertConic(p.getDouble("lat0"), p.getDouble("lon0"), p.getDouble("lat1"),
                if (p.has("lat2")) p.getDouble("lat2") else null, p.getDouble("k0"), p.getDouble("fe"), p.getDouble("fn"))
            else -> com.appdwg.core.Projection.TransverseMercator(p.getDouble("lat0"), p.getDouble("lon0"), p.getDouble("k0"), p.getDouble("fe"), p.getDouble("fn"))
        }
        return com.appdwg.core.Crs(o.getString("id"), o.getString("name"), o.optString("epsg").ifEmpty { null }, datum, proj, o.optBoolean("custom"),
            o.optBoolean("local"), o.optString("region"))
    }
}

/** One line of the measurement bulletin; quantities in the item's unit, price per unit. */
data class BulletinItem(
    val description: String,
    val unit: String = "m³",
    val contracted: Double = 0.0,
    val previous: Double = 0.0,
    val period: Double = 0.0,
    val price: Double = 0.0,
) {
    val accumulated get() = previous + period
    val percent get() = if (contracted > 0) accumulated / contracted * 100 else 0.0
    val periodValue get() = period * price
    val accumulatedValue get() = accumulated * price
    val contractedValue get() = contracted * price

    fun toJson(): JSONObject = JSONObject().put("d", description).put("u", unit).put("c", contracted).put("a", previous).put("p", period).put("v", price)

    companion object {
        fun fromJson(o: JSONObject) = BulletinItem(o.optString("d"), o.optString("u", "m³"), o.optDouble("c", 0.0), o.optDouble("a", 0.0),
            o.optDouble("p", 0.0), o.optDouble("v", 0.0))
    }
}

/** Everything a saved project (.gromatic, antes .appdwg) holds; files are kept as content URIs, not copied. */
class ProjectDoc(
    val main: String,
    val files: List<Pair<String, String>>,
    val pointsSurface: Boolean,
    val drape: List<String>,
    val drapeVisible: Boolean,
    val points: List<CogoPoint>,
    val view: JSONObject,
    val compareShown: Boolean,
    val cross: List<String>,
    val unloaded: List<String>,
    val detached: List<String>,
    val pathEdits: Map<String, String>,
    val userLines: List<com.appdwg.core.Polyline> = emptyList(),
    val hiddenLines: List<Int> = emptyList(),
    /** Files of each 3D model and its placement. */
    val models: List<Pair<List<String>, com.appdwg.core.ModelPlacement>> = emptyList(),
    /** Field notes; their photos stay in the app's storage, named here. */
    val notes: List<com.appdwg.core.FieldNote> = emptyList(),
    /** File name of [main], to name it when it cannot be opened. */
    val mainName: String? = null,
    /** Surfaces made by editing (Editar sup.), stored whole since they come from no file; already encoded. */
    val edited: List<JSONObject> = emptyList(),
    /** Labels of added files switched off. */
    val hiddenFiles: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", APP_TAG)
        put("version", 1)
        put("main", main)
        mainName?.let { put("mainName", it) }
        put("files", JSONArray(files.map { (label, uri) -> JSONObject().put("label", label).put("uri", uri) }))
        put("pointsSurface", pointsSurface)
        put("drape", JSONArray(drape))
        put("drapeVisible", drapeVisible)
        put("points", JSONArray(points.map { p ->
            JSONObject().put("n", p.number).put("d", p.description).put("x", p.x).put("y", p.y).apply { if (!p.z.isNaN()) put("z", p.z) }
        }))
        put("view", view)
        put("compareShown", compareShown)
        put("cross", JSONArray(cross))
        put("unloaded", JSONArray(unloaded))
        put("detached", JSONArray(detached))
        put("pathEdits", JSONObject(pathEdits as Map<*, *>))
        put("userLines", JSONArray(userLines.map { l ->
            JSONObject().put("layer", l.layer).put("closed", l.closed).apply {
                l.color?.let { put("color", it) }
                put("xs", JSONArray(l.xs.toList())); put("ys", JSONArray(l.ys.toList()))
                l.zs?.let { put("zs", JSONArray(it.toList())) }
            }
        }))
        put("hiddenLines", JSONArray(hiddenLines))
        put("notes", JSONArray(notes.map { n ->
            JSONObject().put("id", n.id).put("x", n.x).put("y", n.y).put("text", n.text).put("time", n.time)
                .apply { if (!n.z.isNaN()) put("z", n.z); n.photo?.let { put("photo", it) } }
        }))
        if (edited.isNotEmpty()) put("edited", JSONArray(edited))
        if (hiddenFiles.isNotEmpty()) put("hiddenFiles", JSONArray(hiddenFiles))
        put("models", JSONArray(models.map { (uris, p) ->
            JSONObject().put("uris", JSONArray(uris)).put("east", p.east).put("north", p.north).put("elevation", p.elevation)
                .put("rotation", p.rotationDeg).put("scale", p.scale).put("unit", p.unitScale)
        }))
    }

    companion object {
        const val APP_TAG = "Gromatic"
        /** Tag of projects saved before the app was renamed; still opened. */
        private const val OLD_APP_TAG = "App DWG"

        fun looksLikeProject(head: String) = head.trimStart().startsWith("{") && head.contains("\"app\"")

        fun fromJson(o: JSONObject): ProjectDoc {
            require(o.optString("app") in setOf(APP_TAG, OLD_APP_TAG)) { tr("não é um projeto do Gromatic") }
            fun strings(name: String) = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val files = o.optJSONArray("files")?.let { a ->
                List(a.length()) { a.getJSONObject(it).let { f -> f.getString("label") to f.getString("uri") } }
            }.orEmpty()
            val points = o.optJSONArray("points")?.let { a ->
                List(a.length()) {
                    val p = a.getJSONObject(it)
                    CogoPoint(p.getString("n"), p.optString("d"), p.getDouble("x"), p.getDouble("y"), p.optDouble("z", Double.NaN))
                }
            }.orEmpty()
            val edits = o.optJSONObject("pathEdits")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty()
            fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
            val lines = o.optJSONArray("userLines")?.let { a ->
                List(a.length()) {
                    val l = a.getJSONObject(it)
                    val color = if (l.has("color")) l.getInt("color") else null
                    com.appdwg.core.Polyline(l.getString("layer"), doubles(l.getJSONArray("xs")), doubles(l.getJSONArray("ys")),
                        l.optBoolean("closed"), color, color == null, l.optJSONArray("zs")?.let(::doubles))
                }
            }.orEmpty()
            val hidden = o.optJSONArray("hiddenLines")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty()
            val models = o.optJSONArray("models")?.let { a ->
                List(a.length()) {
                    val m = a.getJSONObject(it)
                    val u = m.getJSONArray("uris")
                    List(u.length()) { k -> u.getString(k) } to com.appdwg.core.ModelPlacement(
                        m.getDouble("east"), m.getDouble("north"), m.getDouble("elevation"), m.optDouble("rotation", 0.0),
                        m.optDouble("scale", 1.0), m.optDouble("unit", 1.0))
                }
            }.orEmpty()
            return ProjectDoc(
                o.getString("main"), files, o.optBoolean("pointsSurface"), strings("drape"), o.optBoolean("drapeVisible", true),
                points, o.optJSONObject("view") ?: JSONObject(), o.optBoolean("compareShown"), strings("cross"),
                strings("unloaded"), strings("detached"), edits, lines, hidden, models,
                o.optJSONArray("notes")?.let { a ->
                    List(a.length()) {
                        val n = a.getJSONObject(it)
                        com.appdwg.core.FieldNote(n.getString("id"), n.getDouble("x"), n.getDouble("y"), n.optDouble("z", Double.NaN),
                            n.optString("text"), n.optString("photo").ifEmpty { null }, n.optString("time"))
                    }
                }.orEmpty(),
                o.optString("mainName").ifEmpty { null },
                edited = o.optJSONArray("edited")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty(),
                hiddenFiles = strings("hiddenFiles"),
            )
        }

        fun viewToJson(v: ViewState): JSONObject = JSONObject().apply {
            put("off", JSONArray(v.offLayers))
            put("frozen", JSONArray(v.frozenLayers))
            put("known", JSONArray(v.knownLayers))
            put("colors", JSONObject(v.colorOverrides as Map<*, *>))
            put("measuring", v.measuring)
            put("measure", JSONArray(v.measurePoints.map { JSONArray().put(it.x).put(it.y) }))
            put("measures", JSONArray(v.savedMeasures.map { m -> JSONArray(m.map { JSONArray().put(it.x).put(it.y) }) }))
            put("showSection", v.showSection)
            put("sectionAlignment", v.sectionAlignment)
            put("sectionStation", v.sectionStation)
            put("sectionHalfWidth", v.sectionHalfWidth)
            v.compareBase?.let { put("compareBase", it) }
            v.compareOther?.let { put("compareOther", it) }
            put("showProfile", v.showProfile)
            put("profileCursor", v.profileCursor)
            put("profileRange", v.profileRange)
            put("showContours", v.showContours)
            put("contourInterval", v.contourInterval)
            put("platformOutline", JSONArray(v.platformOutline.map { JSONArray().put(it.x).put(it.y) }))
            put("platformParams", v.platformParams.toJson())
            v.basinParams.let { b ->
                put("basinParams", JSONObject().put("runoff", b.runoff).put("years", b.years).put("k", b.k).put("a", b.a)
                    .put("b", b.b).put("c", b.c).put("minArea", b.minArea).put("idfPlace", b.idfPlace))
            }
            v.platformTerrain?.let { put("platformTerrain", it) }
            put("platformBuilt", v.platformBuilt)
            put("roadParams", v.roadParams.toJson())
            put("roadBuilt", v.roadBuilt)
            put("show3d", v.show3d)
            put("exaggeration", v.exaggeration)
            put("drapeQuality", v.drapeQuality)
            put("calibration", JSONArray(v.calibrationPairs.map { c ->
                JSONObject().put("name", c.name).put("h", c.useHorizontal).put("v", c.useVertical)
                    .put("m", JSONArray().put(c.measured.x).put(c.measured.y).put(if (c.measured.z.isNaN()) JSONObject.NULL else c.measured.z))
                    .put("l", JSONArray().put(c.local.x).put(c.local.y).put(if (c.local.z.isNaN()) JSONObject.NULL else c.local.z))
            }))
            put("calibrationOn", v.calibrationOn)
            put("calibrationFixScale", v.calibrationFixScale)
            put("bulletin", JSONArray(v.bulletinItems.map { it.toJson() }))
            put("bulletinNumber", v.bulletinNumber)
            put("bulletinPeriod", v.bulletinPeriod)
            put("sheets", v.sheetParams.toJson())
            put("pointLabels", v.pointLabels)
            put("coordDigits", v.coordDigits)
            v.crs?.let { put("crs", CrsJson.toJson(it)) }
            v.area?.let { a -> put("area", JSONArray(listOf(a.minX, a.minY, a.maxX, a.maxY))) }
            put("surfaceStyles", JSONObject().apply { v.surfaceStyles.forEach { (k, st) -> put(k, st.toJson()) } })
        }

        fun applyView(o: JSONObject, v: ViewState) {
            fun strings(name: String) = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }
            strings("off")?.let { v.offLayers = it }
            strings("frozen")?.let { v.frozenLayers = it }
            strings("known")?.let { v.knownLayers = it }
            o.optJSONObject("colors")?.let { c -> v.colorOverrides = c.keys().asSequence().associateWith { c.getInt(it) } }
            v.measuring = o.optBoolean("measuring")
            o.optJSONArray("measure")?.let { a ->
                v.measurePoints = List(a.length()) { a.getJSONArray(it).let { p -> Point2(p.getDouble(0), p.getDouble(1)) } }
            }
            o.optJSONArray("measures")?.let { all ->
                v.savedMeasures = List(all.length()) { k -> all.getJSONArray(k).let { a -> List(a.length()) { a.getJSONArray(it).let { p -> Point2(p.getDouble(0), p.getDouble(1)) } } } }
            }
            v.showSection = o.optBoolean("showSection")
            v.sectionAlignment = o.optInt("sectionAlignment")
            v.sectionStation = o.optDouble("sectionStation", v.sectionStation)
            v.sectionHalfWidth = o.optDouble("sectionHalfWidth", v.sectionHalfWidth)
            v.compareBase = o.optString("compareBase").ifEmpty { null }
            v.compareOther = o.optString("compareOther").ifEmpty { null }
            v.showProfile = o.optBoolean("showProfile")
            v.profileCursor = o.optDouble("profileCursor", v.profileCursor)
            v.profileRange = o.optDouble("profileRange", 0.0)
            v.showContours = o.optBoolean("showContours")
            v.contourInterval = o.optDouble("contourInterval", 0.0)
            o.optJSONArray("platformOutline")?.let { a ->
                v.platformOutline = List(a.length()) { a.getJSONArray(it).let { p -> Point2(p.getDouble(0), p.getDouble(1)) } }
            }
            o.optJSONObject("platformParams")?.let { v.platformParams = PlatformParams.fromJson(it) }
            o.optJSONObject("basinParams")?.let { b ->
                val d = BasinParams()
                v.basinParams = BasinParams(b.optDouble("runoff", d.runoff), b.optDouble("years", d.years), b.optDouble("k", d.k),
                    b.optDouble("a", d.a), b.optDouble("b", d.b), b.optDouble("c", d.c), b.optDouble("minArea", d.minArea), b.optString("idfPlace", ""))
            }
            v.platformTerrain = o.optString("platformTerrain").ifEmpty { null }
            v.platformBuilt = o.optBoolean("platformBuilt")
            o.optJSONObject("roadParams")?.let { v.roadParams = RoadParams.fromJson(it) }
            v.roadBuilt = o.optBoolean("roadBuilt")
            v.show3d = o.optBoolean("show3d")
            v.exaggeration = o.optInt("exaggeration", 1)
            v.drapeQuality = o.optInt("drapeQuality", 0).coerceIn(0, 2)
            o.optJSONArray("calibration")?.let { a ->
                fun p3(j: JSONArray) = com.appdwg.core.Point3(j.getDouble(0), j.getDouble(1), j.optDouble(2))
                v.calibrationPairs = List(a.length()) { a.getJSONObject(it) }.map { c ->
                    com.appdwg.core.CalibrationPair(c.optString("name"), p3(c.getJSONArray("m")), p3(c.getJSONArray("l")), c.optBoolean("h", true), c.optBoolean("v", true))
                }
            }
            v.calibrationOn = o.optBoolean("calibrationOn")
            v.calibrationFixScale = o.optBoolean("calibrationFixScale")
            o.optJSONArray("bulletin")?.let { a -> v.bulletinItems = List(a.length()) { BulletinItem.fromJson(a.getJSONObject(it)) } }
            v.bulletinNumber = o.optString("bulletinNumber", "1")
            v.bulletinPeriod = o.optString("bulletinPeriod")
            o.optJSONObject("sheets")?.let { v.sheetParams = SheetParams.fromJson(it) }
            v.pointLabels = o.optBoolean("pointLabels", true)
            v.coordDigits = o.optInt("coordDigits", 3).coerceIn(0, 6)
            v.crs = o.optJSONObject("crs")?.let { runCatching { CrsJson.fromJson(it) }.getOrNull() }
            o.optJSONArray("area")?.takeIf { it.length() == 4 }?.let { a -> v.area = com.appdwg.core.Bounds(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)) }
            o.optJSONObject("surfaceStyles")?.let { ss -> v.surfaceStyles = ss.keys().asSequence().associateWith { SurfaceStyle.fromJson(ss.getJSONObject(it)) } }
        }
    }
}

/** Platform parameters as typed in the design dialog. */
data class PlatformParams(
    val elevation: Double? = null,
    val gradePercent: Double = 0.0,
    val gradeAzimuth: Double = 0.0,
    val useVertexElevations: Boolean = false,
    val cutRatio: Double = 1.0,
    val cutBenchHeight: Double = 8.0,
    val cutBenchWidth: Double = 3.0,
    val cutBenchFall: Double = 5.0,
    val fillRatio: Double = 1.5,
    val fillBenchHeight: Double = 8.0,
    val fillBenchWidth: Double = 3.0,
    val fillBenchFall: Double = 5.0,
    /** Heights of each bench, first to last (the last repeats); empty = the single height above. */
    val cutHeights: List<Double> = emptyList(),
    val fillHeights: List<Double> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        elevation?.let { put("elevation", it) }
        put("gradePercent", gradePercent); put("gradeAzimuth", gradeAzimuth); put("useVertexElevations", useVertexElevations)
        put("cutRatio", cutRatio); put("cutBenchHeight", cutBenchHeight); put("cutBenchWidth", cutBenchWidth); put("cutBenchFall", cutBenchFall)
        put("fillRatio", fillRatio); put("fillBenchHeight", fillBenchHeight); put("fillBenchWidth", fillBenchWidth); put("fillBenchFall", fillBenchFall)
        put("cutHeights", JSONArray(cutHeights)); put("fillHeights", JSONArray(fillHeights))
    }

    companion object {
        fun fromJson(o: JSONObject) = PlatformParams(
            o.optDouble("elevation").takeIf { !it.isNaN() }, o.optDouble("gradePercent", 0.0), o.optDouble("gradeAzimuth", 0.0),
            o.optBoolean("useVertexElevations"),
            o.optDouble("cutRatio", 1.0), o.optDouble("cutBenchHeight", 8.0), o.optDouble("cutBenchWidth", 3.0), o.optDouble("cutBenchFall", 5.0),
            o.optDouble("fillRatio", 1.5), o.optDouble("fillBenchHeight", 8.0), o.optDouble("fillBenchWidth", 3.0), o.optDouble("fillBenchFall", 5.0),
            o.optJSONArray("cutHeights")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty(),
            o.optJSONArray("fillHeights")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty(),
        )
    }
}

/** Road (corridor) design settings as typed in the road dialog. */
data class RoadParams(
    /** Alignment of the drawing to use, or −1 for one built from [pis]. */
    val alignmentIndex: Int = -1,
    val pis: List<Point2> = emptyList(),
    val radius: Double = 200.0,
    val spiral: Double = 40.0,
    /** PVIs typed by the user; empty uses the alignment's own profile. */
    val pvis: List<com.appdwg.core.Profile.Pvi> = emptyList(),
    val kValue: Double = 20.0,
    val left: com.appdwg.core.SideTemplate = com.appdwg.core.SideTemplate(),
    val right: com.appdwg.core.SideTemplate = com.appdwg.core.SideTemplate(),
    val cut: com.appdwg.core.SlopeRule = com.appdwg.core.SlopeRule(1.0, 8.0, 3.0, 5.0),
    val fill: com.appdwg.core.SlopeRule = com.appdwg.core.SlopeRule(1.5, 8.0, 3.0, 5.0),
    val pavement: Double = 0.5,
    val step: Double = 20.0,
    val swell: Double = 1.0,
    val superelevation: List<com.appdwg.core.Superelevation> = emptyList(),
    val terrain: String? = null,
    /** Stretches with their own lane and shoulder widths, after the first (which uses [left]/[right]). */
    val regions: List<RoadRegion> = emptyList(),
    val speed: Double = 60.0,
    val emax: Double = 8.0,
    /** Radius and spiral of each PI (by index, the ends unused) from a suggested route; empty uses [radius] and [spiral]. */
    val radii: List<Double> = emptyList(),
    val spirals: List<Double> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("alignmentIndex", alignmentIndex)
        put("pis", JSONArray(pis.map { JSONArray().put(it.x).put(it.y) }))
        put("radius", radius); put("spiral", spiral); put("kValue", kValue)
        put("pvis", JSONArray(pvis.map { JSONArray().put(it.station).put(it.elevation).put(it.curveLength) }))
        put("left", side(left)); put("right", side(right))
        put("cut", rule(cut)); put("fill", rule(fill))
        put("pavement", pavement); put("step", step); put("swell", swell)
        put("superelevation", JSONArray(superelevation.map { JSONArray().put(it.station).put(it.left).put(it.right) }))
        terrain?.let { put("terrain", it) }
        put("regions", JSONArray(regions.map { it.toJson() }))
        put("speed", speed); put("emax", emax)
        put("radii", JSONArray(radii)); put("spirals", JSONArray(spirals))
    }

    companion object {
        private fun side(s: com.appdwg.core.SideTemplate) = JSONArray(listOf(s.laneWidth, s.laneSlope, s.shoulderWidth, s.shoulderSlope, s.gutterWidth, s.gutterDepth, s.ditchWidth, s.ditchDepth))
        private fun rule(r: com.appdwg.core.SlopeRule) = JSONArray(listOf(r.ratio, r.benchHeight, r.benchWidth, r.benchFall))
        private fun sideOf(a: JSONArray?) = a?.let { com.appdwg.core.SideTemplate(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4), it.getDouble(5), it.getDouble(6), it.getDouble(7)) }
        private fun ruleOf(a: JSONArray?) = a?.let { com.appdwg.core.SlopeRule(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) }

        fun fromJson(o: JSONObject): RoadParams {
            fun pairs(name: String) = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getJSONArray(it) } }.orEmpty()
            val d = RoadParams()
            return RoadParams(
                o.optInt("alignmentIndex", -1),
                pairs("pis").map { Point2(it.getDouble(0), it.getDouble(1)) },
                o.optDouble("radius", d.radius), o.optDouble("spiral", d.spiral),
                pairs("pvis").map { com.appdwg.core.Profile.Pvi(it.getDouble(0), it.getDouble(1), it.optDouble(2, 0.0)) },
                o.optDouble("kValue", d.kValue),
                sideOf(o.optJSONArray("left")) ?: d.left, sideOf(o.optJSONArray("right")) ?: d.right,
                ruleOf(o.optJSONArray("cut")) ?: d.cut, ruleOf(o.optJSONArray("fill")) ?: d.fill,
                o.optDouble("pavement", d.pavement), o.optDouble("step", d.step), o.optDouble("swell", d.swell),
                pairs("superelevation").map {
                    // Older files kept one value, rising to the right.
                    if (it.length() >= 3) com.appdwg.core.Superelevation(it.getDouble(0), it.getDouble(1), it.getDouble(2))
                    else com.appdwg.core.Superelevation(it.getDouble(0), -it.getDouble(1), it.getDouble(1))
                },
                o.optString("terrain").ifEmpty { null },
                o.optJSONArray("regions")?.let { a -> List(a.length()) { RoadRegion.fromJson(a.getJSONObject(it)) } }.orEmpty(),
                o.optDouble("speed", 60.0), o.optDouble("emax", 8.0),
                o.optJSONArray("radii")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty(),
                o.optJSONArray("spirals")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty(),
            )
        }
    }
}

/** Road stretch from [from] on, with its own sides, a width transition and optional edge target lines. */
data class RoadRegion(
    val from: Double,
    val left: com.appdwg.core.SideTemplate,
    val right: com.appdwg.core.SideTemplate,
    val transition: Double = 20.0,
    val leftTarget: List<Point2>? = null,
    val rightTarget: List<Point2>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("from", from); put("transition", transition)
        put("left", JSONArray(listOf(left.laneWidth, left.laneSlope, left.shoulderWidth, left.shoulderSlope, left.gutterWidth, left.gutterDepth, left.ditchWidth, left.ditchDepth)))
        put("right", JSONArray(listOf(right.laneWidth, right.laneSlope, right.shoulderWidth, right.shoulderSlope, right.gutterWidth, right.gutterDepth, right.ditchWidth, right.ditchDepth)))
        leftTarget?.let { put("leftTarget", JSONArray(it.map { p -> JSONArray().put(p.x).put(p.y) })) }
        rightTarget?.let { put("rightTarget", JSONArray(it.map { p -> JSONArray().put(p.x).put(p.y) })) }
    }

    companion object {
        fun fromJson(o: JSONObject): RoadRegion {
            fun side(a: JSONArray) = com.appdwg.core.SideTemplate(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5), a.getDouble(6), a.getDouble(7))
            fun line(a: JSONArray?) = a?.let { List(it.length()) { k -> it.getJSONArray(k).let { p -> Point2(p.getDouble(0), p.getDouble(1)) } } }
            return RoadRegion(o.getDouble("from"), side(o.getJSONArray("left")), side(o.getJSONArray("right")), o.optDouble("transition", 20.0),
                line(o.optJSONArray("leftTarget")), line(o.optJSONArray("rightTarget")))
        }
    }
}

/** Compact storage of a surface in a project: binary arrays, deflated, in base64. */
object SurfaceCodec {
    fun encode(s: com.appdwg.core.Surface): JSONObject {
        val ox = s.xs.firstOrNull() ?: 0.0; val oy = s.ys.firstOrNull() ?: 0.0
        val bb = java.nio.ByteBuffer.allocate(8 + 24 * s.vertexCount + 4 * s.triangles.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(s.vertexCount).putInt(s.triangles.size)
        for (i in 0 until s.vertexCount) { bb.putDouble(s.xs[i] - ox); bb.putDouble(s.ys[i] - oy); bb.putDouble(s.zs[i]) }
        for (t in s.triangles) bb.putInt(t)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)).use { it.write(bb.array()) }
        return JSONObject().put("name", s.name).put("ox", ox).put("oy", oy)
            .put("data", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
    }

    fun decode(o: JSONObject): com.appdwg.core.Surface {
        val raw = java.util.zip.InflaterInputStream(android.util.Base64.decode(o.getString("data"), android.util.Base64.NO_WRAP).inputStream()).readBytes()
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(); val t = bb.getInt()
        val ox = o.getDouble("ox"); val oy = o.getDouble("oy")
        val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
        for (i in 0 until n) { xs[i] = bb.getDouble() + ox; ys[i] = bb.getDouble() + oy; zs[i] = bb.getDouble() }
        return com.appdwg.core.Surface(o.getString("name"), xs, ys, zs, IntArray(t) { bb.getInt() })
    }
}
