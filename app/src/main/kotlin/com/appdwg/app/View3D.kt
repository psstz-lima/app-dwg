package com.appdwg.app

import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.appdwg.core.LandXmlProject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D view of the surfaces and 3D lines, drawn with OpenGL ES 3. One finger orbits around the
 * target, two fingers pan, pinch zooms.
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
) {
    val scene = remember(project) { Scene3D.build(project) }
    val renderer = remember(project) { Renderer3D(scene) }
    // Holder for the GL view, so gestures and settings can ask it to redraw.
    val holder = remember(project) { arrayOfNulls<GLSurfaceView>(1) }

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

    AndroidView(
        modifier = modifier.pointerInput(project) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var lastCentroid: Offset? = null
                var lastSpread = 0f
                var lastCount = 0
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                    val spread = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                    // A change in finger count restarts the reference, so lifting a finger does not jump.
                    if (pressed.size == lastCount && lastCentroid != null) {
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
            }
        },
        factory = { context ->
            GLSurfaceView(context).apply {
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
    /** Plan position of the scene's (0, 0). */
    val originX: Double = 0.0,
    val originY: Double = 0.0,
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
            return Scene3D(meshes, batches, (max(bx1 - bx0, by1 - by0) / 2).toFloat().coerceAtLeast(1f), ox, oy, models)
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

    private var width = 1
    private var height = 1
    private var program = 0
    private val meshBuffers = ArrayList<IntArray>()
    private val lineBuffers = ArrayList<Int>()
    /** Per surface: element buffers of its edges and of its border. */
    private val edgeBuffers = ArrayList<IntArray>()

    // Picture over the surfaces: set from the UI, uploaded as a texture on the GL thread.
    @Volatile private var drape: Drape? = null
    @Volatile private var drapeDirty = false
    private var drapeTexture = 0

    fun setDrape(drape: Drape?) {
        this.drape = drape
        drapeDirty = true
    }

    /** Uploads the picture, scaled down when it exceeds what the GPU takes. */
    private fun uploadDrape() {
        drapeDirty = false
        if (drapeTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(drapeTexture), 0)
            drapeTexture = 0
        }
        val d = drape ?: return
        val original = d.image.asAndroidBitmap()
        var bitmap = original
        val maxSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0].coerceAtLeast(2048)
        if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val f = maxSize.toFloat() / max(bitmap.width, bitmap.height)
            bitmap = bitmap.scale((bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
        }
        try {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            drapeTexture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, drapeTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        } finally {
            // The scaled copy is only the upload buffer. The drape's own bitmap stays with its ImageBitmap.
            if (bitmap !== original) bitmap.recycle()
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        meshBuffers.clear()
        lineBuffers.clear()
        for (m in scene.surfaces + scene.models) {
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, floats(m.vertices), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ints(m.indices), GLES30.GL_STATIC_DRAW)
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
        // A new GL context has lost the old texture.
        drapeTexture = 0
        drapeDirty = true
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
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uZScale"), zScale)
        val uBias = GLES30.glGetUniformLocation(program, "uDepthBias")
        GLES30.glUniform1f(uBias, 0f)
        val uLit = GLES30.glGetUniformLocation(program, "uLit")
        val uColor = GLES30.glGetUniformLocation(program, "uColor")

        // Surfaces sit slightly behind lines drawn on them.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)
        GLES30.glUniform1i(uLit, 1)
        val d = drape
        val uDrape = GLES30.glGetUniformLocation(program, "uDrape")
        if (d != null && drapeTexture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, drapeTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
            GLES30.glUniform1i(uDrape, 1)
            // Texture v runs down from the picture's top (north) edge.
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(program, "uDrapeRect"),
                (d.bounds.minX - scene.originX).toFloat(), (d.bounds.maxY - scene.originY).toFloat(),
                d.bounds.width.toFloat(), d.bounds.height.toFloat(),
            )
        } else {
            GLES30.glUniform1i(uDrape, 0)
        }
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
        GLES30.glUniform1i(uGray, 0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
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
            uniform vec3 uColor;
            // Picture over the surface: left x, top y, width and height, relative to the scene origin.
            uniform vec4 uDrapeRect;
            out vec3 vColor;
            out float vShade;
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos.xy, aPos.z * uZScale, 1.0);
                gl_Position.z -= uDepthBias * gl_Position.w;
                vUv = vec2((aPos.x - uDrapeRect.x) / uDrapeRect.z, (uDrapeRect.y - aPos.y) / uDrapeRect.w);
                if (uLit == 1) {
                    // Normal under a vertical stretch: (nx, ny, nz / s), normalised.
                    vec3 n = normalize(vec3(aNormal.xy * uZScale, aNormal.z));
                    // Two-sided: model faces may be wound either way, so both sides take the light.
                    float light = abs(dot(n, normalize(vec3(-0.5, 0.5, 0.7))));
                    vColor = uGray == 1 ? vec3(0.82) : aColor;
                    vShade = 0.45 + 0.65 * light;
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
            in vec2 vUv;
            uniform int uDrape;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                vec3 c = vColor;
                if (uDrape == 1 && vUv.x >= 0.0 && vUv.x <= 1.0 && vUv.y >= 0.0 && vUv.y <= 1.0) {
                    // Photos already carry their own shading, so the light is softened over them.
                    vec4 t = texture(uTex, vUv);
                    fragColor = vec4(mix(c * vShade, t.rgb * (0.7 + 0.3 * vShade), t.a), 1.0);
                    return;
                }
                fragColor = vec4(c * vShade, 1.0);
            }
        """.trimIndent()

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun ints(a: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(a); position(0) }
    }
}
