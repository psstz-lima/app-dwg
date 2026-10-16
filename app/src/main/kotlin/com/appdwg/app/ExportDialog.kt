package com.appdwg.app

import com.appdwg.core.tr

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip

/** Which file to export; the screen picture is taken after the dialog closes. */
enum class ExportKind(private val labelPt: String, val suffix: String) {
    DXF("DXF", ".dxf"),
    LANDXML("LandXML", "-landxml.xml"),
    PIPES("Rede de drenagem (LandXML)", "-drenagem.xml"),
    POINTS("Pontos PDENZ (TXT)", "-pontos.txt"),
    KML("KML (Google Earth)", ".kml"),
    GEOJSON("GeoJSON", ".geojson"),
    SHAPEFILE("Shapefile (ZIP)", "-shapefile.zip"),
    VOLUMES("Volumes (CSV)", "-volumes.csv"),
    NOTES_KMZ("Anotações com fotos (KMZ)", "-anotacoes.kmz"),
    NOTES_CSV("Anotações (CSV)", "-anotacoes.csv"),
    PNG("Tela em PNG", ".png"),
    PDF("Tela em PDF", ".pdf");

    val label: String get() = tr(labelPt)
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
    /** Shown when Google Drive is installed: where the files go, chosen once for all of them. */
    toDrive: Boolean? = null,
    onToDrive: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val g = LocalG.current
    // A site grid has no known link to latitude and longitude.
    val geoOk = !crs.local
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth()),
        confirmButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        title = { Text(tr("Exportar")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Destination first: one choice for every file below.
                if (toDrive != null) {
                    Text(tr("Salvar em"), style = MaterialTheme.typography.labelLarge, color = g.muted)
                    ViewSwitch(listOf(tr("Neste aparelho") to !toDrive, tr("☁ Google Drive") to toDrive), onPick = { onToDrive(it == 1) })
                }
                FitRow() {
                    Text(tr("Sistema de coordenadas: {0}", crs.name), style = MaterialTheme.typography.bodySmall, color = g.muted, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onCrs, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Trocar…")) }
                }

                ExportGroup(tr("Desenho e CAD"))
                ExportRow(GIcon.LINES, ExportKind.DXF.label, tr("Linhas, textos, eixos, pontos, medição e seção para CAD, sem as camadas desligadas")) { onExport(ExportKind.DXF) }
                Column(Modifier.padding(start = 48.dp)) {
                    ExportOption(tr("Superfícies como 3DFACE (arquivo grande)"), includeSurfaces, onIncludeSurfaces)
                    ExportOption(tr("Curvas de nível com cota (camada _CURVAS)"), includeContours, onIncludeContours)
                    if (vm.models.isNotEmpty()) ExportOption(tr("Modelos 3D como 3DFACE"), includeModels, onIncludeModels)
                }
                ExportRow(GIcon.CONTOURS, ExportKind.LANDXML.label, tr("Superfícies, eixos, greide e pontos para o Civil 3D")) { onExport(ExportKind.LANDXML) }
                ExportRow(
                    GIcon.DRAINAGE,
                    ExportKind.PIPES.label,
                    if (vm.drainage.isEmpty()) tr("Sem dispositivos de drenagem no projeto") else tr("Tubos e estruturas da rede, para o Civil 3D"),
                    enabled = vm.drainage.isNotEmpty(),
                ) { onExport(ExportKind.PIPES) }

                ExportGroup(tr("Mapas e SIG"))
                val geoNote = if (geoOk) null else tr("Indisponível em sistema local")
                ExportRow(GIcon.MAP, ExportKind.KML.label, geoNote ?: tr("Pontos e linhas em longitude/latitude, para o Google Earth"), enabled = geoOk) { onExport(ExportKind.KML) }
                ExportRow(GIcon.MAP, ExportKind.GEOJSON.label, geoNote ?: tr("Pontos e linhas em longitude/latitude (WGS84), para QGIS e web"), enabled = geoOk) { onExport(ExportKind.GEOJSON) }
                ExportRow(GIcon.MAP, ExportKind.SHAPEFILE.label, tr("Pontos e linhas nas coordenadas do projeto, com o .prj")) { onExport(ExportKind.SHAPEFILE) }

                ExportGroup(tr("Pontos e tabelas"))
                ExportRow(GIcon.POINTS, ExportKind.POINTS.label, if (vm.userPoints.isEmpty()) tr("Sem pontos _POINTS no projeto") else tr("Pontos _POINTS: número, descrição, E, N, cota"),
                    enabled = vm.userPoints.isNotEmpty()) { onExport(ExportKind.POINTS) }
                ExportRow(GIcon.VOLUME, ExportKind.VOLUMES.label, if (vm.hasVolumes) tr("Volumes das comparações de superfícies") else tr("Calcule uma comparação antes"),
                    enabled = vm.hasVolumes) { onExport(ExportKind.VOLUMES) }
                ExportRow(GIcon.NOTE, ExportKind.NOTES_CSV.label, if (vm.notes.isEmpty()) tr("Sem anotações no projeto") else tr("Anotações em tabela"),
                    enabled = vm.notes.isNotEmpty()) { onExport(ExportKind.NOTES_CSV) }
                ExportRow(GIcon.NOTE, ExportKind.NOTES_KMZ.label, if (vm.notes.isEmpty()) tr("Sem anotações no projeto") else tr("Anotações com fotos, para o Google Earth"),
                    enabled = vm.notes.isNotEmpty() && geoOk) { onExport(ExportKind.NOTES_KMZ) }

                ExportGroup(tr("Relatórios e imagens"))
                onSheets?.let { ExportRow(GIcon.SECTION, androidx.compose.ui.res.stringResource(R.string.sh_open), tr("Seções transversais em escala, com carimbo"), onClick = it) }
                ExportRow(GIcon.IMAGE, ExportKind.PDF.label, tr("A tela como está: planta, 3D ou seção aberta")) { onExport(ExportKind.PDF) }
                ExportRow(GIcon.IMAGE, ExportKind.PNG.label, tr("A tela como está, em imagem")) { onExport(ExportKind.PNG) }

                TaskProgress(vm, Modifier.padding(top = 8.dp))
                vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
    )
}

@Composable
private fun ExportGroup(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = LocalG.current.accent, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

/** One export: icon, name and a short line on what goes in; the whole row is the button. */
@Composable
private fun ExportRow(icon: GIcon, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalG.current
    val vec = androidx.compose.runtime.remember(icon, g.iconStroke) { icon.vector(g.iconStroke) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(g.buttonRadius)
    FitRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).border(maxOf(g.borderWidth, 1.dp), g.border, shape)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp), spacedBy = 12.dp) {
        val alpha = if (enabled) 1f else 0.45f
        androidx.compose.material3.Icon(vec, contentDescription = null, tint = g.accent.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = g.text.copy(alpha = alpha), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = g.muted.copy(alpha = alpha), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text("›", color = g.muted.copy(alpha = alpha), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ExportOption(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
