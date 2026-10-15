package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat

/** Radius of each PI as typed (the default radius where none was given). */
private fun RoadParams.radiusList() = List(pis.size) { radii.getOrNull(it)?.takeIf { r -> r > 0 } ?: radius }
private fun RoadParams.spiralList() = List(pis.size) { spirals.getOrNull(it) ?: spiral }

/**
 * Editor of one PI of the axis being drawn: its radius and transition (spiral) length, a PI
 * inserted after it or the PI removed; the arrows step to the neighbours.
 */
@Composable
fun PiEditor(rp: RoadParams, i: Int, onChange: (RoadParams) -> Unit, onSelect: (Int?) -> Unit) {
    val end = i == 0 || i == rp.pis.size - 1
    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onSelect(i - 1) }, enabled = i > 0) { Text("◀") }
        Text(tr("PI {0}", i + 1) + if (end) " · " + tr("início/fim, sem curva") else "", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { onSelect(i + 1) }, enabled = i < rp.pis.size - 1) { Text("▶") }
        if (!end) {
            var r by remember(i, rp.radiusList()[i]) { mutableStateOf(StationFormat.lengthValue(rp.radiusList()[i], 1)) }
            var ls by remember(i, rp.spiralList()[i]) { mutableStateOf(StationFormat.lengthValue(rp.spiralList()[i], 1)) }
            OutlinedTextField(r, { t ->
                r = t
                StationFormat.number(t)?.takeIf { it > 0 }?.let { x -> onChange(rp.copy(radii = rp.radiusList().toMutableList().apply { set(i, StationFormat.fromLength(x)) })) }
            }, label = { Text(tr("Raio ({0})", StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(130.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(ls, { t ->
                ls = t
                StationFormat.number(t)?.takeIf { it >= 0 }?.let { x -> onChange(rp.copy(spirals = rp.spiralList().toMutableList().apply { set(i, StationFormat.fromLength(x)) })) }
            }, label = { Text(tr("Transição Ls ({0})", StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(150.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (i < rp.pis.size - 1) OutlinedButton(onClick = {
            val a = rp.pis[i]; val b = rp.pis[i + 1]
            val mid = Point2((a.x + b.x) / 2, (a.y + b.y) / 2)
            onChange(rp.copy(pis = rp.pis.toMutableList().apply { add(i + 1, mid) },
                radii = rp.radiusList().toMutableList().apply { add(i + 1, rp.radius) },
                spirals = rp.spiralList().toMutableList().apply { add(i + 1, rp.spiral) }))
            onSelect(i + 1)
        }) { Text(tr("Inserir PI depois")) }
        OutlinedButton(onClick = {
            onChange(rp.copy(pis = rp.pis.filterIndexed { k, _ -> k != i }, radii = rp.radiusList().filterIndexed { k, _ -> k != i },
                spirals = rp.spiralList().filterIndexed { k, _ -> k != i }))
            onSelect(null)
        }) { Text(tr("Apagar PI")) }
    }
}

/** Azimuth as degrees, minutes and seconds: 123°45'06". */
private fun dms(deg: Double): String {
    val total = Math.round(deg * 3600) % (360 * 3600)
    return "%d°%02d'%02d\"".format(total / 3600, total / 60 % 60, total % 60)
}

/**
 * Table with a header, borders and banded rows; a tap on a cell selects it and its row, both
 * highlighted, and a second tap clears it. Columns have fixed widths and scroll sideways together.
 */
@Composable
fun DataTable(headers: List<String>, rows: List<List<String>>, widths: List<androidx.compose.ui.unit.Dp>, modifier: Modifier = Modifier) {
    var sel by remember(rows) { mutableStateOf<Pair<Int, Int>?>(null) }
    val cs = MaterialTheme.colorScheme
    val line = cs.outline
    @Composable
    fun cell(text: String, w: androidx.compose.ui.unit.Dp, bg: androidx.compose.ui.graphics.Color, bold: Boolean, onTap: (() -> Unit)?, picked: Boolean = false) {
        androidx.compose.foundation.layout.Box(
            Modifier.width(w).fillMaxHeight()
                .background(bg)
                .border(if (picked) 2.dp else 0.5.dp, if (picked) cs.primary else line)
                .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = if (bold) cs.onPrimaryContainer else cs.onSurface, maxLines = 2)
        }
    }
    androidx.compose.foundation.layout.Column(modifier.horizontalScroll(rememberScrollState()).border(1.dp, line)) {
        Row(Modifier.height(IntrinsicSize.Min)) { headers.forEachIndexed { c, h -> cell(h, widths[c], cs.primaryContainer, true, null) } }
        rows.forEachIndexed { r, cells ->
            val rowBg = when {
                sel?.first == r -> cs.secondaryContainer
                r % 2 == 1 -> cs.surfaceVariant.copy(alpha = 0.5f)
                else -> cs.surface
            }
            Row(Modifier.height(IntrinsicSize.Min)) {
                cells.forEachIndexed { c, t ->
                    val picked = sel == r to c
                    cell(t, widths.getOrElse(c) { 80.dp }, if (picked) cs.tertiaryContainer else rowBg, false,
                        onTap = { sel = if (picked) null else r to c }, picked = picked)
                }
            }
        }
    }
}

/** Horizontal and vertical elements of the axes: the drawing's alignments and the one laid out with PIs. */
@Composable
fun AlignmentElementsDialog(alignments: List<com.appdwg.core.Alignment>, onDismiss: () -> Unit) {
    var pick by remember { mutableStateOf(0) }
    var vertical by remember { mutableStateOf(false) }
    val a = alignments.getOrNull(pick)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        title = { Text(tr("Elementos do eixo")) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    alignments.forEachIndexed { i, al -> androidx.compose.material3.FilterChip(pick == i, { pick = i }, { Text(al.name) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(!vertical, { vertical = false }, { Text(tr("Horizontais")) })
                    androidx.compose.material3.FilterChip(vertical, { vertical = true }, { Text(tr("Verticais")) })
                }
                if (a == null) { Text(tr("Nenhum eixo.")); return@Column }
                if (!vertical) {
                    val rows = com.appdwg.core.AlignmentReport.horizontal(a).mapIndexed { i, h ->
                        listOf("${i + 1}", tr(h.kind), StationFormat.estaca(h.from), StationFormat.estaca(h.to), StationFormat.length(h.length, 2),
                            h.radius?.let { StationFormat.length(it, 1) } ?: "—", dms(h.azimuth),
                            when (h.right) { true -> tr("direita"); false -> tr("esquerda"); null -> "—" })
                    }
                    DataTable(listOf("#", tr("Elemento"), tr("Início"), tr("Fim"), tr("Comprimento"), tr("Raio"), tr("Azimute"), tr("Lado")), rows,
                        listOf(36.dp, 120.dp, 100.dp, 100.dp, 100.dp, 90.dp, 100.dp, 80.dp))
                    Text(tr("Pontos notáveis"), style = MaterialTheme.typography.titleSmall)
                    val kp = com.appdwg.core.AlignmentReport.keyPoints(a)
                    DataTable(listOf(tr("Ponto"), tr("Estaca"), "E", "N"),
                        kp.map { listOf(it.label, StationFormat.estaca(it.station), StationFormat.lengthValue(it.point.x, 3), StationFormat.lengthValue(it.point.y, 3)) },
                        listOf(70.dp, 110.dp, 130.dp, 130.dp))
                } else {
                    val p = a.profile
                    if (p == null || p.pvis.size < 2) Text(tr("Este eixo ainda não tem greide."), style = MaterialTheme.typography.bodySmall)
                    else {
                        fun g(x: Double?) = x?.let { StationFormat.decimal(it, 3) + " %" } ?: "—"
                        val rows = com.appdwg.core.AlignmentReport.vertical(p).mapIndexed { i, v ->
                            listOf("${i + 1}", StationFormat.estaca(v.station), StationFormat.lengthValue(v.elevation), g(v.gradeIn), g(v.gradeOut),
                                if (v.length > 0) StationFormat.length(v.length, 1) else "—", v.k?.let { StationFormat.decimal(it, 1) } ?: "—",
                                v.pcv?.let { StationFormat.estaca(it) } ?: "—", v.ptv?.let { StationFormat.estaca(it) } ?: "—")
                        }
                        DataTable(listOf("PIV", tr("Estaca"), tr("Cota"), tr("Rampa antes"), tr("Rampa depois"), "L", "K", "PCV", "PTV"), rows,
                            listOf(46.dp, 100.dp, 90.dp, 100.dp, 100.dp, 80.dp, 70.dp, 100.dp, 100.dp))
                    }
                }
                Text(tr("Toque numa célula para destacar a linha e a célula."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** Information of the axes that can be turned off on the plan, one by one. */
object AxisInfo {
    const val LINE = "eixo"
    const val STATIONS = "estacas"
    const val KEYPOINTS = "notaveis"
    const val PIS = "pis"
    const val TANGENTS = "tangentes"
    const val LENGTHS = "comprimentos"
    val all = listOf(LINE, STATIONS, KEYPOINTS, PIS, TANGENTS, LENGTHS)
}

/** Turns each piece of axis information on or off on the plan. */
@Composable
fun AxisDisplayDialog(v: ViewState, onDismiss: () -> Unit) {
    val labels = mapOf(
        AxisInfo.LINE to tr("Linha do eixo (com as curvas)"),
        AxisInfo.STATIONS to tr("Estacas"),
        AxisInfo.KEYPOINTS to tr("Pontos notáveis (PC, PT, TS, SC…)"),
        AxisInfo.PIS to tr("PIs"),
        AxisInfo.TANGENTS to tr("Linhas entre os PIs (tangentes)"),
        AxisInfo.LENGTHS to tr("Extensões das linhas entre os PIs"),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { v.axisHidden = (AxisInfo.all - AxisInfo.STATIONS - AxisInfo.LINE).toSet() }) { Text(tr("Só estacas")) }
                OutlinedButton(onClick = { v.axisHidden = emptySet() }) { Text(tr("Tudo")) }
            }
        },
        title = { Text(tr("Exibir no eixo")) },
        text = {
            androidx.compose.foundation.layout.Column {
                AxisInfo.all.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { v.axisHidden = if (k in v.axisHidden) v.axisHidden - k else v.axisHidden + k },
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(k !in v.axisHidden, { on -> v.axisHidden = if (on) v.axisHidden - k else v.axisHidden + k })
                        Text(labels.getValue(k))
                    }
                }
                Text(tr("PIs, tangentes e extensões aparecem enquanto o eixo é desenhado ou editado na planta."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
