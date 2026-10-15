package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.StationFormat
import kotlin.math.abs

/**
 * Several surfaces at once: the matrix of every pair (row − column) and the progress by stages,
 * from the original ground through the surveys to the design, in the order marked.
 */
@Composable
fun CrossCompareDialog(vm: ProjectViewModel, project: LandXmlProject, onShown: () -> Unit, onDismiss: () -> Unit) {
    val marked = vm.crossSelected
    AlertDialog(
        onDismissRequest = onDismiss,
        // Wide, so the matrix fits on a tablet.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        title = { Text(tr("Comparação cruzada")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    tr("Marque as superfícies na ordem das etapas: terreno original primeiro, levantamentos no meio, projeto por último."),
                    style = MaterialTheme.typography.bodySmall,
                )
                project.surfaces.forEach { s ->
                    val i = marked.indexOf(s.name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i >= 0, onCheckedChange = { vm.toggleCross(s.name) })
                        Text(if (i >= 0) "${i + 1}." else "", Modifier.width(28.dp))
                        Text(s.name, Modifier.weight(1f), maxLines = 1)
                        if (i >= 0) {
                            OutlinedButton(onClick = { vm.moveCross(s.name, -1) }, enabled = i > 0) { Text("↑") }
                            OutlinedButton(onClick = { vm.moveCross(s.name, 1) }, enabled = i < marked.size - 1) { Text("↓") }
                        }
                    }
                }
                Row {
                    OutlinedButton(onClick = { vm.computeMatrix() }, enabled = marked.size >= 2 && vm.tasks.isEmpty()) { Text(tr("Calcular matriz")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.computeStages() }, enabled = marked.size >= 2 && vm.tasks.isEmpty()) { Text(tr("Calcular etapas")) }
                }
                TaskProgress(vm)
                vm.compareMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                val inMatrix = marked.filter { n -> vm.matrix.keys.any { it.first == n || it.second == n } }
                if (inMatrix.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(tr("Matriz: saldo linha − coluna (m³). Toque numa célula para ver o mapa."), style = MaterialTheme.typography.labelLarge)
                    Matrix(vm, inMatrix, onShown)
                }
                vm.stageProgress?.let { p ->
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(tr("Etapas"), style = MaterialTheme.typography.labelLarge)
                    p.periods.forEachIndexed { k, period ->
                        Text(tr("Período {0}: {1} → {2}", k + 1, short(period.from), short(period.to)), style = MaterialTheme.typography.bodyMedium)
                        Text("   corte ${m3(period.cut)} · aterro ${m3(period.fill)} · movimentado ${m3(period.cut + period.fill)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("Executado até {0}: ", short(marked.getOrNull(marked.size - 2) ?: "")) +
                        m3(p.periods.sumOf { it.cut + it.fill }), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Falta até o projeto: corte {0} · aterro {1}", m3(p.remainingCut), m3(p.remainingFill)), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Total terreno → projeto: corte {0} · aterro {1}", m3(p.totalCut), m3(p.totalFill)), style = MaterialTheme.typography.bodySmall)
                    Text(tr("Concluído: ") + StationFormat.decimal(p.done * 100, 1) + tr(" % (pelo volume movimentado)"),
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        },
    )
}

@Composable
private fun Matrix(vm: ProjectViewModel, names: List<String>, onShown: () -> Unit) {
    val cell = Modifier.width(150.dp).height(56.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Box(cell) {}
            names.forEach { c -> Box(cell.padding(4.dp), contentAlignment = Alignment.Center) { Text(short(c), maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        }
        names.forEach { r ->
            Row {
                Box(cell.padding(4.dp), contentAlignment = Alignment.CenterStart) { Text(short(r), maxLines = 2, style = MaterialTheme.typography.labelSmall) }
                names.forEach { c ->
                    val d = if (r == c) null else vm.matrixCell(r, c)
                    Box(
                        cell.clickable(enabled = d != null) { vm.showCell(r, c); onShown() }.padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            r == c -> Text("—")
                            d == null -> Text(tr("sem área comum"), style = MaterialTheme.typography.labelSmall)
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text((if (d.netVolume >= 0) "+" else "−") + StationFormat.volume(abs(d.netVolume), 0),
                                    color = Color(0xFF000000 or (if (d.netVolume >= 0) DifferenceImage.FILL else DifferenceImage.CUT).toLong()),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("C ${StationFormat.volume(d.cutVolume, 0)} · A ${StationFormat.volume(d.fillVolume, 0)}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Surface name without the file prefix of an added file, for narrow table cells. */
private fun short(name: String) = name.substringAfterLast('|')

private fun m3(v: Double) = StationFormat.volume(v)
