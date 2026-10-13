package com.appdwg.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.Search
import com.appdwg.core.StationFormat

/** Goes to a station and offset, a coordinate, or something found by name. */
@Composable
fun GoToDialog(project: LandXmlProject, onGo: (Point2) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var alignment by remember { mutableStateOf(0) }
    var station by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf("0") }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val hits = remember(project, query) { Search.find(project, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("Ir para") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(query, { query = it }, label = { Text("Buscar ponto, texto ou alinhamento") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                hits.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { onGo(h.position) }.padding(vertical = 6.dp)) {
                        Text(h.kind, Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium)
                        Text(h.label, maxLines = 1)
                    }
                }
                if (query.isNotBlank() && hits.isEmpty()) Text("Nada encontrado.", style = MaterialTheme.typography.bodySmall)
                if (project.alignments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                    Text("Estaca e afastamento", style = MaterialTheme.typography.labelLarge)
                    if (project.alignments.size > 1) {
                        var open by remember { mutableStateOf(false) }
                        TextButton(onClick = { open = true }) { Text(project.alignments[alignment].name) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            project.alignments.forEachIndexed { i, a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { alignment = i; open = false }) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(station, { station = it }, label = { Text("Estaca (12+5,40)") }, singleLine = true, modifier = Modifier.width(180.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(offset, { offset = it }, label = { Text("Afast. (+D/−E)") }, singleLine = true, modifier = Modifier.width(140.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        Spacer(Modifier.width(8.dp))
                        val a = project.alignments[alignment]
                        val sta = StationFormat.parse(station)
                        OutlinedButton(enabled = sta != null && num(offset) != null, onClick = {
                            val s = sta!!.coerceIn(a.startStation, a.endStation)
                            val c = a.pointAt(s)!!
                            val p0 = a.pointAt(s - 0.5)!!; val p1 = a.pointAt(s + 0.5)!!
                            val len = kotlin.math.hypot(p1.x - p0.x, p1.y - p0.y).takeIf { it > 0 } ?: 1.0
                            val o = num(offset)!!
                            onGo(Point2(c.x + (p1.y - p0.y) / len * o, c.y - (p1.x - p0.x) / len * o))
                        }) { Text("Ir") }
                    }
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("Coordenada", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(east, { east = it }, label = { Text("E") }, singleLine = true, modifier = Modifier.width(180.dp), keyboardOptions = decimal)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(north, { north = it }, label = { Text("N") }, singleLine = true, modifier = Modifier.width(180.dp), keyboardOptions = decimal)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = num(east) != null && num(north) != null, onClick = { onGo(Point2(num(east)!!, num(north)!!)) }) { Text("Ir") }
                }
            }
        },
    )
}
