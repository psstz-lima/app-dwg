package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.LandXmlProject
import com.appdwg.core.StationFormat
import com.appdwg.core.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetsDialog(vm: ProjectViewModel, project: LandXmlProject, fileName: String, onMake: (SheetParams) -> Unit, onDismiss: () -> Unit,
                 onDrawAxis: () -> Unit = {}, onPickLogo: () -> Unit = {}) {
    val v = vm.view ?: return
    var p by remember { mutableStateOf(vm.sheetDefaults(v.sheetParams).let { if (it.work.isBlank()) it.copy(work = fileName) else it }.let {
        // In imperial a section every 100 ft and 100 ft each side by default, instead of 20 m and 30 m;
        // with a road the 20 m default stays, so sheets fall on its sections and show their areas.
        if (com.appdwg.core.Display.imperial && vm.road == null && it.interval == 20.0 && it.halfWidth == 30.0) it.copy(interval = 100 * StationFormat.FOOT, halfWidth = 100 * StationFormat.FOOT) else it
    }) }
    // Measurement sheets by default once the project has survey states.
    LaunchedEffect(Unit) { if (vm.states.size >= 2 && !p.measurement) p = p.copy(measurement = true) }
    val names = project.alignments.map { it.name } + listOfNotNull(PIS_AXIS.takeIf { v.roadParams.pis.size >= 2 })
    val axisName = p.alignment?.takeIf { it in names } ?: names.firstOrNull()
    val axis = vm.sheetAxis(axisName, project)
    fun axisLabel(n: String) = if (n == PIS_AXIS) tr("Eixo desenhado (PIs)") else n
    // The logo is chosen outside the dialog, straight into the view's settings.
    fun keep() = p.copy(logo = v.sheetParams.logo)
    var fromText by remember(axisName) { mutableStateOf(StationFormat.estaca(p.from ?: axis?.startStation ?: 0.0)) }
    var toText by remember(axisName) { mutableStateOf(StationFormat.estaca(p.to ?: axis?.endStation ?: 0.0)) }
    fun num(s: String) = StationFormat.number(s)?.takeIf { it > 0 }
    @Composable
    fun field(value: String, label: Int, w: Int = 170, onValue: (String) -> Unit) =
        OutlinedTextField(value, onValue, label = { Text(stringResource(label, StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(w.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))

    AlertDialog(
        onDismissRequest = { v.sheetParams = keep(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.fillMaxWidth(0.94f)),
        title = { Text(stringResource(R.string.sh_title)) },
        dismissButton = { OutlinedButton(onClick = { v.sheetParams = keep(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            OutlinedButton(enabled = axis != null, onClick = {
                val q = keep().copy(alignment = axisName, from = StationFormat.parse(fromText), to = StationFormat.parse(toText))
                v.sheetParams = q; onMake(q)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sh_make)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sh_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sh_alignment), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { n -> FilterChip(n == axisName, { p = p.copy(alignment = n, from = null, to = null) }, { Text(axisLabel(n)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                if (names.isEmpty()) Text(tr("O projeto não tem eixo. Desenhe os PIs ou gere um eixo automático pelo meio da área medida."), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { v.sheetParams = keep(); onDrawAxis() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desenhar eixo")) }
                    if (v.roadParams.pis.isEmpty()) OutlinedButton(onClick = { if (vm.generateAutoAxis()) p = p.copy(alignment = PIS_AXIS, from = null, to = null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Gerar eixo automático")) }
                }
                if (vm.states.size >= 2) {
                    Text(tr("Medição"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(p.measurement, { p = p.copy(measurement = !p.measurement) }, { Text(tr("Perfis dos estados ({0})", vm.states.size)) }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(p.complete, { p = p.copy(complete = !p.complete) }, { Text(tr("Completar onde a medição não alcança")) }, enabled = p.measurement, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    if (p.measurement) Text(tr("Cada seção traz os perfis de {0}, as áreas de corte e aterro entre eles e, no fim, o quadro de volumes.",
                        vm.states.joinToString(", ") { it.name }), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    field(fromText, R.string.sh_from) { fromText = it }
                    field(toText, R.string.sh_to) { toText = it }
                    // Typed in the shown unit (m or ft), kept in metres.
                    var iv by remember { mutableStateOf(StationFormat.lengthValue(p.interval, 0)) }
                    field(iv, R.string.sh_interval, 150) { iv = it; num(it)?.let { x -> p = p.copy(interval = StationFormat.fromLength(x)) } }
                    var hw by remember { mutableStateOf(StationFormat.lengthValue(p.halfWidth, 0)) }
                    field(hw, R.string.sh_half, 170) { hw = it; num(it)?.let { x -> p = p.copy(halfWidth = StationFormat.fromLength(x)) } }
                }
                Text(stringResource(R.string.sh_paper), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A3", "A4").forEach { f -> FilterChip(p.paper == f, { p = p.copy(paper = f) }, { Text(f) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                Text(stringResource(R.string.sh_scale), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 200, 250, 500).forEach { s -> FilterChip(p.scale == s, { p = p.copy(scale = s) }, { Text("1:$s") }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                Text(stringResource(R.string.sh_exag), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 5, 10).forEach { e -> FilterChip(p.exaggeration == e, { p = p.copy(exaggeration = e) }, { Text("×$e") }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.sh_block), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    field(p.company, R.string.sh_company, 300) { p = p.copy(company = it) }
                    field(p.work, R.string.sh_work, 300) { p = p.copy(work = it) }
                    field(p.responsible, R.string.sh_resp, 260) { p = p.copy(responsible = it) }
                    field(p.registration, R.string.sh_crea, 160) { p = p.copy(registration = it) }
                    field(p.drawing, R.string.sh_number, 160) { p = p.copy(drawing = it) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (v.sheetParams.logo != null) tr("Logo escolhido") else tr("Sem logo"), Modifier.padding(top = 12.dp))
                    OutlinedButton(onClick = onPickLogo, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Escolher logo")) }
                    if (v.sheetParams.logo != null) OutlinedButton(onClick = { vm.setSheetLogo(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover")) }
                }
                TaskProgress(vm)
                vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
