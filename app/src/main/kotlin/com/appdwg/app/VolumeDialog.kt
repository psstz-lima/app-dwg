package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat
import com.appdwg.core.VolumeBase

/** Settings of the volume tool, kept while the drawing is open. */
class VolumeState {
    var surface by mutableStateOf<String?>(null)
    var usePolygon by mutableStateOf(true)
    var base by mutableStateOf(0) // 0 elevation, 1 lowest edge, 2 mean edge, 3 plane edge, 4 other surface
    var elevation by mutableStateOf("")
    var other by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VolumeDialog(vm: ProjectViewModel, project: LandXmlProject, st: VolumeState, polygon: List<Point2>, onDraw: () -> Unit, onDismiss: () -> Unit) {
    val names = project.surfaces.map { it.name }
    val surfaceName = st.surface?.takeIf { it in names } ?: names.firstOrNull()
    val otherName = st.other?.takeIf { it in names && it != surfaceName } ?: names.firstOrNull { it != surfaceName }
    val hasPolygon = polygon.size >= 3
    val elevation = StationFormat.parseLength(st.elevation)
    val r = vm.volume
    fun n(v: Double, d: Int = 1) = StationFormat.decimal(v, d)

    AlertDialog(
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vol_title)) },
        confirmButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.vol_surface), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { s -> FilterChip(s == surfaceName, { st.surface = s; vm.clearVolume() }, { Text(s) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                Text(stringResource(R.string.vol_area), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    FilterChip(!st.usePolygon || !hasPolygon, { st.usePolygon = false; vm.clearVolume() }, { Text(stringResource(R.string.vol_area_all)) }, modifier = Modifier.heightIn(min = 48.dp))
                    if (hasPolygon) FilterChip(st.usePolygon, { st.usePolygon = true; vm.clearVolume() }, { Text(pluralStringResource(R.plurals.vol_area_polygon, polygon.size, polygon.size)) }, modifier = Modifier.heightIn(min = 48.dp))
                    OutlinedButton(onClick = onDraw, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vol_draw)) }
                }
                if (!hasPolygon) Text(stringResource(R.string.vol_draw_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.vol_base), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.vol_base_elevation, R.string.vol_base_lowest, R.string.vol_base_mean, R.string.vol_base_plane, R.string.vol_base_other)
                        .forEachIndexed { i, label ->
                            if (i == 4 && otherName == null) return@forEachIndexed
                            FilterChip(st.base == i, { st.base = i; vm.clearVolume() }, { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                }
                when (st.base) {
                    0 -> OutlinedTextField(st.elevation, { st.elevation = it; vm.clearVolume() }, label = { Text(stringResource(R.string.vol_elevation, StationFormat.lengthUnit)) },
                        singleLine = true, modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth(), isError = elevation == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    4 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.filter { it != surfaceName }.forEach { s -> FilterChip(s == otherName, { st.other = s; vm.clearVolume() }, { Text(s) }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                }
                Text(stringResource(R.string.vol_base_hint), style = MaterialTheme.typography.bodySmall)
                FitRow() {
                    OutlinedButton(
                        enabled = surfaceName != null && (st.base != 0 || elevation != null),
                        onClick = {
                            val s = project.surfaces.first { it.name == surfaceName }
                            val base = when (st.base) {
                                0 -> VolumeBase.Elevation(elevation ?: return@OutlinedButton)
                                1 -> VolumeBase.LowestEdge
                                2 -> VolumeBase.MeanEdge
                                3 -> VolumeBase.PlaneEdge
                                else -> VolumeBase.Other(project.surfaces.firstOrNull { it.name == otherName } ?: return@OutlinedButton)
                            }
                            vm.computeVolume(s, polygon.takeIf { st.usePolygon && hasPolygon }, base)
                        }, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.vol_compute)) }
                    TaskProgress(vm)
                }
                vm.volumeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (r != null) {
                    HorizontalDivider()
                    SpecHeader(stringResource(R.string.vol_result))
                    SpecRow(stringResource(R.string.vol_cut), StationFormat.volume(r.above))
                    SpecRow(stringResource(R.string.vol_cut_area), StationFormat.area(r.aboveArea))
                    SpecRow(stringResource(R.string.vol_fill_row), StationFormat.volume(r.below))
                    SpecRow(stringResource(R.string.vol_fill_area), StationFormat.area(r.belowArea))
                    SpecRow(stringResource(R.string.vol_balance), StationFormat.volume(r.net))
                    SpecRow(stringResource(R.string.vol_elevations), StationFormat.length(r.minSurface, 3) + " – " + StationFormat.length(r.maxSurface, 3))
                    SpecRow(stringResource(R.string.vol_cell), StationFormat.length(r.cellSize, 2))
                    val flat = r.baseZ
                    val plane = r.plane
                    SpecRow(
                        stringResource(R.string.vol_base),
                        when {
                            flat != null -> stringResource(R.string.vol_base_flat, StationFormat.length(flat, 3))
                            plane != null -> stringResource(R.string.vol_base_inclined, n(plane[1] * 100, 2), n(plane[2] * 100, 2))
                            else -> stringResource(R.string.vol_base_surface, otherName ?: "")
                        },
                    )
                }
            }
        },
    )
}
