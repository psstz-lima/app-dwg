package com.appdwg.app

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
    var show3d by mutableStateOf(false)
    var exaggeration by mutableStateOf(1)
}

/** Everything a saved project (.appdwg) holds; files are kept as content URIs, not copied. */
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", APP_TAG)
        put("version", 1)
        put("main", main)
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
        put("models", JSONArray(models.map { (uris, p) ->
            JSONObject().put("uris", JSONArray(uris)).put("east", p.east).put("north", p.north).put("elevation", p.elevation)
                .put("rotation", p.rotationDeg).put("scale", p.scale).put("unit", p.unitScale)
        }))
    }

    companion object {
        const val APP_TAG = "App DWG"

        fun looksLikeProject(head: String) = head.trimStart().startsWith("{") && head.contains("\"app\"")

        fun fromJson(o: JSONObject): ProjectDoc {
            require(o.optString("app") == APP_TAG) { "não é um projeto do App DWG" }
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
            )
        }

        fun viewToJson(v: ViewState): JSONObject = JSONObject().apply {
            put("off", JSONArray(v.offLayers))
            put("frozen", JSONArray(v.frozenLayers))
            put("known", JSONArray(v.knownLayers))
            put("colors", JSONObject(v.colorOverrides as Map<*, *>))
            put("measuring", v.measuring)
            put("measure", JSONArray(v.measurePoints.map { JSONArray().put(it.x).put(it.y) }))
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
            v.platformTerrain?.let { put("platformTerrain", it) }
            put("platformBuilt", v.platformBuilt)
            put("show3d", v.show3d)
            put("exaggeration", v.exaggeration)
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
            v.platformTerrain = o.optString("platformTerrain").ifEmpty { null }
            v.platformBuilt = o.optBoolean("platformBuilt")
            v.show3d = o.optBoolean("show3d")
            v.exaggeration = o.optInt("exaggeration", 1)
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        elevation?.let { put("elevation", it) }
        put("gradePercent", gradePercent); put("gradeAzimuth", gradeAzimuth); put("useVertexElevations", useVertexElevations)
        put("cutRatio", cutRatio); put("cutBenchHeight", cutBenchHeight); put("cutBenchWidth", cutBenchWidth); put("cutBenchFall", cutBenchFall)
        put("fillRatio", fillRatio); put("fillBenchHeight", fillBenchHeight); put("fillBenchWidth", fillBenchWidth); put("fillBenchFall", fillBenchFall)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlatformParams(
            o.optDouble("elevation").takeIf { !it.isNaN() }, o.optDouble("gradePercent", 0.0), o.optDouble("gradeAzimuth", 0.0),
            o.optBoolean("useVertexElevations"),
            o.optDouble("cutRatio", 1.0), o.optDouble("cutBenchHeight", 8.0), o.optDouble("cutBenchWidth", 3.0), o.optDouble("cutBenchFall", 5.0),
            o.optDouble("fillRatio", 1.5), o.optDouble("fillBenchHeight", 8.0), o.optDouble("fillBenchWidth", 3.0), o.optDouble("fillBenchFall", 5.0),
        )
    }
}
