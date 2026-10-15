package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdwg.core.AlignmentInfo
import com.appdwg.core.CogoPoint
import com.appdwg.core.StationFormat
import java.util.Locale
import kotlin.math.abs

/** Import, export, create and turn into a surface the COGO points of layer _POINTS. */
@Composable
fun PointsDialog(
    vm: ProjectViewModel,
    onImport: () -> Unit,
    onImportDrive: (() -> Unit)? = null,
    onExport: () -> Unit,
    onCreate: () -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        title = { Text(tr("Pontos (camada _POINTS)")) },
        text = {
            Column {
                Text(tr("{0} ponto(s). Formato do Civil 3D: número, descrição, E, N, cota (PDENZ).", vm.userPoints.size))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport) { Text(tr("Importar TXT/CSV")) }
                    onImportDrive?.let { OutlinedButton(onClick = it) { Text(tr("☁ Importar do Google Drive")) } }
                    OutlinedButton(onClick = onExport, enabled = vm.userPoints.isNotEmpty()) { Text(tr("Exportar PDENZ")) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCreate) { Text(tr("Criar ponto na planta")) }
                    OutlinedButton(onClick = onGenerate) { Text(tr("Gerar pontos…")) }
                    OutlinedButton(onClick = { vm.surfaceFromPoints() }, enabled = vm.userPoints.count { !it.z.isNaN() } >= 3) {
                        Text(tr("Gerar superfície"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                var showCodes by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.autoDraw() }) { Text(tr("Desenho automático")) }
                    OutlinedButton(onClick = { showCodes = true }) { Text(tr("Códigos de campo…")) }
                }
                Text(tr("Liga os pontos de mesma descrição (pé, crista, bordo…) em 3D polys editáveis; a superfície gerada depois as usa como obrigatórias."),
                    style = MaterialTheme.typography.bodySmall)
                if (showCodes) CodesDialog(vm, onDismiss = { showCodes = false })
                if (vm.lastGenerated.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.undoGenerated() }) { Text(tr("Desfazer última geração ({0} pontos)", vm.lastGenerated.size)) }
                }
                vm.pointsMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

/** Creates (when [old] is null) or edits a point; coordinates and elevation with 3 decimals. */
@Composable
fun PointEditDialog(
    old: CogoPoint?,
    initial: CogoPoint,
    onSave: (CogoPoint) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var number by remember { mutableStateOf(initial.number) }
    var description by remember { mutableStateOf(initial.description) }
    var e by remember { mutableStateOf(f3(initial.x)) }
    var n by remember { mutableStateOf(f3(initial.y)) }
    var z by remember { mutableStateOf(if (initial.z.isNaN()) "" else f3(initial.z)) }
    fun num(s: String) = StationFormat.parseLength(s)
    val valid = number.isNotBlank() && num(e) != null && num(n) != null && (z.isBlank() || num(z) != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OutlinedButton(enabled = valid, onClick = {
                onSave(CogoPoint(number.trim(), description.trim(), num(e)!!, num(n)!!, num(z) ?: Double.NaN))
            }) { Text(tr("Salvar")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) OutlinedButton(onClick = onDelete) { Text(tr("Apagar")) }
                OutlinedButton(onClick = onDismiss) { Text(tr("Cancelar")) }
            }
        },
        title = { Text(if (old == null) tr("Novo ponto") else tr("Ponto {0}", old.number)) },
        text = {
            // Scrolls so the fields stay reachable above the keyboard.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(number, { number = it }, label = { Text(tr("Número")) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(tr("Descrição")) }, singleLine = true)
                val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(e, { e = it }, label = { Text("E (${StationFormat.lengthUnit})") }, singleLine = true, keyboardOptions = decimal, isError = num(e) == null)
                OutlinedTextField(n, { n = it }, label = { Text("N (${StationFormat.lengthUnit})") }, singleLine = true, keyboardOptions = decimal, isError = num(n) == null)
                OutlinedTextField(z, { z = it }, label = { Text(tr("Cota ({0})", StationFormat.lengthUnit)) }, singleLine = true, keyboardOptions = decimal,
                    isError = z.isNotBlank() && num(z) == null)
            }
        },
    )
}

/** Data of a tapped COGO point, with its station and offset on the nearest alignment. */
@Composable
fun PointCard(
    point: CogoPoint,
    alignment: AlignmentInfo?,
    terrain: Double?,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Ponto {0}", point.number) + if (point.description.isNotEmpty()) " · ${point.description}" else "",
                style = MaterialTheme.typography.titleSmall)
            PointLine("E / N", f3(point.x) + " / " + f3(point.y))
            PointLine(tr("Cota"), if (point.z.isNaN()) tr("sem cota") else StationFormat.length(point.z, 3))
            terrain?.let { t ->
                PointLine(tr("Terreno no ponto"), StationFormat.length(t, 3))
                if (!point.z.isNaN()) {
                    val d = point.z - t
                    PointLine(tr("Ponto − terreno"), (if (d >= 0) "+" else "−") + StationFormat.length(abs(d), 3))
                }
            }
            alignment?.let { a ->
                PointLine(a.name, StationFormat.estaca(a.stationOffset.station) + " · " + StationFormat.offset(a.stationOffset.offset))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onEdit) { Text(tr("Editar")) }
                OutlinedButton(onClick = onClose) { Text(tr("Fechar")) }
            }
        }
    }
}

@Composable
private fun PointLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Three decimals with a decimal comma, as the rest of the app shows numbers. */
/** A length or coordinate in the shown unit, 3 decimals, in the device's number format. */
fun f3(v: Double): String = StationFormat.lengthValue(v, 3)
