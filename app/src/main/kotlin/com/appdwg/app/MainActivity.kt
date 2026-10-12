package com.appdwg.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.appdwg.core.DxfReader
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Point2
import com.appdwg.core.PointInfo
import com.appdwg.core.PointQuery
import com.appdwg.core.StationFormat
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: ProjectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                AppScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { viewModel.open(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(viewModel: ProjectViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val openFile = { picker.launch(arrayOf("*/*")) }
    val state = viewModel.state

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state) {
                            is ProjectState.Loaded -> state.fileName
                            is ProjectState.Loading -> state.fileName
                            else -> "App DWG"
                        },
                        maxLines = 1,
                    )
                },
                actions = { TextButton(onClick = openFile) { Text("Abrir") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ProjectState.Empty -> EmptyState(openFile)
                is ProjectState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is ProjectState.Failed -> Message(state.message, openFile)
                is ProjectState.Loaded -> ProjectScreen(state.fileName, state.project, state.terrain, viewModel)
            }
        }
    }
}

@Composable
private fun EmptyState(openFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Abra um arquivo LandXML ou DXF", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "No Civil 3D, use Saída > Exportar para LandXML (.xml) ou salve como DXF (.dxf) e copie o arquivo para o aparelho.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = openFile) { Text("Abrir arquivo") }
    }
}

@Composable
private fun Message(text: String, openFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = openFile) { Text("Abrir outro arquivo") }
    }
}

@Composable
private fun ProjectScreen(
    fileName: String,
    project: LandXmlProject,
    terrain: List<TerrainImage>,
    vm: ProjectViewModel,
) {
    var showReferences by remember(fileName) { mutableStateOf(false) }
    val rasters = vm.rasters.filterKeys { it !in vm.unloaded }
    // The tapped position survives rotation; the info shown is recomputed from it.
    var tapped by rememberSaveable(fileName, stateSaver = Point2Saver) { mutableStateOf<Point2?>(null) }
    var showWarnings by remember(fileName) { mutableStateOf(false) }
    var showLayers by remember(fileName) { mutableStateOf(false) }
    // Layer settings start from the drawing and survive rotation.
    var offLayers by rememberSaveable(fileName) { mutableStateOf(ArrayList(project.layersOff)) }
    var frozenLayers by rememberSaveable(fileName) { mutableStateOf(ArrayList(project.layersFrozen)) }
    // Layers that appear later (an xref loaded) start as their drawing saved them.
    var knownLayers by rememberSaveable(fileName) { mutableStateOf(ArrayList(project.layers)) }
    LaunchedEffect(project) {
        val new = project.layers.filter { it !in knownLayers }
        if (new.isNotEmpty()) {
            offLayers = ArrayList(offLayers + new.filter { it in project.layersOff })
            frozenLayers = ArrayList(frozenLayers + new.filter { it in project.layersFrozen })
            knownLayers = ArrayList(knownLayers + new)
        }
    }
    var colorOverrides by rememberSaveable(fileName) { mutableStateOf(HashMap<String, Int>()) }
    val hidden = remember(offLayers, frozenLayers) { offLayers.toSet() + frozenLayers }
    val layerColor: (String) -> Int? = { layer ->
        colorOverrides[layer]?.let { if (it == FOREGROUND) null else it } ?: project.layerColors[layer]
    }
    val info = remember(project, tapped, frozenLayers) {
        // Frozen layers are left out of the elevation lookup, as if they were not in the drawing.
        tapped?.let { PointQuery.at(project, it, frozenLayers.toSet()) }
    }
    val scheme = MaterialTheme.colorScheme
    val colors = PlanColors(
        background = scheme.background,
        mesh = Color(0x66000000),
        alignment = Color(0xFFE53935),
        drawing = scheme.onBackground,
        stations = scheme.onBackground,
        points = Color(0xFF1E88E5),
        marker = Color(0xFFFF9800),
    )

    var show3d by rememberSaveable(fileName) { mutableStateOf(false) }
    var exaggeration by rememberSaveable(fileName) { mutableStateOf(1) }
    val has3d = remember(project) { project.surfaces.isNotEmpty() || project.polylines.any { it.zs != null } }

    Box(Modifier.fillMaxSize()) {
        if (show3d) key(project) {
            View3D(
                project = project,
                hiddenLayers = hidden,
                layerColor = layerColor,
                background = scheme.background,
                foreground = scheme.onBackground,
                exaggeration = exaggeration.toFloat(),
                modifier = Modifier.fillMaxSize(),
            )
        } else PlanView(
            project = project,
            terrain = terrain,
            colors = colors,
            marker = info?.position,
            markerOnAxis = info?.alignment?.stationOffset?.pointOnAlignment,
            hiddenLayers = hidden,
            layerColor = layerColor,
            rasters = rasters,
            onTap = { tapped = it },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Card {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(summary(project), style = MaterialTheme.typography.labelMedium)
                    if (has3d) {
                        TextButton(onClick = { show3d = !show3d }) { Text(if (show3d) "Planta" else "3D") }
                    }
                    if (show3d) {
                        // Vertical exaggeration helps read gentle terrain.
                        TextButton(onClick = { exaggeration = exaggeration % 3 + 1 }) { Text("Z ×$exaggeration") }
                    }
                    if (project.layers.isNotEmpty()) {
                        TextButton(onClick = { showLayers = true }) { Text("Camadas") }
                    }
                    if (vm.hasReferences) {
                        TextButton(onClick = { showReferences = true }) { Text("Referências") }
                    }
                    if (project.warnings.isNotEmpty()) {
                        TextButton(onClick = { showWarnings = true }) { Text("${project.warnings.size} avisos") }
                    }
                }
            }
        }

        val current = info
        if (show3d) {
            Text(
                "1 dedo gira · 2 dedos movem · pinça aproxima",
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else if (current == null) {
            Text(
                "Toque na planta para ver estaca, afastamento e cotas",
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            // Capped so the card stays readable instead of stretching across a tablet in landscape.
            InfoCard(current, Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth())
        }
    }

    if (showReferences) {
        ReferencesDialog(vm, onDismiss = { showReferences = false })
    }

    if (showLayers) {
        LayersDialog(
            project = project,
            off = offLayers.toSet(),
            frozen = frozenLayers.toSet(),
            colorOf = layerColor,
            onOff = { layer, value -> offLayers = ArrayList(offLayers.toSet().toggle(layer, value)) },
            onFrozen = { layer, value -> frozenLayers = ArrayList(frozenLayers.toSet().toggle(layer, value)) },
            onAllOn = { offLayers = ArrayList(); frozenLayers = ArrayList() },
            onAllOff = { offLayers = ArrayList(project.layers) },
            onColor = { layer, argb ->
                colorOverrides = HashMap(colorOverrides).apply { if (argb == null) remove(layer) else put(layer, argb) }
            },
            onDismiss = { showLayers = false },
        )
    }

    if (showWarnings) {
        AlertDialog(
            onDismissRequest = { showWarnings = false },
            confirmButton = { TextButton(onClick = { showWarnings = false }) { Text("Fechar") } },
            title = { Text("Avisos da leitura") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    project.warnings.forEach { Text("• $it", Modifier.padding(vertical = 2.dp)) }
                }
            },
        )
    }
}

@Composable
private fun InfoCard(info: PointInfo, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            val a = info.alignment
            if (a != null) {
                Text(a.name, style = MaterialTheme.typography.titleSmall)
                Line("Estaca", StationFormat.estaca(a.stationOffset.station) +
                    "  (${StationFormat.decimal(a.stationOffset.station)} m)")
                Line("Afastamento", StationFormat.offset(a.stationOffset.offset) +
                    if (a.stationOffset.outside) "  (fora do eixo)" else "")
                a.designElevation?.let { Line("Greide no eixo", StationFormat.decimal(it, 3) + " m") }
                a.terrainOnAxis?.let { Line("Terreno no eixo", StationFormat.decimal(it, 3) + " m") }
                a.cutFill?.let {
                    Line(if (it >= 0) "Aterro" else "Corte", StationFormat.decimal(abs(it), 3) + " m")
                }
            }
            Line("Terreno no ponto", info.terrainElevation?.let { StationFormat.decimal(it, 3) + " m" } ?: "fora da superfície")
            Line("E / N", StationFormat.decimal(info.position.x, 3) + " / " + StationFormat.decimal(info.position.y, 3))
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun summary(p: LandXmlProject): String = buildList {
    if (p.surfaces.isNotEmpty()) add(plural(p.surfaces.size, "superfície", "superfícies"))
    if (p.alignments.isNotEmpty()) add(plural(p.alignments.size, "alinhamento", "alinhamentos"))
    if (p.cgPoints.isNotEmpty()) add(plural(p.cgPoints.size, "ponto", "pontos"))
    if (p.polylines.isNotEmpty()) add(plural(p.polylines.size, "linha", "linhas"))
    if (p.texts.isNotEmpty()) add(plural(p.texts.size, "texto", "textos"))
    p.coordinateSystem?.epsgCode?.let { add("EPSG $it") }
}.joinToString(" · ")

/** Color override meaning "the foreground color" (CAD color 7); ARGB 0 is never a CAD color. */
private const val FOREGROUND = 0

private fun Set<String>.toggle(item: String, present: Boolean) = if (present) this + item else this - item

/** CAD colors offered when changing a layer color: the 9 standard ones and a spread of hues and grays. */
private val PALETTE = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 70, 90, 110, 130, 150, 170, 190, 210, 230, 250, 252, 254)

@Composable
private fun LayersDialog(
    project: LandXmlProject,
    off: Set<String>,
    frozen: Set<String>,
    colorOf: (String) -> Int?,
    onOff: (String, Boolean) -> Unit,
    onFrozen: (String, Boolean) -> Unit,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onColor: (String, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    val foreground = MaterialTheme.colorScheme.onSurface
    fun swatch(argb: Int?) = argb?.let { Color(it) } ?: foreground

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAllOn) { Text("Ligar todas") }
                TextButton(onClick = onAllOff) { Text("Desligar todas") }
            }
        },
        title = { Text("Camadas") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cor", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
                    Text("Nome", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("Ligada", Modifier.width(64.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                    Text("Congelada", Modifier.width(76.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    project.layers.forEach { layer ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(44.dp).padding(end = 12.dp).height(28.dp)
                                    .background(swatch(colorOf(layer)), RoundedCornerShape(4.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                    .clickable { editing = layer },
                            )
                            Text(
                                layer,
                                Modifier.weight(1f).padding(vertical = 8.dp),
                                maxLines = 1,
                                color = if (layer in off || layer in frozen) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                            )
                            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                                Checkbox(checked = layer !in off, onCheckedChange = { onOff(layer, !it) })
                            }
                            Box(Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                                Checkbox(checked = layer in frozen, onCheckedChange = { onFrozen(layer, it) })
                            }
                        }
                    }
                }
            }
        },
    )

    editing?.let { layer ->
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Fechar") } },
            dismissButton = {
                TextButton(onClick = { onColor(layer, null); editing = null }) { Text("Cor original") }
            },
            title = { Text("Cor de $layer") },
            text = {
                Column {
                    PALETTE.chunked(6).forEach { row ->
                        Row {
                            row.forEach { aci ->
                                val argb = DxfReader.aciColor(aci)
                                Box(
                                    Modifier.padding(4.dp).size(36.dp)
                                        .background(swatch(argb), RoundedCornerShape(6.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                        .clickable { onColor(layer, argb ?: FOREGROUND); editing = null },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

private val Point2Saver = Saver<Point2?, DoubleArray>(
    save = { p -> p?.let { doubleArrayOf(it.x, it.y) } },
    restore = { Point2(it[0], it[1]) },
)

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
