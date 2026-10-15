package com.appdwg.app

import android.Manifest
import android.content.Context
import androidx.core.content.edit
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.appdwg.core.StationFormat
import com.appdwg.core.TileId
import com.appdwg.core.WebTiles
import com.appdwg.core.tr
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** Earth's circumference at the equator (m): the width of the whole Web Mercator world. */
private const val WORLD_M = 40075016.686

/**
 * Where a new project starts, chosen on the OpenStreetMap map: dragged and pinched under a frame
 * that is the project's area, centred on the device's position on request, or on coordinates
 * typed in. No place-name search (the free geocoding services do not allow it in an app like this).
 * [onPick] gets the centre (latitude, longitude) and the frame's half width and half height in metres.
 */
@Composable
fun PlacePicker(basemap: BasemapStore, onPick: (Double, Double, Double, Double) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("picker", Context.MODE_PRIVATE) }
    // The device's last known position, else where the picker was left, else all of Brazil.
    val start = remember {
        lastKnown(context)?.let { Triple(it.latitude, it.longitude, 15.0) }
            ?: if (prefs.contains("lat")) Triple(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), prefs.getFloat("zoom", 4f).toDouble())
            else Triple(-14.5, -51.0, 4.0)
    }
    // Centre in Web Mercator world units (0..1 from the top-left) and zoom (a 256 dp tile at 2^zoom).
    var wx by remember { mutableDoubleStateOf(worldX(start.second)) }
    var wy by remember { mutableDoubleStateOf(worldY(start.first)) }
    var zoom by remember { mutableDoubleStateOf(start.third) }
    var message by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val tilePx = WebTiles.TILE_PX * density
    fun scale() = tilePx * 2.0.pow(zoom)
    fun centerOn(lat: Double, lon: Double, z: Double = maxOf(zoom, 16.0)) { wx = worldX(lon); wy = worldY(lat); zoom = z }

    fun locate() {
        locating = true
        message = tr("Buscando sua posição…")
        currentLocation(context) { loc ->
            locating = false
            if (loc == null) message = tr("Sem sinal de localização. Ligue a localização do aparelho, vá para um lugar aberto e tente de novo.")
            else { message = null; centerOn(loc.latitude, loc.longitude) }
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else message = tr("Sem permissão de localização. Mova o mapa até o local ou digite as coordenadas.")
    }

    // The system bars measured in the activity: a dialog window does not always get them.
    // safeDrawing includes the status bar, navigation bar and display cutout, including the
    // side insets a phone in landscape or a tablet gets.
    val ld = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val barTop = with(ld) { safe.getTop(this).toDp() }
    val barBottom = with(ld) { safe.getBottom(this).toDp() }
    val barStart = with(ld) { safe.getLeft(this, layoutDirection).toDp() }
    val barEnd = with(ld) { safe.getRight(this, layoutDirection).toDp() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val g = LocalG.current
        Box(Modifier.fillMaxSize().background(Color(0xFFE8E4DA))) {
            var screen by remember { mutableStateOf(Size.Zero) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        // The point under the fingers stays under them while zooming.
                        val s0 = scale()
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val px = wx + (centroid.x - cx) / s0; val py = wy + (centroid.y - cy) / s0
                        zoom = (zoom + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(2.0, 19.0)
                        val s1 = scale()
                        wx = (px - (centroid.x - cx) / s1 - pan.x / s1).let { it - floor(it) }
                        wy = (py - (centroid.y - cy) / s1 - pan.y / s1).coerceIn(0.0, 1.0)
                    }
                },
            ) {
                screen = size
                basemap.version // redraw as tiles arrive
                val z = zoom.roundToInt().coerceIn(2, 19)
                val n = 1 shl z
                val tile = tilePx * 2.0.pow(zoom - z)
                // Screen position of the top-left of tile (0, 0) at zoom z.
                val ox = size.width / 2 - wx * n * tile; val oy = size.height / 2 - wy * n * tile
                val x0 = floor(-ox / tile).toInt(); val x1 = floor((size.width - ox) / tile).toInt()
                val y0 = floor(-oy / tile).toInt().coerceAtLeast(0); val y1 = floor((size.height - oy) / tile).toInt().coerceAtMost(n - 1)
                if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 200) for (ty in y0..y1) for (tx in x0..x1) {
                    val (bmp, src) = basemap.pickerTile(TileId(z, ((tx % n) + n) % n, ty)) ?: continue
                    val left = (ox + tx * tile).roundToInt(); val top = (oy + ty * tile).roundToInt()
                    val right = (ox + (tx + 1) * tile).roundToInt(); val bottom = (oy + (ty + 1) * tile).roundToInt()
                    drawImage(bmp, IntOffset(src.left, src.top), IntSize(src.width(), src.height()),
                        IntOffset(left, top), IntSize(right - left, bottom - top))
                }
                // The project's area: a frame inset from the edges, darkened outside.
                val f = frame(size)
                val shade = Color.Black.copy(alpha = 0.25f)
                drawRect(shade, Offset.Zero, Size(size.width, f.top))
                drawRect(shade, Offset(0f, f.bottom), Size(size.width, size.height - f.bottom))
                drawRect(shade, Offset(0f, f.top), Size(f.left, f.height))
                drawRect(shade, Offset(f.right, f.top), Size(size.width - f.right, f.height))
                drawRect(g.accent, Offset(f.left, f.top), Size(f.width, f.height), style = Stroke(3.dp.toPx()))
                val c = Offset(size.width / 2, size.height / 2); val arm = 14.dp.toPx()
                drawLine(g.accent, c - Offset(arm, 0f), c + Offset(arm, 0f), 2.dp.toPx())
                drawLine(g.accent, c - Offset(0f, arm), c + Offset(0f, arm), 2.dp.toPx())
            }

            val lat = latitude(wy); val lon = wx * 360 - 180
            val f = frame(screen)
            val mPerPx = WORLD_M * cos(Math.toRadians(lat)) / scale()
            val widthM = f.width * mPerPx; val heightM = f.height * mPerPx

            Column(Modifier.align(Alignment.TopCenter).padding(top = barTop, start = barStart, end = barEnd).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(g.panel.copy(alpha = 0.94f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Escolha o local do projeto"), style = MaterialTheme.typography.titleMedium, color = g.text, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text(tr("Cancelar")) }
                }
                Text(tr("Arraste e use a pinça para enquadrar a área. O quadro é a área do projeto."), style = MaterialTheme.typography.bodySmall, color = g.muted)
                Text(tr("Mapa: © colaboradores do OpenStreetMap"), style = MaterialTheme.typography.labelSmall, color = g.muted)
            }

            // Buttons for zooming without pinching (one hand, gloves).
            Column(Modifier.align(Alignment.CenterEnd).padding(top = barTop, bottom = barBottom, end = barEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, desc, step) in listOf(Triple(GIcon.PLUS, tr("Aproximar"), 1.0), Triple(GIcon.MINUS, tr("Afastar"), -1.0))) {
                    val vec = remember(icon) { icon.vector(g.iconStroke + 0.4f) }
                    Box(Modifier.size(g.target + 4.dp).clip(RoundedCornerShape(10.dp)).background(g.panel)
                        .clickable(role = Role.Button, onClickLabel = desc) { zoom = (zoom + step).coerceIn(2.0, 19.0) },
                        contentAlignment = Alignment.Center) { Icon(vec, desc, tint = g.text, modifier = Modifier.size(22.dp)) }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom, start = barStart, end = barEnd).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp).widthIn(max = 640.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(g.panel.copy(alpha = 0.94f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lon) + "  ·  " +
                    tr("Área: {0} × {1}", size(widthM), size(heightM)), color = g.text)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = g.accent) }
                if (widthM > 30_000 || heightM > 30_000) Text(tr("Aproxime mais: a área passa de 30 km."), style = MaterialTheme.typography.bodySmall, color = g.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GButton(tr("Minha localização (GPS)"), {
                        if (locating) return@GButton
                        if (hasLocationPermission(context)) locate()
                        else askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, outlined = true, icon = GIcon.GPS)
                    GButton(tr("Digitar coordenadas…"), { typing = true }, outlined = true)
                }
                GButton(tr("Usar este local"), {
                    prefs.edit {
                        putFloat("lat", lat.toFloat())
                        putFloat("lon", lon.toFloat())
                        putFloat("zoom", zoom.toFloat())
                    }
                    onPick(lat, lon, widthM / 2, heightM / 2)
                }, primary = true, enabled = screen.width > 0 && widthM <= 30_000 && heightM <= 30_000, modifier = Modifier.fillMaxWidth())
            }
        }
        if (typing) CoordinatesDialog(onCreate = { a, b -> typing = false; centerOn(a, b) }, onDismiss = { typing = false })
    }
}

/** The project's frame on a screen of [size]: centred on the crosshair, clear of the panels above and below. */
private fun frame(size: Size): androidx.compose.ui.geometry.Rect {
    val w = size.width; val h = size.height
    return androidx.compose.ui.geometry.Rect(w * 0.12f, h * 0.27f, w * 0.88f, h * 0.73f)
}

/** A side of the frame: in the length unit, or in km (miles) past 10 km. */
private fun size(m: Double) = if (m < 10_000) StationFormat.length(m, 0)
    else if (StationFormat.lengthUnit == "m") StationFormat.decimal(m / 1000, 1) + " km" else StationFormat.decimal(m / 1609.344, 1) + " mi"

private fun worldX(lon: Double) = (lon + 180) / 360
private fun worldY(lat: Double): Double {
    val la = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2
}
private fun latitude(wy: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * wy))))

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The newest position the device already knows, without waiting; null without permission. */
@Suppress("MissingPermission")
private fun lastKnown(context: Context): Location? = runCatching {
    if (!hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
}.getOrNull()

/** A fresh position (GPS, else network), or the last known one; null after 20 s without any. */
@Suppress("MissingPermission")
private fun currentLocation(context: Context, onResult: (Location?) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    var done = false
    fun finish(l: Location?) { if (!done) { done = true; main.post { onResult(l) } } }
    if (providers.isEmpty()) { finish(lastKnown(context)); return }
    val cancel = android.os.CancellationSignal()
    main.postDelayed({ cancel.cancel(); finish(lastKnown(context)) }, 20_000)
    var pending = providers.size
    for (p in providers) runCatching {
        androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, p, cancel, ContextCompat.getMainExecutor(context)) { l ->
            pending--
            if (l != null) { cancel.cancel(); finish(l) } else if (pending == 0) finish(lastKnown(context))
        }
    }.onFailure { pending--; if (pending == 0) finish(lastKnown(context)) }
}
