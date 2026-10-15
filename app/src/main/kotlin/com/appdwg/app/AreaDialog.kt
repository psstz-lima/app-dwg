package com.appdwg.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appdwg.core.Bounds
import com.appdwg.core.CopernicusDem
import com.appdwg.core.Overpass
import com.appdwg.core.Point2
import com.appdwg.core.Sentinel2
import com.appdwg.core.StationFormat
import com.appdwg.core.tr

/**
 * Downloads data for an area of the plan: the terrain from the Copernicus DEM (a surface within a
 * vertical tolerance) and OpenStreetMap layers. The area is the plan on screen or the rectangle
 * around the points of a measurement.
 */
@Composable
fun AreaDialog(vm: ProjectViewModel, viewport: Bounds?, measure: List<Point2>, onDrawArea: () -> Unit, onDismiss: () -> Unit, chosen: Bounds? = null) {
    // 0: the area chosen on the map for a new project, 1: the plan on screen, 2: around a measurement.
    var pick by rememberSaveable { mutableStateOf(if (measure.size >= 2) 2 else if (chosen != null) 0 else 1) }
    var dem by rememberSaveable { mutableStateOf(true) }
    var tolerance by rememberSaveable { mutableStateOf(StationFormat.lengthValue(0.5, 1)) }
    var keepTiff by rememberSaveable { mutableStateOf(false) }
    val kinds = remember { mutableStateOf(setOf(Overpass.Kind.ROADS, Overpass.Kind.WATER)) }
    var image by rememberSaveable { mutableStateOf(true) }
    var sceneId by rememberSaveable { mutableStateOf<String?>(null) }
    val hasTerrain = dem || vm.hasSurfaces
    val area = when {
        pick == 2 && measure.size >= 2 -> Bounds(measure.minOf { it.x }, measure.minOf { it.y }, measure.maxOf { it.x }, measure.maxOf { it.y })
        pick == 0 && chosen != null -> chosen
        else -> viewport
    }
    val busy = vm.tasks.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Baixar dados da área")) },
        confirmButton = {
            OutlinedButton(enabled = area != null && (dem || kinds.value.isNotEmpty() || (image && hasTerrain)) && !busy, onClick = {
                val tol = StationFormat.parseLength(tolerance)?.takeIf { it > 0 } ?: 0.5
                vm.downloadArea(area!!, dem, tol, keepTiff, kinds.value, image && hasTerrain, vm.scenes.firstOrNull { it.id == sceneId })
            }) { Text(tr("Baixar")) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Área"), fontWeight = FontWeight.Bold)
                if (chosen != null) Choice(tr("A área escolhida no mapa"), pick == 0) { pick = 0 }
                Choice(tr("A planta visível na tela"), pick == 1) { pick = 1 }
                Choice(if (measure.size >= 2) tr("Retângulo em volta da medição ({0} pontos)", measure.size) else tr("Retângulo em volta da medição (desenhe com Medir)"),
                    pick == 2, enabled = measure.size >= 2) { pick = 2 }
                OutlinedButton(onClick = onDrawArea) { Text(tr("Marcar a área com Medir…")) }
                area?.let { Text(tr("{0} × {1}", StationFormat.length(it.width, 0), StationFormat.length(it.height, 0)), style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Check(tr("Terreno: Copernicus GLO-30 (30 m)"), dem) { dem = it }
                if (dem) {
                    OutlinedTextField(tolerance, { tolerance = it }, label = { Text(tr("Tolerância vertical da superfície ({0})", StationFormat.lengthUnit)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Check(tr("Guardar também a grade (GeoTIFF)"), keepTiff) { keepTiff = it }
                    Text(tr("Modelo de superfície (DSM): inclui prédios e árvores. Bom para estudo preliminar, não substitui levantamento."),
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Check(tr("Imagem de satélite: Sentinel-2 (10 m), colada sobre o terreno"), image && hasTerrain) { image = it }
                if (!hasTerrain) Text(tr("Precisa de uma superfície: marque também o terreno."), style = MaterialTheme.typography.bodySmall)
                if (image && hasTerrain) {
                    val chosen = vm.scenes.firstOrNull { it.id == sceneId }
                    Text(chosen?.let { tr("Data: {0}", sceneLabel(it)) }
                        ?: tr("Data: a mais recente com poucas nuvens"), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = area != null, onClick = { area?.let { vm.searchScenes(it) } }) { Text(tr("Escolher a data…")) }
                    vm.scenesMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    for (sc in vm.scenes.take(12)) {
                        Choice(sceneLabel(sc), sceneId == sc.id) { sceneId = sc.id }
                    }
                    if (vm.scenes.isNotEmpty()) Choice(tr("A mais recente com poucas nuvens"), sceneId == null) { sceneId = null }
                }
                HorizontalDivider()
                Text(tr("OpenStreetMap"), fontWeight = FontWeight.Bold)
                for ((k, label) in listOf(Overpass.Kind.ROADS to tr("Ruas e caminhos"), Overpass.Kind.BUILDINGS to tr("Construções"),
                    Overpass.Kind.WATER to tr("Hidrografia"), Overpass.Kind.RAILWAYS to tr("Ferrovias"), Overpass.Kind.BOUNDARIES to tr("Limites administrativos"))) {
                    Check(label, k in kinds.value) { on -> kinds.value = if (on) kinds.value + k else kinds.value - k }
                }
                Text(tr("Cada tipo vai para uma camada; dá para exportar em DXF, GeoJSON ou Shapefile."), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(tr("Precisa de internet. Os dados entram no projeto como arquivos e ficam salvos nele."), style = MaterialTheme.typography.bodySmall)
                Text(tr("Fontes: {0}. {1}.", CopernicusDem.ATTRIBUTION, Overpass.ATTRIBUTION) + " " + tr("Imagens: Copernicus Sentinel data (ESA)."), style = MaterialTheme.typography.labelSmall)
                TaskProgress(vm)
                vm.areaMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

/** "2026-10-09 · tile 23KPU · 0% de nuvens na área", or the scene's cloud cover when the area's is unknown. */
private fun sceneLabel(sc: Sentinel2.Scene) = sc.areaCloud?.let { tr("{0} · tile {1} · {2}% de nuvens na área", sc.date.take(10), sc.tile, StationFormat.decimal(it * 100, 0)) }
    ?: tr("{0} · tile {1} · {2}% de nuvens na cena", sc.date.take(10), sc.tile, StationFormat.decimal(sc.cloud, 0))

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(text)
    }
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(text)
    }
}
