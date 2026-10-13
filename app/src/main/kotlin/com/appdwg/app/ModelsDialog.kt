package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.ModelGeoref
import com.appdwg.core.ModelPlacement
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat

/** Units a model's numbers may be in. */
private val UNITS = listOf(0.001 to "mm", 0.01 to "cm", 1.0 to "m", 0.3048 to "pé")

/** Adds 3D models and places them: georeference, insertion point, rotation, scale and unit. */
@Composable
fun ModelsDialog(
    vm: ProjectViewModel,
    pickedPoint: Point2?,
    onAdd: () -> Unit,
    onPickPoint: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = onAdd) { Text("Adicionar modelo") } },
        title = { Text("Modelos 3D") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("OBJ (com .mtl), STL, PLY, glTF/GLB (com .bin) e IFC. Selecione junto os arquivos que o modelo usa. " +
                    "FBX, DWG 3D e Revit: exporte em OBJ, glTF ou IFC.", style = MaterialTheme.typography.bodySmall)
                vm.tasks.forEach { LoadProgress(it) }
                vm.modelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vm.models.forEachIndexed { i, entry ->
                    HorizontalDivider()
                    key(entry.name, i) { ModelRow(vm, i, entry, pickedPoint, onPickPoint) }
                }
            }
        },
    )
}

@Composable
private fun ModelRow(vm: ProjectViewModel, index: Int, entry: ModelEntry, pickedPoint: Point2?, onPickPoint: (Int) -> Unit) {
    val p0 = entry.placement
    var e by remember(p0) { mutableStateOf(StationFormat.decimal(p0.east, 3)) }
    var n by remember(p0) { mutableStateOf(StationFormat.decimal(p0.north, 3)) }
    var z by remember(p0) { mutableStateOf(StationFormat.decimal(p0.elevation, 3)) }
    var rot by remember(p0) { mutableStateOf(StationFormat.decimal(p0.rotationDeg, 3)) }
    var scale by remember(p0) { mutableStateOf(StationFormat.decimal(p0.scale, 4)) }
    var unit by remember(p0) { mutableStateOf(p0.unitScale) }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    Text("${entry.name} · ${entry.model.meshes.size} elementos · ${entry.model.triangleCount} triângulos", style = MaterialTheme.typography.labelLarge)
    if (entry.model.warnings.isNotEmpty()) Text("Avisos: " + entry.model.warnings.joinToString("; "), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(e, { e = it }, label = { Text("E inserção") }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
        OutlinedTextField(n, { n = it }, label = { Text("N inserção") }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
        OutlinedTextField(z, { z = it }, label = { Text("Cota") }, singleLine = true, modifier = Modifier.width(130.dp), keyboardOptions = text)
        OutlinedTextField(rot, { rot = it }, label = { Text("Rotação (° anti-horário)") }, singleLine = true, modifier = Modifier.width(190.dp), keyboardOptions = text)
        OutlinedTextField(scale, { scale = it }, label = { Text("Escala") }, singleLine = true, modifier = Modifier.width(110.dp), keyboardOptions = text)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Unidade do arquivo")
        UNITS.forEach { (k, l) -> FilterChip(selected = unit == k, onClick = { unit = k }, label = { Text(l) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onPickPoint(index) }) { Text("Tocar ponto de inserção") }
        if (pickedPoint != null) TextButton(onClick = {
            e = StationFormat.decimal(pickedPoint.x, 3); n = StationFormat.decimal(pickedPoint.y, 3)
            vm.groundAt(pickedPoint)?.let { z = StationFormat.decimal(it, 3) }
        }) { Text("Usar ponto tocado") }
        TextButton(onClick = { val pe = num(e); val pn = num(n); if (pe != null && pn != null) vm.groundAt(Point2(pe, pn))?.let { z = StationFormat.decimal(it, 3) } }) {
            Text("Cota do terreno")
        }
        entry.model.georef?.let { g: ModelGeoref ->
            TextButton(onClick = {
                e = StationFormat.decimal(g.east, 3); n = StationFormat.decimal(g.north, 3); z = StationFormat.decimal(g.height, 3)
                rot = StationFormat.decimal(g.rotationDeg, 3); scale = StationFormat.decimal(g.scale, 4); unit = entry.model.unitScale
            }) { Text("Georreferência do IFC") }
        }
        val ok = listOf(e, n, z, rot, scale).all { num(it) != null } && (num(scale) ?: 0.0) > 0
        OutlinedButton(enabled = ok, onClick = { vm.placeModel(index, ModelPlacement(num(e)!!, num(n)!!, num(z)!!, num(rot)!!, num(scale)!!, unit)) }) { Text("Aplicar") }
        TextButton(onClick = { vm.removeModel(index) }) { Text("Remover") }
    }
}
