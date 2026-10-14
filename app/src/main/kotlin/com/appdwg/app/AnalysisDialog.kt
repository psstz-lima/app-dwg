package com.appdwg.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.StationFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisDialog(vm: ProjectViewModel, project: LandXmlProject, onDismiss: () -> Unit) {
    val names = project.surfaces.map { it.name }
    val current = vm.analysisSurface?.takeIf { it in names } ?: names.firstOrNull()
    fun start(name: String?) { project.surfaces.firstOrNull { it.name == name }?.let { vm.runAnalysis(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ta_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ta_surface), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { n -> FilterChip(n == current, { vm.analysisSurface = n; if (vm.analysisMode != 0) start(n) }, { Text(n) }) }
                }
                Text(stringResource(R.string.ta_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.ta_off, R.string.ta_slope, R.string.ta_flow, R.string.ta_both).forEachIndexed { i, label ->
                        FilterChip(vm.analysisMode == i, {
                            vm.analysisMode = i
                            if (i != 0 && (vm.slopeImage == null || vm.analysisSurface != current)) start(current)
                        }, { Text(stringResource(label)) })
                    }
                }
                TaskProgress(vm)
                if (vm.analysisMode == 2 || vm.analysisMode == 3) {
                    Text(stringResource(R.string.ta_min_area), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2_000.0, 10_000.0, 50_000.0, 200_000.0).forEach { a ->
                            FilterChip(vm.flowMinArea == a, { vm.changeFlowMinArea(a) }, { Text(StationFormat.landArea(a, 1)) })
                        }
                    }
                    Text(stringResource(R.string.ta_flow_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (vm.analysisMode == 1 || vm.analysisMode == 3) {
                    Text(stringResource(R.string.ta_slope_hint), style = MaterialTheme.typography.bodySmall)
                    SlopeLegend(vm, Modifier)
                }
                if (vm.analysisCell > 0) Text(stringResource(R.string.ta_cell, StationFormat.length(vm.analysisCell, 2)), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** Slope classes with their colour and area. */
@Composable
fun SlopeLegend(vm: ProjectViewModel, modifier: Modifier) {
    if (vm.slopeClasses.isEmpty()) return
    Card(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.ta_legend), style = MaterialTheme.typography.labelMedium)
            vm.slopeClasses.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(Color(c.color), RoundedCornerShape(3.dp)))
                    val range = if (c.to == null) "> ${c.from.toInt()}" else "${c.from.toInt()}–${c.to!!.toInt()}"
                    Text("  $range ${c.name}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text(StationFormat.landArea(c.area, 2), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
