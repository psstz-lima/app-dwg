package com.appdwg.app

import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.Ray
import com.appdwg.core.SnapTargets
import com.appdwg.core.Surface
import com.appdwg.core.TinMeasure
import com.appdwg.core.ViewRay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D view of the surfaces and 3D lines, drawn with OpenGL ES 3. One finger orbits around the
 * target, two fingers pan, pinch zooms. A short tap, while a measure or area tool is active,
 * drops a vertex on the TIN.
 */
@Composable
fun View3D(
    project: LandXmlProject,
    hiddenLayers: Set<String>,
    layerColor: (String) -> Int?,
    background: Color,
    foreground: Color,
    exaggeration: Float,
    /** Picture laid over the surfaces, or null. */
    drape: Drape?,
    modifier: Modifier = Modifier,
    /** Display style of each surface: fill by elevation or shading, mesh and border lines. */
    surfaceStyle: (String) -> SurfaceStyle = { SurfaceStyle() },
    /** Short tap adds a vertex. Null while no measure or area tool is active. */
    onTap: ((Point2) -> Unit)? = null,
    /** Snap that tap to COGO points, polyline vertices and alignment vertices, in screen space. */
    snap: Boolean = false,
    userPoints: List<CogoPoint> = emptyList(),
    /** World xyz pairs of the measure line, draped on the TIN. */
    overlayLines: DoubleArray = DoubleArray(0),
    /** World xyz of the highlighted polygon facets, nine doubles per triangle. */
    overlayTris: DoubleArray = DoubleArray(0),
    /** Placed drainage solids, world xyz, nine doubles per triangle. Drawn opaque in the draped view. */
    solidTris: DoubleArray = DoubleArray(0),
    /** Device triangles already split by finish colour (0xRRGGBB). Used instead of [solidTris] when present. */
    coloredSolids: List<Pair<Int, DoubleArray>> = emptyList(),
    /**
     * More drainage layers. Colour is 0xRRGGBB (opaque, drawn with the device) or 0xAARRGGBB
     * with alpha 1–254 (earthwork, drawn see-through so the device stays visible).
     */
    extraSolids: List<Pair<DoubleArray, Int>> = emptyList(),
    /** Terrain fill opacity. Below 1 the ground is a cutaway so buried devices stay visible. */
    terrainAlpha: Float = 1f,
    /** The GPU refused the picture. The caller steps the quality down and tries again. */
    onDrapeFailed: () -> Unit = {},
    /** True while tiles sharper than the overview are still loading. */
    onDrapeRefining: (Boolean) -> Unit = {},
) {
    val scene = remember(project) { Scene3D.build(project) }
    val renderer = remember(project) { Renderer3D(scene) }
    // Holder for the GL view, so gestures and settings can ask it to redraw.
    val holder = remember(project) { arrayOfNulls<GLSurfaceView>(1) }
    val context = LocalContext.current
    val textureBudget = remember(context) {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        if (manager == null) DrapeLod.budgetBytes(4L shl 30, 192)
        else {
            val info = android.app.ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            DrapeLod.budgetBytes(info.totalMem, manager.memoryClass)
        }
    }
    renderer.textureBudget = textureBudget
    renderer.onNeedsFrame = { holder[0]?.requestRender() }

    // An edited surface stands in for its original, which would fill its holes.
    val styles = project.surfaces.associate { sf ->
        sf.name to if (project.surfaces.any { it.name == sf.name + EDITED_SUFFIX }) SurfaceStyle(triangles = false, elevation = ElevationPaint.NONE)
        else surfaceStyle(sf.name)
    }
    LaunchedEffect(hiddenLayers, layerColor, background, foreground, exaggeration, styles) {
        renderer.update(hiddenLayers, layerColor, background.toArgb(), foreground.toArgb(), exaggeration, styles)
        holder[0]?.requestRender()
    }
    LaunchedEffect(drape) {
        renderer.setDrape(drape)
        holder[0]?.requestRender()
    }
    LaunchedEffect(overlayLines, overlayTris) {
        renderer.setOverlay(sceneFloats(overlayLines, scene), sceneFloats(overlayTris, scene))
        holder[0]?.requestRender()
    }
    LaunchedEffect(solidTris, coloredSolids, extraSolids) {
        val packed = withContext(Dispatchers.Default) {
            val opaque = ArrayList<FloatArray>()
            val earth = ArrayList<FloatArray>()
            if (coloredSolids.isNotEmpty()) {
                for ((rgb, tris) in coloredSolids) {
                    if (tris.size < 9) continue
                    opaque += litSolid(tris, scene, ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
                }
            } else if (solidTris.size >= 9) {
                opaque += litSolid(solidTris, scene, 0.62f, 0.62f, 0.58f)
            }
            for ((tris, rgb) in extraSolids) {
                if (tris.size < 9) continue
                val mesh = litSolid(tris, scene, ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
                val alpha = (rgb ushr 24) and 0xFF
                if (alpha in 1..254) earth += mesh else opaque += mesh
            }
            joinFloats(opaque) to joinFloats(earth)
        }
        renderer.setSolid(packed.first, packed.second)
        holder[0]?.requestRender()
    }
    LaunchedEffect(terrainAlpha) {
        renderer.terrainAlpha = terrainAlpha
        holder[0]?.requestRender()
    }
    val failState = rememberUpdatedState(onDrapeFailed)
    val refiningState = rememberUpdatedState(onDrapeRefining)
    val tapState = rememberUpdatedState(onTap)
    val snapState = rememberUpdatedState(snap)
    val projectState = rememberUpdatedState(project)
    val hiddenState = rememberUpdatedState(hiddenLayers)
    val pointsState = rememberUpdatedState(userPoints)
    val scope = rememberCoroutineScope()
    renderer.onDrapeFailed = { scope.launch(Dispatchers.Main.immediate) { failState.value() } }
    renderer.onRefining = { refining -> scope.launch(Dispatchers.Main.immediate) { refiningState.value(refining) } }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    val density = LocalDensity.current.density
    fun pick(x: Float, y: Float) {
        val tap = tapState.value ?: return
        scope.launch(Dispatchers.Default) {
            val ray = renderer.worldRay(x, y) ?: return@launch
            val surfaces = visibleSurfaces(projectState.value, hiddenState.value)
            val hit = TinMeasure.nearestHit(surfaces, ray) ?: return@launch
            val point = if (snapState.value) {
                val px = 24f * density
                val near = renderer.worldPerPixel() * px * 12.0
                val targets = SnapTargets.near(
                    projectState.value, pointsState.value, hiddenState.value,
                    hit.x, hit.y, near, surfaces.firstOrNull(), hit.z,
                )
                SnapTargets.nearest(targets, x.toDouble(), y.toDouble(), px.toDouble()) { p -> renderer.project(p.x, p.y, p.z) }
                    ?: Point2(hit.x, hit.y)
            } else Point2(hit.x, hit.y)
            withContext(Dispatchers.Main.immediate) { tap(point) }
        }
    }

    AndroidView(
        modifier = modifier.pointerInput(project) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val t0 = down.uptimeMillis
                var lastCentroid: Offset? = null
                var lastSpread = 0f
                var lastCount = 0
                var dragging = false
                var fingers = 1
                var uptime = t0
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    uptime = event.changes.first().uptimeMillis
                    if (pressed.size > fingers) fingers = pressed.size
                    val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                    val spread = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                    if (pressed.size > 1 || (centroid - start).getDistance() > 12f) dragging = true
                    // A change in finger count restarts the reference, so lifting a finger does not jump.
                    if (dragging && pressed.size == lastCount && lastCentroid != null) {
                        val d = centroid - lastCentroid!!
                        if (pressed.size == 1) {
                            renderer.orbit(d.x, d.y)
                        } else {
                            renderer.pan(d.x, d.y)
                            if (lastSpread > 0f && spread > 0f) renderer.zoom(lastSpread / spread)
                        }
                        holder[0]?.requestRender()
                    }
                    lastCentroid = centroid
                    lastSpread = spread
                    lastCount = pressed.size
                    event.changes.forEach { it.consume() }
                }
                // A short tap marks a vertex. A drag still orbits, pans and zooms.
                if (!dragging && fingers == 1 && uptime - t0 <= 400L) pick(start.x, start.y)
            }
        },
        factory = { context ->
            object : GLSurfaceView(context) {
                override fun onDetachedFromWindow() {
                    renderer.close()
                    super.onDetachedFromWindow()
                }
            }.apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 24, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                holder[0] = this
            }
        },
        update = { holder[0] = it },
    )
}

/** Geometry ready for the GPU, relative to the project centre so floats keep precision. */
class Scene3D(
    /** Per surface: name, interleaved position (3), normal (3), color (3); and triangle indices. */
    val surfaces: List<Mesh>,
    /** 3D lines grouped by layer and color, as GL_LINES segments. */
    val lines: List<LineBatch>,
    /** Half of the largest horizontal extent, to place the camera. */
    val radius: Float,
    /** Plan position and elevation of the scene's (0, 0, 0). */
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val originZ: Double = 0.0,
    /** 3D models, flat shaded, named by layer. */
    val models: List<Mesh> = emptyList(),
) {
    /** [edges] and [border] are vertex index pairs for lines over the mesh (surfaces only). */
    class Mesh(val name: String, val vertices: FloatArray, val indices: IntArray,
               val edges: IntArray = IntArray(0), val border: IntArray = IntArray(0))

    class LineBatch(val layer: String, val color: Int?, val byLayer: Boolean, val vertices: FloatArray)

    val isEmpty get() = surfaces.isEmpty() && lines.isEmpty() && models.isEmpty()

    companion object {
        private val STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        private val COLORS = intArrayOf(0x2E7D32, 0x9CCC65, 0xFFEE58, 0xA1887F, 0xF5F5F5)

        fun build(project: LandXmlProject): Scene3D {
            val lines3d = project.polylines.filter { it.zs != null }
            var minZ = Double.POSITIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY
            project.surfaces.forEach { s -> s.minElevation?.let { minZ = minOf(minZ, it) }; s.maxElevation?.let { maxZ = maxOf(maxZ, it) } }
            val surfaceMinZ = minZ
            val surfaceRange = (maxZ - minZ).takeIf { it > 0 } ?: 1.0
            lines3d.forEach { l -> l.zs!!.forEach { minZ = minOf(minZ, it); maxZ = maxOf(maxZ, it) } }
            project.models.forEach { m -> for (i in 0 until m.positions.size / 3) { minZ = minOf(minZ, m.positions[3 * i + 2]); maxZ = maxOf(maxZ, m.positions[3 * i + 2]) } }
            if (minZ > maxZ) { minZ = 0.0; maxZ = 0.0 }

            var bx0 = Double.POSITIVE_INFINITY; var by0 = Double.POSITIVE_INFINITY
            var bx1 = Double.NEGATIVE_INFINITY; var by1 = Double.NEGATIVE_INFINITY
            project.surfaces.forEach { s -> s.bounds?.let { bx0 = minOf(bx0, it.minX); by0 = minOf(by0, it.minY); bx1 = maxOf(bx1, it.maxX); by1 = maxOf(by1, it.maxY) } }
            // Without a terrain the 3D lines frame the view; with one, the terrain does.
            if (project.surfaces.isEmpty()) {
                lines3d.forEach { l -> l.bounds?.let { bx0 = minOf(bx0, it.minX); by0 = minOf(by0, it.minY); bx1 = maxOf(bx1, it.maxX); by1 = maxOf(by1, it.maxY) } }
                project.models.forEach { m ->
                    for (i in 0 until m.positions.size / 3) {
                        bx0 = minOf(bx0, m.positions[3 * i]); bx1 = maxOf(bx1, m.positions[3 * i])
                        by0 = minOf(by0, m.positions[3 * i + 1]); by1 = maxOf(by1, m.positions[3 * i + 1])
                    }
                }
            }
            if (bx0 > bx1) return Scene3D(emptyList(), emptyList(), 1f)
            val ox = (bx0 + bx1) / 2
            val oy = (by0 + by1) / 2
            // Centre on the terrain when there is one: stray lines at elevation 0 would pull it down.
            val oz = if (project.surfaces.isNotEmpty()) surfaceMinZ + surfaceRange / 2 else (minZ + maxZ) / 2

            val meshes = project.surfaces.map { s ->
                val n = s.vertexCount
                val normals = FloatArray(n * 3)
                val t = s.triangles
                for (k in 0 until s.triangleCount) {
                    val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
                    val ux = s.xs[b] - s.xs[a]; val uy = s.ys[b] - s.ys[a]; val uz = s.zs[b] - s.zs[a]
                    val vx = s.xs[c] - s.xs[a]; val vy = s.ys[c] - s.ys[a]; val vz = s.zs[c] - s.zs[a]
                    var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                    // Face normals point up whatever the triangle's winding.
                    if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
                    for (i in intArrayOf(a, b, c)) {
                        normals[3 * i] += nx.toFloat(); normals[3 * i + 1] += ny.toFloat(); normals[3 * i + 2] += nz.toFloat()
                    }
                }
                val v = FloatArray(n * 9)
                for (i in 0 until n) {
                    v[9 * i] = (s.xs[i] - ox).toFloat()
                    v[9 * i + 1] = (s.ys[i] - oy).toFloat()
                    v[9 * i + 2] = (s.zs[i] - oz).toFloat()
                    val len = sqrt(normals[3 * i] * normals[3 * i] + normals[3 * i + 1] * normals[3 * i + 1] + normals[3 * i + 2] * normals[3 * i + 2])
                        .takeIf { it > 0f } ?: 1f
                    v[9 * i + 3] = normals[3 * i] / len
                    v[9 * i + 4] = normals[3 * i + 1] / len
                    v[9 * i + 5] = normals[3 * i + 2] / len
                    val rgb = ramp(((s.zs[i] - surfaceMinZ) / surfaceRange).toFloat())
                    v[9 * i + 6] = ((rgb shr 16) and 0xFF) / 255f
                    v[9 * i + 7] = ((rgb shr 8) and 0xFF) / 255f
                    v[9 * i + 8] = (rgb and 0xFF) / 255f
                }
                // Every edge is drawn only for meshes small enough to stay readable and fast.
                val edges = if (s.triangleCount <= MAX_DRAWN_TRIANGLES) com.appdwg.core.SurfaceEdges.all(s) else IntArray(0)
                Mesh(s.name, v, s.triangles, edges, com.appdwg.core.SurfaceEdges.boundary(s))
            }

            val batches = lines3d.groupBy { Triple(it.layer, it.color, it.byLayer) }.map { (key, group) ->
                val segs = ArrayList<Float>()
                for (l in group) {
                    val zs = l.zs!!
                    val count = if (l.closed) l.xs.size else l.xs.size - 1
                    for (i in 0 until count) {
                        val j = (i + 1) % l.xs.size
                        segs += (l.xs[i] - ox).toFloat(); segs += (l.ys[i] - oy).toFloat(); segs += (zs[i] - oz).toFloat()
                        segs += (l.xs[j] - ox).toFloat(); segs += (l.ys[j] - oy).toFloat(); segs += (zs[j] - oz).toFloat()
                    }
                }
                LineBatch(key.first, key.second, key.third, segs.toFloatArray())
            }
            // Models get one normal per face (vertices repeated), so edges stay crisp.
            val models = project.models.map { m ->
                val t = m.triangles
                val v = FloatArray(t.size * 9)
                val r = ((m.color shr 16) and 0xFF) / 255f; val g = ((m.color shr 8) and 0xFF) / 255f; val bl = (m.color and 0xFF) / 255f
                val p = m.positions
                for (k in 0 until t.size / 3) {
                    val a = t[3 * k]; val b = t[3 * k + 1]; val c = t[3 * k + 2]
                    val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
                    val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
                    var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                    val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
                    nx /= len; ny /= len; nz /= len
                    for ((j, i) in intArrayOf(a, b, c).withIndex()) {
                        val o = (3 * k + j) * 9
                        v[o] = (p[3 * i] - ox).toFloat(); v[o + 1] = (p[3 * i + 1] - oy).toFloat(); v[o + 2] = (p[3 * i + 2] - oz).toFloat()
                        v[o + 3] = nx.toFloat(); v[o + 4] = ny.toFloat(); v[o + 5] = nz.toFloat()
                        v[o + 6] = r; v[o + 7] = g; v[o + 8] = bl
                    }
                }
                Mesh(m.layer, v, IntArray(t.size) { it })
            }
            return Scene3D(meshes, batches, (max(bx1 - bx0, by1 - by0) / 2).toFloat().coerceAtLeast(1f), ox, oy, oz, models)
        }

        private fun ramp(t: Float): Int {
            var i = 0
            while (i < STOPS.size - 2 && t > STOPS[i + 1]) i++
            val f = ((t - STOPS[i]) / (STOPS[i + 1] - STOPS[i])).coerceIn(0f, 1f)
            val a = COLORS[i]
            val b = COLORS[i + 1]
            fun ch(shift: Int): Int {
                val ca = (a shr shift) and 0xFF
                val cb = (b shr shift) and 0xFF
                return (ca + (cb - ca) * f).toInt().coerceIn(0, 255)
            }
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

private class Renderer3D(private val scene: Scene3D) : GLSurfaceView.Renderer {
    // Camera: orbit around (tx, ty, tz) at [distance]; yaw around the vertical, pitch above the horizon.
    @Volatile private var yaw = -Math.PI.toFloat() / 2
    @Volatile private var pitch = 0.6f
    @Volatile private var distance = scene.radius * 2.2f
    @Volatile private var tx = 0f
    @Volatile private var ty = 0f
    @Volatile private var tz = 0f
    @Volatile private var hidden: Set<String> = emptySet()
    @Volatile private var layerColor: (String) -> Int? = { null }
    @Volatile private var background = 0
    @Volatile private var foreground = -1
    @Volatile private var zScale = 1f
    @Volatile private var styles: Map<String, SurfaceStyle> = emptyMap()

    @Volatile private var width = 1
    @Volatile private var height = 1
    private var program = 0
    private val meshBuffers = ArrayList<IntArray>()
    /** Surface triangles by plan cell, so each picture tile draws only what lies under it. */
    private val surfaceChunks: List<MeshChunks> by lazy {
        scene.surfaces.map { MeshChunks.build(it.vertices, 9, it.indices) }
    }
    private val lineBuffers = ArrayList<Int>()
    /** Per surface: element buffers of its edges and of its border. */
    private val edgeBuffers = ArrayList<IntArray>()

    // Picture over the surfaces: set from the UI, uploaded as a texture on the GL thread.
    @Volatile private var drape: Drape? = null
    @Volatile private var drapeDirty = false
    private class GpuTile(val id: Int, val rect: FloatArray)
    @Volatile private var gpuTiles: List<GpuTile> = emptyList()
    var onDrapeFailed: (() -> Unit)? = null
    /** True while refined tiles are still on the way. The base overview is already drawn. */
    var onRefining: ((Boolean) -> Unit)? = null
    @Volatile var textureBudget: Long = DrapeLod.budgetBytes(4L shl 30, 192)
    var onNeedsFrame: (() -> Unit)? = null
    @Volatile private var streaming = false
    private var streamingPicture: TiledPicture? = null
    @Volatile private var uploadsBlocked = false
    @Volatile private var streamGeneration = 0
    @Volatile private var gpuTileSide = DrapeLod.STREAM_TILE
    private val residents = HashMap<String, Resident>()
    /** The overview texture. It is drawn on the first frame and stays under the refined tiles. */
    private val baseTiles = ArrayList<GpuTile>()
    private val ready = ConcurrentLinkedQueue<PendingTile>()
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    // One more thread than decodes, so rebuilding the plan after an orbit never waits behind them.
    private val decoder = Executors.newFixedThreadPool(DrapeLod.DECODES_IN_FLIGHT + 1) { runnable ->
        Thread(runnable, "drape-decode").apply { isDaemon = true }
    }
    private var useStamp = 0L
    private var steppedQuality = -1
    @Volatile private var planCache: DrapeLod.Selection? = null
    @Volatile private var planStamp = FloatArray(6) { Float.NaN }
    @Volatile private var planBusy = false
    private var postedRefining = false
    private var gpuRenderer = ""

    private class Resident(val node: DrapeLod.Node, val tile: GpuTile, val bytes: Long, var lastUsed: Long)

    private class PendingTile(
        val generation: Int,
        val node: DrapeLod.Node,
        val bitmap: android.graphics.Bitmap,
        val alignment: Int,
        val tileX: Int,
        val tileY: Int,
        val tileW: Int,
        val tileH: Int,
        val fullW: Int,
        val fullH: Int,
        val bytes: Long,
    )
    @Volatile private var pendingLines = FloatArray(0)
    @Volatile private var pendingTris = FloatArray(0)
    @Volatile private var pendingSolid = FloatArray(0)
    @Volatile private var pendingEarth = FloatArray(0)
    @Volatile var terrainAlpha = 1f
    @Volatile private var overlayDirty = false
    @Volatile private var solidDirty = false
    private var overlayLineBuf = 0
    private var overlayTriBuf = 0
    private var overlayLineCount = 0
    private var overlayTriCount = 0
    private var solidBuf = 0
    private var solidCount = 0
    private var earthBuf = 0
    private var earthCount = 0

    /** Stops background decodes and planning. Safe to call again after the view is already gone. */
    fun close() {
        if (decoder.isShutdown) return
        streamGeneration++
        onNeedsFrame = null
        onDrapeFailed = null
        onRefining = null
        decoder.shutdownNow()
    }

    fun setDrape(drape: Drape?) {
        val previous = this.drape
        this.drape = drape
        val sameTiles = previous?.tiles != null && previous.tiles === drape?.tiles
        val sameImage = previous?.image === drape?.image
        val sameView = previous?.quality == drape?.quality && previous?.focus == drape?.focus
        // A new overview (the pyramid finishing) is uploaded. A quality change only rebuilds the plan.
        if (!sameTiles || !sameImage) drapeDirty = true
        if (sameTiles && !sameView) {
            planCache = null
            planStamp = FloatArray(6) { Float.NaN }
            // A new quality starts clean: a texture refused at Máxima must not stop Alta from refining.
            uploadsBlocked = false
            failedKeys.clear()
        }
    }

    private var anisotropy = Float.NaN

    /** Sharper on a tilted surface when the driver has the extension. A missing one is left at 1. */
    private fun applyAnisotropy() {
        if (anisotropy.isNaN()) {
            val count = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
            val extensions = if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                emptyList()
            } else {
                List(count[0].coerceAtLeast(0)) { i -> GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i) ?: "" }
            }
            val maxA = FloatArray(1)
            GLES30.glGetFloatv(GlTexture.MAX_TEXTURE_MAX_ANISOTROPY_EXT, maxA, 0)
            val reported = if (GLES30.glGetError() != GLES30.GL_NO_ERROR) 1f else maxA[0]
            anisotropy = GlTexture.anisotropy(extensions, reported)
        }
        if (anisotropy > 1f) {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GlTexture.TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
        }
    }

    private fun deleteTextures(ids: List<Int>) {
        if (ids.isEmpty()) return
        GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
    }

    /**
     * Uploads the draped picture in tiles, on this GL thread. The previous textures stay bound
     * until every new tile has uploaded. A failure keeps them and asks for a lower quality.
     */
    private fun uploadDrape() {
        val snapshot = drape
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
        if (snapshot == null) {
            streaming = false
            uploadsBlocked = false
            streamingPicture = null
            streamGeneration++
            drainReady()
            val previous = gpuTiles
            residents.clear()
            baseTiles.clear()
            gpuTiles = emptyList()
            deleteTextures(previous.map { it.id })
            publishRefining(false)
            if (drape == null) drapeDirty = false
            return
        }
        val picture = snapshot.tiles
        if (picture == null) {
            streaming = false
            uploadsBlocked = false
            streamingPicture = null
            streamGeneration++
            drainReady()
            publishRefining(false)
        }
        val source = snapshot.image.asAndroidBitmap()
        // A 1×1 stand-in is not a picture. Streaming still starts; the real overview arrives later.
        if (picture != null && (source.isRecycled || source.width < 2 || source.height < 2)) {
            armStream()
            return
        }
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            if (drape === snapshot) drapeDirty = false
            return
        }
        val reported = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val maxSize = reported.takeIf { it > 0 } ?: 2048
        val tiles = GlTexture.plan(source.width, source.height, source.hasAlpha(), maxSize)
        if (tiles == null) {
            if (drape === snapshot) {
                drapeDirty = false
                onDrapeFailed?.invoke()
            }
            return
        }
        val built = ArrayList<GpuTile>()
        var failed = false
        try {
            for (tile in tiles) {
                val bitmap = GlTexture.tileBitmap(source, tile) ?: throw OutOfMemoryError()
                try {
                    if (bitmap.isRecycled) throw IllegalStateException("tile recycled")
                    val ids = IntArray(1)
                    GLES30.glGenTextures(1, ids, 0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, tile.unpackAlignment)
                    while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                    if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                        GLES30.glDeleteTextures(1, ids, 0)
                        failed = true
                        break
                    }
                    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                    applyAnisotropy()
                    val placed = GlTexture.placed(
                        snapshot.bounds.minX, snapshot.bounds.maxY, snapshot.bounds.width, snapshot.bounds.height,
                        source.width, source.height, tile,
                    )
                    built += GpuTile(
                        ids[0],
                        floatArrayOf(
                            (placed[0] - scene.originX).toFloat(),
                            (placed[1] - scene.originY).toFloat(),
                            placed[2].toFloat(),
                            placed[3].toFloat(),
                        ),
                    )
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("Gromatic", "textura 3D", e)
            failed = true
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        }
        if (drape !== snapshot) {
            deleteTextures(built.map { it.id })
            return
        }
        if (failed || built.size != tiles.size) {
            deleteTextures(built.map { it.id })
            if (picture != null) {
                armStream()
                return
            }
            drapeDirty = false
            onDrapeFailed?.invoke()
            return
        }
        val old = gpuTiles
        gpuTiles = built
        val samePicture = picture != null && streamingPicture === picture
        val keep = if (samePicture) residents.values.map { it.tile.id }.toSet() else emptySet()
        if (!samePicture) {
            deleteTextures(residents.values.map { it.tile.id }.filter { id -> old.none { it.id == id } })
            residents.clear()
        }
        deleteTextures(old.map { it.id }.filter { it !in keep })
        drapeDirty = false
        if (picture != null) {
            baseTiles.clear()
            baseTiles.addAll(built)
            armStream()
        }
    }

    /**
     * Starts streaming [drape]'s tiles. The overview uploaded just above is already the picture
     * on screen. Decoding is not done here, and the plan is not built here.
     */
    private fun armStream() {
        val picture = drape?.tiles ?: return
        val changed = streamingPicture !== picture
        streamGeneration++
        streaming = true
        uploadsBlocked = false
        queuedKeys.clear()
        failedKeys.clear()
        drainReady()
        if (changed) {
            residents.clear()
            streamingPicture = picture
            planCache = null
            planStamp = FloatArray(6) { Float.NaN }
        }
        val reported = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val maxSize = reported.takeIf { it > 0 } ?: 2048
        gpuTileSide = min(DrapeLod.STREAM_TILE, GlTexture.tileSide(maxSize))
        steppedQuality = -1
        drapeDirty = false
    }

    private fun drainReady() {
        while (true) {
            val pending = ready.poll() ?: break
            if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
        }
    }

    /**
     * Uploads a few tiles that the decoder has finished and asks for the next ones. Runs every
     * frame, so a large orthophoto does not stall this thread or get the process killed for
     * uploading the whole file.
     */
    private fun streamDrape() {
        if (!streaming) return
        val snapshot = drape
        val picture = snapshot?.tiles ?: return
        val generation = streamGeneration
        // A plan whose near tiles do not all fit is still drawn: the budget keeps the farther ones
        // coarser. Only the GPU refusing a texture steps the quality down, never the plan alone.
        val plan = planCache
        var uploaded = 0
        var failed = false
        try {
            val uploads = min(DrapeLod.uploadsPerFrame(gpuRenderer), DrapeLod.UPLOADS_PER_FRAME)
            while (uploaded < uploads) {
                val pending = ready.poll() ?: break
                if (pending.generation != generation) {
                    if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
                    continue
                }
                val id = uploadPending(
                    pending.bitmap,
                    pending.alignment,
                    DrapeLod.mipmap(pending.bitmap.width, pending.bitmap.height, base = false),
                )
                if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
                if (id == null) {
                    failed = true
                    break
                }
                val placed = GlTexture.placed(
                    worldLeft(picture, pending.node), worldTop(picture, pending.node),
                    pending.node.fileW * picture.bounds.width / picture.width.coerceAtLeast(1),
                    pending.node.fileH * picture.bounds.height / picture.height.coerceAtLeast(1),
                    pending.fullW, pending.fullH,
                    GlTexture.Tile(
                        pending.tileX, pending.tileY, pending.tileW, pending.tileH,
                        GlTexture.PixelFormat.RGB_565, pending.alignment,
                    ),
                )
                val previous = residents.put(
                    pending.node.key,
                    Resident(
                        pending.node,
                        GpuTile(
                            id,
                            floatArrayOf(
                                (placed[0] - scene.originX).toFloat(),
                                (placed[1] - scene.originY).toFloat(),
                                placed[2].toFloat(),
                                placed[3].toFloat(),
                            ),
                        ),
                        pending.bytes,
                        ++useStamp,
                    ),
                )
                if (previous != null) deleteTextures(listOf(previous.tile.id))
                uploaded++
            }
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        }
        if (failed) {
            uploadsBlocked = true
            drainReady()
            if (steppedQuality != snapshot.quality) {
                steppedQuality = snapshot.quality
                onDrapeFailed?.invoke()
            }
        }
        if (plan != null) evictResidents(plan, picture.bytesPerPixel)
        val refined = if (plan == null) {
            residents.values.sortedBy { it.node.level }.take(DrapeLod.DRAW_REFINEMENTS).map { it.tile }
        } else {
            // The plan's tiles, then any older tile still on the GPU (the previous quality, or
            // the view before this one), finest first, so a change never drops back to the overview.
            val planKeys = plan.tiles.mapTo(HashSet()) { it.key }
            val current = plan.tiles.mapNotNull { residents[it.key] }
            val older = residents.values.filter { it.node.key !in planKeys }.sortedBy { it.node.level }
            (current + older).take(DrapeLod.DRAW_REFINEMENTS).map { it.tile }
        }
        // Finer tiles first. The base overview fills everything they do not cover.
        gpuTiles = refined + baseTiles
        val waiting = ready.map { it.node.key }.toSet()
        val inflight = ready.size + queuedKeys.size
        val tick = DrapeLod.tick(
            baseReady = baseTiles.isNotEmpty(),
            residents = residents.keys,
            plan = plan?.tiles ?: emptyList(),
            queued = waiting + queuedKeys,
            slots = (DrapeLod.DECODES_IN_FLIGHT - inflight).coerceAtLeast(0),
            blocked = failedKeys,
        )
        publishRefining(plan != null && tick.refining)
        if (!uploadsBlocked && plan != null) {
            enqueueDecodes(picture, tick.request, generation)
        }
        schedulePlan(snapshot, picture)
        if (!uploadsBlocked && (tick.refining || ready.isNotEmpty())) onNeedsFrame?.invoke()
    }

    /** Rebuilds the tile plan off this thread, and only after the camera has actually moved. */
    private fun schedulePlan(snapshot: Drape, picture: TiledPicture) {
        val sample = floatArrayOf(yaw, pitch, distance, tx, ty, tz)
        if (planCache != null && DrapeLod.cameraSettled(planStamp, sample)) return
        if (planBusy) return
        planBusy = true
        val generation = streamGeneration
        val side = gpuTileSide
        val budget = textureBudget
        val yaw0 = yaw
        val pitch0 = pitch
        val distance0 = distance
        val tx0 = tx
        val ty0 = ty
        val tz0 = tz
        if (decoder.isShutdown) {
            planBusy = false
            return
        }
        try {
            decoder.execute {
                try {
                    if (generation != streamGeneration) return@execute
                    val stamp = floatArrayOf(yaw0, pitch0, distance0, tx0, ty0, tz0)
                    if (planCache != null && DrapeLod.cameraSettled(planStamp, stamp)) return@execute
                    planCache = cameraPlan(snapshot, picture, side, budget, yaw0, pitch0, distance0, tx0, ty0, tz0)
                    planStamp = stamp
                } finally {
                    planBusy = false
                    onNeedsFrame?.invoke()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            planBusy = false
        }
    }

    private fun publishRefining(value: Boolean) {
        if (postedRefining == value) return
        postedRefining = value
        onRefining?.invoke(value)
    }

    private fun cameraPlan(
        snapshot: Drape,
        picture: TiledPicture,
        side: Int,
        budget: Long,
        yaw: Float,
        pitch: Float,
        distance: Float,
        tx: Float,
        ty: Float,
        tz: Float,
    ): DrapeLod.Selection {
        val camX = scene.originX + tx + distance * cos(pitch) * cos(yaw)
        val camY = scene.originY + ty + distance * cos(pitch) * sin(yaw)
        val camZ = scene.originZ + tz + distance * sin(pitch)
        val clip = fileClip(picture, snapshot.focus)
        return DrapeLod.plan(
            imageWidth = picture.width,
            imageHeight = picture.height,
            boundsMinX = picture.bounds.minX,
            boundsMaxY = picture.bounds.maxY,
            boundsWidth = picture.bounds.width,
            boundsHeight = picture.bounds.height,
            cameraX = camX,
            cameraY = camY,
            cameraZ = camZ,
            groundZ = scene.originZ,
            targetDistance = distance.toDouble(),
            worldPerPixel = 2.0 * distance * tan(Math.toRadians(FOV / 2.0)) / height.coerceAtLeast(1),
            finestLevel = DrapeQuality.pyramidLevel(snapshot.quality),
            topLevel = picture.topLevel,
            tileSide = side,
            budgetBytes = budget,
            bytesPerPixel = picture.bytesPerPixel,
            clipX = clip[0],
            clipY = clip[1],
            clipW = clip[2],
            clipH = clip[3],
        )
    }

    private fun fileClip(picture: TiledPicture, focus: com.appdwg.core.Bounds?): IntArray {
        val w = picture.width
        val h = picture.height
        if (focus == null || picture.bounds.width <= 0.0 || picture.bounds.height <= 0.0) return intArrayOf(0, 0, w, h)
        val px = picture.bounds.width / w
        val py = picture.bounds.height / h
        val x0 = floor((focus.minX - picture.bounds.minX) / px).toInt().coerceIn(0, w)
        val x1 = ceil((focus.maxX - picture.bounds.minX) / px).toInt().coerceIn(0, w)
        val y0 = floor((picture.bounds.maxY - focus.maxY) / py).toInt().coerceIn(0, h)
        val y1 = ceil((picture.bounds.maxY - focus.minY) / py).toInt().coerceIn(0, h)
        if (x1 <= x0 || y1 <= y0) return intArrayOf(0, 0, 0, 0)
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private fun worldLeft(picture: TiledPicture, node: DrapeLod.Node): Double {
        val px = if (picture.width > 0) picture.bounds.width / picture.width else 0.0
        return picture.bounds.minX + node.fileX * px
    }

    private fun worldTop(picture: TiledPicture, node: DrapeLod.Node): Double {
        val py = if (picture.height > 0) picture.bounds.height / picture.height else 0.0
        return picture.bounds.maxY - node.fileY * py
    }

    /**
     * GL upload of one bitmap. Null when the driver refuses it. The bitmap is not recycled here.
     * [mipmap] is true for the base overview and for small tiles. A 1024 tile on Mali-G57 spends
     * the whole frame inside glGenerateMipmap, so streamed tiles at screen size stay linear.
     */
    private fun uploadPending(bitmap: android.graphics.Bitmap, alignment: Int, mipmap: Boolean): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val minFilter = if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, alignment)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, ids, 0)
            return null
        }
        if (mipmap) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            applyAnisotropy()
        }
        return ids[0]
    }

    private fun evictResidents(plan: DrapeLod.Selection, bytesPerPixel: Int) {
        val residentNodeKeys = residents.values.map { it.node.key }.toSet()
        val planKeys = plan.tiles.mapTo(HashSet()) { it.key }
        val replaced = ArrayList<String>()
        for ((key, resident) in residents) {
            if (resident.node.key in planKeys) {
                resident.lastUsed = ++useStamp
                continue
            }
            if (DrapeLod.dropReason(resident.node, plan.tiles, residentNodeKeys) == DrapeLod.DROP_REPLACED) {
                replaced += key
            }
        }
        for (key in replaced) {
            val resident = residents.remove(key) ?: continue
            deleteTextures(listOf(resident.tile.id))
        }
        val pinned = residents.filter { (_, resident) ->
            resident.node.key in planKeys ||
                DrapeLod.dropReason(resident.node, plan.tiles, residentNodeKeys) == DrapeLod.KEEP
        }
        val missing = plan.tiles.filter { it.key !in residents }.sumOf { nodeBytes(it, bytesPerPixel) }
        var spare = textureBudget - pinned.values.sumOf { it.bytes } - missing
        val offscreen = residents.filter { it.key !in pinned }.entries.sortedByDescending { it.value.lastUsed }
        for ((key, resident) in offscreen) {
            if (resident.bytes <= spare) spare -= resident.bytes
            else {
                residents.remove(key)
                deleteTextures(listOf(resident.tile.id))
            }
        }
    }

    private fun nodeBytes(node: DrapeLod.Node, bytesPerPixel: Int): Long {
        val (width, height) = DrapeLod.outputSize(node)
        return DrapeLod.textureBytes(width, height, bytesPerPixel)
    }

    /** Asks the decode thread for the nearest missing tiles. File reads stay off this thread. */
    private fun enqueueDecodes(picture: TiledPicture, nodes: List<DrapeLod.Node>, generation: Int) {
        val residentNodeKeys = residents.values.map { it.node.key }.toSet()
        val waiting = ready.map { it.node.key }.toSet()
        val planKeys = (planCache?.tiles ?: nodes).mapTo(HashSet()) { it.key }
        val residentPairs = residents.values.map { it.node to it.bytes }
        for (node in nodes) {
            if (ready.size + queuedKeys.size >= DrapeLod.DECODES_IN_FLIGHT) return
            if (node.key in residentNodeKeys || node.key in waiting || node.key in queuedKeys || node.key in failedKeys) continue
            val bytes = nodeBytes(node, picture.bytesPerPixel)
            val used = residents.values.sumOf { it.bytes }
            val credit = DrapeLod.replacedBytes(node, residentPairs, planKeys)
            if (bytes > textureBudget) continue
            if (!DrapeLod.admits(used, bytes, credit, textureBudget)) break
            if (!queuedKeys.add(node.key)) continue
            if (decoder.isShutdown) {
                queuedKeys.remove(node.key)
                return
            }
            try {
            decoder.execute {
                if (generation != streamGeneration) return@execute
                try {
                    val bitmap = picture.decodeWindow(node.fileX, node.fileY, node.fileW, node.fileH, node.level)
                    if (bitmap == null || generation != streamGeneration) {
                        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        if (generation == streamGeneration) {
                            queuedKeys.remove(node.key)
                            // A window that cannot be read stays on the coarser picture under it. It
                            // is not asked again, and it no longer lowers the quality of the whole view.
                            if (bitmap == null) failedKeys.add(node.key)
                        }
                        onNeedsFrame?.invoke()
                        return@execute
                    }
                    // One texture for the node. The plan already keeps it within the GPU tile limit.
                    val part = GlTexture.plan(
                        bitmap.width, bitmap.height, bitmap.hasAlpha(), max(bitmap.width, bitmap.height),
                    )?.singleOrNull()
                    if (part == null) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        if (generation == streamGeneration) queuedKeys.remove(node.key)
                        failedKeys.add(node.key)
                        onNeedsFrame?.invoke()
                        return@execute
                    }
                    val piece = GlTexture.tileBitmap(bitmap, part)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (piece == null || generation != streamGeneration) {
                        if (piece != null && !piece.isRecycled) piece.recycle()
                        if (generation == streamGeneration) {
                            queuedKeys.remove(node.key)
                            if (piece == null) failedKeys.add(node.key)
                        }
                        onNeedsFrame?.invoke()
                        return@execute
                    }
                    ready.add(
                        PendingTile(
                            generation, node, piece, part.unpackAlignment,
                            part.x, part.y, part.width, part.height,
                            part.width, part.height,
                            DrapeLod.textureBytes(part.width, part.height, picture.bytesPerPixel),
                        ),
                    )
                    if (generation == streamGeneration) queuedKeys.remove(node.key)
                    onNeedsFrame?.invoke()
                } catch (_: Throwable) {
                    if (generation == streamGeneration) {
                        queuedKeys.remove(node.key)
                        failedKeys.add(node.key)
                    }
                    onNeedsFrame?.invoke()
                }
            }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                queuedKeys.remove(node.key)
            }
        }
    }

    fun update(hidden: Set<String>, layerColor: (String) -> Int?, background: Int, foreground: Int, zScale: Float, styles: Map<String, SurfaceStyle>) {
        this.styles = styles
        this.hidden = hidden
        this.layerColor = layerColor
        this.background = background
        this.foreground = foreground
        this.zScale = zScale
    }

    fun orbit(dx: Float, dy: Float) {
        yaw -= dx * 0.008f
        pitch = (pitch + dy * 0.006f).coerceIn(0.05f, 1.55f)
    }

    fun pan(dx: Float, dy: Float) {
        // Moves the target in the screen plane, so the ground follows the fingers.
        val worldPerPx = 2 * distance * tan(Math.toRadians(FOV / 2.0)).toFloat() / height
        val rx = -sin(yaw); val ry = cos(yaw)
        val ux = -sin(pitch) * cos(yaw); val uy = -sin(pitch) * sin(yaw); val uz = cos(pitch)
        tx -= (rx * dx - ux * dy) * worldPerPx
        ty -= (ry * dx - uy * dy) * worldPerPx
        tz += uz * dy * worldPerPx
    }

    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(scene.radius * 0.0005f, scene.radius * 20f)
    }

    /** World ray through a screen pixel, with the vertical exaggeration undone. Safe off the GL thread. */
    fun worldRay(screenX: Float, screenY: Float): Ray? = ViewRay.world(
        screenX, screenY, width, height,
        yaw, pitch, distance, tx, ty, tz, zScale,
        FOV, scene.radius, scene.originX, scene.originY, scene.originZ,
    )

    /** Screen pixel of a world point, or null when it is behind the camera. */
    fun project(x: Double, y: Double, z: Double): Pair<Double, Double>? = ViewRay.project(
        x, y, z, width, height,
        yaw, pitch, distance, tx, ty, tz, zScale,
        FOV, scene.radius, scene.originX, scene.originY, scene.originZ,
    )

    /** Metres per pixel at the orbit target, for a snap prefilter. */
    fun worldPerPixel(): Double {
        val h = height.coerceAtLeast(1)
        return 2.0 * distance * tan(Math.toRadians(FOV / 2.0)) / h
    }

    fun setOverlay(lines: FloatArray, tris: FloatArray) {
        pendingLines = lines
        pendingTris = tris
        overlayDirty = true
    }

    fun setSolid(tris: FloatArray, earth: FloatArray = FloatArray(0)) {
        pendingSolid = tris
        pendingEarth = earth
        solidDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        meshBuffers.clear()
        lineBuffers.clear()
        val chunks = surfaceChunks
        for ((i, m) in (scene.surfaces + scene.models).withIndex()) {
            // Surfaces upload their triangles in cell order; the set of triangles is the same.
            val order = if (i < chunks.size) chunks[i].indices else m.indices
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, floats(m.vertices), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, order.size * 4, ints(order), GLES30.GL_STATIC_DRAW)
            meshBuffers += ids
        }
        edgeBuffers.clear()
        for (m in scene.surfaces) {
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.edges.size * 4, ints(m.edges), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.border.size * 4, ints(m.border), GLES30.GL_STATIC_DRAW)
            edgeBuffers += ids
        }
        for (b in scene.lines) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, b.vertices.size * 4, floats(b.vertices), GLES30.GL_STATIC_DRAW)
            lineBuffers += ids[0]
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // A new GL context has lost the old textures. They are not deleted: the context is gone.
        gpuTiles = emptyList()
        residents.clear()
        baseTiles.clear()
        queuedKeys.clear()
        failedKeys.clear()
        gpuRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
        streamGeneration++
        drainReady()
        drapeDirty = true
        overlayLineBuf = 0
        overlayTriBuf = 0
        solidBuf = 0
        overlayDirty = true
        solidDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = background
        GLES30.glClearColor(((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val proj = FloatArray(16)
        val viewM = FloatArray(16)
        val mvp = FloatArray(16)
        val near = max(distance * 0.01f, 0.05f)
        Matrix.perspectiveM(proj, 0, FOV, width.toFloat() / height, near, distance * 4 + scene.radius * 8)
        val ex = tx + distance * cos(pitch) * cos(yaw)
        val ey = ty + distance * cos(pitch) * sin(yaw)
        val ez = tz + distance * sin(pitch)
        Matrix.setLookAtM(viewM, 0, ex, ey, ez, tx, ty, tz, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        if (drapeDirty) uploadDrape()
        streamDrape()
        if (overlayDirty) uploadOverlay()
        if (solidDirty) uploadSolid()
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uZScale"), zScale)
        val uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        val clearGround = terrainAlpha < 0.99f
        if (clearGround) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
        }
        GLES30.glUniform1f(uAlpha, terrainAlpha)
        val uBias = GLES30.glGetUniformLocation(program, "uDepthBias")
        GLES30.glUniform1f(uBias, 0f)
        val uLit = GLES30.glGetUniformLocation(program, "uLit")
        val uColor = GLES30.glGetUniformLocation(program, "uColor")

        // Surfaces sit slightly behind lines drawn on them.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)
        GLES30.glUniform1i(uLit, 1)
        val uSun = GLES30.glGetUniformLocation(program, "uSun")
        GLES30.glUniform1i(uSun, if (sunContrast(background, foreground)) 1 else 0)
        val uDrape = GLES30.glGetUniformLocation(program, "uDrape")
        val uTex = GLES30.glGetUniformLocation(program, "uTex")
        val uRect = GLES30.glGetUniformLocation(program, "uDrapeRect")
        GLES30.glUniform1i(uDrape, 0)
        val uGray = GLES30.glGetUniformLocation(program, "uGray")
        scene.surfaces.forEachIndexed { i, m ->
            if (m.name in hidden) return@forEachIndexed
            val st = styles[m.name] ?: SurfaceStyle()
            if (st.elevation == ElevationPaint.NONE) return@forEachIndexed
            GLES30.glUniform1i(uGray, if (st.elevation == ElevationPaint.SHADE) 1 else 0)
            val ids = meshBuffers[i]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 36, 24)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.size, GLES30.GL_UNSIGNED_INT, 0)
        }
        val tiles = gpuTiles
        if (tiles.isNotEmpty()) {
            GLES30.glUniform1i(uDrape, 1)
            GLES30.glUniform1i(uTex, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glPolygonOffset(-1f, -1f)
            val chunks = surfaceChunks
            // Surface first, then its tiles: the vertex setup is bound once per surface.
            scene.surfaces.forEachIndexed { i, m ->
                if (m.name in hidden) return@forEachIndexed
                val st = styles[m.name] ?: SurfaceStyle()
                if (st.elevation == ElevationPaint.NONE) return@forEachIndexed
                val ids = meshBuffers[i]
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 12)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 36, 24)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
                // Finer tiles come first. Where they are drawn the coarser ones fail the depth test.
                for (tile in tiles) {
                    val ranges = chunks[i].ranges(tile.rect[0], tile.rect[1], tile.rect[2], tile.rect[3])
                    if (ranges.isEmpty()) continue
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tile.id)
                    GLES30.glUniform4f(uRect, tile.rect[0], tile.rect[1], tile.rect[2], tile.rect[3])
                    var r = 0
                    while (r < ranges.size) {
                        GLES30.glDrawElements(GLES30.GL_TRIANGLES, ranges[r + 1], GLES30.GL_UNSIGNED_INT, ranges[r] * 4)
                        r += 2
                    }
                }
            }
            GLES30.glUniform1i(uDrape, 0)
        }
        GLES30.glUniform1i(uGray, 0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        if (clearGround) {
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glUniform1f(uAlpha, 1f)
        // Mesh and border lines over the surfaces, unlit.
        GLES30.glUniform1i(uLit, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        val fg = foreground
        scene.surfaces.forEachIndexed { i, m ->
            if (m.name in hidden) return@forEachIndexed
            val st = styles[m.name] ?: SurfaceStyle()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuffers[i][0])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
            if (st.triangles && m.edges.isNotEmpty()) {
                // Dark lines over a painted surface, the foreground colour over the bare background.
                if (st.elevation == ElevationPaint.NONE) GLES30.glUniform3f(uColor, ((fg shr 16) and 0xFF) / 255f, ((fg shr 8) and 0xFF) / 255f, (fg and 0xFF) / 255f)
                else GLES30.glUniform3f(uColor, 0.2f, 0.2f, 0.2f)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, edgeBuffers[i][0])
                GLES30.glDrawElements(GLES30.GL_LINES, m.edges.size, GLES30.GL_UNSIGNED_INT, 0)
            }
            if (st.border && m.border.isNotEmpty()) {
                GLES30.glUniform3f(uColor, 1f, 0.6f, 0f)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, edgeBuffers[i][1])
                GLES30.glDrawElements(GLES30.GL_LINES, m.border.size, GLES30.GL_UNSIGNED_INT, 0)
            }
        }
        GLES30.glUniform1i(uLit, 1)
        // Models: lit like the terrain, without the picture over the surfaces.
        GLES30.glUniform1i(uDrape, 0)
        scene.models.forEachIndexed { i, m ->
            if (m.name in hidden) return@forEachIndexed
            val ids = meshBuffers[scene.surfaces.size + i]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 36, 24)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.size, GLES30.GL_UNSIGNED_INT, 0)
        }

        GLES30.glUniform1i(uLit, 0)
        GLES30.glUniform1i(uDrape, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glUniform1f(uBias, 0.0015f)
        scene.lines.forEachIndexed { i, b ->
            if (b.layer in hidden) return@forEachIndexed
            val argb = (if (b.byLayer) layerColor(b.layer) else b.color) ?: foreground
            GLES30.glUniform3f(uColor, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuffers[i])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, b.vertices.size / 3)
        }
        drawOverlay(uAlpha, uBias, uColor, uLit, uDrape)
        drawSolid(uAlpha, uBias, uColor, uLit, uDrape)
    }

    /** Highlight draped on the surface. Uploaded when the polygon changes, drawn as one batch. */
    private fun drawOverlay(uAlpha: Int, uBias: Int, uColor: Int, uLit: Int, uDrape: Int) {
        if (overlayTriCount == 0 && overlayLineCount == 0) return
        GLES30.glUniform1i(uLit, 0)
        GLES30.glUniform1i(uDrape, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glUniform3f(uColor, 0f, 0.722f, 0.831f)
        if (overlayTriCount > 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUniform1f(uAlpha, 0.35f)
            GLES30.glUniform1f(uBias, 0.002f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayTriBuf)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, overlayTriCount)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUniform1f(uAlpha, 1f)
        }
        if (overlayLineCount > 0) {
            GLES30.glUniform1f(uBias, 0.004f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayLineBuf)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, overlayLineCount)
        }
        GLES30.glUniform1f(uBias, 0f)
    }

    private fun uploadOverlay() {
        val lines = pendingLines
        val tris = pendingTris
        overlayLineBuf = replaceBuffer(overlayLineBuf, lines)
        overlayLineCount = lines.size / 3
        overlayTriBuf = replaceBuffer(overlayTriBuf, tris)
        overlayTriCount = tris.size / 3
        // A polygon that arrived while this uploaded stays dirty for the next frame.
        if (pendingLines === lines && pendingTris === tris) overlayDirty = false
    }

    /** Drainage solids. Earthwork is drawn first, see-through, so the device colour stays on top. */
    private fun drawSolid(uAlpha: Int, uBias: Int, uColor: Int, uLit: Int, uDrape: Int) {
        if (solidCount == 0 && earthCount == 0) return
        GLES30.glUniform1i(uLit, 1)
        GLES30.glUniform1i(uDrape, 0)
        GLES30.glUniform3f(uColor, 0.62f, 0.62f, 0.58f)
        if (earthCount > 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            GLES30.glUniform1f(uAlpha, 0.40f)
            GLES30.glUniform1f(uBias, 0.0016f)
            bindSolid(earthBuf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, earthCount)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        if (solidCount > 0) {
            GLES30.glUniform1f(uAlpha, 1f)
            GLES30.glUniform1f(uBias, 0.0008f)
            bindSolid(solidBuf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, solidCount)
        }
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glUniform1i(uLit, 0)
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uBias, 0f)
    }

    private fun bindSolid(buffer: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 36, 24)
    }

    private fun uploadSolid() {
        val tris = pendingSolid
        val earth = pendingEarth
        solidBuf = replaceBuffer(solidBuf, tris)
        solidCount = tris.size / 9
        earthBuf = replaceBuffer(earthBuf, earth)
        earthCount = earth.size / 9
        if (pendingSolid === tris && pendingEarth === earth) solidDirty = false
    }

    private fun replaceBuffer(old: Int, data: FloatArray): Int {
        if (old != 0) GLES30.glDeleteBuffers(1, intArrayOf(old), 0)
        if (data.isEmpty()) return 0
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES30.GL_DYNAMIC_DRAW)
        return ids[0]
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String) = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES30.glLinkProgram(p)
        return p
    }

    companion object {
        private const val FOV = 45f

        private val VERTEX = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            uniform mat4 uMvp;
            uniform float uZScale;
            // Pulls lines towards the eye, so a line lying on the terrain is not hidden by it.
            uniform float uDepthBias;
            uniform int uLit;
            uniform int uGray;
            uniform int uSun;
            uniform vec3 uColor;
            // Picture over the surface: left x, top y, width and height, relative to the scene origin.
            uniform vec4 uDrapeRect;
            out vec3 vColor;
            out float vShade;
            out vec2 vUv;
            out vec3 vPos;
            void main() {
                gl_Position = uMvp * vec4(aPos.xy, aPos.z * uZScale, 1.0);
                gl_Position.z -= uDepthBias * gl_Position.w;
                vPos = aPos;
                vUv = vec2((aPos.x - uDrapeRect.x) / uDrapeRect.z, (uDrapeRect.y - aPos.y) / uDrapeRect.w);
                if (uLit == 1) {
                    // Normal under a vertical stretch: (nx, ny, nz / s), normalised.
                    vec3 n = normalize(vec3(aNormal.xy * uZScale, aNormal.z));
                    // Two-sided: model faces may be wound either way, so both sides take the light.
                    float light = abs(dot(n, normalize(vec3(-0.5, 0.5, 0.7))));
                    vec3 tint = aColor;
                    // Alto contraste is black on white. A dark green ramp reads as a flat black sheet,
                    // so those faces are lifted to a warm tan and the light is stretched.
                    if (uSun == 1) {
                        float y = dot(tint, vec3(0.30, 0.59, 0.11));
                        tint = mix(vec3(0.78, 0.66, 0.42), tint, clamp(y * 2.2, 0.2, 1.0));
                    }
                    vColor = uGray == 1 ? (uSun == 1 ? vec3(0.86, 0.78, 0.58) : vec3(0.82)) : tint;
                    vShade = (uSun == 1 ? 0.25 : 0.45) + (uSun == 1 ? 0.95 : 0.65) * light;
                } else {
                    vColor = uColor;
                    vShade = 1.0;
                }
            }
        """.trimIndent()

        private val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            in float vShade;
            // highp: at mediump (16 bits on Mali) a 2048 texture is read in steps of a whole texel,
            // so every quality looked blocky in 3D.
            in highp vec2 vUv;
            in vec3 vPos;
            uniform int uDrape;
            uniform sampler2D uTex;
            uniform float uAlpha;
            out vec4 fragColor;
            float grit(vec3 p) {
                return fract(sin(dot(floor(p * 37.0), vec3(127.1, 311.7, 74.7))) * 43758.5453);
            }
            void main() {
                vec3 c = vColor;
                if (uDrape == 1) {
                    if (vUv.x < 0.0 || vUv.y < 0.0 || vUv.x > 1.0 || vUv.y > 1.0) discard;
                    // Photos already carry their own shading, so the light is softened over them.
                    vec4 t = texture(uTex, vUv);
                    fragColor = vec4(mix(c * vShade, t.rgb * (0.7 + 0.3 * vShade), t.a), uAlpha);
                    return;
                }
                float n = grit(vPos);
                bool water = vColor.b > vColor.r + 0.12;
                float grain = water ? 0.0 : (n - 0.5) * 0.14;
                float shade = vShade * (water ? 1.0 : (0.92 + 0.16 * n));
                fragColor = vec4((c + vec3(grain)) * shade, uAlpha);
            }
        """.trimIndent()

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun ints(a: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(a); position(0) }
    }
}

/** Surfaces the 3D view actually draws. An edited copy stands in for its original. */
private fun visibleSurfaces(project: LandXmlProject, hidden: Set<String>): List<Surface> {
    val replaced = project.surfaces.map { it.name }.filter { n -> project.surfaces.any { it.name == n + EDITED_SUFFIX } }.toSet()
    return project.surfaces.filter { it.name !in hidden && it.name !in replaced && it.triangleCount > 0 }
}

/**
 * Drainage triangles as a lit mesh: position, face normal and concrete colour.
 * Nine floats per vertex. Built off the UI thread. Faces stay two-sided in the shader,
 * so the inside of a channel is shaded as well.
 */
private fun joinFloats(parts: List<FloatArray>): FloatArray {
    var count = 0
    for (part in parts) count += part.size
    val out = FloatArray(count)
    var at = 0
    for (part in parts) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

private fun litSolid(world: DoubleArray, scene: Scene3D, red: Float, green: Float, blue: Float): FloatArray {
    if (world.size < 9) return FloatArray(0)
    val triangles = world.size / 9
    val out = FloatArray(triangles * 27)
    var t = 0
    var o = 0
    while (t + 8 < world.size) {
        val ax = (world[t] - scene.originX).toFloat()
        val ay = (world[t + 1] - scene.originY).toFloat()
        val az = (world[t + 2] - scene.originZ).toFloat()
        val bx = (world[t + 3] - scene.originX).toFloat()
        val by = (world[t + 4] - scene.originY).toFloat()
        val bz = (world[t + 5] - scene.originZ).toFloat()
        val cx = (world[t + 6] - scene.originX).toFloat()
        val cy = (world[t + 7] - scene.originY).toFloat()
        val cz = (world[t + 8] - scene.originZ).toFloat()
        var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
        var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
        var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-8f) {
            nx = 0f
            ny = 0f
            nz = 1f
        } else {
            nx /= len
            ny /= len
            nz /= len
        }
        val xs = floatArrayOf(ax, bx, cx)
        val ys = floatArrayOf(ay, by, cy)
        val zs = floatArrayOf(az, bz, cz)
        for (k in 0 until 3) {
            out[o] = xs[k]
            out[o + 1] = ys[k]
            out[o + 2] = zs[k]
            out[o + 3] = nx
            out[o + 4] = ny
            out[o + 5] = nz
            out[o + 6] = red
            out[o + 7] = green
            out[o + 8] = blue
            o += 9
        }
        t += 9
    }
    return out
}

/**
 * Alto contraste is pure black ink on a white sheet. Other light themes (Engineering, Earth)
 * use a dark navy or olive, which stays on the normal ramp.
 */
internal fun sunContrast(background: Int, foreground: Int): Boolean {
    fun lum(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        return 0.30f * r + 0.59f * g + 0.11f * b
    }
    return lum(background) > 0.90f && lum(foreground) < 0.05f
}

/** World coordinates shifted to the scene origin, so the GPU floats keep their precision. */
private fun sceneFloats(world: DoubleArray, scene: Scene3D): FloatArray {
    if (world.isEmpty()) return FloatArray(0)
    val out = FloatArray(world.size)
    var i = 0
    while (i + 2 < world.size) {
        out[i] = (world[i] - scene.originX).toFloat()
        out[i + 1] = (world[i + 1] - scene.originY).toFloat()
        out[i + 2] = (world[i + 2] - scene.originZ).toFloat()
        i += 3
    }
    return out
}
