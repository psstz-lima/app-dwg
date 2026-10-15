package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdwg.core.AsBuiltStatus
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.StationFormat

/** Colour of each as-built status, on the plan and in the list. */
fun asBuiltColor(s: AsBuiltStatus): Color = when (s) {
    AsBuiltStatus.OK -> Color(0xFF2E9E4F)
    AsBuiltStatus.ABOVE -> Color(0xFF1E6FD9)
    AsBuiltStatus.BELOW -> Color(0xFFD93025)
    AsBuiltStatus.OUTSIDE -> Color(0xFF8A8A8A)
}

@Composable
private fun statusLabel(s: AsBuiltStatus) = stringResource(when (s) {
    AsBuiltStatus.OK -> R.string.asbuilt_status_ok
    AsBuiltStatus.ABOVE -> R.string.asbuilt_status_above
    AsBuiltStatus.BELOW -> R.string.asbuilt_status_below
    AsBuiltStatus.OUTSIDE -> R.string.asbuilt_status_outside
})

/** Settings of the as-built check, kept while the drawing is open. */
class AsBuiltState {
    var source by mutableStateOf(0) // 0 = _POINTS, 1 = file, 2 = both
    var surface by mutableStateOf<String?>(null)
    var alignment by mutableStateOf<String?>(null)
    // 5 cm, or 2 in in imperial.
    var toleranceCm by mutableStateOf(if (com.appdwg.core.Display.imperial) "2" else "5")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsBuiltDialog(
    vm: ProjectViewModel,
    project: LandXmlProject,
    st: AsBuiltState,
    onImport: () -> Unit,
    onImportDrive: (() -> Unit)? = null,
    onCsv: () -> Unit,
    onPdf: () -> Unit,
    onDismiss: () -> Unit,
) {
    val filePoints = remember(project) {
        project.cgPoints.map { CogoPoint(it.name, it.code ?: "", it.position.x, it.position.y, it.position.z) }
    }
    val user = vm.userPoints.filter { !it.z.isNaN() }
    val points = when (st.source) { 0 -> user; 1 -> filePoints; else -> user + filePoints }
    val surfaces = project.surfaces.map { it.name }
    // The design is usually the last surface added (a corridor or the project file), the ground the first.
    val surfaceName = st.surface?.takeIf { it in surfaces } ?: surfaces.lastOrNull()
    // Tolerance in centimetres, or inches in imperial.
    val tolerance = StationFormat.number(st.toleranceCm)?.takeIf { it > 0 }?.times(if (com.appdwg.core.Display.imperial) 0.0254 else 0.01)
    val report = vm.asBuilt

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.asbuilt_title)) },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (report != null) ({ OutlinedButton(onClick = { vm.clearAsBuilt() }) { Text(stringResource(R.string.asbuilt_clear)) } }) else null,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.asbuilt_intro), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.asbuilt_points), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(st.source == 0, { st.source = 0 }, { Text(stringResource(R.string.asbuilt_points_user, user.size)) })
                    if (filePoints.isNotEmpty()) {
                        FilterChip(st.source == 1, { st.source = 1 }, { Text(stringResource(R.string.asbuilt_points_file, filePoints.size)) })
                        FilterChip(st.source == 2, { st.source = 2 }, { Text(stringResource(R.string.asbuilt_points_all, user.size + filePoints.size)) })
                    }
                    OutlinedButton(onClick = onImport) { Text(stringResource(R.string.asbuilt_import)) }
                    onImportDrive?.let { OutlinedButton(onClick = it) { Text(tr("☁ Google Drive")) } }
                }
                Text(stringResource(R.string.asbuilt_design), style = MaterialTheme.typography.labelLarge)
                if (surfaces.isEmpty()) Text(stringResource(R.string.asbuilt_no_surface), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    surfaces.forEach { n -> FilterChip(n == surfaceName, { st.surface = n }, { Text(n) }) }
                }
                if (project.alignments.isNotEmpty()) {
                    Text(stringResource(R.string.asbuilt_alignment), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(st.alignment == null, { st.alignment = null }, { Text(stringResource(R.string.asbuilt_none)) })
                        project.alignments.forEach { a -> FilterChip(st.alignment == a.name, { st.alignment = a.name }, { Text(a.name) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(st.toleranceCm, { st.toleranceCm = it }, label = { Text(stringResource(R.string.asbuilt_tolerance, if (com.appdwg.core.Display.imperial) "in" else "cm")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(170.dp),
                        isError = tolerance == null)
                    OutlinedButton(
                        onClick = {
                            val s = project.surfaces.firstOrNull { it.name == surfaceName } ?: return@OutlinedButton
                            vm.checkAsBuilt(points, s, project.alignments.firstOrNull { it.name == st.alignment }, tolerance ?: return@OutlinedButton)
                        },
                        enabled = points.isNotEmpty() && surfaceName != null && tolerance != null,
                    ) { Text(stringResource(R.string.asbuilt_check)) }
                }
                if (points.isEmpty()) Text(stringResource(R.string.asbuilt_no_points), style = MaterialTheme.typography.bodySmall)

                if (report != null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    fun n(v: Double?) = v?.let { StationFormat.decimal(it, 3) } ?: "—"
                    Text(stringResource(R.string.asbuilt_summary, report.checked, StationFormat.decimal(report.passRate * 100, 1),
                        report.above, report.below, report.outside), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.asbuilt_stats, len(report.mean), len(report.rms), len(report.stdDev), len(report.min), len(report.max)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.asbuilt_sign), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCsv) { Text(stringResource(R.string.asbuilt_csv)) }
                        OutlinedButton(onClick = onPdf) { Text(stringResource(R.string.asbuilt_pdf)) }
                    }
                    vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                    // Worst first: the points that need attention.
                    report.rows.sortedByDescending { it.delta?.let { d -> kotlin.math.abs(d) } ?: -1.0 }.take(200).forEach { r ->
                        val s = report.status(r)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.point.number, Modifier.width(56.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            Text(r.stationOffset?.let { StationFormat.estaca(it.station) } ?: "", Modifier.width(90.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            Text(r.delta?.let { (if (it > 0) "+" else "") + StationFormat.length(it, 3) } ?: "—", Modifier.width(90.dp),
                                style = MaterialTheme.typography.bodySmall)
                            Text(statusLabel(s), color = asBuiltColor(s), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}

private fun len(v: Double?) = v?.let { StationFormat.length(it, 3) } ?: "—"
