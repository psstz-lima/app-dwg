package com.appdwg.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.appdwg.core.Alignment
import com.appdwg.core.LandXmlProject
import com.appdwg.core.PlanText
import com.appdwg.core.Point2
import com.appdwg.core.Polyline
import com.appdwg.core.StationFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Above this many triangles the mesh is not drawn, to keep panning smooth. */
const val MAX_DRAWN_TRIANGLES = 150_000

/** Pixels per metre from which the triangle mesh is drawn over the terrain. */
private const val MESH_MIN_SCALE = 1.5

data class PlanColors(
    val background: Color,
    val mesh: Color,
    val alignment: Color,
    val drawing: Color,
    val stations: Color,
    val points: Color,
    val marker: Color,
)

/**
 * Pan/zoom plan view. Geometry is kept relative to [origin] so single-precision drawing stays
 * accurate with UTM-sized coordinates.
 */
private class Camera(var centerX: Double, var centerY: Double, var scale: Double)

private val CameraSaver = Saver<MutableState<Camera?>, DoubleArray>(
    save = { state -> state.value?.let { doubleArrayOf(it.centerX, it.centerY, it.scale) } },
    restore = { mutableStateOf(Camera(it[0], it[1], it[2])) },
)

@Composable
fun PlanView(
    project: LandXmlProject,
    terrain: List<TerrainImage>,
    colors: PlanColors,
    marker: Point2?,
    markerOnAxis: Point2?,
    /** CAD layers not to draw. */
    hiddenLayers: Set<String>,
    /** Current color of a CAD layer (ARGB, null for the foreground color). */
    layerColor: (String) -> Int?,
    onTap: (Point2) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = project.bounds ?: return
    val origin = bounds.center
    val scene = remember(project) { Scene.build(project, origin) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Saved so rotating the device keeps the current zoom and position.
    var camera by rememberSaveable(project, saver = CameraSaver) { mutableStateOf<Camera?>(null) }
    // Bumped on every gesture so the Canvas redraws; the camera itself is mutated in place.
    var frame by remember { mutableStateOf(0) }

    LaunchedEffect(project, size) {
        if (camera == null && size.width > 0 && size.height > 0) {
            val w = maxOf(bounds.width, 1.0)
            val h = maxOf(bounds.height, 1.0)
            camera = Camera(0.0, 0.0, 0.9 * min(size.width / w, size.height / h))
        }
    }

    fun toLocal(screen: Offset, cam: Camera) = Point2(
        (screen.x - size.width / 2.0) / cam.scale + cam.centerX,
        -(screen.y - size.height / 2.0) / cam.scale + cam.centerY,
    )

    fun toScreen(world: Point2, cam: Camera) = Offset(
        ((world.x - origin.x - cam.centerX) * cam.scale + size.width / 2.0).toFloat(),
        (-(world.y - origin.y - cam.centerY) * cam.scale + size.height / 2.0).toFloat(),
    )

    Canvas(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(project) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cam = camera ?: return@detectTransformGestures
                    val before = toLocal(centroid, cam)
                    cam.scale = (cam.scale * zoom).coerceIn(1e-4, 1e4)
                    cam.centerX = before.x - (centroid.x - size.width / 2.0) / cam.scale
                    cam.centerY = before.y + (centroid.y - size.height / 2.0) / cam.scale
                    cam.centerX -= pan.x / cam.scale
                    cam.centerY += pan.y / cam.scale
                    frame++
                }
            }
            .pointerInput(project) {
                detectTapGestures { offset ->
                    val cam = camera ?: return@detectTapGestures
                    val local = toLocal(offset, cam)
                    onTap(Point2(local.x + origin.x, local.y + origin.y))
                }
            },
    ) {
        frame // read so gestures trigger a redraw
        drawRect(colors.background)
        val cam = camera ?: return@Canvas
        val s = cam.scale.toFloat()

        for (t in terrain) if (t.name !in hiddenLayers) drawTerrain(t) { toScreen(it, cam) }

        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(s, -s, pivot = Offset.Zero)
            translate(-cam.centerX.toFloat(), -cam.centerY.toFloat())
        }) {
            // The triangle mesh only helps once zoomed in; from afar it hides the shaded terrain.
            if (cam.scale >= MESH_MIN_SCALE) scene.mesh?.let { drawPath(it, colors.mesh, style = Stroke(width = 1f / s)) }
            fun Style.paint() = (if (byLayer) layerColor(layer) else color)?.let { Color(it) } ?: colors.drawing
            for ((key, path) in scene.hatches) {
                if (key.layer in hiddenLayers) continue
                drawPath(path, key.paint().copy(alpha = 0.45f))
            }
            for ((key, path) in scene.drawing) {
                if (key.layer in hiddenLayers) continue
                drawPath(path, key.paint(), style = Stroke(width = 1.5f / s))
            }
            for (path in scene.alignments) drawPath(path, colors.alignment, style = Stroke(width = 3f / s))
        }

        drawTexts(project.texts, hiddenLayers, layerColor, cam, colors) { toScreen(it, cam) }
        drawStations(project.alignments, cam, colors) { toScreen(it, cam) }
        drawPoints(project, cam, colors) { toScreen(it, cam) }

        if (marker != null) {
            val m = toScreen(marker, cam)
            if (markerOnAxis != null) {
                drawLine(colors.marker, m, toScreen(markerOnAxis, cam), strokeWidth = 2f)
            }
            drawCircle(colors.marker, radius = 7.sp.toPx(), center = m, style = Stroke(width = 3f))
            drawLine(colors.marker, m - Offset(14f, 0f), m + Offset(14f, 0f), strokeWidth = 2f)
            drawLine(colors.marker, m - Offset(0f, 14f), m + Offset(0f, 14f), strokeWidth = 2f)
        }
    }
}

/** Draws only the part of the terrain image that is on screen, so deep zoom stays cheap. */
private fun DrawScope.drawTerrain(t: TerrainImage, toScreen: (Point2) -> Offset) {
    val topLeft = toScreen(Point2(t.bounds.minX, t.bounds.maxY))
    val bottomRight = toScreen(Point2(t.bounds.maxX, t.bounds.minY))
    val pxW = (bottomRight.x - topLeft.x) / t.image.width
    val pxH = (bottomRight.y - topLeft.y) / t.image.height
    if (pxW <= 0f || pxH <= 0f) return
    val left = floor(-topLeft.x / pxW).toInt().coerceIn(0, t.image.width)
    val top = floor(-topLeft.y / pxH).toInt().coerceIn(0, t.image.height)
    val right = ceil((size.width - topLeft.x) / pxW).toInt().coerceIn(0, t.image.width)
    val bottom = ceil((size.height - topLeft.y) / pxH).toInt().coerceIn(0, t.image.height)
    if (right <= left || bottom <= top) return
    drawImage(
        image = t.image,
        srcOffset = IntOffset(left, top),
        srcSize = IntSize(right - left, bottom - top),
        dstOffset = IntOffset((topLeft.x + left * pxW).roundToInt(), (topLeft.y + top * pxH).roundToInt()),
        dstSize = IntSize(
            ((right - left) * pxW).roundToInt().coerceAtLeast(1),
            ((bottom - top) * pxH).roundToInt().coerceAtLeast(1),
        ),
    )
}

/** CAD layer and color (null is the foreground color) that share one path. */
private data class Style(val layer: String, val color: Int?, val byLayer: Boolean)

private class Scene(
    val mesh: Path?,
    val drawing: Map<Style, Path>,
    val hatches: Map<Style, Path>,
    val alignments: List<Path>,
) {
    companion object {
        fun build(project: LandXmlProject, origin: Point2): Scene {
            val mesh = if (project.surfaces.sumOf { it.triangleCount } in 1..MAX_DRAWN_TRIANGLES) {
                Path().apply {
                    for (sf in project.surfaces) {
                        val t = sf.triangles
                        for (k in 0 until sf.triangleCount) {
                            val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
                            moveTo((sf.xs[a] - origin.x).toFloat(), (sf.ys[a] - origin.y).toFloat())
                            lineTo((sf.xs[b] - origin.x).toFloat(), (sf.ys[b] - origin.y).toFloat())
                            lineTo((sf.xs[c] - origin.x).toFloat(), (sf.ys[c] - origin.y).toFloat())
                            close()
                        }
                    }
                }
            } else null
            val alignments = project.alignments.map { a ->
                Path().apply {
                    a.vertices.forEachIndexed { i, p ->
                        val x = (p.x - origin.x).toFloat()
                        val y = (p.y - origin.y).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
            }
            fun Path.add(l: Polyline) {
                for (i in l.xs.indices) {
                    val x = (l.xs[i] - origin.x).toFloat()
                    val y = (l.ys[i] - origin.y).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                if (l.closed) close()
            }
            val drawing = LinkedHashMap<Style, Path>()
            for (l in project.polylines) drawing.getOrPut(Style(l.layer, l.color, l.byLayer)) { Path() }.add(l)
            val hatches = LinkedHashMap<Style, Path>()
            for (h in project.hatches) {
                val path = hatches.getOrPut(Style(h.layer, h.color, h.byLayer)) { Path().apply { fillType = PathFillType.EvenOdd } }
                h.loops.forEach { path.add(it) }
            }
            return Scene(mesh, drawing, hatches, alignments)
        }
    }
}

/** Station intervals in metres; all are whole 20 m stakes so labels stay round. */
private val STATION_STEPS = doubleArrayOf(20.0, 40.0, 100.0, 200.0, 400.0, 1000.0, 2000.0, 5000.0, 10000.0)

private fun DrawScope.drawStations(
    alignments: List<Alignment>,
    cam: Camera,
    colors: PlanColors,
    toScreen: (Point2) -> Offset,
) {
    val minSpacingPx = 70.sp.toPx()
    val step = STATION_STEPS.firstOrNull { it * cam.scale >= minSpacingPx } ?: return
    val tick = 8.sp.toPx()
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 11.sp.toPx()
        color = colors.stations.toArgb()
    }
    for (a in alignments) {
        if (a.vertices.size < 2) continue
        var sta = ceil(a.startStation / step) * step
        while (sta <= a.endStation) {
            val p = a.pointAt(sta) ?: break
            val sp = toScreen(p)
            if (sp.x in -50f..size.width + 50f && sp.y in -50f..size.height + 50f) {
                // pointAt clamps to the ends, so this direction is defined at the first and last stake too.
                val dir = toScreen(a.pointAt(sta + 0.5)!!) - toScreen(a.pointAt(sta - 0.5)!!)
                val len = hypot(dir.x, dir.y)
                if (len > 0f) {
                    val n = Offset(-dir.y / len, dir.x / len) * tick
                    drawLine(colors.stations, sp - n, sp + n, strokeWidth = 2f)
                    drawContext.canvas.nativeCanvas.drawText(
                        StationFormat.estaca(sta), sp.x + n.x * 1.4f + 4f, sp.y + n.y * 1.4f, paint,
                    )
                }
            }
            sta += step
        }
    }
}

/** Texts smaller than this on screen are skipped; they would be unreadable and slow down panning. */
private const val MIN_TEXT_PX = 5f

private fun DrawScope.drawTexts(
    texts: List<PlanText>,
    hiddenLayers: Set<String>,
    layerColor: (String) -> Int?,
    cam: Camera,
    colors: PlanColors,
    toScreen: (Point2) -> Offset,
) {
    if (texts.isEmpty()) return
    val paint = Paint().apply { isAntiAlias = true }
    val canvas = drawContext.canvas.nativeCanvas
    val default = colors.drawing.toArgb()
    for (t in texts) {
        val px = (t.height * cam.scale).toFloat()
        if (px < MIN_TEXT_PX || t.layer in hiddenLayers) continue
        val p = toScreen(Point2(t.x, t.y))
        val lines = t.text.lines()
        // Rough extent check before measuring: skip texts far off screen.
        val reach = px * (lines.maxOf { it.length } + lines.size * 2)
        if (p.x < -reach || p.y < -reach || p.x > size.width + reach || p.y > size.height + reach) continue
        paint.textSize = px
        paint.color = (if (t.byLayer) layerColor(t.layer) else t.color) ?: default
        val lineHeight = px * 1.6f
        val block = lineHeight * (lines.size - 1)
        // Baseline of the first line relative to the anchor, in text space (y down).
        val firstBaseline = when (t.vAlign) {
            PlanText.TOP -> px
            PlanText.MIDDLE -> px / 2 - block / 2
            PlanText.BOTTOM -> -block
            else -> -block
        }
        canvas.save()
        canvas.translate(p.x, p.y)
        canvas.rotate(-Math.toDegrees(t.rotation).toFloat())
        lines.forEachIndexed { i, line ->
            val w = paint.measureText(line)
            val x = when (t.hAlign) { 1 -> -w / 2; 2 -> -w; else -> 0f }
            canvas.drawText(line, x, firstBaseline + i * lineHeight, paint)
        }
        canvas.restore()
    }
}

private fun DrawScope.drawPoints(
    project: LandXmlProject,
    cam: Camera,
    colors: PlanColors,
    toScreen: (Point2) -> Offset,
) {
    if (project.cgPoints.isEmpty()) return
    val showNames = project.cgPoints.size <= 2000 && cam.scale > 0.5
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 10.sp.toPx()
        color = colors.points.toArgb()
    }
    val r = 3.sp.toPx()
    for (pt in project.cgPoints) {
        val sp = toScreen(Point2(pt.position.x, pt.position.y))
        if (sp.x < -20f || sp.y < -20f || sp.x > size.width + 20f || sp.y > size.height + 20f) continue
        drawCircle(colors.points, radius = r, center = sp)
        if (showNames) drawContext.canvas.nativeCanvas.drawText(pt.name, sp.x + r * 1.5f, sp.y - r * 1.5f, paint)
    }
}
