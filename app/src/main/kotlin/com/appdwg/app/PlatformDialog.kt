package com.appdwg.app

import com.appdwg.core.tr

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
    var elevation by remember { mutableStateOf(p0.elevation?.let { StationFormat.lengthValue(it) } ?: "") }
    var grade by remember { mutableStateOf(StationFormat.decimal(p0.gradePercent, 2)) }
    var azimuth by remember { mutableStateOf(StationFormat.decimal(p0.gradeAzimuth, 1)) }
    var useVertex by remember { mutableStateOf(p0.useVertexElevations) }
    var maxGrade by remember { mutableStateOf("2") }
    var swell by remember { mutableStateOf("1") }
    val f = remember {
        mutableMapOf(
            "cr" to StationFormat.decimal(p0.cutRatio, 2), "ch" to heightsText(p0.cutHeights, p0.cutBenchHeight),
            "cw" to StationFormat.lengthValue(p0.cutBenchWidth, 2), "cf" to StationFormat.decimal(p0.cutBenchFall, 1),
            "fr" to StationFormat.decimal(p0.fillRatio, 2), "fh" to heightsText(p0.fillHeights, p0.fillBenchHeight),
            "fw" to StationFormat.lengthValue(p0.fillBenchWidth, 2), "ff" to StationFormat.decimal(p0.fillBenchFall, 1),
        )
    }
    var fields by remember { mutableStateOf(f.toMap()) }
    fun num(s: String) = StationFormat.number(s)
    fun len(s: String) = StationFormat.parseLength(s)
    val ratiosOk = (num(fields["cr"]!!) ?: 0.0) > 0 && (num(fields["fr"]!!) ?: 0.0) > 0 &&
        listOf("cw", "cf", "fw", "ff").all { num(fields[it]!!) != null } && listOf("ch", "fh").all { heights(fields[it]!!) != null }
    val valid = ratiosOk && (elevation.isBlank() || len(elevation) != null) && num(grade) != null && num(azimuth) != null
    fun params() = PlatformParams(
        len(elevation), num(grade)!!, num(azimuth)!!, useVertex,
        num(fields["cr"]!!)!!, heights(fields["ch"]!!)!!.first(), len(fields["cw"]!!)!!, num(fields["cf"]!!)!!,
        num(fields["fr"]!!)!!, heights(fields["fh"]!!)!!.first(), len(fields["fw"]!!)!!, num(fields["ff"]!!)!!,
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
            }) { Text(tr("Calcular")) }
        },
        dismissButton = {
            Row {
                if (vm.platform != null) OutlinedButton(onClick = { vm.removePlatform() }) { Text(tr("Remover projeto")) }
                TextButton(onClick = { if (valid) v.platformParams = params(); onDismiss() }) { Text(tr("Fechar")) }
            }
        },
        title = { Text(tr("Projeto de plataforma")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Contorno: {0} vértice(s)", v.platformOutline.size), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (valid) v.platformParams = params(); onDraw() }) { Text(tr("Desenhar na planta")) }
                    OutlinedButton(enabled = vm.userPoints.size >= 3, onClick = {
                        v.platformOutline = vm.userPoints.map { Point2(it.x, it.y) }
                        useVertex = vm.userPoints.all { !it.z.isNaN() }
                    }) { Text(tr("Usar pontos _POINTS ({0})", vm.userPoints.size)) }
                }
                val names = project.surfaces.map { it.name }.filter { it != PLATFORM_LABEL }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Terreno"), Modifier.width(120.dp))
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text(v.platformTerrain ?: names.firstOrNull() ?: "—", maxLines = 1) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { v.platformTerrain = n; open = false }) }
                        }
                    }
                }
                HorizontalDivider()
                Text(tr("Cota da plataforma"), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(elevation, { elevation = it }, label = { Text(tr("Cota no 1º vértice ({0}; vazio = média do terreno)", StationFormat.lengthUnit)) },
                        singleLine = true, modifier = Modifier.width(320.dp), keyboardOptions = decimal, enabled = !useVertex)
                    OutlinedTextField(grade, { grade = it }, label = { Text(tr("Inclinação %")) }, singleLine = true, modifier = Modifier.width(140.dp),
                        keyboardOptions = decimal, enabled = !useVertex)
                    OutlinedTextField(azimuth, { azimuth = it }, label = { Text(tr("Direção (azimute °)")) }, singleLine = true,
                        modifier = Modifier.width(170.dp), keyboardOptions = decimal, enabled = !useVertex)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useVertex, onCheckedChange = { useVertex = it })
                    Text(tr("Usar a cota de cada ponto _POINTS como cota do vértice"), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                SlopeRow(tr("Corte"), "c", fields) { k, value -> fields = fields + (k to value) }
                SlopeRow(tr("Aterro"), "f", fields) { k, value -> fields = fields + (k to value) }
                Text(tr("Talude H:V (ex.: 1,5 = 1,5 m na horizontal por 1 m de altura). Alturas das bermas: uma altura para todas (ex.: 8) ") +
                    tr("ou a altura de cada berma, da plataforma para fora, separadas por ; (ex.: 6; 8; 10; a última se repete). 0 = sem bermas. ") +
                    tr("A inclinação interna da berma (%) cai para o pé do talude de cima, levando a água para a canaleta."), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(tr("Formas sugeridas para esta área"), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(maxGrade, { maxGrade = it }, label = { Text(tr("Inclinação máxima %")) }, singleLine = true,
                        modifier = Modifier.width(170.dp), keyboardOptions = decimal, isError = num(maxGrade) == null)
                    OutlinedTextField(swell, { swell = it }, label = { Text(tr("Corte por m³ de aterro")) }, singleLine = true,
                        modifier = Modifier.width(190.dp), keyboardOptions = decimal, isError = num(swell)?.takeIf { it > 0 } == null)
                    OutlinedButton(enabled = valid && v.platformOutline.size >= 3 && vm.tasks.isEmpty() && num(maxGrade) != null && (num(swell) ?: 0.0) > 0, onClick = {
                        v.platformParams = params(); vm.suggestPlatforms(params(), num(maxGrade)!!, num(swell)!!)
                    }) { Text(tr("Sugerir formas")) }
                }
                vm.platformOptions.takeIf { vm.platformOptionsOutline == v.platformOutline }.orEmpty().forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(optionTitle(o), style = MaterialTheme.typography.bodyMedium)
                            Text(tr("Cota {0} · corte {1} · aterro {2} · talude até {3} · ocupa mais {4}",
                                StationFormat.lengthValue(o.baseElevation, 2), StationFormat.volume(o.cut), StationFormat.volume(o.fill),
                                StationFormat.length(o.maxSlopeHeight, 1), StationFormat.area(o.footprint, 0)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = vm.tasks.isEmpty(), onClick = {
                            useVertex = false
                            elevation = StationFormat.lengthValue(o.baseElevation, 3)
                            grade = StationFormat.decimal(o.gradePercent, 2)
                            azimuth = StationFormat.decimal(o.gradeAzimuth, 1)
                            v.platformParams = v.platformParams.copy(elevation = o.baseElevation, gradePercent = o.gradePercent,
                                gradeAzimuth = o.gradeAzimuth, useVertexElevations = false)
                            vm.designPlatform()
                        }) { Text(tr("Usar")) }
                    }
                }
                TaskProgress(vm)
                vm.platformMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vm.platformVolumes?.let { d ->
                    HorizontalDivider()
                    Text(tr("Resultado contra {0}", d.baseName), style = MaterialTheme.typography.labelLarge)
                    Text(tr("Corte {0} · Aterro {1} · ", StationFormat.volume(d.cutVolume), StationFormat.volume(d.fillVolume)) +
                        tr("Saldo {0}", (if (d.netVolume >= 0) "+" else "−") + StationFormat.volume(kotlin.math.abs(d.netVolume))))
                    Text(tr("Área do projeto (até o offset): {0} · malha {1}", StationFormat.area(d.cutArea + d.fillArea, 0), StationFormat.length(d.cellSize, 2)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("A superfície \"{0}\" já entra na seção, no perfil, no comparativo e na exportação (LandXML com breaklines e limite; DXF com borda, offset e bermas).", PLATFORM_LABEL),
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
        listOf("r" to tr("Talude H:V"), "h" to tr("Alturas das bermas ({0})", StationFormat.lengthUnit), "w" to tr("Largura berma ({0})", StationFormat.lengthUnit), "f" to tr("Inclinação interna %")).forEach { (k, label) ->
            OutlinedTextField(fields[prefix + k]!!, { onChange(prefix + k, it) }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.width(if (k == "h") 230.dp else 170.dp), keyboardOptions = decimal,
                isError = if (k == "h") heights(fields[prefix + k]!!) == null else StationFormat.number(fields[prefix + k]!!) == null)
        }
    }
    Spacer(Modifier.height(2.dp))
}

/** "8" or "6; 8; 10" (decimal comma allowed): bench heights, or null when not numbers. */
private fun heights(s: String): List<Double>? {
    val parts = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { StationFormat.parseLength(it)?.takeIf { v -> v >= 0 } ?: return null }
}

private fun heightsText(list: List<Double>, single: Double) =
    (list.ifEmpty { listOf(single) }).joinToString("; ") { StationFormat.lengthValue(it, 2) }

private fun optionTitle(o: com.appdwg.core.PlatformOption) = when (o.kind) {
    com.appdwg.core.PlatformOption.Kind.FLAT_BALANCED -> tr("Plana, corte igual ao aterro")
    com.appdwg.core.PlatformOption.Kind.SLOPED_BALANCED -> tr("Inclinada {0} % como o terreno, corte igual ao aterro", StationFormat.decimal(o.gradePercent, 2))
    com.appdwg.core.PlatformOption.Kind.SLOPED_LEAST -> tr("Inclinada {0} % como o terreno, menor movimento de terra", StationFormat.decimal(o.gradePercent, 2))
    com.appdwg.core.PlatformOption.Kind.FLAT_LEAST -> tr("Plana, menor movimento de terra")
}
