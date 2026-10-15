package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdwg.core.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Background map settings and offline download of the project area or the area on screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BasemapDialog(
    store: BasemapStore,
    crs: com.appdwg.core.Crs,
    onCrs: () -> Unit,
    projectArea: Bounds?,
    viewArea: Bounds?,
    onDismiss: () -> Unit,
) {
    // The map needs a projected system to sit under the drawing.
    val zoneNumber = if (crs.isGeographic) null else 1
    var useView by remember { mutableStateOf(false) }
    var maxZoom by remember { mutableStateOf(18) }
    var usage by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(store.version, store.progress == null) { usage = withContext(Dispatchers.IO) { store.diskUsage() } }
    val area = (if (useView) viewArea else projectArea)?.let { b ->
        // A margin around the drawing, at least 200 m.
        val m = maxOf(200.0, 0.1 * maxOf(b.width, b.height))
        Bounds(b.minX - m, b.minY - m, b.maxX + m, b.maxY + m)
    }
    val count = remember(area, crs, maxZoom) {
        if (area == null || zoneNumber == null) 0 else runCatching { store.count(area, crs, maxZoom) }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Mapa de fundo")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(store.enabled, store::enable)
                    Text(tr("Mostrar mapa sob o desenho"))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in BASEMAP_SOURCES) FilterChip(store.source == s, { store.choose(s) }, label = { Text(s.label) })
                }
                Text(tr("Fonte: {0}", store.source.credit), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Opacidade {0}%", (store.opacity * 100).toInt()), Modifier.width(130.dp))
                    Slider(store.opacity, store::changeOpacity, valueRange = 0.2f..1f, modifier = Modifier.width(260.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(store.online, store::allowOnline)
                    Text(tr("Baixar da internet o que faltar ao navegar (desligado = só offline)"))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Sistema do projeto: {0}", crs.name), Modifier.weight(1f, fill = false))
                    OutlinedButton(onClick = onCrs) { Text(tr("Trocar…")) }
                }

                Text(tr("Uso offline"), style = MaterialTheme.typography.titleSmall)
                if (!store.source.bulk) {
                    Text(tr("{0} não permite baixar áreas inteiras: só ficam salvos os trechos vistos com internet. ", store.source.label) +
                        tr("Para uso offline escolha Satélite, Ruas ou Topográfico."), style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!useView, { useView = false }, label = { Text(tr("Área do projeto")) }, enabled = projectArea != null)
                        FilterChip(useView, { useView = true }, label = { Text(tr("Área na tela")) }, enabled = viewArea != null)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        Text(tr("Detalhe máximo:"), Modifier.align(Alignment.CenterVertically))
                        for ((z, label) in listOf(16 to "16 (~2,4 m/px)", 17 to "17 (~1,2 m/px)", 18 to "18 (~0,6 m/px)", 19 to "19 (~0,3 m/px)"))
                            FilterChip(maxZoom == z, { maxZoom = z }, label = { Text(label) })
                    }
                    val tooMany = count > MAX_TILES
                    Text(
                        if (zoneNumber == null) tr("Escolha um sistema projetado (UTM, LTM…) para o mapa.")
                        else tr("{0} tiles (≈ {1})", count, mb(count * 25_000L)) + if (tooMany) tr(" — área grande demais, use menos detalhe ou a área na tela.") else "",
                        color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    val p = store.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p.first / p.second.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${p.first} de ${p.second}")
                            OutlinedButton(onClick = store::cancelDownload) { Text(tr("Parar")) }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { if (area != null && zoneNumber != null) store.download(area, crs, maxZoom) },
                            enabled = area != null && zoneNumber != null && count in 1..MAX_TILES,
                        ) { Text(tr("Baixar para uso offline")) }
                    }
                }
                store.lastResult?.let { Text(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (usage < 0) tr("Calculando espaço…") else tr("Mapas salvos: {0}", mb(usage)))
                    OutlinedButton(onClick = store::clear, enabled = usage > 0 && store.progress == null) { Text(tr("Apagar mapas salvos")) }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
    )
}

private const val MAX_TILES = 20_000

private fun mb(bytes: Long) = com.appdwg.core.StationFormat.decimal(bytes / 1_000_000.0, 1) + " MB"
