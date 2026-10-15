package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.Calibration
import com.appdwg.core.CalibrationPair
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point3
import com.appdwg.core.StationFormat

/** Geoid model for the GNSS height and the site calibration from control points. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationDialog(
    vm: ProjectViewModel,
    v: ViewState,
    project: LandXmlProject,
    crs: com.appdwg.core.Crs,
    onLoadGrid: () -> Unit,
    onDismiss: () -> Unit,
) {
    val geo = vm.geo
    val fix = vm.gnss.fix
    // Adding 0.0 turns −0.0 into 0.0, so a zero residual does not print as "−0,000".
    fun n(x: Double, d: Int = 3) = if (x.isNaN()) "—" else StationFormat.decimal(if (kotlin.math.abs(x) < 0.5 * Math.pow(10.0, -d.toDouble())) 0.0 else x + 0.0, d)
    // Known points: the user's and those of the file.
    val known = remember(project, vm.userPoints) {
        vm.userPoints + project.cgPoints.map { CogoPoint(it.name, it.code ?: "", it.position.x, it.position.y, it.position.z) }
    }
    var picked by remember { mutableStateOf<CogoPoint?>(null) }
    var pickOpen by remember { mutableStateOf(false) }
    var nText by remember { mutableStateOf(StationFormat.lengthValue(geo.fixedN)) }
    val cal = Calibration.fit(v.calibrationPairs, v.calibrationFixScale)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.fillMaxWidth(0.94f)),
        title = { Text(stringResource(R.string.calib_title)) },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.calib_height), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(geo.heightMode == HeightMode.RECEIVER, { geo.changeMode(HeightMode.RECEIVER) }, { Text(stringResource(R.string.calib_height_receiver)) })
                    FilterChip(geo.heightMode == HeightMode.GEOID_GRID, { geo.changeMode(HeightMode.GEOID_GRID) }, { Text(stringResource(R.string.calib_height_grid)) })
                    FilterChip(geo.heightMode == HeightMode.FIXED_N, { geo.changeMode(HeightMode.FIXED_N) }, { Text(stringResource(R.string.calib_height_fixed)) })
                }
                Text(stringResource(R.string.calib_height_hint), style = MaterialTheme.typography.bodySmall)
                when (geo.heightMode) {
                    HeightMode.GEOID_GRID -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onLoadGrid) { Text(stringResource(R.string.calib_load_grid)) }
                            geo.gridMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(stringResource(R.string.calib_grid_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    HeightMode.FIXED_N -> OutlinedTextField(nText, { t -> nText = t; StationFormat.parseLength(t)?.let { geo.changeFixedN(it) } },
                        label = { Text(stringResource(R.string.calib_fixed_n, StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(200.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    HeightMode.RECEIVER -> {}
                }
                if (fix != null && geo.heightMode != HeightMode.RECEIVER) {
                    if (fix.ellipsoidalHeight == null) Text(stringResource(R.string.calib_no_ellipsoidal), style = MaterialTheme.typography.bodySmall)
                    else geo.undulation(fix)?.let { Text(stringResource(R.string.calib_n_here, StationFormat.length(it, 3)), style = MaterialTheme.typography.bodySmall) }
                        ?: Text(stringResource(R.string.calib_n_outside), style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()
                Text(stringResource(R.string.calib_pairs), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.calib_pairs_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { pickOpen = true }, enabled = known.isNotEmpty()) {
                            Text(picked?.let { "${it.number} · E ${StationFormat.lengthValue(it.x)} N ${StationFormat.lengthValue(it.y)} Z ${if (it.z.isNaN()) "—" else StationFormat.lengthValue(it.z)}" } ?: stringResource(R.string.calib_pick_point), maxLines = 1)
                        }
                        DropdownMenu(pickOpen, { pickOpen = false }) {
                            known.take(500).forEach { p ->
                                DropdownMenuItem(text = { Text("${p.number} ${p.description}".trim()) }, onClick = { picked = p; pickOpen = false })
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = picked != null && fix != null,
                        onClick = {
                            val p = picked ?: return@OutlinedButton
                            val m = geo.measured(fix ?: return@OutlinedButton, crs)
                            v.calibrationPairs = v.calibrationPairs.filter { it.name != p.number } +
                                CalibrationPair(p.number, m, Point3(p.x, p.y, p.z), useVertical = !p.z.isNaN() && !m.z.isNaN())
                            picked = null
                        },
                    ) { Text(stringResource(R.string.calib_measure)) }
                }
                if (fix == null) Text(stringResource(R.string.calib_no_fix), style = MaterialTheme.typography.bodySmall)

                v.calibrationPairs.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, Modifier.width(70.dp), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Checkbox(p.useHorizontal, { on -> v.calibrationPairs = v.calibrationPairs.toMutableList().also { it[i] = p.copy(useHorizontal = on) } })
                        Text(stringResource(R.string.calib_use_h))
                        Checkbox(p.useVertical, { on -> v.calibrationPairs = v.calibrationPairs.toMutableList().also { it[i] = p.copy(useVertical = on) } },
                            enabled = !p.local.z.isNaN() && !p.measured.z.isNaN())
                        Text(stringResource(R.string.calib_use_v))
                        val r = cal?.residual(p)
                        Text(if (r == null) "" else stringResource(R.string.calib_residual, n(StationFormat.toLength(r.x)), n(StationFormat.toLength(r.y)), n(StationFormat.toLength(r.z))),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { v.calibrationPairs = v.calibrationPairs.filterIndexed { j, _ -> j != i } }) { Text(stringResource(R.string.calib_remove)) }
                    }
                }

                if (cal == null) Text(stringResource(R.string.calib_none), style = MaterialTheme.typography.bodySmall)
                else {
                    Text(stringResource(R.string.calib_params, StationFormat.decimal(cal.scale, 6), StationFormat.decimal((cal.scale - 1) * 1e6, 1),
                        StationFormat.decimal(cal.rotationDeg, 5), StationFormat.length(cal.shiftE, 3), StationFormat.length(cal.shiftN, 3)), style = MaterialTheme.typography.bodySmall)
                    if (cal.verticalCount > 0) Text(stringResource(R.string.calib_params_v, StationFormat.length(cal.verticalShift, 3), StationFormat.decimal(cal.slopeE * 1000, 3),
                        StationFormat.decimal(cal.slopeN * 1000, 3)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.calib_rms, StationFormat.length(cal.horizontalRms, 3), StationFormat.length(cal.verticalRms, 3)), fontWeight = FontWeight.SemiBold)
                }
                // The whole row toggles, not only the box.
                Row(Modifier.toggleable(v.calibrationOn, enabled = cal != null, role = Role.Checkbox) { v.calibrationOn = it },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v.calibrationOn, null, enabled = cal != null)
                    Text(stringResource(R.string.calib_apply))
                }
                Row(Modifier.toggleable(v.calibrationFixScale, role = Role.Checkbox) { v.calibrationFixScale = it },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v.calibrationFixScale, null)
                    Text(stringResource(R.string.calib_fix_scale))
                }
            }
        },
    )
}
