package com.appdwg.core.drainage

import com.appdwg.core.Alignment
import com.appdwg.core.Point2
import java.util.Locale
import kotlin.math.hypot

/** A device placed in the project. Old project files simply omit the list. */
sealed interface PlacedDevice {
    val id: String
    val code: String
    val params: Map<String, Double>

    data class Linear(
        override val id: String,
        override val code: String,
        val source: PathSource,
        val alignment: String?,
        val xs: List<Double>,
        val ys: List<Double>,
        val side: DeviceSide,
        val offset: Double,
        val start: Double?,
        val end: Double?,
        val grade: GradeSpec,
        val profile: String?,
        override val params: Map<String, Double> = emptyMap(),
    ) : PlacedDevice

    data class Point(
        override val id: String,
        override val code: String,
        val x: Double,
        val y: Double,
        val rotationDeg: Double,
        val skewDeg: Double,
        val grade: GradeSpec,
        val alignment: String?,
        val profile: String?,
        override val params: Map<String, Double> = emptyMap(),
    ) : PlacedDevice
}

data class DeviceProfile(
    val id: String,
    val code: String,
    val hydraulic: HydraulicGrade.Result,
    val deviceTriangles: Int,
    val trenchTriangles: Int,
    val waterTriangles: Int,
)

data class DrainageBuilt(
    val glyphs: List<DrainageGlyph>,
    val triangles: DoubleArray,
    val takeoff: List<DeviceTakeoff>,
    val trench: DoubleArray = DoubleArray(0),
    val beddingMesh: DoubleArray = DoubleArray(0),
    val backfillMesh: DoubleArray = DoubleArray(0),
    val fillMesh: DoubleArray = DoubleArray(0),
    val water: DoubleArray = DoubleArray(0),
    val profiles: List<DeviceProfile> = emptyList(),
    val runs: List<DrainAlignment> = emptyList(),
    val runProfiles: List<DrainProfile> = emptyList(),
    val sections: List<DrainCrossSection> = emptyList(),
)

object DrainageScene {
    /**
     * Meshes and plan geometry for every placed device. Safe to call off the UI thread.
     * A missing catalog device is skipped; a missing surface or profile falls back to elevation 0.
     */
    fun build(
        placed: List<PlacedDevice>,
        deviceOf: (String) -> DeviceDef?,
        terrain: (Double, Double) -> Double?,
        alignmentOf: (String) -> Alignment?,
        names: Map<String, String> = emptyMap(),
    ): DrainageBuilt {
        val glyphs = ArrayList<DrainageGlyph>()
        val triangles = ArrayList<Double>()
        val takeoff = ArrayList<DeviceTakeoff>()
        val profiles = ArrayList<DeviceProfile>()
        val trenchParts = ArrayList<DoubleArray>()
        val bedParts = ArrayList<DoubleArray>()
        val backParts = ArrayList<DoubleArray>()
        val fillParts = ArrayList<DoubleArray>()
        val waterParts = ArrayList<DoubleArray>()
        var budget = Sweep.MAX_TRIANGLES
        for (placedDevice in placed) {
            val device = deviceOf(placedDevice.code) ?: continue
            val section = resolveSection(device, placedDevice.params)
            when (placedDevice) {
                is PlacedDevice.Linear -> {
                    val nodes = nodesOf(placedDevice, alignmentOf) ?: continue
                    val profile = profileOf(placedDevice.profile ?: placedDevice.alignment, alignmentOf)
                    val signed = Sweep.signedOffset(placedDevice.side, placedDevice.offset)
                    val profileAt: (Double) -> Double? = { station -> profile?.elevationAt(station) }
                    val drawn = DeviceBody.linear(device, placedDevice.params, nodes, signed, placedDevice.grade, terrain, profileAt)
                    val mesh = drawn ?: if (device.needsReview || section == null) {
                        null
                    } else {
                        Sweep.sweep(section, nodes, signed, placedDevice.grade, terrain, profileAt)
                    }
                    if (mesh == null) continue
                    val kept = keep(mesh.triangles, budget)
                    budget -= kept.size / 9
                    for (value in kept) triangles += value
                    val layers = Corridor.along(
                        mesh.frames, terrain,
                        (mesh.widthM / 2.0).takeIf { it > 0.05 } ?: 0.3,
                        mesh.innerWidthM / 2.0,
                        placedDevice.grade.buried,
                        if (placedDevice.grade.buried) HydraulicGrade.DEFAULT_BEDDING else 0.0,
                    )
                    trenchParts += layers.trench
                    bedParts += layers.bedding
                    backParts += layers.backfill
                    fillParts += layers.fill
                    waterParts += layers.water
                    val flow = if (mesh.hydraulic?.downstreamAtStart == true) mesh.invert.asReversed() else mesh.invert
                    glyphs += DrainageGlyph(
                        placedDevice.id, mesh.footprint, mesh.invert, flowArrows(flow, mesh.length),
                        null, mesh.simplified, mesh.channel, mesh.channels, mesh.widthM,
                    )
                    takeoff += DrainageTakeoff.of(placedDevice.id, device, mesh.length, section, point = false).earth(mesh.hydraulic)
                    mesh.hydraulic?.let { hydraulic ->
                        profiles += DeviceProfile(
                            placedDevice.id, device.code, hydraulic, kept.size / 9,
                            layers.trench.size / 9, layers.water.size / 9,
                        )
                    }
                }
                is PlacedDevice.Point -> {
                    val profileName = placedDevice.profile ?: placedDevice.alignment
                    val profile = profileOf(profileName, alignmentOf)
                    val station = alignmentOf(profileName ?: "")?.stationOffset(Point2(placedDevice.x, placedDevice.y))?.station
                    val ground = terrain(placedDevice.x, placedDevice.y)
                    val profileZ = if (station == null) null else profile?.elevationAt(station)
                    val drawn = DeviceBody.point(
                        device, placedDevice.params, placedDevice.x, placedDevice.y,
                        placedDevice.rotationDeg, placedDevice.skewDeg, placedDevice.grade, ground, profileZ,
                    )
                    val mesh = drawn ?: if (device.needsReview) {
                        null
                    } else {
                        Sweep.point(section, placedDevice.x, placedDevice.y, placedDevice.rotationDeg, placedDevice.skewDeg, placedDevice.grade, ground, profileZ)
                    }
                    if (mesh == null) continue
                    val kept = keep(mesh.triangles, budget)
                    budget -= kept.size / 9
                    for (value in kept) triangles += value
                    if (placedDevice.grade.mode == GradeMode.HYDRAULIC && mesh.footprint.size >= 3 && ground != null) {
                        val invert = mesh.frames.minOfOrNull { it.invertZ } ?: (ground - if (placedDevice.grade.buried) placedDevice.grade.minCover else 0.0)
                        val pit = Corridor.pit(mesh.footprint, ground, invert - if (placedDevice.grade.buried) HydraulicGrade.DEFAULT_BEDDING else 0.0)
                        trenchParts += pit.trench
                    }
                    val label = if (mesh.simplified) placedDevice.code else null
                    glyphs += DrainageGlyph(
                        placedDevice.id, mesh.footprint, mesh.invert, emptyList(), label, mesh.simplified,
                        mesh.channel, mesh.channels, mesh.widthM,
                    )
                    takeoff += DrainageTakeoff.of(placedDevice.id, device, 1.0, section, point = true)
                }
            }
            if (budget <= 0) break
        }
        val runs = DrainAlignments.build(placed, names, terrain)
        val metrics = { id: String -> metricsOf(placed, id, deviceOf) }
        val runProfiles = runs.map { DrainAlignments.profile(it, placed, terrain, metrics) }
        val sections = runs.flatMap { run ->
            val drawn = runProfiles.find { it.alignmentId == run.id } ?: return@flatMap emptyList()
            DrainAlignments.sections(run, drawn, terrain, metrics)
        }
        return DrainageBuilt(
            glyphs, triangles.toDoubleArray(), takeoff,
            joinDoubles(trenchParts), joinDoubles(bedParts), joinDoubles(backParts), joinDoubles(fillParts), joinDoubles(waterParts),
            profiles, runs, runProfiles, sections,
        )
    }

    /** Nearest placed device within [radius] metres of the plan point, or null. */
    fun hit(glyphs: List<DrainageGlyph>, x: Double, y: Double, radius: Double): String? {
        var best: String? = null
        var bestD = radius
        for (glyph in glyphs) {
            val parts = ArrayList<List<Point2>>()
            if (glyph.invert.isNotEmpty()) parts += glyph.invert
            if (glyph.footprint.size >= 2) parts += glyph.footprint
            val holes = glyph.channels.ifEmpty { listOf(glyph.channel) }
            for (hole in holes) if (hole.size >= 2) parts += hole
            for (lines in parts) {
                for (i in 1 until lines.size) {
                    val d = segmentDistance(x, y, lines[i - 1], lines[i])
                    if (d < bestD) {
                        bestD = d
                        best = glyph.id
                    }
                }
                if (lines.size == 1) {
                    val d = hypot(lines[0].x - x, lines[0].y - y)
                    if (d < bestD) {
                        bestD = d
                        best = glyph.id
                    }
                }
            }
        }
        return best
    }

    private fun nodesOf(device: PlacedDevice.Linear, alignmentOf: (String) -> Alignment?): List<PathNode>? {
        if (device.source == PathSource.ALIGNMENT) {
            val alignment = device.alignment?.let(alignmentOf)
            if (alignment != null) return Sweep.alignment(alignment, device.start, device.end).ifEmpty { null }
        }
        if (device.xs.size < 2) return null
        return Sweep.polyline(device.xs.toDoubleArray(), device.ys.toDoubleArray(), device.start, device.end).ifEmpty { null }
    }

    private fun profileOf(name: String?, alignmentOf: (String) -> Alignment?) =
        name?.let(alignmentOf)?.profile

    private fun metricsOf(placed: List<PlacedDevice>, id: String, deviceOf: (String) -> DeviceDef?): DrainMetrics {
        val device = placed.find { it.id == id }
        val def = device?.let { deviceOf(it.code) }
        val section = if (device != null && def != null) resolveSection(def, device.params) else null
        val pts = section?.points.orEmpty()
        val height = if (pts.isEmpty()) 0.30 else (pts.maxOf { it.y } - pts.minOf { it.y }).coerceAtLeast(0.05)
        val width = if (pts.isEmpty()) 0.60 else (pts.maxOf { it.x } - pts.minOf { it.x }).coerceAtLeast(0.05)
        val grade = when (device) {
            is PlacedDevice.Linear -> device.grade
            is PlacedDevice.Point -> device.grade
            null -> null
        }
        val buried = if (grade?.mode == GradeMode.HYDRAULIC) grade.buried else def?.let(::deviceRunsBuried) == true
        return DrainMetrics(height, width, section?.areaM2 ?: width * height * 0.5, buried, pts)
    }

    private fun DeviceTakeoff.earth(hydraulic: HydraulicGrade.Result?): DeviceTakeoff {
        if (hydraulic == null) return this
        return copy(
            backfill = hydraulic.backfill, bedding = hydraulic.bedding, spoil = hydraulic.spoil, fill = hydraulic.fill,
            trench = hydraulic.excavation, shoring = hydraulic.shoring, tooDeep = hydraulic.tooDeep,
            invertUp = hydraulic.invertUpstream, invertDown = hydraulic.invertDownstream,
            gradeSlope = hydraulic.minSlope, cutDepth = hydraulic.maxCut,
        )
    }

    private fun joinDoubles(parts: List<DoubleArray>): DoubleArray {
        val cap = 12_000 * 9
        var count = 0
        for (part in parts) count += part.size
        if (count == 0) return DoubleArray(0)
        val out = DoubleArray(kotlin.math.min(count, cap))
        var at = 0
        for (part in parts) {
            var i = 0
            while (i < part.size && at < out.size) out[at++] = part[i++]
            if (at >= out.size) break
        }
        return if (at == out.size) out else out.copyOf(at)
    }

    private fun keep(triangles: DoubleArray, budget: Int): DoubleArray {
        if (budget <= 0) return DoubleArray(0)
        val have = triangles.size / 9
        if (have <= budget) return triangles
        return triangles.copyOf(budget * 9)
    }

    private fun segmentDistance(x: Double, y: Double, a: Point2, b: Point2): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(x - (a.x + dx * t), y - (a.y + dy * t))
    }
}

/** JSON for the project file. Unknown fields are ignored, and a missing list is an empty project. */
object DrainageIo {
    fun toJson(devices: List<PlacedDevice>): String = devices.joinToString(",", "[", "]") { device ->
        when (device) {
            is PlacedDevice.Linear -> obj(
                listOf(
                    "id" to str(device.id), "kind" to str("linear"), "code" to str(device.code),
                    "source" to str(device.source.name.lowercase(Locale.US)),
                    "alignment" to (device.alignment?.let(::str) ?: "null"),
                    "xs" to nums(device.xs), "ys" to nums(device.ys),
                    "side" to str(device.side.name.lowercase(Locale.US)),
                    "offset" to num(device.offset),
                    "start" to (device.start?.let(::num) ?: "null"),
                    "end" to (device.end?.let(::num) ?: "null"),
                    "profile" to (device.profile?.let(::str) ?: "null"),
                    "params" to params(device.params),
                ) + gradeFields(device.grade),
            )
            is PlacedDevice.Point -> obj(
                listOf(
                    "id" to str(device.id), "kind" to str("point"), "code" to str(device.code),
                    "x" to num(device.x), "y" to num(device.y),
                    "rotation" to num(device.rotationDeg), "skew" to num(device.skewDeg),
                    "alignment" to (device.alignment?.let(::str) ?: "null"),
                    "profile" to (device.profile?.let(::str) ?: "null"),
                    "params" to params(device.params),
                ) + gradeFields(device.grade),
            )
        }
    }

    fun fromJson(text: String): List<PlacedDevice> {
        val root = runCatching { parseJv(text) }.getOrNull() as? Jv.Arr ?: return emptyList()
        return root.items.mapNotNull { item ->
            val obj = item as? Jv.Obj ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val code = obj.str("code") ?: return@mapNotNull null
            val params = readParams(obj.child("params"))
            val grade = readGrade(obj)
            when (obj.str("kind")) {
                "linear" -> PlacedDevice.Linear(
                    id, code, pathSource(obj.str("source")), obj.str("alignment"),
                    readDoubles(obj, "xs"), readDoubles(obj, "ys"),
                    side(obj.str("side")), obj.num("offset") ?: 0.0,
                    obj.num("start"), obj.num("end"), grade, obj.str("profile"), params,
                )
                "point" -> PlacedDevice.Point(
                    id, code, obj.num("x") ?: return@mapNotNull null, obj.num("y") ?: return@mapNotNull null,
                    obj.num("rotation") ?: 0.0, obj.num("skew") ?: 0.0, grade,
                    obj.str("alignment"), obj.str("profile"), params,
                )
                else -> null
            }
        }
    }

    private fun gradeFields(grade: GradeSpec) = listOf(
        "grade" to str(grade.mode.name.lowercase(Locale.US)),
        "z0" to num(grade.z0), "z1" to num(grade.z1),
        "depth" to num(grade.depth), "slope" to num(grade.slope),
        "minSlope" to num(grade.minSlope), "minCover" to num(grade.minCover), "maxDepth" to num(grade.maxDepth),
        "buried" to if (grade.buried) "true" else "false",
        "flowToStart" to if (grade.flowToStart) "true" else "false",
        "flowAuto" to if (grade.flowAuto) "true" else "false",
    )

    private fun readGrade(obj: Jv.Obj) = GradeSpec(
        mode = when (obj.str("grade")) {
            "surface_top" -> GradeMode.SURFACE_TOP
            "fixed" -> GradeMode.FIXED
            "slope" -> GradeMode.SLOPE
            "profile" -> GradeMode.PROFILE
            "hydraulic" -> GradeMode.HYDRAULIC
            else -> GradeMode.SURFACE_INVERT
        },
        z0 = obj.num("z0") ?: 0.0,
        z1 = obj.num("z1") ?: 0.0,
        depth = obj.num("depth") ?: 0.0,
        slope = obj.num("slope") ?: 0.0,
        minSlope = obj.num("minSlope") ?: 0.005,
        minCover = obj.num("minCover") ?: 0.60,
        maxDepth = obj.num("maxDepth") ?: 6.0,
        buried = obj.fields["buried"] == Jv.Yes,
        flowToStart = obj.fields["flowToStart"] == Jv.Yes,
        flowAuto = obj.fields["flowAuto"] != Jv.No,
    )

    private fun pathSource(text: String?) = when (text) {
        "alignment" -> PathSource.ALIGNMENT
        "line" -> PathSource.LINE
        else -> PathSource.POLYLINE
    }

    private fun side(text: String?) = when (text) {
        "left" -> DeviceSide.LEFT
        "right" -> DeviceSide.RIGHT
        else -> DeviceSide.AXIS
    }

    private fun readDoubles(obj: Jv.Obj, key: String): List<Double> {
        val array = obj.fields[key] as? Jv.Arr ?: return emptyList()
        return array.items.mapNotNull { (it as? Jv.Num)?.n }
    }

    private fun readParams(obj: Jv.Obj?): Map<String, Double> {
        if (obj == null) return emptyMap()
        return obj.fields.mapNotNull { (key, value) -> (value as? Jv.Num)?.n?.let { key to it } }.toMap()
    }

    private fun obj(fields: List<Pair<String, String>>) = fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    private fun str(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun num(value: Double) = if (value.isFinite()) java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else "0"
    private fun nums(values: List<Double>) = values.joinToString(",", "[", "]", transform = ::num)
    private fun params(values: Map<String, Double>) = values.entries.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + num(v) }
}
