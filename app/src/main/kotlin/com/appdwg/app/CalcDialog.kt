package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.Cogo
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Measure
import com.appdwg.core.Point2
import com.appdwg.core.PointFile
import com.appdwg.core.StationFormat

private enum class CalcTab(private val labelPt: String) { INVERSE("Inverso"), RADIATE("Irradiação"), INTERSECT("Interseção"), AREA("Área/perímetro"), CONVERT("Conversões"); val label: String get() = tr(labelPt) }

private fun num(s: String) = StationFormat.number(s)
/** Distances and coordinates typed in the shown unit, in metres. */
private fun len(s: String) = StationFormat.parseLength(s)

/** A point typed as a point number/name, or as "E N" (or "E;N"); null when neither. */
private fun resolve(text: String, project: LandXmlProject, points: List<CogoPoint>): Pair<Point2, Double?>? {
    val t = text.trim()
    if (t.isEmpty()) return null
    points.firstOrNull { it.number.equals(t, true) }?.let { return Point2(it.x, it.y) to it.z.takeIf { z -> !z.isNaN() } }
    project.cgPoints.firstOrNull { it.name.equals(t, true) }?.let { return Point2(it.position.x, it.position.y) to it.position.z.takeIf { z -> !z.isNaN() } }
    val parts = t.split(Regex("[;\\s]+")).mapNotNull { len(it) }
    return if (parts.size >= 2) Point2(parts[0], parts[1]) to parts.getOrNull(2) else null
}

/** Field calculations; resulting points can become _POINTS. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalcDialog(vm: ProjectViewModel, project: LandXmlProject, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(CalcTab.INVERSE) }
    var pa by remember { mutableStateOf("") }
    var pb by remember { mutableStateOf("") }
    var az by remember { mutableStateOf("") }
    var az2 by remember { mutableStateOf("") }
    var dist by remember { mutableStateOf("") }
    var dist2 by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) }
    var areaPts by remember { mutableStateOf("") }
    var conv by remember { mutableStateOf("") }
    var created by remember { mutableStateOf<String?>(null) }
    val pts = vm.userPoints
    @Composable
    fun pointField(v: String, set: (String) -> Unit, label: String) {
        val r = resolve(v, project, pts)
        OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, modifier = Modifier.width(240.dp), isError = v.isNotBlank() && r == null,
            supportingText = { Text(r?.let { "E ${f3(it.first.x)} N ${f3(it.first.y)}" } ?: tr("nº do ponto ou \"E N\"")) })
    }
    @Composable
    fun field(v: String, set: (String) -> Unit, label: String, w: Int = 160) =
        OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, modifier = Modifier.width(w.dp), isError = v.isNotBlank() && num(v) == null)
    @Composable
    fun results(points: List<Point2>) {
        points.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Ponto{0}: E {1} · N {2}", if (points.size > 1) " ${i + 1}" else "", f3(p.x), f3(p.y)), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    val n = PointFile.nextNumber(vm.userPoints)
                    vm.savePoint(null, CogoPoint(n, "CALC", p.x, p.y, project.surfaces.firstNotNullOfOrNull { it.elevationAt(p) } ?: Double.NaN))
                    created = tr("Ponto {0} criado na camada _POINTS.", n)
                }) { Text(tr("Criar ponto _POINTS")) }
            }
        }
        if (points.isEmpty()) Text(tr("Sem solução com esses valores."), style = MaterialTheme.typography.bodySmall)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f),
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        title = { Text(tr("Cálculos de campo")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalcTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) }
                }
                when (tab) {
                    CalcTab.INVERSE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pointField(pa, { pa = it }, tr("De (A)")); pointField(pb, { pb = it }, tr("Para (B)")) }
                        val a = resolve(pa, project, pts); val b = resolve(pb, project, pts)
                        if (a != null && b != null) {
                            val za = a.second ?: project.surfaces.firstNotNullOfOrNull { it.elevationAt(a.first) }
                            val zb = b.second ?: project.surfaces.firstNotNullOfOrNull { it.elevationAt(b.first) }
                            val inv = Cogo.inverse(a.first, b.first, za, zb)
                            Text(tr("Distância {0} {1} · Azimute {2} ({3}°)", f3(inv.distance), StationFormat.lengthUnit, Measure.dms(inv.azimuth), StationFormat.decimal(inv.azimuth, 6)))
                            Text("ΔE ${f3(b.first.x - a.first.x)} · ΔN ${f3(b.first.y - a.first.y)}" +
                                (inv.rise?.let { tr(" · Desnível {0} · Inclinada {1} · Rampa {2} %", f3(it), f3(inv.slopeDistance!!), StationFormat.decimal(inv.gradePercent!!, 2)) } ?: ""))
                        }
                    }
                    CalcTab.RADIATE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pointField(pa, { pa = it }, tr("Estação")); field(az, { az = it }, tr("Azimute (°)")); field(dist, { dist = it }, tr("Distância ({0})", StationFormat.lengthUnit))
                        }
                        val a = resolve(pa, project, pts); val z = num(az); val d = len(dist)
                        if (a != null && z != null && d != null) results(listOf(Cogo.radiate(a.first, z, d)))
                    }
                    CalcTab.INTERSECT -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(tr("Azimute × azimute"), tr("Distância × distância"), tr("Azimute × distância")).forEachIndexed { i, l ->
                                FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(l) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pointField(pa, { pa = it }, tr("Ponto A"))
                            if (mode == 1) field(dist, { dist = it }, tr("Distância de A")) else field(az, { az = it }, tr("Azimute de A (°)"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pointField(pb, { pb = it }, tr("Ponto B"))
                            if (mode == 0) field(az2, { az2 = it }, tr("Azimute de B (°)")) else field(dist2, { dist2 = it }, tr("Distância de B"))
                        }
                        val a = resolve(pa, project, pts)?.first; val b = resolve(pb, project, pts)?.first
                        if (a != null && b != null) when (mode) {
                            0 -> { val x = num(az); val y = num(az2); if (x != null && y != null) results(listOfNotNull(Cogo.bearingBearing(a, x, b, y))) }
                            1 -> { val x = len(dist); val y = len(dist2); if (x != null && y != null) results(Cogo.distanceDistance(a, x, b, y)) }
                            else -> { val x = num(az); val y = len(dist2); if (x != null && y != null) results(Cogo.bearingDistance(a, x, b, y)) }
                        }
                    }
                    CalcTab.AREA -> {
                        OutlinedTextField(areaPts, { areaPts = it }, label = { Text(tr("Pontos na ordem (nºs separados por vírgula, ex.: 101, 102, 104)")) },
                            modifier = Modifier.fillMaxWidth())
                        val list = areaPts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { resolve(it, project, pts) }
                        if (list.size >= 2 && list.all { it != null }) {
                            val m = Measure.of(list.map { it!!.first }, list.map { it!!.second })
                            Text(tr("Perímetro {0} {1}", m.perimeter?.let { f3(it) } ?: f3(m.length), StationFormat.lengthUnit) + (m.area?.let { tr(" · Área {0}", Measure.area(it)) } ?: ""))
                        } else if (list.isNotEmpty()) Text(tr("Algum ponto não foi encontrado."), style = MaterialTheme.typography.bodySmall)
                    }
                    CalcTab.CONVERT -> {
                        OutlinedTextField(conv, { conv = it }, label = { Text(tr("Valor (estaca \"12+5,4\", metros, ângulo \"45 30 15\", rampa %)")) }, modifier = Modifier.fillMaxWidth())
                        val parts = conv.trim().split(Regex("[\\s°'\"]+")).mapNotNull { num(it) }
                        StationFormat.parse(conv)?.let { m -> Text(tr("Estaca {0} = {1}", StationFormat.estaca(m), StationFormat.length(m, 3))) }
                        if (parts.isNotEmpty()) {
                            val deg = Cogo.fromDms(parts[0], parts.getOrElse(1) { 0.0 }, parts.getOrElse(2) { 0.0 })
                            Text(tr("Ângulo: {0}° = {1} = {2} gon", StationFormat.decimal(deg, 6), Measure.dms(deg), StationFormat.decimal(Cogo.toGon(deg), 6)))
                            if (parts.size == 1 && parts[0] != 0.0) Text(tr("Rampa {0} % = talude {1}:1 (H:V)", StationFormat.decimal(parts[0], 3), StationFormat.decimal(Cogo.percentToRatio(parts[0]), 3)))
                        }
                    }
                }
                created?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
