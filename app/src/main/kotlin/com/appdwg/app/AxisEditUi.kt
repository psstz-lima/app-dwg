package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat

/** Radius of each PI as typed (the default radius where none was given). */
private fun RoadParams.radiusList() = List(pis.size) { radii.getOrNull(it)?.takeIf { r -> r > 0 } ?: radius }
private fun RoadParams.spiralList() = List(pis.size) { spirals.getOrNull(it) ?: spiral }

/**
 * Editor of one PI of the axis being drawn: its radius and transition (spiral) length, a PI
 * inserted after it or the PI removed; the arrows step to the neighbours.
 */
@Composable
fun PiEditor(rp: RoadParams, i: Int, onChange: (RoadParams) -> Unit, onSelect: (Int?) -> Unit) {
    val end = i == 0 || i == rp.pis.size - 1
    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { onSelect(i - 1) }, enabled = i > 0) { Text("◀") }
        Text(tr("PI {0}", i + 1) + if (end) " · " + tr("início/fim, sem curva") else "", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onSelect(i + 1) }, enabled = i < rp.pis.size - 1) { Text("▶") }
        if (!end) {
            var r by remember(i, rp.radiusList()[i]) { mutableStateOf(StationFormat.lengthValue(rp.radiusList()[i], 1)) }
            var ls by remember(i, rp.spiralList()[i]) { mutableStateOf(StationFormat.lengthValue(rp.spiralList()[i], 1)) }
            OutlinedTextField(r, { t ->
                r = t
                StationFormat.number(t)?.takeIf { it > 0 }?.let { x -> onChange(rp.copy(radii = rp.radiusList().toMutableList().apply { set(i, StationFormat.fromLength(x)) })) }
            }, label = { Text(tr("Raio ({0})", StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(130.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(ls, { t ->
                ls = t
                StationFormat.number(t)?.takeIf { it >= 0 }?.let { x -> onChange(rp.copy(spirals = rp.spiralList().toMutableList().apply { set(i, StationFormat.fromLength(x)) })) }
            }, label = { Text(tr("Transição Ls ({0})", StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(150.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (i < rp.pis.size - 1) TextButton(onClick = {
            val a = rp.pis[i]; val b = rp.pis[i + 1]
            val mid = Point2((a.x + b.x) / 2, (a.y + b.y) / 2)
            onChange(rp.copy(pis = rp.pis.toMutableList().apply { add(i + 1, mid) },
                radii = rp.radiusList().toMutableList().apply { add(i + 1, rp.radius) },
                spirals = rp.spiralList().toMutableList().apply { add(i + 1, rp.spiral) }))
            onSelect(i + 1)
        }) { Text(tr("Inserir PI depois")) }
        TextButton(onClick = {
            onChange(rp.copy(pis = rp.pis.filterIndexed { k, _ -> k != i }, radii = rp.radiusList().filterIndexed { k, _ -> k != i },
                spirals = rp.spiralList().filterIndexed { k, _ -> k != i }))
            onSelect(null)
        }) { Text(tr("Apagar PI")) }
    }
}
