package com.appdwg.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appdwg.core.ArCamera
import com.appdwg.core.ArControl
import com.appdwg.core.ArFit
import com.appdwg.core.ArScene
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.Point3
import com.appdwg.core.StationFormat
import com.appdwg.core.tr
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Points, a coarse terrain grid and nothing past 300 m, capped so the camera stays light. */
internal fun arTargets(project: LandXmlProject, userPoints: List<CogoPoint>, hidden: Set<String>, origin: Point3): List<Point3> {
    val raw = ArrayList<Point3>()
    for (p in project.cgPoints) raw += p.position
    for (p in userPoints) raw += Point3(p.x, p.y, p.z)
    val surface = project.surfaces.firstOrNull { it.name !in hidden && it.vertexCount > 0 }
    if (surface != null) {
        val step = 25.0
        var x = origin.x - ArScene.RANGE_M
        while (x <= origin.x + ArScene.RANGE_M) {
            var y = origin.y - ArScene.RANGE_M
            while (y <= origin.y + ArScene.RANGE_M) {
                surface.elevationAt(Point2(x, y))?.let { raw += Point3(x, y, it) }
                y += step
            }
            x += step
        }
    }
    return ArScene.take(origin.x, origin.y, raw)
}

internal fun arCoreInstalled(context: Context): Boolean = try {
    val availability = ArCoreApk.getInstance().checkAvailability(context)
    !availability.isTransient && availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
} catch (_: Throwable) {
    false
}

/**
 * Augmented view of the open project. ARCore draws the camera when it is installed.
 * Otherwise the camera preview and the rotation sensor do. Either way the overlay is the
 * project, clipped to 300 m, placed by GPS and the calibration.
 */
@Composable
fun ArScreen(
    project: LandXmlProject,
    userPoints: List<CogoPoint>,
    hidden: Set<String>,
    origin: Point3?,
    controls: List<ArControl>,
    onControls: (List<ArControl>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    var cameraOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var explain by remember { mutableStateOf(!cameraOk || !locationOk) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraOk = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
        locationOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        explain = false
    }
    if (!cameraOk || !locationOk) {
        if (explain) {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(tr("Câmera e localização")) },
                text = { Text(tr("A realidade aumentada usa a câmera para mostrar o terreno e a localização para posicioná-lo. O Gromatic não grava vídeo nem envia a posição.")) },
                confirmButton = {
                    GButton(tr("Continuar"), {
                        ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }, primary = true)
                },
                dismissButton = { GButton(tr("Cancelar"), onClose) },
            )
        } else {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(tr("Permissão necessária")) },
                text = { Text(tr("Sem a câmera e a localização a vista aumentada não abre. Você pode permitir nas configurações do Android.")) },
                confirmButton = { GButton(tr("Fechar"), onClose, primary = true) },
            )
        }
        return
    }

    val useArCore = remember { arCoreInstalled(context) }
    var fallback by remember { mutableStateOf(false) }
    val pose = rememberDevicePose()
    val fov = remember { cameraFov(context) }
    val fit = remember(controls) { ArFit.solve(controls) }
    var picking by remember { mutableStateOf<Point3?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (useArCore && !fallback) {
            ArCoreCamera(onFailed = { fallback = true })
        } else {
            FallbackCamera()
        }
        Canvas(Modifier.fillMaxSize()) {
            val here = origin ?: return@Canvas
            val targets = arTargets(project, userPoints, hidden, here)
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            for (point in targets) {
                val observed = fit?.toCamera(point) ?: point
                val dx = observed.x - here.x
                val dy = observed.y - here.y
                val dz = observed.z - here.z
                val cam = ArCamera.observe(dx, dy, dz, pose.heading, pose.pitch, pose.roll)
                val px = ArCamera.project(cam.x, cam.y, cam.z, fov.first, fov.second, w, h) ?: continue
                if (px.first < -20 || px.second < -20 || px.first > w + 20 || px.second > h + 20) continue
                drawCircle(Color(0xFF00B8D4), radius = 5f, center = Offset(px.first.toFloat(), px.second.toFloat()))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    origin == null -> tr("Sem posição do GPS. A câmera abre, mas o projeto ainda não tem onde se apoiar.")
                    fit == null -> tr("Toque em dois ou três pontos conhecidos para calibrar. RMS horizontal aparece aqui.")
                    else -> tr("Calibrado. RMS horizontal {0} m.", StationFormat.decimal(fit.horizontalRms, 2))
                },
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GButton(tr("Calibrar"), { if (origin != null) picking = origin }, enabled = origin != null)
                if (controls.isNotEmpty()) GButton(tr("Limpar"), { onControls(emptyList()) })
                GButton(tr("Fechar"), onClose)
            }
        }
        picking?.let { observed ->
            val names = (project.cgPoints.map { it.name to it.position } + userPoints.map { it.number to Point3(it.x, it.y, it.z) }).take(40)
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text(tr("Você está sobre qual ponto?")) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (names.isEmpty()) Text(tr("O projeto não tem pontos para calibrar."))
                        for ((label, point) in names) {
                            GButton(label, {
                                onControls(controls + ArControl(observed, point))
                                picking = null
                            })
                        }
                    }
                },
                confirmButton = { GButton(tr("Cancelar"), { picking = null }) },
            )
        }
    }
}

private data class DevicePose(val heading: Double, val pitch: Double, val roll: Double)

@Composable
private fun rememberDevicePose(): DevicePose {
    val context = LocalContext.current
    var pose by remember { mutableStateOf(DevicePose(0.0, 0.0, 0.0)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val matrix = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                pose = DevicePose(orientation[0].toDouble(), orientation[1].toDouble(), orientation[2].toDouble())
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    return pose
}

private fun cameraFov(context: Context): Pair<Double, Double> {
    val fallback = 60.0 to 45.0
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return fallback
    val id = manager.cameraIdList.firstOrNull { cameraId ->
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: return fallback
    val chars = manager.getCameraCharacteristics(id)
    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return fallback
    val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return fallback
    return ArCamera.fov(focal.toDouble(), size.width.toDouble(), size.height.toDouble()) ?: fallback
}

@Composable
private fun FallbackCamera() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }
        }, executor)
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** ARCore camera background. A failure (service missing, camera busy) drops back to CameraX. */
@Composable
private fun ArCoreCamera(onFailed: () -> Unit) {
    val context = LocalContext.current
    val renderer = remember { ArCameraRenderer(context, onFailed) }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class ArCameraRenderer(private val context: Context, private val onFailed: () -> Unit) : GLSurfaceView.Renderer {
    private var session: Session? = null
    private var textureId = 0
    private var program = 0
    private var failed = false
    private val quad: FloatBuffer = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coords: FloatBuffer = buffer(FloatArray(8))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = build()
        try {
            val created = Session(context)
            created.configure(Config(created).apply {
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            })
            created.setCameraTextureName(textureId)
            created.resume()
            session = created
        } catch (e: Throwable) {
            android.util.Log.w("Gromatic", "ARCore", e)
            fail()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val current = session ?: return
        try {
            val frame = current.update()
            if (frame.camera.trackingState == TrackingState.STOPPED) return
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quad,
                Coordinates2d.TEXTURE_NORMALIZED,
                coords,
            )
            quad.position(0)
            coords.position(0)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: Throwable) {
            android.util.Log.w("Gromatic", "ARCore", e)
            fail()
        }
    }

    fun close() {
        runCatching { session?.pause() }
        runCatching { session?.close() }
        session = null
    }

    private fun fail() {
        if (failed) return
        failed = true
        close()
        android.os.Handler(android.os.Looper.getMainLooper()).post { onFailed() }
    }

    private fun build(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """.trimIndent())
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent())
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        return id
    }

    private fun buffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
