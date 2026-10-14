package com.appdwg.app

import com.appdwg.core.tr

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
private val UNITS get() = listOf(0.001 to "mm", 0.01 to "cm", 1.0 to "m", 0.3048 to tr("pé"))

/** Adds 3D models and places them: georeference, insertion point, rotation, scale and unit. */
@Composable
fun ModelsDialog(
    vm: ProjectViewModel,
    pickedPoint: Point2?,
    /** Plan points tapped for the two-point alignment (A, B). */
    alignPoints: Pair<Point2?, Point2?>,
    onAdd: () -> Unit,
    onPickPoint: (Int) -> Unit,
    /** Asks for plan point A (1) or B (2) of model [index]. */
    onPickAlign: (Int, Int) -> Unit,
    /** Model coordinates typed for A and B, per model; kept outside so they survive picking on the plan. */
    alignInputs: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, List<String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        dismissButton = { TextButton(onClick = onAdd) { Text(tr("Adicionar modelo")) } },
        title = { Text(tr("Modelos 3D")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("OBJ (com .mtl), STL, PLY, glTF/GLB (com .bin) e IFC. Selecione junto os arquivos que o modelo usa. ") +
                    tr("FBX, DWG 3D e Revit: exporte em OBJ, glTF ou IFC."), style = MaterialTheme.typography.bodySmall)
                TaskProgress(vm)
                vm.modelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                vm.models.forEachIndexed { i, entry ->
                    HorizontalDivider()
                    key(entry.name, i) { ModelRow(vm, i, entry, pickedPoint, alignPoints, onPickPoint, onPickAlign, alignInputs) }
                }
            }
        },
    )
}

@Composable
private fun ModelRow(
    vm: ProjectViewModel, index: Int, entry: ModelEntry, pickedPoint: Point2?, alignPoints: Pair<Point2?, Point2?>,
    onPickPoint: (Int) -> Unit, onPickAlign: (Int, Int) -> Unit,
    alignInputs: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, List<String>>,
) {
    val p0 = entry.placement
    var e by remember(p0) { mutableStateOf(StationFormat.lengthValue(p0.east)) }
    var n by remember(p0) { mutableStateOf(StationFormat.lengthValue(p0.north)) }
    var z by remember(p0) { mutableStateOf(StationFormat.lengthValue(p0.elevation)) }
    var rot by remember(p0) { mutableStateOf(StationFormat.decimal(p0.rotationDeg, 3)) }
    var scale by remember(p0) { mutableStateOf(StationFormat.decimal(p0.scale, 4)) }
    var unit by remember(p0) { mutableStateOf(p0.unitScale) }
    fun num(s: String) = StationFormat.number(s)
    fun len(s: String) = StationFormat.parseLength(s)
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    Text(tr("{0} · {1} elementos · {2} triângulos", entry.name, entry.model.meshes.size, entry.model.triangleCount), style = MaterialTheme.typography.labelLarge)
    if (entry.model.warnings.isNotEmpty()) Text(tr("Avisos: ") + entry.model.warnings.joinToString("; "), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(e, { e = it }, label = { Text(tr("E inserção")) }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
        OutlinedTextField(n, { n = it }, label = { Text(tr("N inserção")) }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
        OutlinedTextField(z, { z = it }, label = { Text(tr("Cota")) }, singleLine = true, modifier = Modifier.width(130.dp), keyboardOptions = text)
        OutlinedTextField(rot, { rot = it }, label = { Text(tr("Rotação (° anti-horário)")) }, singleLine = true, modifier = Modifier.width(190.dp), keyboardOptions = text)
        OutlinedTextField(scale, { scale = it }, label = { Text(tr("Escala")) }, singleLine = true, modifier = Modifier.width(110.dp), keyboardOptions = text)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Unidade do arquivo"))
        UNITS.forEach { (k, l) -> FilterChip(selected = unit == k, onClick = { unit = k }, label = { Text(l) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onPickPoint(index) }) { Text(tr("Tocar ponto de inserção")) }
        if (pickedPoint != null) TextButton(onClick = {
            e = StationFormat.lengthValue(pickedPoint.x); n = StationFormat.lengthValue(pickedPoint.y)
            vm.groundAt(pickedPoint)?.let { z = StationFormat.lengthValue(it) }
        }) { Text(tr("Usar ponto tocado")) }
        TextButton(onClick = { val pe = len(e); val pn = len(n); if (pe != null && pn != null) vm.groundAt(Point2(pe, pn))?.let { z = StationFormat.lengthValue(it) } }) {
            Text(tr("Cota do terreno"))
        }
        entry.model.georef?.let { g: ModelGeoref ->
            TextButton(onClick = {
                e = StationFormat.lengthValue(g.east); n = StationFormat.lengthValue(g.north); z = StationFormat.lengthValue(g.height)
                rot = StationFormat.decimal(g.rotationDeg, 3); scale = StationFormat.decimal(g.scale, 4); unit = entry.model.unitScale
            }) { Text(tr("Georreferência do IFC")) }
        }
        val ok = listOf(e, n, z, rot, scale).all { num(it) != null } && (num(scale) ?: 0.0) > 0
        OutlinedButton(enabled = ok, onClick = { vm.placeModel(index, ModelPlacement(len(e)!!, len(n)!!, len(z)!!, num(rot)!!, num(scale)!!, unit)) }) { Text(tr("Aplicar")) }
        OutlinedButton(onClick = { vm.removeModel(index) }) { Text(tr("Remover")) }
    }
    // Two-point alignment: two points known in the model's own coordinates and where they are on the plan.
    val typed = alignInputs[index] ?: listOf("0", "0", "", "")
    fun setTyped(k: Int, value: String) { alignInputs[index] = typed.toMutableList().also { it[k] = value } }
    val ax = typed[0]; val ay = typed[1]; val bx = typed[2]; val by = typed[3]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Alinhar por 2 pontos"), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(ax, { setTyped(0, it) }, label = { Text("A: X modelo") }, singleLine = true, modifier = Modifier.width(120.dp), keyboardOptions = text)
        OutlinedTextField(ay, { setTyped(1, it) }, label = { Text("A: Y modelo") }, singleLine = true, modifier = Modifier.width(120.dp), keyboardOptions = text)
        OutlinedButton(onClick = { onPickAlign(index, 1) }) { Text(if (alignPoints.first != null) tr("A na planta ✓") else tr("Tocar A na planta")) }
        OutlinedTextField(bx, { setTyped(2, it) }, label = { Text("B: X modelo") }, singleLine = true, modifier = Modifier.width(120.dp), keyboardOptions = text)
        OutlinedTextField(by, { setTyped(3, it) }, label = { Text("B: Y modelo") }, singleLine = true, modifier = Modifier.width(120.dp), keyboardOptions = text)
        OutlinedButton(onClick = { onPickAlign(index, 2) }) { Text(if (alignPoints.second != null) tr("B na planta ✓") else tr("Tocar B na planta")) }
        val pa = alignPoints.first; val pb = alignPoints.second
        val ma = len(ax)?.let { x -> len(ay)?.let { y -> Point2(x, y) } }
        val mb = len(bx)?.let { x -> len(by)?.let { y -> Point2(x, y) } }
        OutlinedButton(enabled = pa != null && pb != null && ma != null && mb != null, onClick = {
            com.appdwg.core.ModelAlign.twoPoints(ma!!, mb!!, pa!!, pb!!, unit, len(z) ?: p0.elevation)?.let { vm.placeModel(index, it) }
        }) { Text(tr("Alinhar")) }
    }
}
