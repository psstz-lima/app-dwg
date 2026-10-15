package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appdwg.core.CrossSection
import com.appdwg.core.StationFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Half widths offered for the cross section, in metres to each side of the axis. */
val SECTION_WIDTHS = listOf(10.0, 20.0, 30.0, 50.0, 100.0)

/** Colors of the surfaces in the section chart, in surface order. */
private val SECTION_COLORS = listOf(Color(0xFF8D6E63), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFFB8C00), Color(0xFF8E24AA))
private val DESIGN_COLOR = Color(0xFFE53935)

/**
 * Cross section panel: alignment, station (typed, stepped or tapped on the plan), width and the
 * chart of every surface across the axis, with the design grade on the axis.
 */
@Composable
fun SectionPanel(
    section: CrossSection?,
    alignmentNames: List<String>,
    alignmentIndex: Int,
    onAlignment: (Int) -> Unit,
    station: Double,
    onStation: (Double) -> Unit,
    halfWidth: Double,
    onHalfWidth: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    // Taps on the panel must not reach the plan underneath.
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            FitRow(spacedBy = 4.dp) {
                // Scrolls sideways on a narrow panel (phone) instead of squashing the controls.
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Seção"), style = MaterialTheme.typography.titleSmall)
                    if (alignmentNames.size > 1) {
                        var open by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(alignmentNames[alignmentIndex], maxLines = 1) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            alignmentNames.forEachIndexed { i, n ->
                                DropdownMenuItem(text = { Text(n) }, onClick = { onAlignment(i); open = false })
                            }
                        }
                    } else {
                        Text(alignmentNames.firstOrNull() ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    // Steps land on whole 20 m stakes, so a station off a stake goes to the next one.
                    OutlinedButton(onClick = { onStation(ceil(station / 20 - 1e-6) * 20 - 20) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("−20") }
                    StationField(station, onStation)
                    OutlinedButton(onClick = { onStation(floor(station / 20 + 1e-6) * 20 + 20) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+20") }
                    Text(tr("Largura"), style = MaterialTheme.typography.labelMedium)
                    SECTION_WIDTHS.forEach { w ->
                        FilterChip(selected = w == halfWidth, onClick = { onHalfWidth(w) }, label = { Text("±" + StationFormat.lengthValue(w, 0)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) }
            }
            if (section == null) {
                Text(tr("Sem seção nesta estaca."), Modifier.padding(16.dp))
            } else {
                SectionChart(section, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Station typed as "12+5,40" or metres; applied on the keyboard's done key. */
@Composable
private fun StationField(station: Double, onStation: (Double) -> Unit) {
    var text by remember { mutableStateOf(StationFormat.estaca(station)) }
    LaunchedEffect(station) { text = StationFormat.estaca(station) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        isError = StationFormat.parse(text) == null,
        // A hardware Enter applies the station and is consumed here, down and up, so the key-up
        // does not land on whatever takes focus next (it used to switch on Medir).
        modifier = Modifier.widthIn(max = 140.dp).fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown) {
                StationFormat.parse(text)?.let(onStation)
                focus.clearFocus()
            }
            true
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            StationFormat.parse(text)?.let(onStation)
            focus.clearFocus()
        }),
    )
}

@Composable
private fun SectionChart(s: CrossSection, modifier: Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    // Offset under the finger, for reading elevations; starts on the axis.
    var cursor by remember(s.alignment) { mutableStateOf(0.0) }
    val halfWidth = s.offsets.last()

    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(s.offsets.last()) {
                fun pick(x: Float) {
                    val left = 64.dp.toPx()
                    val right = size.width - 16.dp.toPx()
                    cursor = (-halfWidth + 2 * halfWidth * ((x - left) / (right - left))).coerceIn(-halfWidth, halfWidth)
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(s.offsets.last(), "drag") {
                detectDragGestures { change, _ ->
                    val left = 64.dp.toPx()
                    val right = size.width - 16.dp.toPx()
                    cursor = (-halfWidth + 2 * halfWidth * ((change.position.x - left) / (right - left))).coerceIn(-halfWidth, halfWidth)
                }
            },
    ) {
        val left = 64.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 28.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas

        val values = s.lines.flatMap { l -> l.elevations.filter { !it.isNaN() } } + listOfNotNull(s.design)
        if (values.isEmpty()) {
            text(tr("A seção não cruza nenhuma superfície."), left, (top + bottom) / 2, fg)
            return@Canvas
        }
        var zMin = values.min()
        var zMax = values.max()
        val pad = max((zMax - zMin) * 0.1, 0.5)
        zMin -= pad
        zMax += pad
        fun x(o: Double) = (left + (o + halfWidth) / (2 * halfWidth) * (right - left)).toFloat()
        fun y(z: Double) = (bottom - (z - zMin) / (zMax - zMin) * (bottom - top)).toFloat()

        // Grid with round steps and labels.
        // Steps are round in the shown unit (m or ft); positions stay in metres.
        val hwU = StationFormat.toLength(halfWidth)
        val oStep = niceStep(2 * hwU / 8)
        var o = ceil(-hwU / oStep) * oStep
        while (o <= hwU + 1e-9) {
            val om = StationFormat.fromLength(o)
            drawLine(grid, Offset(x(om), top), Offset(x(om), bottom), strokeWidth = 1f)
            val label = if (o == 0.0) "0" else StationFormat.decimal(abs(o), if (oStep < 1) 1 else 0) + if (o > 0) if (com.appdwg.core.Display.portuguese) " D" else " R" else if (com.appdwg.core.Display.portuguese) " E" else " L"
            text(label, x(om) - 12f, bottom + 18.dp.toPx(), fg, small = true)
            o += oStep
        }
        val zMinU = StationFormat.toLength(zMin); val zMaxU = StationFormat.toLength(zMax)
        val zStep = niceStep((zMaxU - zMinU) / 5)
        var z = ceil(zMinU / zStep) * zStep
        while (z <= zMaxU) {
            val zm = StationFormat.fromLength(z)
            drawLine(grid, Offset(left, y(zm)), Offset(right, y(zm)), strokeWidth = 1f)
            text(StationFormat.decimal(z, if (zStep < 1) 2 else 0), 4f, y(zm) + 4f, fg, small = true)
            z += zStep
        }
        // Axis.
        drawLine(fg.copy(alpha = 0.6f), Offset(x(0.0), top), Offset(x(0.0), bottom), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))

        s.lines.forEachIndexed { k, l ->
            val path = Path()
            var pen = false
            for (i in s.offsets.indices) {
                val e = l.elevations[i]
                if (e.isNaN()) { pen = false; continue }
                if (pen) path.lineTo(x(s.offsets[i]), y(e)) else path.moveTo(x(s.offsets[i]), y(e))
                pen = true
            }
            drawPath(path, SECTION_COLORS[k % SECTION_COLORS.size], style = Stroke(width = 3f))
        }
        s.design?.let { d ->
            drawLine(DESIGN_COLOR, Offset(x(0.0) - 24f, y(d)), Offset(x(0.0) + 24f, y(d)), strokeWidth = 4f)
            drawCircle(DESIGN_COLOR, 6f, Offset(x(0.0), y(d)))
        }

        // Cursor and readout.
        val cx = x(cursor)
        drawLine(fg, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.5f)
        val parts = mutableListOf(
            if (abs(cursor) < 0.005) tr("Eixo") else StationFormat.offset(cursor),
        )
        s.lines.forEachIndexed { k, l ->
            val e = s.interpolate(l.elevations, cursor)
            if (!e.isNaN()) {
                drawCircle(SECTION_COLORS[k % SECTION_COLORS.size], 6f, Offset(cx, y(e)))
                parts += "${l.surface} ${StationFormat.lengthValue(e)}"
            }
        }
        s.design?.let { parts += tr("Greide no eixo {0}", StationFormat.lengthValue(it)) }
        s.cutFill?.let { parts += (if (it >= 0) tr("Aterro ") else tr("Corte ")) + StationFormat.length(abs(it), 3) }
        val exaggeration = ((bottom - top) / (zMax - zMin)) / ((right - left) / (2 * halfWidth))
        parts += "V/H ×" + StationFormat.decimal(exaggeration, 1)
        text(tr("Estaca {0} · ", StationFormat.estaca(s.station)) + parts.joinToString(" · "), left, 18.dp.toPx(), fg)
    }
}

private fun DrawScope.text(t: String, x: Float, y: Float, color: Color, small: Boolean = false) {
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = (if (small) 11 else 13).sp.toPx()
        this.color = color.toArgb()
    }
    drawContext.canvas.nativeCanvas.drawText(t, x, y, paint)
}

/** Round grid step (1, 2 or 5 times a power of ten) close to [raw]. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val p = 10.0.pow(floor(log10(raw)))
    val f = raw / p
    return p * when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
}
