package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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

private enum class RoadTab(private val labelPt: String) { AXIS("Eixo"), GRADE("Greide"), SECTION("Seção tipo"), REGIONS("Regiões"), SLOPES("Taludes"), SE("Superelevação"), RESULT("Resultado"); val label: String get() = tr(labelPt) }

private fun num(s: String) = StationFormat.number(s)

/** Fields whose label names the length unit hold lengths: shown in that unit, kept in metres. */
private fun isLength(label: String) = label.contains("(" + StationFormat.lengthUnit)
private fun shown(label: String, v: Double) = if (isLength(label)) StationFormat.toLength(v) else v
private fun stored(label: String, v: Double) = if (isLength(label)) StationFormat.fromLength(v) else v
private fun f(v: Double, d: Int = 2) = StationFormat.decimal(v, d)

/** Road (corridor) design: axis, grade, typical section, slopes, superelevation and the results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoadDialog(
    vm: ProjectViewModel,
    project: LandXmlProject,
    onDrawAxis: () -> Unit,
    onServiceNote: () -> Unit,
    onServiceNotePdf: () -> Unit,
    onBulletin: () -> Unit,
    /** Asks the plan for a line to be the edge target of region [index] on the left (−1) or right (1). */
    onPickTarget: (index: Int, side: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Opens on the grade tab (the app suggests a grade). */
    startOnGrade: Boolean = false,
    /** Shows the horizontal and vertical elements of the axes. */
    onElements: () -> Unit = {},
    /** Chooses which axis information the plan shows. */
    onDisplay: () -> Unit = {},
) {
    val v = vm.view ?: return
    var rp by remember { mutableStateOf(v.roadParams) }
    var tab by remember { mutableStateOf(if (startOnGrade) RoadTab.GRADE else if (vm.road != null) RoadTab.RESULT else RoadTab.AXIS) }
    var autoMaxGrade by remember { mutableStateOf(8.0) }
    var autoSpacing by remember { mutableStateOf(100.0) }
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun numField(value: Double, label: String, width: Int = 150, onChange: (Double) -> Unit) {
        var t by remember(label, value) { mutableStateOf(f(shown(label, value), 3).trimEnd('0').trimEnd(',', '.')) }
        OutlinedTextField(t, { t = it; num(it)?.let { x -> onChange(stored(label, x)) } }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.width(width.dp), keyboardOptions = text, isError = num(t) == null)
    }
    val axes = project.alignments.filter { !it.name.endsWith(ROAD_SUFFIX) }
    AlertDialog(
        onDismissRequest = { v.roadParams = rp; onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.fillMaxWidth(0.95f)),
        confirmButton = {
            OutlinedButton(enabled = vm.tasks.isEmpty(), onClick = { v.roadParams = rp; vm.designRoad(); tab = RoadTab.RESULT }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Calcular")) }
        },
        dismissButton = {
            Row {
                if (vm.road != null) OutlinedButton(onClick = { vm.removeRoad() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover estrada")) }
                OutlinedButton(onClick = { v.roadParams = rp; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) }
            }
        },
        title = { Text(tr("Projeto de estrada (corredor)")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoadTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                when (tab) {
                    RoadTab.AXIS -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = rp.alignmentIndex < 0, onClick = { rp = rp.copy(alignmentIndex = -1) }, label = { Text(tr("Novo eixo por PIs")) }, modifier = Modifier.heightIn(min = 48.dp))
                            axes.forEachIndexed { i, a -> FilterChip(selected = rp.alignmentIndex == i, onClick = { rp = rp.copy(alignmentIndex = i) }, label = { Text(a.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
                        }
                        OutlinedButton(onClick = { v.roadParams = rp; onDisplay() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Exibir no eixo: estacas, PIs, tangentes…")) }
                        if (rp.alignmentIndex < 0) {
                            Text(tr("PIs: {0}. O primeiro e o último são o início e o fim; nos demais entra curva circular com espirais.", rp.pis.size),
                                style = MaterialTheme.typography.bodySmall)
                            FitRow() {
                                OutlinedButton(onClick = { v.roadParams = rp; onDrawAxis() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (rp.pis.isEmpty()) tr("Desenhar PIs na planta") else tr("Editar eixo na planta")) }
                                OutlinedButton(onClick = { v.roadParams = rp; onElements() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Elementos")) }
                                OutlinedButton(enabled = vm.userPoints.size >= 2, onClick = {
                                    rp = rp.copy(pis = vm.userPoints.map { com.appdwg.core.Point2(it.x, it.y) }, radii = emptyList(), spirals = emptyList())
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Usar pontos _POINTS")) }
                                numField(rp.radius, tr("Raio ({0})", StationFormat.lengthUnit)) { rp = rp.copy(radius = it, radii = emptyList(), spirals = emptyList()) }
                                numField(rp.spiral, tr("Espiral Ls ({0}, 0 = sem)", StationFormat.lengthUnit), 190) { rp = rp.copy(spiral = it, radii = emptyList(), spirals = emptyList()) }
                            }
                            vm.shrunkRadii(rp).takeIf { it.isNotEmpty() }?.let { list ->
                                Text(tr("Raio grande demais para as tangentes; reduzido para caber: {0}.",
                                    list.joinToString(", ") { (n, r) -> "PI $n → ${StationFormat.length(r, 0)}" }),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            HorizontalDivider()
                            Text(tr("Traçado automático"), style = MaterialTheme.typography.labelLarge)
                            Text(tr("Marque só a saída e a chegada (o primeiro e o último PI). O app procura caminhos pelo terreno dentro da rampa máxima, com raio mínimo pela velocidade ({0} km/h, e máx. {1} %), desviando de construções e água do OSM baixado.",
                                f(rp.speed, 0), f(rp.emax, 1)), style = MaterialTheme.typography.bodySmall)
                            FitRow() {
                                numField(autoMaxGrade, tr("Rampa máxima %")) { autoMaxGrade = it }
                                numField(rp.speed, tr("Velocidade (km/h)")) { if (it > 0) rp = rp.copy(speed = it) }
                                OutlinedButton(enabled = rp.pis.size >= 2 && vm.tasks.isEmpty() && autoMaxGrade > 0, onClick = {
                                    v.roadParams = rp; vm.suggestRoute(rp, autoMaxGrade)
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Sugerir traçado")) }
                            }
                            vm.routeSuggestions.forEachIndexed { i, r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${i + 1}. " + routeTitle(r.kind), style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(ROUTE_COLORS[i % ROUTE_COLORS.size]))
                                        Text(tr("{0} · {1} · rampa máx. {2} % · corte ≈ {3} · aterro ≈ {4}", StationFormat.length(r.length, 0),
                                            (r.pis.size - 2).let { if (it == 1) tr("1 curva") else tr("{0} curvas", it) },
                                            f(r.grade?.maxGrade ?: 0.0, 2), StationFormat.volume(r.grade?.cut ?: 0.0), StationFormat.volume(r.grade?.fill ?: 0.0)),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(onClick = {
                                        rp = rp.copy(alignmentIndex = -1, pis = r.pis.map { it.position }, radii = r.pis.map { it.radius }, spirals = r.pis.map { it.spiral },
                                            pvis = r.grade?.pvis.orEmpty())
                                        vm.clearRouteSuggestions()
                                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Usar")) }
                                }
                            }
                        } else Text(tr("Usa o alinhamento do arquivo, com estaqueamento de 20 m."), style = MaterialTheme.typography.bodySmall)
                        val names = project.surfaces.map { it.name }.filter { !it.startsWith(ROAD_LABEL) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Terreno"))
                            var open by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text((rp.terrain ?: names.firstOrNull())?.substringAfterLast('|') ?: "—", maxLines = 1) }
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    names.forEach { n -> DropdownMenuItem(text = { Text(n.substringAfterLast('|')) }, onClick = { rp = rp.copy(terrain = n); open = false }) }
                                }
                            }
                        }
                    }
                    RoadTab.GRADE -> {
                        val axis = vm.roadAxis(rp, project)
                        Text(if (rp.pvis.isEmpty()) (if (axis?.profile != null) tr("Usando o greide do arquivo. Edite abaixo para substituir.") else tr("Sem greide: gere ou digite os PVIs."))
                            else tr("{0} PVI(s). Curva vertical: comprimento digitado ou K × |Δg|.", rp.pvis.size), style = MaterialTheme.typography.bodySmall)
                        FitRow() {
                            numField(rp.kValue, tr("K das curvas verticais")) { rp = rp.copy(kValue = it) }
                            OutlinedButton(enabled = axis != null, onClick = {
                                // PVIs on the terrain every 100 m, a starting point to adjust.
                                val a = axis!!
                                val terrain = project.surfaces.firstOrNull { it.name == rp.terrain } ?: project.surfaces.firstOrNull { !it.name.startsWith(ROAD_LABEL) }
                                val stas = (generateSequence(a.startStation) { it + 100.0 }.takeWhile { it < a.endStation } + a.endStation).toList()
                                rp = rp.copy(pvis = stas.map { s -> Profile.Pvi(s, a.pointAt(s)?.let { terrain?.elevationAt(it) } ?: 0.0) })
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Gerar PVIs no terreno (a cada 100 m)")) }
                            if (axis?.profile != null) OutlinedButton(onClick = { rp = rp.copy(pvis = axis.profile!!.pvis) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Copiar greide do arquivo")) }
                        }
                        FitRow() {
                            numField(autoMaxGrade, tr("Rampa máxima %")) { autoMaxGrade = it }
                            numField(autoSpacing, tr("Espaçamento dos PVIs ({0})", StationFormat.lengthUnit)) { autoSpacing = it }
                            OutlinedButton(enabled = axis != null && vm.tasks.isEmpty() && autoMaxGrade > 0 && autoSpacing >= 20, onClick = {
                                vm.suggestGrade(rp, autoMaxGrade, autoSpacing)
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Sugerir greide")) }
                        }
                        vm.gradeSuggestions.forEach { g ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (g.kind == com.appdwg.core.GradeSuggestion.Kind.BALANCED) tr("Corte igual ao aterro") else tr("Menor movimento de terra"),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(tr("{0} PVIs · rampa máx. {1} % · corte ≈ {2} · aterro ≈ {3}", g.pvis.size, f(g.maxGrade, 2),
                                        StationFormat.volume(g.cut), StationFormat.volume(g.fill)), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { rp = rp.copy(pvis = g.pvis) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Usar")) }
                            }
                        }
                        rp.pvis.forEachIndexed { i, p ->
                            FitRow() {
                                Text("PVI ${i + 1}", Modifier.width(60.dp))
                                var st by remember(i, p.station) { mutableStateOf(StationFormat.estaca(p.station)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(station = s) }) } },
                                    label = { Text(tr("Estaca")) }, singleLine = true, modifier = Modifier.widthIn(max = 150.dp).fillMaxWidth())
                                numField(p.elevation, tr("Cota ({0})", StationFormat.lengthUnit)) { z -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(elevation = z) }) }
                                numField(p.curveLength, tr("Curva vertical ({0})", StationFormat.lengthUnit)) { c -> rp = rp.copy(pvis = rp.pvis.toMutableList().also { l -> l[i] = p.copy(curveLength = c) }) }
                                if (i > 0) Text(tr("rampa {0} %", f((p.elevation - rp.pvis[i - 1].elevation) / (p.station - rp.pvis[i - 1].station) * 100)),
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { rp = rp.copy(pvis = rp.pvis.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover")) }
                            }
                        }
                        Row {
                            OutlinedButton(onClick = {
                                val last = rp.pvis.lastOrNull()
                                rp = rp.copy(pvis = rp.pvis + Profile.Pvi((last?.station ?: 0.0) + 100, last?.elevation ?: 0.0))
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Adicionar PVI")) }
                            if (rp.pvis.isNotEmpty()) OutlinedButton(onClick = { rp = rp.copy(pvis = emptyList()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                        }
                    }
                    RoadTab.SECTION -> {
                        var same by remember { mutableStateOf(rp.left == rp.right) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = same, onCheckedChange = { same = it; if (it) rp = rp.copy(right = rp.left) })
                            Text(tr("Lados iguais"))
                        }
                        SideEditor(tr("Lado esquerdo"), rp.left) { s -> rp = if (same) rp.copy(left = s, right = s) else rp.copy(left = s) }
                        if (!same) SideEditor(tr("Lado direito"), rp.right) { s -> rp = rp.copy(right = s) }
                        FitRow() {
                            numField(rp.pavement, tr("Espessura do pavimento ({0})", StationFormat.lengthUnit), 220) { rp = rp.copy(pavement = it) }
                            numField(rp.step, tr("Seções a cada ({0})", StationFormat.lengthUnit)) { rp = rp.copy(step = it.coerceAtLeast(1.0)) }
                        }
                        Text(tr("Caimento negativo desce para fora. A sarjeta é um degrau no fim do acostamento; a valeta (em V) entra só em corte."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    RoadTab.SLOPES -> {
                        SlopeEditor(tr("Corte"), rp.cut) { rp = rp.copy(cut = it) }
                        SlopeEditor(tr("Aterro"), rp.fill) { rp = rp.copy(fill = it) }
                        numField(rp.swell, tr("Fator de empolamento/compactação do aterro"), 330) { rp = rp.copy(swell = it) }
                    }
                    RoadTab.SE -> {
                        Text(tr("Caimento de cada pista por estaca, em % (negativo desce para fora; positivo sobe para fora). Interpolado entre as estacas; ") +
                            tr("sem linhas, vale o caimento da seção tipo."), style = MaterialTheme.typography.bodySmall)
                        FitRow() {
                            numField(rp.speed, tr("Velocidade (km/h)")) { rp = rp.copy(speed = it) }
                            numField(rp.emax, tr("e máx. (%)")) { rp = rp.copy(emax = it) }
                            val axis = vm.roadAxis(rp, project)
                            OutlinedButton(enabled = axis != null, onClick = {
                                rp = rp.copy(superelevation = com.appdwg.core.Superelevations.auto(axis!!, rp.speed, rp.emax, -rp.left.laneSlope.coerceAtMost(0.0).let { if (it == 0.0) 2.0 else it }))
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Calcular pelas curvas (DNIT)")) }
                        }
                        rp.superelevation.forEachIndexed { i, e ->
                            FitRow() {
                                var st by remember(i, e.station) { mutableStateOf(StationFormat.estaca(e.station)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(station = s) }) } },
                                    label = { Text(tr("Estaca")) }, singleLine = true, modifier = Modifier.widthIn(max = 150.dp).fillMaxWidth())
                                numField(e.left, tr("Pista esquerda %")) { v2 -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(left = v2) }) }
                                numField(e.right, tr("Pista direita %")) { v2 -> rp = rp.copy(superelevation = rp.superelevation.toMutableList().also { l -> l[i] = e.copy(right = v2) }) }
                                OutlinedButton(onClick = { rp = rp.copy(superelevation = rp.superelevation.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover")) }
                            }
                        }
                        Row {
                            OutlinedButton(onClick = { rp = rp.copy(superelevation = rp.superelevation + Superelevation((rp.superelevation.lastOrNull()?.station ?: 0.0) + 20, -2.0, -2.0)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(tr("Adicionar estaca"))
                            }
                            if (rp.superelevation.isNotEmpty()) OutlinedButton(onClick = { rp = rp.copy(superelevation = emptyList()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                        }
                    }
                    RoadTab.REGIONS -> {
                        Text(tr("A seção tipo vale do início do eixo. Cada região muda as larguras a partir da sua estaca, com transição linear; ") +
                            tr("um alvo (linha tocada na planta) faz o bordo da pista seguir essa linha."), style = MaterialTheme.typography.bodySmall)
                        rp.regions.forEachIndexed { i, r ->
                            HorizontalDivider()
                            FitRow() {
                                var st by remember(i, r.from) { mutableStateOf(StationFormat.estaca(r.from)) }
                                OutlinedTextField(st, { st = it; StationFormat.parse(it)?.let { s -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(from = s) }) } },
                                    label = { Text(tr("A partir da estaca")) }, singleLine = true, modifier = Modifier.widthIn(max = 170.dp).fillMaxWidth())
                                numField(r.transition, tr("Transição ({0})", StationFormat.lengthUnit)) { t -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(transition = t) }) }
                                OutlinedButton(onClick = { v.roadParams = rp; onPickTarget(i, -1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (r.leftTarget != null) tr("Alvo E ✓ (trocar)") else tr("Alvo bordo E")) }
                                OutlinedButton(onClick = { v.roadParams = rp; onPickTarget(i, 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (r.rightTarget != null) tr("Alvo D ✓ (trocar)") else tr("Alvo bordo D")) }
                                if (r.leftTarget != null || r.rightTarget != null) OutlinedButton(onClick = {
                                    rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(leftTarget = null, rightTarget = null) })
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Sem alvos")) }
                                OutlinedButton(onClick = { rp = rp.copy(regions = rp.regions.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover")) }
                            }
                            SideEditor(tr("Esquerda"), r.left) { sd -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(left = sd) }) }
                            SideEditor(tr("Direita"), r.right) { sd -> rp = rp.copy(regions = rp.regions.toMutableList().also { l -> l[i] = r.copy(right = sd) }) }
                        }
                        OutlinedButton(onClick = {
                            val last = rp.regions.lastOrNull()
                            rp = rp.copy(regions = rp.regions + RoadRegion((last?.from ?: 0.0) + 100, last?.left ?: rp.left, last?.right ?: rp.right))
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Adicionar região")) }
                    }
                    RoadTab.RESULT -> {
                        TaskProgress(vm)
                        vm.roadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        vm.road?.let { r -> RoadResult(r, v.roadParams.swell, onServiceNote, onServiceNotePdf, onBulletin) } ?: if (vm.tasks.isEmpty()) Text(tr("Toque em Calcular.")) else Unit
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
        var t by remember(label, value) { mutableStateOf(f(shown(label, value), 3).trimEnd('0').trimEnd(',', '.')) }
        OutlinedTextField(t, { t = it; num(it)?.let { x -> onChange(set(stored(label, x))) } }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.widthIn(max = 150.dp).fillMaxWidth(), keyboardOptions = text, isError = num(t) == null)
    }
    Text(title, style = MaterialTheme.typography.labelLarge)
    FitRow(spacedBy = 6.dp) {
        field(s.laneWidth, tr("Pista ({0})", StationFormat.lengthUnit)) { s.copy(laneWidth = it) }
        field(s.laneSlope, tr("Caimento pista %")) { s.copy(laneSlope = it) }
        field(s.shoulderWidth, tr("Acostamento ({0})", StationFormat.lengthUnit)) { s.copy(shoulderWidth = it) }
        field(s.shoulderSlope, tr("Caimento acost. %")) { s.copy(shoulderSlope = it) }
    }
    FitRow(spacedBy = 6.dp) {
        field(s.gutterWidth, tr("Sarjeta ({0})", StationFormat.lengthUnit)) { s.copy(gutterWidth = it) }
        field(s.gutterDepth, tr("Degrau sarjeta ({0})", StationFormat.lengthUnit)) { s.copy(gutterDepth = it) }
        field(s.ditchWidth, tr("Valeta ({0})", StationFormat.lengthUnit)) { s.copy(ditchWidth = it) }
        field(s.ditchDepth, tr("Prof. valeta ({0})", StationFormat.lengthUnit)) { s.copy(ditchDepth = it) }
    }
}

@Composable
private fun SlopeEditor(title: String, r: SlopeRule, onChange: (SlopeRule) -> Unit) {
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun field(value: Double, label: String, set: (Double) -> SlopeRule?) {
        var t by remember(label, value) { mutableStateOf(f(shown(label, value), 3).trimEnd('0').trimEnd(',', '.')) }
        OutlinedTextField(t, { t = it; num(it)?.let { x -> set(stored(label, x))?.let(onChange) } }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.widthIn(max = 190.dp).fillMaxWidth(), keyboardOptions = text, isError = num(t) == null)
    }
    FitRow(spacedBy = 6.dp) {
        Text(title, Modifier.width(70.dp), style = MaterialTheme.typography.labelLarge)
        field(r.ratio, tr("Talude H:V")) { if (it > 0) r.copy(ratio = it) else null }
        field(r.benchHeight, tr("Altura entre bermas ({0})", StationFormat.lengthUnit)) { r.copy(benchHeight = it) }
        field(r.benchWidth, tr("Largura berma ({0})", StationFormat.lengthUnit)) { r.copy(benchWidth = it) }
        field(r.benchFall, tr("Caimento berma %")) { r.copy(benchFall = it) }
    }
}

@Composable
private fun RoadResult(r: CorridorResult, swell: Double, onServiceNote: () -> Unit, onServiceNotePdf: () -> Unit, onBulletin: () -> Unit) {
    Text(tr("Eixo {0}: {1}, {2} seções", r.alignment.name, StationFormat.length(r.alignment.length), r.sections.size), style = MaterialTheme.typography.labelLarge)
    Text(tr("Corte {0} · Aterro {1} · Saldo final (Brückner) {2}", StationFormat.volume(r.totalCut), StationFormat.volume(r.totalFill), StationFormat.volume(r.volumes.last().accumulated)))
    Text(tr("Superfícies \"{0}\" (acabada) e \"{1} (terraplenagem)\" e o eixo com greide já estão no projeto: use Seção, Perfil e Comparar.", ROAD_LABEL, ROAD_LABEL),
        style = MaterialTheme.typography.bodySmall)
    FitRow() {
        OutlinedButton(onClick = onServiceNote, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Nota de serviço e volumes (CSV)")) }
        OutlinedButton(onClick = onServiceNotePdf, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Nota de serviço (PDF)")) }
    }
    Text(tr("No Exportar: LandXML leva o eixo, o greide, as seções e as superfícies com breaklines; DXF leva bordos, crista e pé em 3D."),
        style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text(tr("Diagrama de Brückner (volume acumulado: acima = sobra de corte)"), style = MaterialTheme.typography.labelMedium)
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
    HaulSummary(r, swell, onBulletin)
    HorizontalDivider()
    Text(tr("Estaca · corte · aterro · acumulado"), style = MaterialTheme.typography.labelMedium)
    r.volumes.filterIndexed { i, _ -> i % maxOf(1, r.volumes.size / 40) == 0 || i == r.volumes.size - 1 }.forEach { row ->
        Text("${StationFormat.estaca(row.station)} · ${f(row.cut, 1)} · ${f(row.fill, 1)} · ${f(row.accumulated, 1)}", style = MaterialTheme.typography.bodySmall)
    }
}

/** Mass distribution from the Brückner diagram: waves, DMT, bands, spoil and borrow. */
@Composable
private fun HaulSummary(r: CorridorResult, swell: Double, onBulletin: () -> Unit) {
    val m = remember(r, swell) { com.appdwg.core.MassHaul.of(r.volumes, swell) }
    HorizontalDivider()
    Text(androidx.compose.ui.res.stringResource(R.string.haul_title), style = MaterialTheme.typography.labelLarge)
    Text(androidx.compose.ui.res.stringResource(R.string.haul_summary, StationFormat.volume(m.hauled), haulMoment(m.moment), StationFormat.length(m.dmt, 0)),
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text(androidx.compose.ui.res.stringResource(R.string.haul_local, StationFormat.volume(m.lateral), StationFormat.volume(m.spoil), StationFormat.volume(m.borrow)), style = MaterialTheme.typography.bodySmall)
    if (m.bands.isNotEmpty()) {
        Text(androidx.compose.ui.res.stringResource(R.string.haul_bands), style = MaterialTheme.typography.labelMedium)
        m.bands.forEach { b ->
            Text(androidx.compose.ui.res.stringResource(R.string.haul_band_row, b.label, StationFormat.volume(b.volume), StationFormat.length(if (b.volume > 0) b.moment / b.volume else 0.0, 0)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (m.waves.isNotEmpty()) {
        Text(androidx.compose.ui.res.stringResource(R.string.haul_waves), style = MaterialTheme.typography.labelMedium)
        val fwd = androidx.compose.ui.res.stringResource(R.string.haul_forward); val back = androidx.compose.ui.res.stringResource(R.string.haul_back)
        m.waves.take(40).forEach { w ->
            Text(androidx.compose.ui.res.stringResource(R.string.haul_wave_row, StationFormat.estaca(w.start), StationFormat.estaca(w.end), StationFormat.volume(w.volume),
                StationFormat.length(w.dmt, 0), if (w.forward) fwd else back), style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = onBulletin, modifier = Modifier.heightIn(min = 48.dp)) { Text(androidx.compose.ui.res.stringResource(R.string.haul_bulletin)) }
}

/** Transport moment: m³·km, or yd³·mi in imperial. */
private fun haulMoment(m3m: Double) = if (com.appdwg.core.Display.imperial)
    StationFormat.grouped(m3m / StationFormat.CUBIC_YARD / 1609.344, 1) + " yd³·mi" else StationFormat.grouped(m3m / 1000, 1) + " m³·km"

/** Colours of the suggested routes on the plan, in the order listed. */
val ROUTE_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF43A047.toInt())

private fun routeTitle(k: com.appdwg.core.RouteSuggestion.Kind) = when (k) {
    com.appdwg.core.RouteSuggestion.Kind.SHORTEST -> tr("Mais curto")
    com.appdwg.core.RouteSuggestion.Kind.BALANCED -> tr("Equilibrado")
    com.appdwg.core.RouteSuggestion.Kind.GENTLEST -> tr("Mais suave (menos terraplenagem)")
}
