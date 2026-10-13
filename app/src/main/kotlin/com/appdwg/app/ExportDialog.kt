package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Which file to export; the screen picture is taken after the dialog closes. */
enum class ExportKind(val label: String, val suffix: String) {
    DXF("Desenho DXF", ".dxf"),
    LANDXML("Superfícies e pontos LandXML", "-landxml.xml"),
    POINTS("Pontos PDENZ (TXT)", "-pontos.txt"),
    KML("Pontos e linhas KML (Google Earth)", ".kml"),
    GEOJSON("Pontos e linhas GeoJSON", ".geojson"),
    SHAPEFILE("Pontos e linhas Shapefile (ZIP)", "-shapefile.zip"),
    VOLUMES("Volumes CSV", "-volumes.csv"),
    NOTES_KMZ("Anotações com fotos KMZ (Google Earth)", "-anotacoes.kmz"),
    NOTES_CSV("Anotações CSV", "-anotacoes.csv"),
    PNG("Imagem da tela PNG", ".png"),
    PDF("Imagem da tela PDF", ".pdf"),
}

@Composable
fun ExportDialog(
    vm: ProjectViewModel,
    includeSurfaces: Boolean,
    onIncludeSurfaces: (Boolean) -> Unit,
    includeContours: Boolean,
    onIncludeContours: (Boolean) -> Unit,
    includeModels: Boolean,
    onIncludeModels: (Boolean) -> Unit,
    crs: com.appdwg.core.Crs,
    onCrs: () -> Unit,
    onExport: (ExportKind) -> Unit,
    onSheets: (() -> Unit)? = null,
    /** Shown when Google Drive is installed: exports start in Drive while on. */
    toDrive: Boolean? = null,
    onToDrive: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("Exportar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (toDrive != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = toDrive, onCheckedChange = onToDrive)
                    Text("☁ Salvar no Google Drive")
                }
                OutlinedButton(onClick = { onExport(ExportKind.DXF) }) { Text(ExportKind.DXF.label) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSurfaces, onCheckedChange = onIncludeSurfaces)
                    Text("Incluir superfícies como 3DFACE (arquivo grande)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeContours, onCheckedChange = onIncludeContours)
                    Text("Incluir curvas de nível (camada _CURVAS, superfícies inteiras, com cota)", style = MaterialTheme.typography.bodySmall)
                }
                if (vm.models.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeModels, onCheckedChange = onIncludeModels)
                    Text("Incluir modelos 3D como 3DFACE (camadas do modelo)", style = MaterialTheme.typography.bodySmall)
                }
                Text("O DXF leva linhas, textos, eixos, pontos _POINTS, a medição (_MEDICAO) e a linha da seção (_SECAO), sem as camadas desligadas.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onExport(ExportKind.LANDXML) }) { Text(ExportKind.LANDXML.label) }
                onSheets?.let { OutlinedButton(onClick = it) { Text(androidx.compose.ui.res.stringResource(R.string.sh_open)) } }
                OutlinedButton(onClick = { onExport(ExportKind.POINTS) }, enabled = vm.userPoints.isNotEmpty()) { Text(ExportKind.POINTS.label) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sistema de coordenadas: ${crs.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                    OutlinedButton(onClick = onCrs) { Text("Trocar…") }
                }
                // A site grid has no known link to latitude and longitude.
                val geoOk = !crs.local
                Row {
                    OutlinedButton(onClick = { onExport(ExportKind.KML) }, enabled = geoOk) { Text(ExportKind.KML.label) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onExport(ExportKind.GEOJSON) }, enabled = geoOk) { Text(ExportKind.GEOJSON.label) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onExport(ExportKind.SHAPEFILE) }) { Text(ExportKind.SHAPEFILE.label) }
                }
                Text("KML e GeoJSON em longitude/latitude (WGS84 ≈ SIRGAS 2000), convertidos do sistema do projeto; Shapefile nas coordenadas do projeto, com o .prj desse sistema. " +
                    "Para importar, abra ou adicione (Comparar) um .geojson, .json, .shp ou um .zip com o shapefile.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onExport(ExportKind.VOLUMES) }, enabled = vm.hasVolumes) { Text(ExportKind.VOLUMES.label) }
                Row {
                    OutlinedButton(onClick = { onExport(ExportKind.NOTES_KMZ) }, enabled = vm.notes.isNotEmpty() && !crs.local) { Text(ExportKind.NOTES_KMZ.label) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onExport(ExportKind.NOTES_CSV) }, enabled = vm.notes.isNotEmpty()) { Text(ExportKind.NOTES_CSV.label) }
                }
                Row {
                    OutlinedButton(onClick = { onExport(ExportKind.PNG) }) { Text(ExportKind.PNG.label) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onExport(ExportKind.PDF) }) { Text(ExportKind.PDF.label) }
                }
                Text("A imagem é a tela como está (planta, 3D ou seção aberta).", style = MaterialTheme.typography.bodySmall)
                vm.tasks.forEach { LoadProgress(it) }
                vm.exportMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
