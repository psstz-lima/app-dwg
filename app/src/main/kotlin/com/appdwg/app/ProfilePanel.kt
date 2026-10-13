package com.appdwg.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appdwg.core.LongProfile
import com.appdwg.core.StationFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Station windows offered for the profile chart: the whole alignment, or this many metres each side of the cursor. */
val PROFILE_RANGES = listOf(0.0, 500.0, 200.0, 50.0)

private val TERRAIN_COLORS = listOf(Color(0xFF8D6E63), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFFB8C00), Color(0xFF8E24AA))
private val GRADE_COLOR = Color(0xFFE53935)

/**
 * Longitudinal profile panel: terrain of each surface and the design grade along the alignment,
 * PVIs with grades, and a cursor (dragged here or tapped on the plan) that reads the values.
 */
@Composable
fun ProfilePanel(
    profile: LongProfile,
    cursor: Double,
    onCursor: (Double) -> Unit,
    range: Double,
    onRange: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Perfil · ${profile.alignment}", style = MaterialTheme.typography.titleSmall)
                Text("Janela", style = MaterialTheme.typography.labelMedium)
                PROFILE_RANGES.forEach { r ->
                    FilterChip(selected = r == range, onClick = { onRange(r) }, label = { Text(if (r == 0.0) "Inteiro" else "±${r.toInt()} m") })
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Fechar") }
                }
            }
            ProfileChart(profile, cursor, onCursor, range, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ProfileChart(p: LongProfile, cursor: Double, onCursor: (Double) -> Unit, range: Double, modifier: Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    // Visible stations: the whole alignment or a window around the cursor kept inside it.
    val (s0, s1) = if (range <= 0 || 2 * range >= p.end - p.start) p.start to p.end else {
        val a = (cursor - range).coerceIn(p.start, p.end - 2 * range)
        a to a + 2 * range
    }
    fun pick(x: Float, width: Float, left: Float, right: Float) =
        (s0 + (s1 - s0) * ((x - left) / (right - left))).coerceIn(p.start, p.end)

    Canvas(
        modifier
            .pointerInput(s0, s1) {
                detectTapGestures { onCursor(pick(it.x, size.width.toFloat(), 64.dp.toPx(), size.width - 16.dp.toPx())) }
            }
            .pointerInput(s0, s1, "drag") {
                detectDragGestures { change, _ ->
                    onCursor(pick(change.position.x, size.width.toFloat(), 64.dp.toPx(), size.width - 16.dp.toPx()))
                }
            },
    ) {
        val left = 64.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 28.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        val i0 = ((s0 - p.start) / (p.end - p.start) * (p.stations.size - 1)).toInt().coerceIn(0, p.stations.size - 1)
        val i1 = ceil((s1 - p.start) / (p.end - p.start) * (p.stations.size - 1)).toInt().coerceIn(0, p.stations.size - 1)
        val values = ArrayList<Double>()
        for (i in i0..i1) {
            p.lines.forEach { l -> l.elevations[i].takeIf { !it.isNaN() }?.let(values::add) }
            p.design?.get(i)?.takeIf { !it.isNaN() }?.let(values::add)
        }
        if (values.isEmpty()) {
            text("Sem superfície nem greide neste trecho.", left, (top + bottom) / 2, fg)
            return@Canvas
        }
        var zMin = values.min()
        var zMax = values.max()
        val pad = max((zMax - zMin) * 0.08, 0.5)
        zMin -= pad; zMax += pad
        fun x(s: Double) = (left + (s - s0) / (s1 - s0) * (right - left)).toFloat()
        fun y(z: Double) = (bottom - (z - zMin) / (zMax - zMin) * (bottom - top)).toFloat()

        // Station grid on whole 20 m stakes, elevation grid on round steps.
        val sStep = max(20.0, niceStep((s1 - s0) / 8))
        var s = ceil(s0 / sStep) * sStep
        while (s <= s1 + 1e-9) {
            drawLine(grid, Offset(x(s), top), Offset(x(s), bottom), strokeWidth = 1f)
            text(StationFormat.estaca(s).substringBefore(" + 0,00"), x(s) - 14f, bottom + 18.dp.toPx(), fg, small = true)
            s += sStep
        }
        val zStep = niceStep((zMax - zMin) / 5)
        var z = ceil(zMin / zStep) * zStep
        while (z <= zMax) {
            drawLine(grid, Offset(left, y(z)), Offset(right, y(z)), strokeWidth = 1f)
            text(StationFormat.decimal(z, if (zStep < 1) 2 else 0), 4f, y(z) + 4f, fg, small = true)
            z += zStep
        }
        fun drawSeries(v: DoubleArray, color: Color, width: Float) {
            val path = Path()
            var pen = false
            for (i in i0..i1) {
                val e = v[i]
                if (e.isNaN()) { pen = false; continue }
                if (pen) path.lineTo(x(p.stations[i]), y(e)) else path.moveTo(x(p.stations[i]), y(e))
                pen = true
            }
            drawPath(path, color, style = Stroke(width = width))
        }
        p.lines.forEachIndexed { k, l -> drawSeries(l.elevations, TERRAIN_COLORS[k % TERRAIN_COLORS.size], 3f) }
        p.design?.let { drawSeries(it, GRADE_COLOR, 3.5f) }
        // PVIs with their station and, between them, the grade in percent.
        p.pvis.forEachIndexed { k, v ->
            if (v.station in s0..s1) {
                drawCircle(GRADE_COLOR, 6f, Offset(x(v.station), y(v.elevation)))
                if (v.curveLength > 0) text("CV ${StationFormat.decimal(v.curveLength, 0)}", x(v.station) + 6f, y(v.elevation) - 10f, GRADE_COLOR, small = true)
            }
            if (k > 0) {
                val a = p.pvis[k - 1]
                val mid = (a.station + v.station) / 2
                if (mid in s0..s1 && v.station != a.station) {
                    val g = (v.elevation - a.elevation) / (v.station - a.station) * 100
                    text((if (g >= 0) "+" else "−") + StationFormat.decimal(abs(g), 2) + " %", x(mid) - 20f, y((a.elevation + v.elevation) / 2) - 12f, GRADE_COLOR, small = true)
                }
            }
        }
        // Cursor and readout.
        val c = cursor.coerceIn(s0, s1)
        drawLine(fg, Offset(x(c), top), Offset(x(c), bottom), strokeWidth = 1.5f)
        val parts = mutableListOf("Estaca ${StationFormat.estaca(c)}")
        var terrain: Double? = null
        p.lines.forEachIndexed { k, l ->
            val e = p.interpolate(l.elevations, c)
            if (!e.isNaN()) {
                if (terrain == null) terrain = e
                drawCircle(TERRAIN_COLORS[k % TERRAIN_COLORS.size], 6f, Offset(x(c), y(e)))
                parts += "${l.surface.substringAfterLast('|')} ${StationFormat.decimal(e, 3)}"
            }
        }
        val g = p.design?.let { p.interpolate(it, c) }?.takeIf { !it.isNaN() }
        g?.let {
            drawCircle(GRADE_COLOR, 6f, Offset(x(c), y(it)))
            parts += "Greide ${StationFormat.decimal(it, 3)}"
            terrain?.let { t -> parts += (if (it >= t) "Aterro " else "Corte ") + StationFormat.decimal(abs(it - t), 3) }
        }
        val exaggeration = ((bottom - top) / (zMax - zMin)) / ((right - left) / (s1 - s0))
        parts += "V/H ×" + StationFormat.decimal(exaggeration, 1)
        text(parts.joinToString(" · "), left, 18.dp.toPx(), fg)
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

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val pw = 10.0.pow(floor(log10(raw)))
    val f = raw / pw
    return pw * when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
}
