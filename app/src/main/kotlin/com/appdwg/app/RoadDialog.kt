package com.appdwg.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.CorridorResult
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Profile
import com.appdwg.core.SideTemplate
import com.appdwg.core.SlopeRule
import com.appdwg.core.StationFormat
import com.appdwg.core.Superelevation

private enum class RoadTab(val label: String) { AXIS("Eixo"), GRADE("Greide"), SECTION("Seção tipo"), REGIONS("Regiões"), SLOPES("Taludes"), SE("Superelevação"), RESULT("Resultado") }

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
private fun f(v: Double, d: Int = 2) = StationFormat.decimal(v, d)

/** Road (corridor) design: axis, grade, typical section, slopes, superelevation and the results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoadDialog(
    vm: ProjectViewModel,
    project: LandXmlProject,
    onDrawAxis: () -> Unit,
    onServiceNote: () -> Unit,
    /** Asks the plan for a line to be the edge target of region [index] on the left (−1) or right (1). */
    onPickTarget: (index: Int, side: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val v = vm.view ?: return
    var rp by remember { mutableStateOf(v.roadParams) }
    var tab by remember { mutableStateOf(if (vm.road != null) RoadTab.RESULT else RoadTab.AXIS) }
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun numField(value: Double, label: String, width: Int = 150, onChange: (Double) -> Unit) {
        var t by remember(label, value) { mutableStateOf(f(value, 3).trimEnd('0').trimEnd(',')) }
        OutlinedTextField(t, { t = it; num(it)?.let(onChange) }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.width(width.dp), keyboardOptions = text, isError = num(t) == null)
    }
    val axes = project.alignments.filter { !it.name.endsWith(ROAD_SUFFIX) }
    AlertDialog(
        onDismissRequest = { v.roadParams = rp; onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        confirmButton = {
            TextButton(enabled = vm.tasks.isEmpty(), onClick = { v.roadParams = rp; vm.designRoad(); tab = RoadTab.RESULT }) { Text("Calcular") }
        },
        dismissButton = {
            Row {
                if (vm.road != null) TextButton(onClick = { vm.removeRoad() }) { Text("Remover estrada") }
                TextButton(onClick = { v.roadParams = rp; onDismiss() }) { Text("Fechar") }
            }
        },
        title = { Text("Projeto de estrada (corredor)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoadTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) }
                }
                when (tab) {
                    RoadTab.AXIS -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = rp.alignmentIndex < 0, onClick = { rp = rp.copy(alignmentIndex = -1) }, label = { Text("Novo eixo por PIs") })
                            axes.forEachIndexed { i, a -> FilterChip(selected = rp.alignmentIndex == i, onClick = { rp = rp.copy(alignmentIndex = i) }, label = { Text(a.name) }) }
                        }
                        if (rp.alignmentIndex < 0) {
                            Text("PIs: ${rp.pis.size}. O primeiro e o último são o início e o fim; nos demais entra curva circular com espirais.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { v.roadParams = rp; onDrawAxis() }) { Text("Desenhar PIs na planta") }
                                OutlinedButton(enabled = vm.userPoints.size >= 2, onClick = {
                                    rp = rp.copy(pis = vm.userPoints.map { com.appdwg.core.Point2(it.x, it.y) })
                                }) { Text("Usar pontos _POINTS") }
                                numField(rp.radius, "Raio (m)") { rp = rp.copy(radius = it) }
                                numField(rp.spiral, "Espiral Ls (m, 0 = sem)", 190) { rp = rp.copy(spiral = it) }
                            }
                        } else Text("Usa o alinhamento do arquivo, com estaqueamento de 20 m.", style = MaterialTheme.typography.bodySmall)
                        val names = project.surfaces.map { it.name }.filter { !it.startsWith(ROAD_LABEL) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Terreno")
                            var open by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { open = true }) { Text(rp.terrain ?: names.firstOrNull() ?: "—", maxLines = 1) }
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { rp = rp.copy(terrain = n); open = false }) }
                                }
                            }
                        }
                    }
                    RoadTab.GRADE -> {
                        val axis = vm.roadAxis(rp, project)
                        Text(if (rp.pvis.isEmpty()) (if (axis?.profile != null) "Usando o greide do arquivo. Edite abaixo para substituir." else "Sem greide: gere ou digite os PVIs.")
                            else "${rp.pvis.size} PVI(s). Curva vertical: comprimento digitado ou K × |Δg|.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            numField(rp.kValue, "K das curvas verticais") { rp = rp.copy(kValue = it) }
                            OutlinedButton(enabled = axis != null, onClick = {
                                // PVIs on the terrain every 100 m, a starting point to adjust.
                                val a = axis!!
                                val terrain = project.surfaces.firstOrNull { it.name == rp.terrain } ?: project.surfaces.firstOrNull { !it.name.startsWith(ROAD_LABEL) }
                                val stas = (generateSequence(a.startStation) { it + 100.0 }.takeWhile { it < a.endStation } + a.endStation).toList()
                                rp = rp.copy(pvis = stas.map { s -> Profile.Pvi(s, a.pointAt(s)?.let { terrain?.elevationAt(it) } ?: 0.0) })
                            }) { Text("Gerar PVIs no terreno (a cada 100 m)") }
                            if (axis?.profile != null) OutlinedButton(onClick = { rp = rp.copy(pvis = axis.profile!!.pvis) }) { Text("Copiar greide do arquivo") }
                        }
                        rp.pvis.forEachIndexed { i, p ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("PVI ${i + 1}", Modifier.width(60.dp))
                                var st by remember(i, p.station) { mutableStateOf(StationFormat.estaca(p.station)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(station = s) }) } },
                                    label = { Text("Estaca") }, singleLine = true, modifier = Modifier.width(150.dp))
                                numField(p.elevation, "Cota") { z -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(elevation = z) }) }
                                numField(p.curveLength, "Curva vertical (m)") { c -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(curveLength = c) }) }
                                if (i > 0) Text("rampa " + f((p.elevation - rp.pvis[i - 1].elevation) / (p.station - rp.pvis[i - 1].station) * 100) + " %",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { rp = rp.copy(pvis = rp.pvis.filterIndexed { j, _ -> j != i }) }) { Text("Remover") }
                            }
                        }
                        Row {
                            TextButton(onClick = {
                                val last = rp.pvis.lastOrNull()
                                rp = rp.copy(pvis = rp.pvis + Profile.Pvi((last?.station ?: 0.0) + 100, last?.elevation ?: 0.0))
                            }) { Text("Adicionar PVI") }
                            if (rp.pvis.isNotEmpty()) TextButton(onClick = { rp = rp.copy(pvis = emptyList()) }) { Text("Limpar") }
                        }
                    }
                    RoadTab.SECTION -> {
                        var same by remember { mutableStateOf(rp.left == rp.right) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = same, onCheckedChange = { same = it; if (it) rp = rp.copy(right = rp.left) })
                            Text("Lados iguais")
                        }
                        SideEditor("Lado esquerdo", rp.left) { s -> rp = if (same) rp.copy(left = s, right = s) else rp.copy(left = s) }
                        if (!same) SideEditor("Lado direito", rp.right) { s -> rp = rp.copy(right = s) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            numField(rp.pavement, "Espessura do pavimento (m)", 220) { rp = rp.copy(pavement = it) }
                            numField(rp.step, "Seções a cada (m)") { rp = rp.copy(step = it.coerceAtLeast(1.0)) }
                        }
                        Text("Caimento negativo desce para fora. A sarjeta é um degrau no fim do acostamento; a valeta (em V) entra só em corte.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    RoadTab.SLOPES -> {
                        SlopeEditor("Corte", rp.cut) { rp = rp.copy(cut = it) }
                        SlopeEditor("Aterro", rp.fill) { rp = rp.copy(fill = it) }
                        numField(rp.swell, "Fator de empolamento/compactação do aterro", 330) { rp = rp.copy(swell = it) }
                    }
                    RoadTab.SE -> {
                        Text("Caimento de cada pista por estaca, em % (negativo desce para fora; positivo sobe para fora). Interpolado entre as estacas; " +
                            "sem linhas, vale o caimento da seção tipo.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            numField(rp.speed, "Velocidade (km/h)") { rp = rp.copy(speed = it) }
                            numField(rp.emax, "e máx. (%)") { rp = rp.copy(emax = it) }
                            val axis = vm.roadAxis(rp, project)
                            OutlinedButton(enabled = axis != null, onClick = {
                                rp = rp.copy(superelevation = com.appdwg.core.Superelevations.auto(axis!!, rp.speed, rp.emax, -rp.left.laneSlope.coerceAtMost(0.0).let { if (it == 0.0) 2.0 else it }))
                            }) { Text("Calcular pelas curvas (DNIT)") }
                        }
                        rp.superelevation.forEachIndexed { i, e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                var st by remember(i, e.station) { mutableStateOf(StationFormat.estaca(e.station)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(station = s) }) } },
                                    label = { Text("Estaca") }, singleLine = true, modifier = Modifier.width(150.dp))
                                numField(e.left, "Pista esquerda %") { v2 -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(left = v2) }) }
                                numField(e.right, "Pista direita %") { v2 -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(right = v2) }) }
                                TextButton(onClick = { rp = rp.copy(superelevation = rp.superelevation.filterIndexed { j, _ -> j != i }) }) { Text("Remover") }
                            }
                        }
                        Row {
                            TextButton(onClick = { rp = rp.copy(superelevation = rp.superelevation + Superelevation((rp.superelevation.lastOrNull()?.station ?: 0.0) + 20, -2.0, -2.0)) }) {
                                Text("Adicionar estaca")
                            }
                            if (rp.superelevation.isNotEmpty()) TextButton(onClick = { rp = rp.copy(superelevation = emptyList()) }) { Text("Limpar") }
                        }
                    }
                    RoadTab.REGIONS -> {
                        Text("A seção tipo vale do início do eixo. Cada região muda as larguras a partir da sua estaca, com transição linear; " +
                            "um alvo (linha tocada na planta) faz o bordo da pista seguir essa linha.", style = MaterialTheme.typography.bodySmall)
                        rp.regions.forEachIndexed { i, r ->
                            HorizontalDivider()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                var st by remember(i, r.from) { mutableStateOf(StationFormat.estaca(r.from)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(from = s) }) } },
                                    label = { Text("A partir da estaca") }, singleLine = true, modifier = Modifier.width(170.dp))
                                numField(r.transition, "Transição (m)") { t -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(transition = t) }) }
                                OutlinedButton(onClick = { v.roadParams = rp; onPickTarget(i, -1) }) { Text(if (r.leftTarget != null) "Alvo E ✓ (trocar)" else "Alvo bordo E") }
                                OutlinedButton(onClick = { v.roadParams = rp; onPickTarget(i, 1) }) { Text(if (r.rightTarget != null) "Alvo D ✓ (trocar)" else "Alvo bordo D") }
                                if (r.leftTarget != null || r.rightTarget != null) TextButton(onClick = {
                                    rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(leftTarget = null, rightTarget = null) })
                                }) { Text("Sem alvos") }
                                TextButton(onClick = { rp = rp.copy(regions = rp.regions.filterIndexed { j, _ -> j != i }) }) { Text("Remover") }
                            }
                            SideEditor("Esquerda", r.left) { sd -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(left = sd) }) }
                            SideEditor("Direita", r.right) { sd -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(right = sd) }) }
                        }
                        TextButton(onClick = {
                            val last = rp.regions.lastOrNull()
                            rp = rp.copy(regions = rp.regions + RoadRegion((last?.from ?: 0.0) + 100, last?.left ?: rp.left, last?.right ?: rp.right))
                        }) { Text("Adicionar região") }
                    }
                    RoadTab.RESULT -> {
                        vm.tasks.forEach { LoadProgress(it) }
                        vm.roadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        vm.road?.let { r -> RoadResult(r, onServiceNote) } ?: if (vm.tasks.isEmpty()) Text("Toque em Calcular.") else Unit
                    }
                }
            }
        },
    )
}

@Composable
private fun SideEditor(title: String, s: SideTemplate, onChange: (SideTemplate) -> Unit) {
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun field(value: Double, label: String, set: (Double) -> SideTemplate) {
        var t by remember(label, value) { mutableStateOf(f(value, 3).trimEnd('0').trimEnd(',')) }
        OutlinedTextField(t, { t = it; num(it)?.let { x -> onChange(set(x)) } }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.width(150.dp), keyboardOptions = text, isError = num(t) == null)
    }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field(s.laneWidth, "Pista (m)") { s.copy(laneWidth = it) }
        field(s.laneSlope, "Caimento pista %") { s.copy(laneSlope = it) }
        field(s.shoulderWidth, "Acostamento (m)") { s.copy(shoulderWidth = it) }
        field(s.shoulderSlope, "Caimento acost. %") { s.copy(shoulderSlope = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field(s.gutterWidth, "Sarjeta (m)") { s.copy(gutterWidth = it) }
        field(s.gutterDepth, "Degrau sarjeta (m)") { s.copy(gutterDepth = it) }
        field(s.ditchWidth, "Valeta (m)") { s.copy(ditchWidth = it) }
        field(s.ditchDepth, "Prof. valeta (m)") { s.copy(ditchDepth = it) }
    }
}

@Composable
private fun SlopeEditor(title: String, r: SlopeRule, onChange: (SlopeRule) -> Unit) {
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun field(value: Double, label: String, set: (Double) -> SlopeRule?) {
        var t by remember(label, value) { mutableStateOf(f(value, 3).trimEnd('0').trimEnd(',')) }
        OutlinedTextField(t, { t = it; num(it)?.let { x -> set(x)?.let(onChange) } }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.width(190.dp), keyboardOptions = text, isError = num(t) == null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(70.dp), style = MaterialTheme.typography.labelLarge)
        field(r.ratio, "Talude H:V") { if (it > 0) r.copy(ratio = it) else null }
        field(r.benchHeight, "Altura entre bermas (m)") { r.copy(benchHeight = it) }
        field(r.benchWidth, "Largura berma (m)") { r.copy(benchWidth = it) }
        field(r.benchFall, "Caimento berma %") { r.copy(benchFall = it) }
    }
}

@Composable
private fun RoadResult(r: CorridorResult, onServiceNote: () -> Unit) {
    Text("Eixo ${r.alignment.name}: ${f(r.alignment.length)} m, ${r.sections.size} seções", style = MaterialTheme.typography.labelLarge)
    Text("Corte ${f(r.totalCut, 1)} m³ · Aterro ${f(r.totalFill, 1)} m³ · Saldo final (Brückner) ${f(r.volumes.last().accumulated, 1)} m³")
    Text("Superfícies \"$ROAD_LABEL\" (acabada) e \"$ROAD_LABEL (terraplenagem)\" e o eixo com greide já estão no projeto: use Seção, Perfil e Comparar.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onServiceNote) { Text("Exportar nota de serviço e volumes (CSV)") }
    HorizontalDivider()
    Text("Diagrama de Brückner (volume acumulado: acima = sobra de corte)", style = MaterialTheme.typography.labelMedium)
    val fg = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val vs = r.volumes
        val minV = minOf(0.0, vs.minOf { it.accumulated }); val maxV = maxOf(0.0, vs.maxOf { it.accumulated })
        val s0 = vs.first().station; val s1 = vs.last().station
        fun x(s: Double) = ((s - s0) / (s1 - s0).coerceAtLeast(1e-9) * size.width).toFloat()
        fun y(v: Double) = (size.height - (v - minV) / (maxV - minV).coerceAtLeast(1e-9) * size.height).toFloat()
        drawLine(fg.copy(alpha = 0.4f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        val path = Path()
        vs.forEachIndexed { i, row -> if (i == 0) path.moveTo(x(row.station), y(row.accumulated)) else path.lineTo(x(row.station), y(row.accumulated)) }
        drawPath(path, Color(0xFF1E88E5), style = Stroke(width = 3f))
    }
    Text("Estaca · corte · aterro · acumulado", style = MaterialTheme.typography.labelMedium)
    r.volumes.filterIndexed { i, _ -> i % maxOf(1, r.volumes.size / 40) == 0 || i == r.volumes.size - 1 }.forEach { row ->
        Text("${StationFormat.estaca(row.station)} · ${f(row.cut, 1)} · ${f(row.fill, 1)} · ${f(row.accumulated, 1)}", style = MaterialTheme.typography.bodySmall)
    }
}
