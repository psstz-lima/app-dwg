package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetsDialog(vm: ProjectViewModel, project: LandXmlProject, fileName: String, onMake: (SheetParams) -> Unit, onDismiss: () -> Unit) {
    val v = vm.view ?: return
    var p by remember { mutableStateOf(vm.sheetDefaults(v.sheetParams).let { if (it.work.isBlank()) it.copy(work = fileName) else it }) }
    val names = project.alignments.map { it.name }
    val axisName = p.alignment?.takeIf { it in names } ?: names.firstOrNull()
    val axis = project.alignments.firstOrNull { it.name == axisName }
    var fromText by remember(axisName) { mutableStateOf(StationFormat.estaca(p.from ?: axis?.startStation ?: 0.0)) }
    var toText by remember(axisName) { mutableStateOf(StationFormat.estaca(p.to ?: axis?.endStation ?: 0.0)) }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    @Composable
    fun field(value: String, label: Int, w: Int = 170, onValue: (String) -> Unit) =
        OutlinedTextField(value, onValue, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.width(w.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))

    AlertDialog(
        onDismissRequest = { v.sheetParams = p; onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = { Text(stringResource(R.string.sh_title)) },
        dismissButton = { TextButton(onClick = { v.sheetParams = p; onDismiss() }) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            TextButton(enabled = axis != null, onClick = {
                val q = p.copy(alignment = axisName, from = StationFormat.parse(fromText), to = StationFormat.parse(toText))
                v.sheetParams = q; onMake(q)
            }) { Text(stringResource(R.string.sh_make)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sh_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sh_alignment), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { n -> FilterChip(n == axisName, { p = p.copy(alignment = n, from = null, to = null) }, { Text(n) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    field(fromText, R.string.sh_from) { fromText = it }
                    field(toText, R.string.sh_to) { toText = it }
                    var iv by remember { mutableStateOf(StationFormat.decimal(p.interval, 0)) }
                    field(iv, R.string.sh_interval, 130) { iv = it; num(it)?.let { x -> p = p.copy(interval = x) } }
                    var hw by remember { mutableStateOf(StationFormat.decimal(p.halfWidth, 0)) }
                    field(hw, R.string.sh_half, 150) { hw = it; num(it)?.let { x -> p = p.copy(halfWidth = x) } }
                }
                Text(stringResource(R.string.sh_paper), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A3", "A4").forEach { f -> FilterChip(p.paper == f, { p = p.copy(paper = f) }, { Text(f) }) }
                }
                Text(stringResource(R.string.sh_scale), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 200, 250, 500).forEach { s -> FilterChip(p.scale == s, { p = p.copy(scale = s) }, { Text("1:$s") }) }
                }
                Text(stringResource(R.string.sh_exag), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 5, 10).forEach { e -> FilterChip(p.exaggeration == e, { p = p.copy(exaggeration = e) }, { Text("×$e") }) }
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
                vm.tasks.forEach { LoadProgress(it) }
                vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
