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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.appdwg.core.CrossSection
import com.appdwg.core.LandXmlProject
import com.appdwg.core.Measure
import com.appdwg.core.Measurement
import com.appdwg.core.Point2
import com.appdwg.core.PointInfo
import com.appdwg.core.PointQuery
import com.appdwg.core.StationFormat
import com.appdwg.core.SurfaceDifference
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
                is ProjectState.Loading -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    LoadProgress(state.task)
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
    var showDrape by remember(fileName) { mutableStateOf(false) }
    // Picture and, optionally, its world file, chosen together.
    val drapePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.loadDrape(uris)
            showDrape = true
        }
    }
    val drape = vm.drape?.takeIf { vm.drapeVisible }
    // Files laid over this one and the two surfaces compared.
    var showCompare by remember(fileName) { mutableStateOf(false) }
    var compareBase by rememberSaveable(fileName) { mutableStateOf<String?>(null) }
    var compareOther by rememberSaveable(fileName) { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFile(uri)
    }
    val difference = vm.difference
    val differenceOverlay = vm.differenceImage?.takeIf { vm.differenceVisible }
    val rasters = vm.rasters.filterKeys { it !in vm.unloaded }
    // The tapped position survives rotation; the info shown is recomputed from it.
    var tapped by rememberSaveable(fileName, stateSaver = Point2Saver) { mutableStateOf<Point2?>(null) }
    // Measuring: taps add points instead of showing the info card; points survive rotation.
    var measuring by rememberSaveable(fileName) { mutableStateOf(false) }
    var measurePoints by rememberSaveable(fileName, stateSaver = PointListSaver) { mutableStateOf(emptyList<Point2>()) }
    // Cross section: alignment, station and half width survive rotation.
    var showSection by rememberSaveable(fileName) { mutableStateOf(false) }
    var sectionAlignment by rememberSaveable(fileName) { mutableStateOf(0) }
    var sectionStation by rememberSaveable(fileName) { mutableStateOf(project.alignments.firstOrNull()?.startStation ?: 0.0) }
    var sectionHalfWidth by rememberSaveable(fileName) { mutableStateOf(30.0) }
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
    val sectionAxis = project.alignments.getOrNull(sectionAlignment)
    val section = remember(project, sectionAxis, sectionStation, sectionHalfWidth, frozenLayers, showSection) {
        if (!showSection || sectionAxis == null) null
        else CrossSection.at(sectionAxis, sectionStation, project.surfaces.filter { it.name !in frozenLayers }, sectionHalfWidth)
    }
    val measurement = remember(project, measurePoints, frozenLayers) {
        if (measurePoints.isEmpty()) null
        else Measure.of(measurePoints, measurePoints.map { PointQuery.at(project, it, frozenLayers.toSet()).terrainElevation })
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
                drape = drape,
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
            drape = drape,
            onTap = {
                // Tapping the same snapped point twice would add a zero-length leg.
                when {
                    measuring -> if (it != measurePoints.lastOrNull()) measurePoints = measurePoints + it
                    // With the section open, a tap picks the station on the chosen alignment.
                    showSection && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> sectionStation = so.station }
                    else -> tapped = it
                }
            },
            modifier = Modifier.fillMaxSize(),
            measure = if (measuring) measurePoints else emptyList(),
            snap = measuring,
            sectionLine = section?.let { it.pointAt(-it.offsets.last()) to it.pointAt(it.offsets.last()) },
            overlay = differenceOverlay,
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
                    if (!show3d && project.alignments.isNotEmpty()) {
                        TextButton(onClick = {
                            if (!showSection) tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { sectionStation = it.station } }
                            showSection = !showSection
                            measuring = false
                            tapped = null
                        }) { Text(if (showSection) "Fechar seção" else "Seção") }
                    }
                    if (!show3d) {
                        TextButton(onClick = { measuring = !measuring; showSection = false; tapped = null }) {
                            Text(if (measuring) "Sair da medição" else "Medir")
                        }
                    }
                    if (project.layers.isNotEmpty()) {
                        TextButton(onClick = { showLayers = true }) { Text("Camadas") }
                    }
                    if (project.surfaces.isNotEmpty()) {
                        TextButton(onClick = { if (vm.drape == null) drapePicker.launch(arrayOf("*/*")) else showDrape = true }) {
                            Text("Imagem")
                        }
                    }
                    TextButton(onClick = { showCompare = true }) { Text("Comparar") }
                    if (vm.hasReferences) {
                        TextButton(onClick = { showReferences = true }) { Text("Referências") }
                    }
                    if (project.warnings.isNotEmpty()) {
                        TextButton(onClick = { showWarnings = true }) { Text(plural(project.warnings.size, "aviso", "avisos")) }
                    }
                }
            }
            if (differenceOverlay != null && difference != null && !show3d) {
                DifferenceLegend(difference, Modifier.padding(top = 8.dp))
            }
        }

        val current = info
        if (show3d) {
            Text(
                "1 dedo gira · 2 dedos movem · pinça aproxima",
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else if (showSection && sectionAxis != null) {
            SectionPanel(
                section = section,
                alignmentNames = project.alignments.map { it.name },
                alignmentIndex = sectionAlignment,
                onAlignment = { sectionAlignment = it; sectionStation = project.alignments[it].startStation },
                station = section?.station ?: sectionStation,
                onStation = { sectionStation = it.coerceIn(sectionAxis.startStation, sectionAxis.endStation) },
                halfWidth = sectionHalfWidth,
                onHalfWidth = { sectionHalfWidth = it },
                onClose = { showSection = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().fillMaxHeight(0.45f),
            )
        } else if (measuring) {
            MeasureCard(
                measurement,
                onUndo = { measurePoints = measurePoints.dropLast(1) },
                onClear = { measurePoints = emptyList() },
                onDone = { measuring = false },
                // At the side, so the points being measured stay visible on a tablet.
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).widthIn(max = 380.dp).fillMaxWidth(),
            )
        } else if (current == null) {
            Text(
                "Toque na planta para ver estaca, afastamento e cotas",
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            // Capped so the card stays readable instead of stretching across a tablet in landscape.
            InfoCard(current, differenceAt(project, difference, current.position), Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth())
        }
        BackgroundTasks(vm.tasks, Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 360.dp))
    }

    if (showDrape) {
        DrapeDialog(vm, onPick = { drapePicker.launch(arrayOf("*/*")) }, onDismiss = { showDrape = false })
    }

    if (showCompare) {
        CompareDialog(
            vm = vm,
            project = project,
            base = compareBase,
            other = compareOther,
            onBase = { compareBase = it },
            onOther = { compareOther = it },
            onAddFile = { filePicker.launch(arrayOf("*/*")) },
            onDismiss = { showCompare = false },
        )
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

/** Shows, swaps or removes the picture laid over the surfaces. */
@Composable
private fun DrapeDialog(vm: ProjectViewModel, onPick: () -> Unit, onDismiss: () -> Unit) {
    val d = vm.drape
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onPick) { Text(if (d == null) "Escolher" else "Trocar") }
                if (d != null) TextButton(onClick = { vm.removeDrape(); onDismiss() }) { Text("Remover") }
            }
        },
        title = { Text("Imagem sobre a superfície") },
        text = {
            Column {
                if (d != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.drapeVisible, onCheckedChange = { vm.drapeVisible = it })
                        Text(d.name, maxLines = 1)
                    }
                } else if (vm.tasks.isEmpty()) {
                    Text("Escolha um TIFF, JPG ou PNG. Para posicionar um JPG ou PNG, selecione junto o arquivo de georreferência (.jgw, .pgw ou .tfw).")
                }
                vm.drapeMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.tasks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    vm.tasks.forEach { LoadProgress(it) }
                }
            }
        },
    )
}

@Composable
private fun InfoCard(info: PointInfo, difference: String?, modifier: Modifier) {
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
            difference?.let { Line("B − A no ponto", it) }
            Line("Terreno no ponto", info.terrainElevation?.let { StationFormat.decimal(it, 3) + " m" } ?: "fora da superfície")
            Line("E / N", StationFormat.decimal(info.position.x, 3) + " / " + StationFormat.decimal(info.position.y, 3))
        }
    }
}

/** B − A at [p] with the surfaces of the last comparison, as "+0,250 m (aterro)"; null without one. */
private fun differenceAt(project: LandXmlProject, d: SurfaceDifference?, p: Point2): String? {
    d ?: return null
    val za = project.surfaces.firstOrNull { it.name == d.baseName }?.elevationAt(p)
    val zb = project.surfaces.firstOrNull { it.name == d.otherName }?.elevationAt(p)
    if (za == null || zb == null) return "fora de uma das superfícies"
    val v = zb - za
    return signed(v, 3) + " m" + when {
        v > 0.0005 -> " (aterro)"
        v < -0.0005 -> " (corte)"
        else -> ""
    }
}

/** Results of the measurement being taken, with undo, clear and done. */
@Composable
private fun MeasureCard(m: Measurement?, onUndo: () -> Unit, onClear: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    // Taps on the card must not reach the plan underneath and add a point.
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(12.dp)) {
            Text("Medir", style = MaterialTheme.typography.titleSmall)
            val leg = m?.legs?.lastOrNull()
            if (m == null || leg == null) {
                Text(
                    "Toque na planta para marcar os pontos. O toque encaixa em pontos e vértices próximos." +
                        if (m != null) " Marque o próximo ponto." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (m.legs.size > 1) Text("Último trecho", style = MaterialTheme.typography.labelMedium)
                Line("Distância", StationFormat.decimal(leg.distance, 3) + " m")
                Line("Azimute", Measure.dms(leg.azimuth))
                leg.rise?.let { r ->
                    Line("Desnível", signed(r, 3) + " m")
                    leg.slopePercent?.let { Line("Inclinação", signed(it, 2) + " %") }
                }
                if (m.legs.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Text("Total (${m.legs.size + 1} pontos)", style = MaterialTheme.typography.labelMedium)
                    Line("Comprimento", StationFormat.decimal(m.length, 3) + " m")
                    m.rise?.let { r -> Line("Desnível 1º → último", signed(r, 3) + " m") }
                }
                m.slopeLength?.let { Line("Comprimento inclinado", StationFormat.decimal(it, 3) + " m") }
                m.area?.let { Line("Área", Measure.area(it)) }
                m.perimeter?.let { Line("Perímetro", StationFormat.decimal(it, 3) + " m") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (m != null) {
                    TextButton(onClick = onUndo) { Text("Desfazer") }
                    TextButton(onClick = onClear) { Text("Limpar") }
                }
                TextButton(onClick = onDone) { Text("Concluir") }
            }
        }
    }
}

private fun signed(v: Double, digits: Int) = (if (v >= 0) "+" else "−") + StationFormat.decimal(abs(v), digits)

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

private val PointListSaver = Saver<List<Point2>, DoubleArray>(
    save = { pts -> DoubleArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y } },
    restore = { a -> List(a.size / 2) { Point2(a[2 * it], a[2 * it + 1]) } },
)

private val Point2Saver = Saver<Point2?, DoubleArray>(
    save = { p -> p?.let { doubleArrayOf(it.x, it.y) } },
    restore = { Point2(it[0], it[1]) },
)

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
