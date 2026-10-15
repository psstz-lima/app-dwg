package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
    onAddDrive: (() -> Unit)? = null,
    onCross: () -> Unit,
    onAddState: () -> Unit,
    onTimeline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = project.surfaces.map { it.name }
    AlertDialog(
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onAddFile, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Adicionar arquivos")) }
                onAddDrive?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("☁ Google Drive")) } }
            }
        },
        title = { Text(tr("Arquivos do projeto e comparação")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatesSection(vm, onAddState, onTimeline, onMapShown = onDismiss)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(tr("Arquivos do projeto (mosaico)"), style = MaterialTheme.typography.labelLarge)
                if (vm.addedFiles.isEmpty()) {
                    Text(
                        tr("Adicione outros LandXML, DXF ou .aecc.pnt (trechos vizinhos, o levantamento ou o projeto) para montar um mosaico e comparar. Dá para escolher vários de uma vez."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                vm.addedFiles.forEach { (label, _, uri) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Files read from disk can be switched off; generated surfaces (road, platform) cannot.
                        if (uri != null) androidx.compose.material3.Checkbox(label !in vm.hiddenFiles, { vm.setFileVisible(label, it) })
                        Text(vm.states.firstOrNull { it.file == label }?.name ?: label, Modifier.weight(1f), maxLines = 1)
                        OutlinedButton(onClick = { vm.removeFile(label) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover")) }
                    }
                }
                TaskProgress(vm)
                vm.compareMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(tr("Comparar superfícies"), style = MaterialTheme.typography.labelLarge)
                if (names.size < 2 && vm.activeState != null) {
                    Text(tr("Só um estado está à vista. Toque em Mostrar todos para comparar superfícies, ou use Volumes entre estados."), style = MaterialTheme.typography.bodySmall)
                } else if (names.size < 2) {
                    Text(tr("É preciso ter duas superfícies. Adicione um arquivo com outra superfície."), style = MaterialTheme.typography.bodySmall)
                } else {
                    SurfacePicker(tr("A (base, terreno)"), names, base, onBase)
                    SurfacePicker(tr("B (projeto ou levantamento)"), names, other, onOther)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (base != null && other != null) vm.compare(base, other) },
                            enabled = base != null && other != null && base != other && vm.tasks.isEmpty(), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(tr("Calcular B − A")) }
                        if (vm.difference != null) OutlinedButton(onClick = { vm.clearDifference() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                    }
                    OutlinedButton(onClick = onCross, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Comparação cruzada e por etapas…")) }
                }
                vm.difference?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.differenceVisible, onCheckedChange = { vm.differenceVisible = it })
                        Text(tr("Mostrar mapa de B − A na planta"))
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
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(selected ?: tr("Escolher"), maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { onSelect(n); open = false }) }
            }
        }
    }
}

@Composable
private fun DifferenceSummary(d: SurfaceDifference) {
    Column {
        Row2(tr("Corte (B abaixo de A)"), m3(d.cutVolume) + " em " + StationFormat.area(d.cutArea, 0))
        Row2(tr("Aterro (B acima de A)"), m3(d.fillVolume) + " em " + StationFormat.area(d.fillArea, 0))
        Row2(tr("Saldo (aterro − corte)"), (if (d.netVolume >= 0) "+" else "−") + m3(kotlin.math.abs(d.netVolume)))
        Row2(tr("Maior corte / aterro"), StationFormat.lengthValue(-d.minDifference) + " / " + StationFormat.length(d.maxDifference, 3))
        Text(
            tr("Calculado na área comum às duas, em malha de {0}.", StationFormat.length(d.cellSize, 2)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun m3(v: Double) = StationFormat.volume(v)

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
            Text("B − A (${StationFormat.lengthUnit})", style = MaterialTheme.typography.labelMedium)
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
                Text("−" + StationFormat.lengthValue(s, 2), style = MaterialTheme.typography.labelSmall)
                Text("0", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("+" + StationFormat.lengthValue(s, 2), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.width(220.dp)) {
                Text(tr("corte"), style = MaterialTheme.typography.labelSmall, color = Color(0xFF000000 or DifferenceImage.CUT.toLong()))
                Spacer(Modifier.weight(1f))
                Text(tr("aterro"), style = MaterialTheme.typography.labelSmall, color = Color(0xFF000000 or DifferenceImage.FILL.toLong()))
            }
        }
    }
}
