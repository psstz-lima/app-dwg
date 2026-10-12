package com.appdwg.app

import android.opengl.GLES30
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
    modifier: Modifier = Modifier,
) {
    val scene = remember(project) { Scene3D.build(project) }
    val renderer = remember(project) { Renderer3D(scene) }
    // Holder for the GL view, so gestures and settings can ask it to redraw.
    val holder = remember(project) { arrayOfNulls<GLSurfaceView>(1) }

    LaunchedEffect(hiddenLayers, layerColor, background, foreground, exaggeration) {
        renderer.update(hiddenLayers, layerColor, background.toArgb(), foreground.toArgb(), exaggeration)
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
) {
    class Mesh(val name: String, val vertices: FloatArray, val indices: IntArray)

    class LineBatch(val layer: String, val color: Int?, val byLayer: Boolean, val vertices: FloatArray)

    val isEmpty get() = surfaces.isEmpty() && lines.isEmpty()

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
            if (minZ > maxZ) { minZ = 0.0; maxZ = 0.0 }

            var bx0 = Double.POSITIVE_INFINITY; var by0 = Double.POSITIVE_INFINITY
            var bx1 = Double.NEGATIVE_INFINITY; var by1 = Double.NEGATIVE_INFINITY
            project.surfaces.forEach { s -> s.bounds?.let { bx0 = minOf(bx0, it.minX); by0 = minOf(by0, it.minY); bx1 = maxOf(bx1, it.maxX); by1 = maxOf(by1, it.maxY) } }
            // Without a terrain the 3D lines frame the view; with one, the terrain does.
            if (project.surfaces.isEmpty()) {
                lines3d.forEach { l -> l.bounds?.let { bx0 = minOf(bx0, it.minX); by0 = minOf(by0, it.minY); bx1 = maxOf(bx1, it.maxX); by1 = maxOf(by1, it.maxY) } }
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
                Mesh(s.name, v, s.triangles)
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
            return Scene3D(meshes, batches, (max(bx1 - bx0, by1 - by0) / 2).toFloat().coerceAtLeast(1f))
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

    private var width = 1
    private var height = 1
    private var program = 0
    private val meshBuffers = ArrayList<IntArray>()
    private val lineBuffers = ArrayList<Int>()

    fun update(hidden: Set<String>, layerColor: (String) -> Int?, background: Int, foreground: Int, zScale: Float) {
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
        distance = (distance * factor).coerceIn(scene.radius * 0.005f, scene.radius * 20f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        meshBuffers.clear()
        lineBuffers.clear()
        for (m in scene.surfaces) {
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, floats(m.vertices), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ints(m.indices), GLES30.GL_STATIC_DRAW)
            meshBuffers += ids
        }
        for (b in scene.lines) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, b.vertices.size * 4, floats(b.vertices), GLES30.GL_STATIC_DRAW)
            lineBuffers += ids[0]
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
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

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uZScale"), zScale)
        val uLit = GLES30.glGetUniformLocation(program, "uLit")
        val uColor = GLES30.glGetUniformLocation(program, "uColor")

        // Surfaces sit slightly behind lines drawn on them.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)
        GLES30.glUniform1i(uLit, 1)
        scene.surfaces.forEachIndexed { i, m ->
            if (m.name in hidden) return@forEachIndexed
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
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        GLES30.glUniform1i(uLit, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
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
            uniform int uLit;
            uniform vec3 uColor;
            out vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos.xy, aPos.z * uZScale, 1.0);
                if (uLit == 1) {
                    // Normal under a vertical stretch: (nx, ny, nz / s), normalised.
                    vec3 n = normalize(vec3(aNormal.xy * uZScale, aNormal.z));
                    float light = max(dot(n, normalize(vec3(-0.5, 0.5, 0.7))), 0.0);
                    vColor = aColor * (0.45 + 0.65 * light);
                } else {
                    vColor = uColor;
                }
            }
        """.trimIndent()

        private val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() { fragColor = vec4(vColor, 1.0); }
        """.trimIndent()

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun ints(a: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(a); position(0) }
    }
}
