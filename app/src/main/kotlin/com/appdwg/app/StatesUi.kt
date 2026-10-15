package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.appdwg.core.StationFormat
import kotlinx.coroutines.delay

/** File name of a state's picture, for the list. */
private fun pictureName(s: SurveyState) = s.image.map { android.net.Uri.decode(it).substringAfterLast('/').substringAfterLast(':') }
    .firstOrNull { it.substringAfterLast('.').lowercase() in setOf("tif", "tiff", "jpg", "jpeg", "png", "webp") }

/**
 * Survey states of the works (primitiva, medição 1, 2…), each with its file, picture and date:
 * shown one at a time, stepped through on the timeline and compared by volume.
 */
@Composable
fun StatesSection(vm: ProjectViewModel, onAddState: () -> Unit, onTimeline: () -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Text(tr("Estados da obra (linha do tempo)"), style = MaterialTheme.typography.labelLarge)
    if (vm.states.isEmpty()) {
        Text(tr("O desenho aberto é a primitiva. Adicione cada medição com o XML ou DXF e a TIFF dela, escolhidos juntos."), style = MaterialTheme.typography.bodySmall)
    }
    vm.states.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = vm.activeState == i, onClick = { vm.showState(i) })
            Column(Modifier.weight(1f)) {
                Text(s.name + (if (s.date.isNotBlank()) " · ${s.date}" else ""), maxLines = 1)
                Text(pictureName(s)?.let { tr("Imagem: {0}", it) } ?: tr("Sem imagem"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = { editing = i }) { Text(tr("Editar")) }
            if (s.file.isNotEmpty()) TextButton(onClick = { vm.removeState(i) }) { Text(tr("Remover")) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onAddState, enabled = vm.tasks.isEmpty()) { Text(tr("Adicionar medição")) }
        if (vm.states.size > 1) {
            TextButton(onClick = onTimeline) { Text(tr("Linha do tempo")) }
            if (vm.activeState != null) TextButton(onClick = { vm.showAllStates() }) { Text(tr("Mostrar todos")) }
            TextButton(onClick = { vm.computeStatePeriods() }, enabled = vm.tasks.isEmpty()) { Text(tr("Volumes entre estados")) }
        }
    }
    if (vm.activeState != null) Text(tr("Para trocar a imagem do estado marcado, use Imagem > Trocar."), style = MaterialTheme.typography.bodySmall)
    vm.statePeriods.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("{0} → {1}: corte {2} · aterro {3}", d.baseName, d.otherName, StationFormat.volume(d.cutVolume), StationFormat.volume(d.fillVolume)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.showStateDifference(d) }) { Text(tr("Ver mapa")) }
        }
    }
    vm.stateTotal?.takeIf { vm.statePeriods.size > 1 }?.let { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Acumulado {0} → {1}: corte {2} · aterro {3}", d.baseName, d.otherName, StationFormat.volume(d.cutVolume), StationFormat.volume(d.fillVolume)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.showStateDifference(d) }) { Text(tr("Ver mapa")) }
        }
    }
    editing?.let { i ->
        val s = vm.states.getOrNull(i) ?: return@let
        var name by remember(i) { mutableStateOf(s.name) }
        var date by remember(i) { mutableStateOf(s.date) }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = { TextButton(onClick = { vm.editState(i, name, date); editing = null }) { Text(tr("OK")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancelar")) } },
            title = { Text(tr("Estado")) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Nome")) }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(date, { date = it }, label = { Text(tr("Data (dd/mm/aaaa)")) }, singleLine = true)
                }
            },
        )
    }
}

/** Timeline over the plan or the 3D view: steps through the states in date order, or plays them. */
@Composable
fun TimelineBar(vm: ProjectViewModel, modifier: Modifier) {
    var playing by remember { mutableStateOf(false) }
    val n = vm.states.size
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if ((vm.activeState ?: n - 1) >= n - 1) vm.showState(0)
        while (playing) {
            delay(3000)
            val next = (vm.activeState ?: -1) + 1
            if (next >= vm.states.size) { playing = false; break }
            vm.showState(next)
        }
    }
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val a = vm.activeState
            TextButton(onClick = { vm.showState(((a ?: n) - 1).coerceAtLeast(0)) }, enabled = (a ?: n) > 0) { Text("◀") }
            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.states.forEachIndexed { i, s ->
                    FilterChip(selected = a == i, onClick = { playing = false; vm.showState(i) },
                        label = { Text(if (s.date.isBlank()) s.name else "${s.name} · ${s.date}", maxLines = 1) })
                }
            }
            TextButton(onClick = { vm.showState(((a ?: -1) + 1).coerceAtMost(n - 1)) }, enabled = (a ?: -1) < n - 1) { Text("▶") }
            TextButton(onClick = { playing = !playing }) { Text(if (playing) tr("Pausar") else tr("Reproduzir")) }
            TextButton(onClick = { playing = false; vm.showAllStates() }, enabled = a != null) { Text(tr("Todos")) }
            TextButton(onClick = { playing = false; vm.timelineShown = false }) { Text(tr("Fechar")) }
        }
    }
}
