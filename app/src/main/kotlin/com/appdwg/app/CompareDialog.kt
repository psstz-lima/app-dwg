package com.appdwg.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.StationFormat
import com.appdwg.core.SurfaceDifference

/**
 * Files laid over the open project and the comparison of two surfaces: B − A in color and the
 * cut and fill volumes between them.
 */
@Composable
fun CompareDialog(
    vm: ProjectViewModel,
    project: LandXmlProject,
    base: String?,
    other: String?,
    onBase: (String) -> Unit,
    onOther: (String) -> Unit,
    onAddFile: () -> Unit,
    onCross: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = project.surfaces.map { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = onAddFile) { Text("Adicionar arquivo") } },
        title = { Text("Arquivos e comparação") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Arquivos sobrepostos", style = MaterialTheme.typography.labelLarge)
                if (vm.addedFiles.isEmpty()) {
                    Text(
                        "Adicione outro DXF, LandXML ou .aecc.pnt (por exemplo, o levantamento ou o projeto) para ver junto e comparar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                vm.addedFiles.forEach { (label, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), maxLines = 1)
                        OutlinedButton(onClick = { vm.removeFile(label) }) { Text("Remover") }
                    }
                }
                vm.tasks.forEach { LoadProgress(it) }
                vm.compareMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Comparar superfícies", style = MaterialTheme.typography.labelLarge)
                if (names.size < 2) {
                    Text("É preciso ter duas superfícies. Adicione um arquivo com outra superfície.", style = MaterialTheme.typography.bodySmall)
                } else {
                    SurfacePicker("A (base, terreno)", names, base, onBase)
                    SurfacePicker("B (projeto ou levantamento)", names, other, onOther)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (base != null && other != null) vm.compare(base, other) },
                            enabled = base != null && other != null && base != other && vm.tasks.isEmpty(),
                        ) { Text("Calcular B − A") }
                        if (vm.difference != null) TextButton(onClick = { vm.clearDifference() }) { Text("Limpar") }
                    }
                    TextButton(onClick = onCross) { Text("Comparação cruzada e por etapas…") }
                }
                vm.difference?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.differenceVisible, onCheckedChange = { vm.differenceVisible = it })
                        Text("Mostrar mapa de B − A na planta")
                    }
                    DifferenceSummary(d)
                }
            }
        },
    )
}

@Composable
private fun SurfacePicker(label: String, names: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(200.dp), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { open = true }) { Text(selected ?: "Escolher", maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { onSelect(n); open = false }) }
            }
        }
    }
}

@Composable
private fun DifferenceSummary(d: SurfaceDifference) {
    Column {
        Row2("Corte (B abaixo de A)", m3(d.cutVolume) + " em " + StationFormat.decimal(d.cutArea, 0) + " m²")
        Row2("Aterro (B acima de A)", m3(d.fillVolume) + " em " + StationFormat.decimal(d.fillArea, 0) + " m²")
        Row2("Saldo (aterro − corte)", (if (d.netVolume >= 0) "+" else "−") + m3(kotlin.math.abs(d.netVolume)))
        Row2("Maior corte / aterro", StationFormat.decimal(-d.minDifference, 3) + " / " + StationFormat.decimal(d.maxDifference, 3) + " m")
        Text(
            "Calculado na área comum às duas, em malha de ${StationFormat.decimal(d.cellSize, 2)} m.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun m3(v: Double) = StationFormat.decimal(v, 1) + " m³"

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Color key of the B − A map. */
@Composable
fun DifferenceLegend(d: SurfaceDifference, modifier: Modifier) {
    val s = DifferenceImage.scale(d)
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text("B − A (m)", style = MaterialTheme.typography.labelMedium)
            Text("A: ${d.baseName}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("B: ${d.otherName}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.width(220.dp).height(14.dp).background(
                    Brush.horizontalGradient((0..10).map { Color(DifferenceImage.color(it / 5f - 1f)) }),
                    RoundedCornerShape(3.dp),
                ),
            )
            Row(Modifier.width(220.dp)) {
                Text("−" + StationFormat.decimal(s), style = MaterialTheme.typography.labelSmall)
                Text("0", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("+" + StationFormat.decimal(s), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.width(220.dp)) {
                Text("corte", style = MaterialTheme.typography.labelSmall, color = Color(0xFF000000 or DifferenceImage.CUT.toLong()))
                Spacer(Modifier.weight(1f))
                Text("aterro", style = MaterialTheme.typography.labelSmall, color = Color(0xFF000000 or DifferenceImage.FILL.toLong()))
            }
        }
    }
}
