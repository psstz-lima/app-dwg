package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.Point3
import com.appdwg.core.StationFormat
import com.appdwg.core.SurfaceEdit

/** Settings of the surface editor, kept while the drawing is open. */
class SurfaceEditState {
    var surface by mutableStateOf<String?>(null)
    var dz by mutableStateOf(StationFormat.decimal(0.5))
    var z by mutableStateOf("")
    var pointZ by mutableStateOf("")
}

/** Lengths typed in the shown unit, back in metres. */
private fun dbl(s: String) = StationFormat.parseLength(s)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurfaceEditDialog(vm: ProjectViewModel, project: LandXmlProject, st: SurfaceEditState, polygon: List<Point2>, tapped: Point2?,
                      onDraw: () -> Unit, onDismiss: () -> Unit) {
    val names = project.surfaces.map { it.name }
    val name = st.surface?.takeIf { it in names } ?: names.firstOrNull()
    val source = project.surfaces.firstOrNull { it.name == name }
    val poly = polygon.takeIf { it.size >= 3 }
    fun edit(op: (com.appdwg.core.Surface, String) -> com.appdwg.core.Surface?) {
        val s = source ?: return
        vm.editSurface(s, op)
        // Further edits go on the edited surface.
        st.surface = vm.editedName(s.name)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.se_title)) },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (vm.canUndoSurface) ({ OutlinedButton(onClick = { vm.undoSurfaceEdit() }) { Text(stringResource(R.string.se_undo)) } }) else null,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.se_surface), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { n -> FilterChip(n == name, { st.surface = n }, { Text(n) }) }
                }
                if (name != null) Text(stringResource(R.string.se_result, vm.editedName(name)), style = MaterialTheme.typography.bodySmall)
                vm.surfaceEditMessage?.let { Text(it, fontWeight = FontWeight.SemiBold) }

                HorizontalDivider()
                Text(stringResource(R.string.se_area_ops), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (poly != null) stringResource(R.string.se_polygon, poly.size) else stringResource(R.string.se_no_polygon),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDraw) { Text(stringResource(R.string.se_draw)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = poly != null && source != null, onClick = { edit { s, n -> SurfaceEdit.clip(s, poly!!, true, n) } }) {
                        Text(stringResource(R.string.se_keep_inside))
                    }
                    OutlinedButton(enabled = poly != null && source != null, onClick = { edit { s, n -> SurfaceEdit.clip(s, poly!!, false, n) } }) {
                        Text(stringResource(R.string.se_remove_inside))
                    }
                    OutlinedButton(enabled = poly != null && source != null, onClick = { edit { s, n -> SurfaceEdit.deletePoints(s, poly!!, n) } }) {
                        Text(stringResource(R.string.se_delete_points))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(st.dz, { st.dz = it }, label = { Text(stringResource(R.string.se_dz, StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(130.dp),
                        isError = dbl(st.dz) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    OutlinedButton(enabled = poly != null && source != null && dbl(st.dz) != null,
                        onClick = { val d = dbl(st.dz)!!; edit { s, n -> SurfaceEdit.raise(s, poly!!, d, null, n) } }) { Text(stringResource(R.string.se_raise)) }
                    OutlinedTextField(st.z, { st.z = it }, label = { Text(stringResource(R.string.se_z, StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(130.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedButton(enabled = poly != null && source != null && dbl(st.z) != null,
                        onClick = { val z = dbl(st.z)!!; edit { s, n -> SurfaceEdit.raise(s, poly!!, 0.0, z, n) } }) { Text(stringResource(R.string.se_flatten)) }
                }

                HorizontalDivider()
                Text(stringResource(R.string.se_point_ops), style = MaterialTheme.typography.labelLarge)
                if (tapped == null) Text(stringResource(R.string.se_no_tap), style = MaterialTheme.typography.bodySmall)
                else {
                    Text(stringResource(R.string.se_tapped, StationFormat.lengthValue(tapped.x), StationFormat.lengthValue(tapped.y)), style = MaterialTheme.typography.bodySmall)
                    val here = source?.elevationAt(tapped)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(st.pointZ, { st.pointZ = it }, label = { Text(stringResource(R.string.se_z, StationFormat.lengthUnit)) }, singleLine = true,
                            placeholder = { here?.let { Text(StationFormat.lengthValue(it)) } }, modifier = Modifier.width(150.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedButton(enabled = source != null && (dbl(st.pointZ) != null || here != null), onClick = {
                            val z = dbl(st.pointZ) ?: here ?: return@OutlinedButton
                            edit { s, n -> SurfaceEdit.addPoint(s, Point3(tapped.x, tapped.y, z), n) }
                        }) { Text(stringResource(R.string.se_add_point)) }
                        OutlinedButton(enabled = source != null, onClick = { edit { s, n -> SurfaceEdit.flipEdge(s, tapped, n) } }) { Text(stringResource(R.string.se_flip)) }
                    }
                }
            }
        },
    )
}
