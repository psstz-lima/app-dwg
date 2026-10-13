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
            return ProjectDoc(
                o.getString("main"), files, o.optBoolean("pointsSurface"), strings("drape"), o.optBoolean("drapeVisible", true),
                points, o.optJSONObject("view") ?: JSONObject(), o.optBoolean("compareShown"), strings("cross"),
                strings("unloaded"), strings("detached"), edits,
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
            v.show3d = o.optBoolean("show3d")
            v.exaggeration = o.optInt("exaggeration", 1)
        }
    }
}
