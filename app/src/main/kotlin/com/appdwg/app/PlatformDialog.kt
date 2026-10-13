package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat

/**
 * Platform design: outline (drawn on the plan or taken from the _POINTS), elevation and grade,
 * cut and fill slopes with benches, and the result with its volumes against the terrain.
 */
@Composable
fun PlatformDialog(
    vm: ProjectViewModel,
    project: LandXmlProject,
    onDraw: () -> Unit,
    onDismiss: () -> Unit,
) {
    val v = vm.view ?: return
    val p0 = v.platformParams
    // Fields as typed; parsed when calculating.
    var elevation by remember { mutableStateOf(p0.elevation?.let { StationFormat.decimal(it, 3) } ?: "") }
    var grade by remember { mutableStateOf(StationFormat.decimal(p0.gradePercent, 2)) }
    var azimuth by remember { mutableStateOf(StationFormat.decimal(p0.gradeAzimuth, 1)) }
    var useVertex by remember { mutableStateOf(p0.useVertexElevations) }
    val f = remember {
        mutableMapOf(
            "cr" to StationFormat.decimal(p0.cutRatio, 2), "ch" to heightsText(p0.cutHeights, p0.cutBenchHeight),
            "cw" to StationFormat.decimal(p0.cutBenchWidth, 2), "cf" to StationFormat.decimal(p0.cutBenchFall, 1),
            "fr" to StationFormat.decimal(p0.fillRatio, 2), "fh" to heightsText(p0.fillHeights, p0.fillBenchHeight),
            "fw" to StationFormat.decimal(p0.fillBenchWidth, 2), "ff" to StationFormat.decimal(p0.fillBenchFall, 1),
        )
    }
    var fields by remember { mutableStateOf(f.toMap()) }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val ratiosOk = (num(fields["cr"]!!) ?: 0.0) > 0 && (num(fields["fr"]!!) ?: 0.0) > 0 &&
        listOf("cw", "cf", "fw", "ff").all { num(fields[it]!!) != null } && listOf("ch", "fh").all { heights(fields[it]!!) != null }
    val valid = ratiosOk && (elevation.isBlank() || num(elevation) != null) && num(grade) != null && num(azimuth) != null
    fun params() = PlatformParams(
        num(elevation), num(grade)!!, num(azimuth)!!, useVertex,
        num(fields["cr"]!!)!!, heights(fields["ch"]!!)!!.first(), num(fields["cw"]!!)!!, num(fields["cf"]!!)!!,
        num(fields["fr"]!!)!!, heights(fields["fh"]!!)!!.first(), num(fields["fw"]!!)!!, num(fields["ff"]!!)!!,
        heights(fields["ch"]!!)!!.takeIf { it.size > 1 }.orEmpty(), heights(fields["fh"]!!)!!.takeIf { it.size > 1 }.orEmpty(),
    )
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Text)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = {
            TextButton(enabled = valid && v.platformOutline.size >= 3 && vm.tasks.isEmpty(), onClick = {
                v.platformParams = params(); vm.designPlatform()
            }) { Text("Calcular") }
        },
        dismissButton = {
            Row {
                if (vm.platform != null) OutlinedButton(onClick = { vm.removePlatform() }) { Text("Remover projeto") }
                TextButton(onClick = { if (valid) v.platformParams = params(); onDismiss() }) { Text("Fechar") }
            }
        },
        title = { Text("Projeto de plataforma") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Contorno: ${v.platformOutline.size} vértice(s)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (valid) v.platformParams = params(); onDraw() }) { Text("Desenhar na planta") }
                    OutlinedButton(enabled = vm.userPoints.size >= 3, onClick = {
                        v.platformOutline = vm.userPoints.map { Point2(it.x, it.y) }
                        useVertex = vm.userPoints.all { !it.z.isNaN() }
                    }) { Text("Usar pontos _POINTS (${vm.userPoints.size})") }
                }
                val names = project.surfaces.map { it.name }.filter { it != PLATFORM_LABEL }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Terreno", Modifier.width(120.dp))
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text(v.platformTerrain ?: names.firstOrNull() ?: "—", maxLines = 1) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { v.platformTerrain = n; open = false }) }
                        }
                    }
                }
                HorizontalDivider()
                Text("Cota da plataforma", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(elevation, { elevation = it }, label = { Text("Cota no 1º vértice (vazio = média do terreno)") },
                        singleLine = true, modifier = Modifier.width(320.dp), keyboardOptions = decimal, enabled = !useVertex)
                    OutlinedTextField(grade, { grade = it }, label = { Text("Inclinação %") }, singleLine = true, modifier = Modifier.width(140.dp),
                        keyboardOptions = decimal, enabled = !useVertex)
                    OutlinedTextField(azimuth, { azimuth = it }, label = { Text("Direção (azimute °)") }, singleLine = true,
                        modifier = Modifier.width(170.dp), keyboardOptions = decimal, enabled = !useVertex)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useVertex, onCheckedChange = { useVertex = it })
                    Text("Usar a cota de cada ponto _POINTS como cota do vértice", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                SlopeRow("Corte", "c", fields) { k, value -> fields = fields + (k to value) }
                SlopeRow("Aterro", "f", fields) { k, value -> fields = fields + (k to value) }
                Text("Talude H:V (ex.: 1,5 = 1,5 m na horizontal por 1 m de altura). Alturas das bermas: uma altura para todas (ex.: 8) " +
                    "ou a altura de cada berma, da plataforma para fora, separadas por ; (ex.: 6; 8; 10; a última se repete). 0 = sem bermas. " +
                    "A inclinação interna da berma (%) cai para o pé do talude de cima, levando a água para a canaleta.", style = MaterialTheme.typography.bodySmall)
                vm.tasks.forEach { LoadProgress(it) }
                vm.platformMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vm.platformVolumes?.let { d ->
                    HorizontalDivider()
                    Text("Resultado contra ${d.baseName}", style = MaterialTheme.typography.labelLarge)
                    Text("Corte ${StationFormat.decimal(d.cutVolume, 1)} m³ · Aterro ${StationFormat.decimal(d.fillVolume, 1)} m³ · " +
                        "Saldo ${(if (d.netVolume >= 0) "+" else "−") + StationFormat.decimal(kotlin.math.abs(d.netVolume), 1)} m³")
                    Text("Área do projeto (até o offset): ${StationFormat.decimal(d.cutArea + d.fillArea, 0)} m² · malha ${StationFormat.decimal(d.cellSize, 2)} m",
                        style = MaterialTheme.typography.bodySmall)
                    Text("A superfície \"$PLATFORM_LABEL\" já entra na seção, no perfil, no comparativo e na exportação (LandXML com breaklines e limite; DXF com borda, offset e bermas).",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun SlopeRow(title: String, prefix: String, fields: Map<String, String>, onChange: (String, String) -> Unit) {
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.width(70.dp), style = MaterialTheme.typography.labelLarge)
        listOf("r" to "Talude H:V", "h" to "Alturas das bermas (m)", "w" to "Largura berma (m)", "f" to "Inclinação interna %").forEach { (k, label) ->
            OutlinedTextField(fields[prefix + k]!!, { onChange(prefix + k, it) }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.width(if (k == "h") 230.dp else 170.dp), keyboardOptions = decimal,
                isError = if (k == "h") heights(fields[prefix + k]!!) == null else fields[prefix + k]!!.trim().replace(',', '.').toDoubleOrNull() == null)
        }
    }
    Spacer(Modifier.height(2.dp))
}

/** "8" or "6; 8; 10" (decimal comma allowed): bench heights, or null when not numbers. */
private fun heights(s: String): List<Double>? {
    val parts = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: return null }
}

private fun heightsText(list: List<Double>, single: Double) =
    (list.ifEmpty { listOf(single) }).joinToString("; ") { StationFormat.decimal(it, 2) }
