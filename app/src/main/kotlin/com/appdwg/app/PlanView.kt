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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.appdwg.core.Bounds
import com.appdwg.core.CogoPoint
import com.appdwg.core.FieldNote
import com.appdwg.core.NOTES_LAYER
import com.appdwg.core.LineRef
import com.appdwg.core.PointGen
import com.appdwg.core.POINTS_LAYER
import com.appdwg.core.LandXmlProject
import com.appdwg.core.PlanImage
import com.appdwg.core.PlanText
import com.appdwg.core.Point2
import com.appdwg.core.Polyline
import com.appdwg.core.StationFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Above this many triangles the mesh is not drawn, to keep panning smooth. */
const val MAX_DRAWN_TRIANGLES = 150_000

/** Detail image: wait after a gesture, extra area around the view, and largest side in pixels. */
private const val DETAIL_DELAY_MS = 250L
private const val DETAIL_MARGIN = 1.3
private const val DETAIL_MAX_PX = 3072

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
    val contour: Color = Color(0xFF8D6E63),
    val contourMajor: Color = Color(0xFF5D4037),
)

/** Zoom asked from outside the plan (buttons): [factor] multiplies the scale, 0 fits the drawing. [id] repeats it. */
data class ZoomRequest(val id: Int, val factor: Double)

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
    /** Pictures loaded for the drawing's images, by file path. */
    rasters: Map<String, ImageBitmap>,
    /** Picture laid over the surfaces, or null. */
    drape: Drape?,
    onTap: (Point2) -> Unit,
    modifier: Modifier = Modifier,
    /** Points of the measurement being taken, drawn as a line and, from 3 points on, an area. */
    measure: List<Point2> = emptyList(),
    /** Taps snap to the nearest point or vertex, for measuring. */
    snap: Boolean = false,
    /** Ends of the cross section being shown, drawn across the alignment. */
    sectionLine: Pair<Point2, Point2>? = null,
    /** Colored picture drawn over the terrain, such as the difference between two surfaces. */
    overlay: TerrainImage? = null,
    /** User COGO points (layer _POINTS). */
    points: List<CogoPoint> = emptyList(),
    /** Called instead of [onTap] when a tap lands on one of [points]; not while snapping. */
    onPointTap: ((CogoPoint) -> Unit)? = null,
    /** Point highlighted as selected. */
    selectedPoint: CogoPoint? = null,
    /** Centres the plan on this point whenever it changes (search, "go to"). */
    focus: Point2? = null,
    /** Bumped to centre on [focus] again even when it did not change. */
    focusKey: Int = 0,
    /** Contour interval in metres (0 = from the zoom), or null for no contours. */
    contourInterval: Double? = null,
    /** Told the interval actually drawn, or null when contours are too dense to draw. */
    onContours: (Double?) -> Unit = {},
    /** Lines of a design (platform edge, daylight, benches). */
    designLines: List<List<Point2>> = emptyList(),
    /** Identity of the opened drawing; the view resets only when it changes. */
    viewKey: Any = project,
    /** Taps pick the nearest line instead ([onLineSelected]). */
    selectLine: Boolean = false,
    onLineSelected: (LineRef) -> Unit = {},
    /** Line drawn highlighted. */
    selectedLine: LineRef? = null,
    /** Points shown before they are created. */
    previewPoints: List<Point2> = emptyList(),
    /** Told the area on screen after each gesture. */
    onViewport: (Bounds) -> Unit = {},
    /** Lines picked for editing, drawn highlighted (shown line indices). */
    selectedLines: List<Int> = emptyList(),
    /** Lines an edit would produce, drawn before it is applied. */
    previewLines: List<Polyline> = emptyList(),
    /** Receiver position, its horizontal precision (m) and the colour of its fix. */
    gps: Point2? = null,
    gpsAccuracy: Double? = null,
    gpsColor: Color = Color.Gray,
    /** Field notes, drawn as pins; a tap on one calls [onNoteTap]. */
    notes: List<FieldNote> = emptyList(),
    onNoteTap: ((FieldNote) -> Unit)? = null,
    /** Background map drawn under everything, in the project's UTM [basemapZone]; null hides it. */
    basemap: BasemapStore? = null,
    basemapZone: Int = 23,
    basemapSouth: Boolean = true,
    zoom: ZoomRequest? = null,
) {
    val bounds = project.bounds ?: return
    // Origin and camera follow the opened drawing, not each rebuild of it (files added, a design),
    // so the view stays where it was.
    val origin = remember(viewKey) { bounds.center }
    val scene = remember(project) { Scene.build(project, origin) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Saved so rotating the device keeps the current zoom and position.
    var camera by rememberSaveable(viewKey, saver = CameraSaver) { mutableStateOf<Camera?>(null) }
    // Bumped on every gesture so the Canvas redraws; the camera itself is mutated in place.
    var frame by remember { mutableStateOf(0) }

    // Sharper terrain for the visible area once zoomed past the overview image's resolution.
    var detail by remember(project) { mutableStateOf<List<TerrainImage>>(emptyList()) }
    LaunchedEffect(project, frame, size, hiddenLayers) {
        val cam = camera ?: return@LaunchedEffect
        if (size.width <= 0 || terrain.isEmpty()) return@LaunchedEffect
        delay(DETAIL_DELAY_MS) // waits for the gesture to settle
        val overviewPx = terrain.minOf { it.bounds.width / it.image.width }
        if (overviewPx * cam.scale < 1.5) {
            detail = emptyList()
            return@LaunchedEffect
        }
        // Visible area plus a margin, so short pans stay sharp.
        val halfW = size.width / cam.scale / 2 * DETAIL_MARGIN
        val halfH = size.height / cam.scale / 2 * DETAIL_MARGIN
        val cx = cam.centerX + origin.x
        val cy = cam.centerY + origin.y
        val region = Bounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        val cols = (size.width * DETAIL_MARGIN).toInt().coerceAtMost(DETAIL_MAX_PX)
        val rows = (size.height * DETAIL_MARGIN).toInt().coerceAtMost(DETAIL_MAX_PX)
        val job = coroutineContext.job
        detail = withContext(Dispatchers.Default) {
            project.surfaces.filter { it.name !in hiddenLayers }
                .mapNotNull { TerrainImage.detail(it, region, cols, rows) { !job.isActive } }
        }
    }

    // Contours of the visible area, rebuilt once a gesture settles.
    var contours by remember(project) { mutableStateOf<ContourPaths?>(null) }
    LaunchedEffect(project, frame, size, hiddenLayers, contourInterval) {
        if (contourInterval == null) { contours = null; return@LaunchedEffect }
        val cam = camera ?: return@LaunchedEffect
        if (size.width <= 0) return@LaunchedEffect
        delay(DETAIL_DELAY_MS)
        val halfW = size.width / cam.scale / 2 * DETAIL_MARGIN
        val halfH = size.height / cam.scale / 2 * DETAIL_MARGIN
        val cx = cam.centerX + origin.x
        val cy = cam.centerY + origin.y
        val region = Bounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        val surfaces = project.surfaces.filter { it.name !in hiddenLayers }
        val job = coroutineContext.job
        contours = withContext(Dispatchers.Default) { ContourPaths.build(surfaces, region, contourInterval, origin) { !job.isActive } }
        onContours(contours?.interval)
    }

    LaunchedEffect(frame, size) {
        val cam = camera ?: return@LaunchedEffect
        if (size.width <= 0) return@LaunchedEffect
        delay(DETAIL_DELAY_MS)
        val halfW = size.width / cam.scale / 2
        val halfH = size.height / cam.scale / 2
        onViewport(Bounds(cam.centerX + origin.x - halfW, cam.centerY + origin.y - halfH, cam.centerX + origin.x + halfW, cam.centerY + origin.y + halfH))
    }

    LaunchedEffect(project, size) {
        if (camera == null && size.width > 0 && size.height > 0) {
            val w = maxOf(bounds.width, 1.0)
            val h = maxOf(bounds.height, 1.0)
            camera = Camera(0.0, 0.0, 0.9 * min(size.width / w, size.height / h))
        }
    }

    LaunchedEffect(zoom) {
        val z = zoom ?: return@LaunchedEffect
        val cam = camera ?: return@LaunchedEffect
        if (z.factor == 0.0) {
            cam.centerX = bounds.center.x - origin.x
            cam.centerY = bounds.center.y - origin.y
            cam.scale = 0.9 * min(size.width / maxOf(bounds.width, 1.0), size.height / maxOf(bounds.height, 1.0))
        } else cam.scale = (cam.scale * z.factor).coerceIn(1e-4, 1e4)
        frame++
    }

    LaunchedEffect(focus, focusKey, size) {
        val f = focus ?: return@LaunchedEffect
        val cam = camera ?: return@LaunchedEffect
        cam.centerX = f.x - origin.x
        cam.centerY = f.y - origin.y
        // Close enough to read stakes and point labels.
        cam.scale = maxOf(cam.scale, 2.0)
        frame++
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
            .clipToBounds()
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
            .pointerInput(project, snap, hiddenLayers, points, onPointTap != null, selectLine, notes, onNoteTap != null) {
                detectTapGestures(
                    // Double tap zooms in 2x around the finger, handy with one hand.
                    onDoubleTap = { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val before = toLocal(offset, cam)
                        cam.scale = (cam.scale * 2).coerceIn(1e-4, 1e4)
                        cam.centerX = before.x - (offset.x - size.width / 2.0) / cam.scale
                        cam.centerY = before.y + (offset.y - size.height / 2.0) / cam.scale
                        frame++
                    },
                    onTap = { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val local = toLocal(offset, cam)
                        val world = Point2(local.x + origin.x, local.y + origin.y)
                        val radius = SNAP_DP * density / cam.scale
                        val showPoints = POINTS_LAYER !in hiddenLayers
                        if (!snap && onNoteTap != null && NOTES_LAYER !in hiddenLayers) {
                            notes.minByOrNull { hypot(it.x - world.x, it.y - world.y) }?.takeIf { hypot(it.x - world.x, it.y - world.y) <= radius }?.let {
                                onNoteTap(it); return@detectTapGestures
                            }
                        }
                        val hit = if (!snap && onPointTap != null && showPoints) nearestPoint(points, world, radius) else null
                        if (selectLine) {
                            PointGen.nearestLine(project, world, radius * 1.5, hiddenLayers)?.let(onLineSelected)
                            return@detectTapGestures
                        }
                        when {
                            hit != null -> onPointTap!!(hit)
                            snap -> onTap(snapTarget(project, if (showPoints) points else emptyList(), world, radius, hiddenLayers) ?: world)
                            else -> onTap(world)
                        }
                    },
                )
            },
    ) {
        frame // read so gestures trigger a redraw
        drawRect(colors.background)
        val cam = camera ?: return@Canvas
        val s = cam.scale.toFloat()

        if (basemap != null) {
            basemap.version // redraw as tiles arrive
            val halfW = size.width / 2.0 / cam.scale; val halfH = size.height / 2.0 / cam.scale
            val cx = cam.centerX + origin.x; val cy = cam.centerY + origin.y
            val tiles = basemap.visible(Bounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH), 1 / cam.scale, basemapZone, basemapSouth)
            drawBasemap(tiles, basemap.opacity) { toScreen(it, cam) }
        }

        for (img in project.images) {
            if (img.layer !in hiddenLayers) drawRaster(img, rasters[img.file], colors) { toScreen(it, cam) }
        }
        // The picture laid over the surfaces covers only the terrain: it is drawn in the same layer
        // with SrcAtop, so it shows where the terrain images have pixels and nowhere else.
        if (drape != null) drawContext.canvas.saveLayer(Rect(Offset.Zero, this.size), androidx.compose.ui.graphics.Paint())
        for (t in terrain) if (t.name !in hiddenLayers) drawTerrain(t) { toScreen(it, cam) }
        for (t in detail) if (t.name !in hiddenLayers) drawTerrain(t) { toScreen(it, cam) }
        if (drape != null) {
            drawTerrain(TerrainImage(drape.name, drape.bounds, drape.image), BlendMode.SrcAtop) { toScreen(it, cam) }
            drawContext.canvas.restore()
        }
        if (overlay != null) drawTerrain(overlay, alpha = 0.9f) { toScreen(it, cam) }

        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(s, -s, pivot = Offset.Zero)
            translate(-cam.centerX.toFloat(), -cam.centerY.toFloat())
        }) {
            // The triangle mesh only helps once zoomed in; from afar it hides the shaded terrain.
            if (cam.scale >= MESH_MIN_SCALE) scene.mesh?.let { drawPath(it, colors.mesh, style = Stroke(width = 1f / s)) }
            // 3D models seen from above: their projection filled, a little see-through.
            for ((layer, color, path) in scene.models) {
                if (layer in hiddenLayers) continue
                drawPath(path, Color(0xFF000000.toInt() or color).copy(alpha = 0.55f))
                drawPath(path, Color(0xFF000000.toInt() or color).copy(alpha = 0.9f), style = Stroke(width = 0.6f / s))
            }
            fun Style.paint() = (if (byLayer) layerColor(layer) else color)?.let { Color(it) } ?: colors.drawing
            for ((key, path) in scene.hatches) {
                if (key.layer in hiddenLayers) continue
                drawPath(path, key.paint().copy(alpha = 0.45f))
            }
            for ((key, path) in scene.drawing) {
                if (key.layer in hiddenLayers) continue
                drawPath(path, key.paint(), style = Stroke(width = 1.5f / s))
            }
            contours?.let { c ->
                drawPath(c.minor, colors.contour.copy(alpha = 0.8f), style = Stroke(width = 1f / s))
                drawPath(c.major, colors.contourMajor, style = Stroke(width = 2f / s))
            }
            for (path in scene.alignments) drawPath(path, colors.alignment, style = Stroke(width = 3f / s))
        }
        contours?.let { c -> drawContourLabels(c, cam, colors) { toScreen(it, cam) } }

        drawTexts(project.texts, hiddenLayers, layerColor, cam, colors) { toScreen(it, cam) }
        drawStations(project.alignments, cam, colors) { toScreen(it, cam) }
        drawPoints(project, cam, colors) { toScreen(it, cam) }
        if (POINTS_LAYER !in hiddenLayers) drawCogoPoints(points, selectedPoint, layerColor(POINTS_LAYER), cam, colors) { toScreen(it, cam) }

        for ((k, l) in designLines.withIndex()) {
            if (l.size < 2) continue
            val path = Path()
            l.forEachIndexed { i, p -> val sp = toScreen(p, cam); if (i == 0) path.moveTo(sp.x, sp.y) else path.lineTo(sp.x, sp.y) }
            // Edge red, daylight green, benches blue.
            val c = when (k) { 0 -> Color(0xFFD32F2F); 1 -> Color(0xFF2E7D32); else -> Color(0xFF1565C0) }
            drawPath(path, c, style = Stroke(width = if (k < 2) 3f else 2f))
        }
        selectedLine?.let { ref ->
            PointGen.path(project, ref)?.let { pth ->
                val path = Path()
                for (i in pth.xs.indices) { val sp = toScreen(Point2(pth.xs[i], pth.ys[i]), cam); if (i == 0) path.moveTo(sp.x, sp.y) else path.lineTo(sp.x, sp.y) }
                if (pth.closed) path.close()
                drawPath(path, Color(0xFFFF6D00), style = Stroke(width = 6f))
            }
        }
        fun drawPolyline(l: Polyline, c: Color, w: Float) {
            if (l.xs.size < 2) return
            val path = Path()
            for (i in l.xs.indices) { val sp = toScreen(Point2(l.xs[i], l.ys[i]), cam); if (i == 0) path.moveTo(sp.x, sp.y) else path.lineTo(sp.x, sp.y) }
            if (l.closed) path.close()
            drawPath(path, c, style = Stroke(width = w))
        }
        for (i in selectedLines) project.polylines.getOrNull(i)?.let { drawPolyline(it, Color(0xFFFF6D00), 6f) }
        for (l in previewLines) drawPolyline(l, Color(0xFF00E5FF), 3f)
        if (previewPoints.isNotEmpty()) {
            val r = 3.sp.toPx()
            for (p in previewPoints) {
                val sp = toScreen(p, cam)
                if (sp.x < -10 || sp.y < -10 || sp.x > size.width + 10 || sp.y > size.height + 10) continue
                drawCircle(Color(0xFF00E5FF), radius = r, center = sp)
            }
        }
        if (measure.isNotEmpty()) drawMeasure(measure) { toScreen(it, cam) }
        if (sectionLine != null) {
            val a = toScreen(sectionLine.first, cam)
            val b = toScreen(sectionLine.second, cam)
            drawLine(SECTION_LINE_COLOR, a, b, strokeWidth = 4f)
            drawCircle(SECTION_LINE_COLOR, 5.sp.toPx(), (a + b) / 2f)
        }

        if (NOTES_LAYER !in hiddenLayers) for (n in notes) {
            val c = toScreen(Point2(n.x, n.y), cam)
            val r = 7.sp.toPx()
            // Pin: a dot on a short stem, purple, with a camera mark when it has a photo.
            drawLine(Color(0xFF6A1B9A), c, c - Offset(0f, r * 2.2f), strokeWidth = 3f)
            drawCircle(Color.White, r + 2f, c - Offset(0f, r * 2.6f))
            drawCircle(Color(0xFF8E24AA), r, c - Offset(0f, r * 2.6f))
            if (n.photo != null) drawCircle(Color.White, r * 0.35f, c - Offset(0f, r * 2.6f))
        }
        if (gps != null) {
            val g = toScreen(gps, cam)
            gpsAccuracy?.let { acc -> drawCircle(gpsColor.copy(alpha = 0.18f), radius = maxOf((acc * cam.scale).toFloat(), 4f), center = g) }
            drawCircle(Color.White, radius = 9.sp.toPx(), center = g)
            drawCircle(gpsColor, radius = 7.sp.toPx(), center = g)
        }

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

/**
 * Draws a drawing image: the picture, when loaded, mapped onto its plan position and clipped to its
 * boundary; otherwise a dashed outline with the file name.
 */
private val basemapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
private val basemapMatrix = android.graphics.Matrix()

private fun DrawScope.drawBasemap(tiles: List<BasemapTile>, opacity: Float, toScreen: (Point2) -> Offset) {
    val canvas = drawContext.canvas.nativeCanvas
    // Tiles drawn opaque in one layer faded as a whole, so their slight overlap shows no seams.
    canvas.saveLayerAlpha(0f, 0f, size.width, size.height, (opacity * 255).roundToInt().coerceIn(0, 255))
    val src = FloatArray(8); val dst = FloatArray(8)
    for (t in tiles) {
        val r = t.src
        src[0] = r.left.toFloat(); src[1] = r.top.toFloat(); src[2] = r.right.toFloat(); src[3] = r.top.toFloat()
        src[4] = r.right.toFloat(); src[5] = r.bottom.toFloat(); src[6] = r.left.toFloat(); src[7] = r.bottom.toFloat()
        val c = t.corners
        for ((i, p) in listOf(c.nw, c.ne, c.se, c.sw).withIndex()) { val o = toScreen(p); dst[2 * i] = o.x; dst[2 * i + 1] = o.y }
        // Grow the quad by about a pixel so neighbours overlap instead of leaving a hairline.
        val mx = (dst[0] + dst[2] + dst[4] + dst[6]) / 4; val my = (dst[1] + dst[3] + dst[5] + dst[7]) / 4
        for (i in 0 until 4) {
            val dx = dst[2 * i] - mx; val dy = dst[2 * i + 1] - my
            val d = kotlin.math.hypot(dx, dy)
            if (d > 1f) { dst[2 * i] += dx / d * 0.8f; dst[2 * i + 1] += dy / d * 0.8f }
        }
        basemapMatrix.setPolyToPoly(src, 0, dst, 0, 4)
        val cropped = r.left != 0 || r.top != 0 || r.right != t.bitmap.width || r.bottom != t.bitmap.height
        if (!cropped) { canvas.drawBitmap(t.bitmap, basemapMatrix, basemapPaint); continue }
        canvas.save()
        // Clip to the tile so a cropped coarser tile does not spill over its neighbours.
        val clip = android.graphics.Path().apply { moveTo(dst[0], dst[1]); lineTo(dst[2], dst[3]); lineTo(dst[4], dst[5]); lineTo(dst[6], dst[7]); close() }
        canvas.clipPath(clip)
        canvas.drawBitmap(t.bitmap, basemapMatrix, basemapPaint)
        canvas.restore()
    }
    canvas.restore()
}

private fun DrawScope.drawRaster(img: PlanImage, bitmap: ImageBitmap?, colors: PlanColors, toScreen: (Point2) -> Offset) {
    val outline = android.graphics.Path()
    val b = img.boundary
    for (i in b.xs.indices) {
        val p = toScreen(Point2(b.xs[i], b.ys[i]))
        if (i == 0) outline.moveTo(p.x, p.y) else outline.lineTo(p.x, p.y)
    }
    outline.close()
    val canvas = drawContext.canvas.nativeCanvas
    if (bitmap != null) {
        // Picture corners: upper-left, upper-right and lower-left of the full image in plan.
        val w = img.widthPx.toDouble()
        val h = img.heightPx.toDouble()
        fun corner(px: Double, py: Double) = toScreen(Point2(
            img.originX + img.pixelU.x * px + img.pixelV.x * (h - py),
            img.originY + img.pixelU.y * px + img.pixelV.y * (h - py),
        ))
        val tl = corner(0.0, 0.0); val tr = corner(w, 0.0); val bl = corner(0.0, h)
        val matrix = android.graphics.Matrix()
        val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
        matrix.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, 0f, bh), 0, floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y), 0, 3)
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawBitmap(bitmap.asAndroidBitmap(), matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    } else {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = colors.drawing.copy(alpha = 0.6f).toArgb()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 10f), 0f)
        }
        canvas.drawPath(outline, paint)
        val first = toScreen(Point2(b.xs[0], b.ys[0]))
        val label = Paint().apply { isAntiAlias = true; textSize = 12.sp.toPx(); color = paint.color }
        canvas.drawText("Imagem: ${img.fileName}", first.x + 8f, first.y - 8f, label)
    }
}

/** Draws only the part of the terrain image that is on screen, so deep zoom stays cheap. */
private fun DrawScope.drawTerrain(t: TerrainImage, blendMode: BlendMode = BlendMode.SrcOver, alpha: Float = 1f, toScreen: (Point2) -> Offset) {
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
        blendMode = blendMode,
        alpha = alpha,
    )
}

/** CAD layer and color (null is the foreground color) that share one path. */
private data class Style(val layer: String, val color: Int?, val byLayer: Boolean)

private class Scene(
    val mesh: Path?,
    val drawing: Map<Style, Path>,
    val hatches: Map<Style, Path>,
    val alignments: List<Path>,
    /** Per model element: layer, colour and the plan path of its triangles. */
    val models: List<Triple<String, Int, Path>> = emptyList(),
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
            val models = project.models.map { m ->
                val q = m.positions; val t = m.triangles
                val path = Path()
                for (k in 0 until t.size / 3) {
                    val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
                    path.moveTo((q[3 * a] - origin.x).toFloat(), (q[3 * a + 1] - origin.y).toFloat())
                    path.lineTo((q[3 * b] - origin.x).toFloat(), (q[3 * b + 1] - origin.y).toFloat())
                    path.lineTo((q[3 * c] - origin.x).toFloat(), (q[3 * c + 1] - origin.y).toFloat())
                    path.close()
                }
                Triple(m.layer, m.color, path)
            }
            return Scene(mesh, drawing, hatches, alignments, models)
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
                    drawContext.canvas.nativeCanvas.drawHaloText(
                        StationFormat.estaca(sta), sp.x + n.x * 1.4f + 4f, sp.y + n.y * 1.4f, paint,
                    )
                }
            }
            sta += step
        }
    }
}

/** Text with a thin outline in the opposite tone, so labels stay readable over light and dark terrain alike. */
private fun android.graphics.Canvas.drawHaloText(text: String, x: Float, y: Float, paint: Paint) {
    val fill = paint.color
    val light = android.graphics.Color.luminance(fill) > 0.5f
    val style = paint.style
    val width = paint.strokeWidth
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = paint.textSize / 4f
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = if (light) 0xB0000000.toInt() else 0xC0FFFFFF.toInt()
    drawText(text, x, y, paint)
    paint.style = style
    paint.strokeWidth = width
    paint.color = fill
    drawText(text, x, y, paint)
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
        if (showNames) drawContext.canvas.nativeCanvas.drawHaloText(pt.name, sp.x + r * 1.5f, sp.y - r * 1.5f, paint)
    }
}

/** Snap radius for measuring, in dp. */
private const val SNAP_DP = 24f

private val MEASURE_COLOR = Color(0xFF00B8D4)
private val SECTION_LINE_COLOR = Color(0xFFFFD600)

/**
 * Nearest COGO point, drawing vertex or alignment vertex within [radius] metres of [p], or null.
 * Hidden layers are skipped.
 */
private fun snapTarget(project: LandXmlProject, cogo: List<CogoPoint>, p: Point2, radius: Double, hiddenLayers: Set<String>): Point2? {
    var best: Point2? = null
    var bestD = radius
    fun consider(x: Double, y: Double) {
        val d = hypot(x - p.x, y - p.y)
        if (d < bestD) {
            bestD = d
            best = Point2(x, y)
        }
    }
    for (pt in project.cgPoints) consider(pt.position.x, pt.position.y)
    for (pt in cogo) consider(pt.x, pt.y)
    for (l in project.polylines) {
        if (l.layer in hiddenLayers) continue
        for (i in l.xs.indices) consider(l.xs[i], l.ys[i])
    }
    for (a in project.alignments) for (v in a.vertices) consider(v.x, v.y)
    return best
}

/** Measurement line, its closing edge and fill from 3 points on, vertices and leg lengths. */
private fun DrawScope.drawMeasure(points: List<Point2>, toScreen: (Point2) -> Offset) {
    val screen = points.map(toScreen)
    if (screen.size >= 3) {
        val fill = Path().apply {
            moveTo(screen[0].x, screen[0].y)
            for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
            close()
        }
        drawPath(fill, MEASURE_COLOR.copy(alpha = 0.18f))
        drawLine(
            MEASURE_COLOR, screen.last(), screen[0], strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
        )
    }
    for (i in 1 until screen.size) drawLine(MEASURE_COLOR, screen[i - 1], screen[i], strokeWidth = 4f)
    val label = Paint().apply {
        isAntiAlias = true
        textSize = 12.sp.toPx()
        color = MEASURE_COLOR.toArgb()
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    }
    for (i in 1 until screen.size) {
        val mid = (screen[i - 1] + screen[i]) / 2f
        val d = hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        drawContext.canvas.nativeCanvas.drawText(StationFormat.decimal(d) + " m", mid.x + 6f, mid.y - 6f, label)
    }
    for ((i, sp) in screen.withIndex()) {
        drawCircle(Color.White, radius = 6.sp.toPx(), center = sp)
        drawCircle(MEASURE_COLOR, radius = 6.sp.toPx(), center = sp, style = Stroke(width = 3f))
        if (i == 0) drawCircle(MEASURE_COLOR, radius = 3.sp.toPx(), center = sp)
    }
}

private fun nearestPoint(points: List<CogoPoint>, p: Point2, radius: Double): CogoPoint? =
    points.minByOrNull { hypot(it.x - p.x, it.y - p.y) }?.takeIf { hypot(it.x - p.x, it.y - p.y) <= radius }

private val COGO_COLOR = Color(0xFFFFA000)

/** COGO points as a cross in a circle, labelled with number, description and elevation when zoomed in. */
private fun DrawScope.drawCogoPoints(
    points: List<CogoPoint>,
    selected: CogoPoint?,
    layerColor: Int?,
    cam: Camera,
    colors: PlanColors,
    toScreen: (Point2) -> Offset,
) {
    if (points.isEmpty()) return
    val color = layerColor?.let { Color(it) } ?: COGO_COLOR
    val labels = points.size <= 3000 && cam.scale > 0.4
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 11.sp.toPx()
        this.color = color.toArgb()
        setShadowLayer(3f, 0f, 0f, colors.background.toArgb())
    }
    val r = 5.sp.toPx()
    val canvas = drawContext.canvas.nativeCanvas
    val taken = ArrayList<android.graphics.RectF>()
    for (pt in points) {
        val sp = toScreen(Point2(pt.x, pt.y))
        if (sp.x < -100f || sp.y < -60f || sp.x > size.width + 20f || sp.y > size.height + 60f) continue
        drawCircle(color, radius = r, center = sp, style = Stroke(width = 2f))
        drawLine(color, sp - Offset(r * 1.6f, 0f), sp + Offset(r * 1.6f, 0f), strokeWidth = 1.5f)
        drawLine(color, sp - Offset(0f, r * 1.6f), sp + Offset(0f, r * 1.6f), strokeWidth = 1.5f)
        if (pt == selected) drawCircle(colors.marker, radius = r * 2.2f, center = sp, style = Stroke(width = 3f))
        if (labels && taken.size < 400) {
            val x = sp.x + r * 1.8f
            val line2 = listOfNotNull(pt.description.takeIf { it.isNotEmpty() }, pt.z.takeIf { !it.isNaN() }?.let { StationFormat.decimal(it, 3) })
                .joinToString("  ")
            val box = android.graphics.RectF(x, sp.y - r * 0.6f - paint.textSize, x + maxOf(paint.measureText(pt.number), paint.measureText(line2)),
                sp.y + paint.textSize * 1.3f)
            // A label that would cover one already drawn is left out; zooming in brings it back.
            if (taken.none { android.graphics.RectF.intersects(it, box) }) {
                taken += box
                canvas.drawText(pt.number, x, sp.y - r * 0.6f, paint)
                if (line2.isNotEmpty()) canvas.drawText(line2, x, sp.y + paint.textSize, paint)
            }
        }
    }
}


/** Contours ready to draw: thin and thick (every fifth) lines relative to the plan origin, and label spots. */
class ContourPaths(val interval: Double, val minor: Path, val major: Path, val labels: List<Pair<Point2, Double>>) {
    companion object {
        /** Null when cancelled or too dense; the interval comes from the zoom when [wanted] is 0. */
        fun build(surfaces: List<com.appdwg.core.Surface>, region: Bounds, wanted: Double, origin: Point2, cancelled: () -> Boolean): ContourPaths? {
            if (surfaces.isEmpty()) return null
            val interval = if (wanted > 0) wanted else {
                // Relief of the view estimated from each surface's range and the share of it in view.
                val relief = surfaces.maxOf { s ->
                    val b = s.bounds ?: return@maxOf 0.0
                    val range = (s.maxElevation ?: 0.0) - (s.minElevation ?: 0.0)
                    val share = kotlin.math.sqrt((region.width * region.height / (b.width * b.height)).coerceAtMost(1.0))
                    range * share
                }
                com.appdwg.core.Contours.autoInterval(relief)
            }
            val minor = Path(); val major = Path()
            val labels = ArrayList<Pair<Point2, Double>>()
            var segments = 0
            for (s in surfaces) {
                val levels = com.appdwg.core.Contours.segments(s, interval, region, maxSegments = 400_000 - segments, cancelled = cancelled) ?: return null
                for (lv in levels) {
                    val isMajor = Math.round(lv.elevation / interval) % 5 == 0L
                    val path = if (isMajor) major else minor
                    val seg = lv.segments
                    for (i in 0 until lv.count) {
                        path.moveTo((seg[4 * i] - origin.x).toFloat(), (seg[4 * i + 1] - origin.y).toFloat())
                        path.lineTo((seg[4 * i + 2] - origin.x).toFloat(), (seg[4 * i + 3] - origin.y).toFloat())
                    }
                    if (isMajor && lv.count > 0) {
                        // A few spots per level, spread along its segments.
                        val step = maxOf(1, lv.count / 6)
                        var i = lv.count / 2 % step
                        while (i < lv.count && labels.size < 400) {
                            labels += Point2(seg[4 * i], seg[4 * i + 1]) to lv.elevation
                            i += step
                        }
                    }
                    segments += lv.count
                }
            }
            return ContourPaths(interval, minor, major, labels)
        }
    }
}

private fun DrawScope.drawContourLabels(c: ContourPaths, cam: Camera, colors: PlanColors, toScreen: (Point2) -> Offset) {
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 10.sp.toPx()
        color = colors.contourMajor.toArgb()
        setShadowLayer(3f, 0f, 0f, colors.background.toArgb())
    }
    val digits = if (c.interval < 1) 2 else 0
    var lastX = Float.NaN; var lastY = Float.NaN
    for ((p, z) in c.labels) {
        val sp = toScreen(p)
        if (sp.x < 0 || sp.y < 0 || sp.x > size.width || sp.y > size.height) continue
        // Skips labels crowding the previous one.
        if (!lastX.isNaN() && kotlin.math.abs(sp.x - lastX) < 40 && kotlin.math.abs(sp.y - lastY) < 20) continue
        drawContext.canvas.nativeCanvas.drawText(StationFormat.decimal(z, digits), sp.x, sp.y, paint)
        lastX = sp.x; lastY = sp.y
    }
}
