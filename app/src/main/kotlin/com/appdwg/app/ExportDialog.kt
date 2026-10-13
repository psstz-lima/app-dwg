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
    VOLUMES("Volumes CSV", "-volumes.csv"),
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
    zone: String,
    onZone: (String) -> Unit,
    onExport: (ExportKind) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("Exportar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onExport(ExportKind.DXF) }) { Text(ExportKind.DXF.label) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSurfaces, onCheckedChange = onIncludeSurfaces)
                    Text("Incluir superfícies como 3DFACE (arquivo grande)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeContours, onCheckedChange = onIncludeContours)
                    Text("Incluir curvas de nível (camada _CURVAS, superfícies inteiras, com cota)", style = MaterialTheme.typography.bodySmall)
                }
                Text("O DXF leva linhas, textos, eixos, pontos _POINTS, a medição (_MEDICAO) e a linha da seção (_SECAO), sem as camadas desligadas.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onExport(ExportKind.LANDXML) }) { Text(ExportKind.LANDXML.label) }
                OutlinedButton(onClick = { onExport(ExportKind.POINTS) }, enabled = vm.userPoints.isNotEmpty()) { Text(ExportKind.POINTS.label) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onExport(ExportKind.KML) }, enabled = zone.trimEnd('S', 's', 'N', 'n').toIntOrNull() in 1..60) {
                        Text(ExportKind.KML.label)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        zone, onZone, label = { Text("Zona UTM") }, singleLine = true, modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
                OutlinedButton(onClick = { onExport(ExportKind.VOLUMES) }, enabled = vm.hasVolumes) { Text(ExportKind.VOLUMES.label) }
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
