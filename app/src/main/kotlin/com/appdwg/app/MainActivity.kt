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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appdwg.core.CrossSection
import com.appdwg.core.LongProfile
import com.appdwg.core.Utm
import com.appdwg.core.CogoPoint
import com.appdwg.core.PointFile
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
    val state = viewModel.state
    // "Open" and back ask first when the project has unsaved changes.
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.saveProject(uri) { afterSave?.invoke(); afterSave = null } else afterSave = null
    }
    fun projectName() = ((state as? ProjectState.Loaded)?.fileName ?: "projeto").substringBefore(".xml.aecc.pnt").substringBeforeLast('.') + ".appdwg"
    fun save(then: () -> Unit = {}) {
        val target = viewModel.projectUri
        if (target != null) viewModel.saveProject(target, then) else { afterSave = then; saver.launch(projectName()) }
    }
    fun guarded(action: () -> Unit) {
        if (state is ProjectState.Loaded && viewModel.isDirty()) confirm = action else action()
    }
    val openFile = { guarded { picker.launch(arrayOf("*/*")) } }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = state is ProjectState.Loaded) { guarded { activity?.finish() } }

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
                actions = {
                    if (state is ProjectState.Loaded) {
                        TextButton(onClick = { save() }) { Text("Salvar") }
                        TextButton(onClick = { afterSave = null; saver.launch(projectName()) }) { Text("Salvar como") }
                    }
                    TextButton(onClick = openFile) { Text("Abrir") }
                },
            )
        },
    ) { padding ->
        confirm?.let { action ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Salvar alterações?") },
                text = { Text("O projeto tem alterações que ainda não foram salvas (camadas, pontos, medições, arquivos adicionados…).") },
                confirmButton = { TextButton(onClick = { confirm = null; save(action) }) { Text("Salvar") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { confirm = null; action() }) { Text("Descartar") }
                        TextButton(onClick = { confirm = null }) { Text("Cancelar") }
                    }
                },
            )
        }
        viewModel.projectMessage?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(5000); viewModel.dismissProjectMessage() }
        }
        Box(Modifier.padding(padding).fillMaxSize()) {
            viewModel.projectMessage?.let { msg ->
                Card(Modifier.align(Alignment.TopCenter).padding(top = 80.dp).zIndex(1f)) { Text(msg, Modifier.padding(12.dp)) }
            }
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
    // Settings of this drawing live in the view model, so they go into the saved project.
    val v = vm.view ?: return
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
    var showCross by remember(fileName) { mutableStateOf(false) }
    var compareBase by v::compareBase
    var compareOther by v::compareOther
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFile(uri)
    }
    // COGO points: dialog, tapped point, point being edited or created, and the "create" mode.
    var showPoints by remember(fileName) { mutableStateOf(false) }
    var selectedPoint by remember(fileName) { mutableStateOf<CogoPoint?>(null) }
    var editingPoint by remember(fileName) { mutableStateOf<Pair<CogoPoint?, CogoPoint>?>(null) }
    var addingPoints by remember(fileName) { mutableStateOf(false) }
    val pointImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPoints(uri)
    }
    val pointExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportPoints(uri)
    }
    // Export: dialog, chosen kind waiting for the save picker, and the screen picture for PNG/PDF.
    var showExport by remember(fileName) { mutableStateOf(false) }
    var showGoTo by remember(fileName) { mutableStateOf(false) }
    var showContourDialog by remember(fileName) { mutableStateOf(false) }
    var showPlatform by remember(fileName) { mutableStateOf(false) }
    var drawingPlatform by v::drawingPlatform
    var platformOutline by v::platformOutline
    var showContours by v::showContours
    var contourInterval by v::contourInterval
    // Interval the plan drew (null: too dense at this zoom), for the status line.
    var contourDrawn by remember(fileName) { mutableStateOf<Double?>(null) }
    var includeContours by remember(fileName) { mutableStateOf(false) }
    // Point the plan centres on after a search; the key repeats it for the same place.
    var focus by remember(fileName) { mutableStateOf<Point2?>(null) }
    var focusKey by remember(fileName) { mutableStateOf(0) }
    var includeSurfaces by remember(fileName) { mutableStateOf(false) }
    var utmZone by remember(fileName) { mutableStateOf((Utm.zoneOfEpsg(project.coordinateSystem?.epsgCode) ?: 23).toString() + "S") }
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    var screenShot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureFor by remember { mutableStateOf<ExportKind?>(null) }
    val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val kind = pendingExport
        pendingExport = null
        if (uri != null && kind != null) {
            val line = vm.extraLines(sectionLineFor(vm, project)) + vm.platformLines()
            when (kind) {
                ExportKind.DXF -> vm.exportDxf(uri, includeSurfaces, line, if (includeContours) contourDrawn ?: contourInterval.takeIf { it > 0 } ?: 1.0 else null)
                ExportKind.LANDXML -> vm.exportLandXml(uri)
                ExportKind.POINTS -> vm.exportPoints(uri)
                ExportKind.KML -> vm.exportKml(uri, utmZone.trimEnd('S', 's', 'N', 'n').toInt(), !utmZone.endsWith("N", true), line)
                ExportKind.VOLUMES -> vm.exportVolumes(uri)
                ExportKind.PNG, ExportKind.PDF -> screenShot?.let { vm.exportImage(uri, it, kind == ExportKind.PDF) }
            }
            showExport = true
        }
    }
    val window = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)?.window
    val baseName = fileName.substringBefore(".xml.aecc.pnt").substringBeforeLast('.')
    LaunchedEffect(captureFor) {
        val kind = captureFor ?: return@LaunchedEffect
        // Waits for the dialog to leave the screen, then copies the window (3D included).
        kotlinx.coroutines.delay(400)
        screenShot = window?.let { captureWindow(it) }
        captureFor = null
        pendingExport = kind
        exportSaver.launch(baseName + kind.suffix)
    }
    val difference = vm.difference
    val differenceOverlay = vm.differenceImage?.takeIf { vm.differenceVisible }
    val rasters = vm.rasters.filterKeys { it !in vm.unloaded }
    // The tapped position survives rotation; the info shown is recomputed from it.
    var tapped by rememberSaveable(fileName, stateSaver = Point2Saver) { mutableStateOf<Point2?>(null) }
    // Measuring: taps add points instead of showing the info card; points survive rotation.
    var measuring by v::measuring
    var measurePoints by v::measurePoints
    // Cross section: alignment, station and half width survive rotation.
    var showSection by v::showSection
    var sectionAlignment by v::sectionAlignment
    var sectionStation by v::sectionStation
    var sectionHalfWidth by v::sectionHalfWidth
    // Longitudinal profile of the same alignment as the section.
    var showProfile by v::showProfile
    var profileCursor by v::profileCursor
    var profileRange by v::profileRange
    var showWarnings by remember(fileName) { mutableStateOf(false) }
    var showLayers by remember(fileName) { mutableStateOf(false) }
    // Layer settings start from the drawing and survive rotation.
    var offLayers by v::offLayers
    var frozenLayers by v::frozenLayers
    // Layers that appear later (an xref loaded) start as their drawing saved them.
    var knownLayers by v::knownLayers
    LaunchedEffect(project) {
        val new = project.layers.filter { it !in knownLayers }
        if (new.isNotEmpty()) {
            offLayers = ArrayList(offLayers + new.filter { it in project.layersOff })
            frozenLayers = ArrayList(frozenLayers + new.filter { it in project.layersFrozen })
            knownLayers = ArrayList(knownLayers + new)
        }
    }
    var colorOverrides by v::colorOverrides
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
    val longProfile = remember(project, sectionAxis, frozenLayers, showProfile) {
        if (!showProfile || sectionAxis == null) null
        else LongProfile.of(sectionAxis, project.surfaces.filter { it.name !in frozenLayers })
    }
    val profileMark = remember(sectionAxis, profileCursor, showProfile) {
        if (!showProfile || sectionAxis == null) null
        else CrossSection.at(sectionAxis, profileCursor, emptyList(), 8.0)?.let { it.pointAt(-8.0) to it.pointAt(8.0) }
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

    var show3d by v::show3d
    var exaggeration by v::exaggeration
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
                    drawingPlatform -> if (it != platformOutline.lastOrNull()) platformOutline = platformOutline + it
                    addingPoints -> {
                        val z = PointQuery.at(project, it, frozenLayers.toSet()).terrainElevation ?: Double.NaN
                        editingPoint = null to CogoPoint(PointFile.nextNumber(vm.userPoints), "", it.x, it.y, z)
                    }
                    measuring -> if (it != measurePoints.lastOrNull()) measurePoints = measurePoints + it
                    // With the section open, a tap picks the station on the chosen alignment.
                    showSection && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> sectionStation = so.station }
                    showProfile && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> profileCursor = so.station }
                    else -> { tapped = it; selectedPoint = null }
                }
            },
            modifier = Modifier.fillMaxSize(),
            measure = if (measuring) measurePoints else if (drawingPlatform) platformOutline else emptyList(),
            snap = measuring || drawingPlatform,
            designLines = vm.platform?.let { r -> listOf(r.edge, r.daylight) + r.benches }?.map { l -> l.map { Point2(it.x, it.y) } }.orEmpty(),
            sectionLine = section?.let { it.pointAt(-it.offsets.last()) to it.pointAt(it.offsets.last()) } ?: profileMark,
            overlay = differenceOverlay,
            points = vm.userPoints,
            onPointTap = if (measuring || addingPoints) null else { pt -> selectedPoint = pt; tapped = null },
            selectedPoint = selectedPoint,
            focus = focus,
            focusKey = focusKey,
            contourInterval = if (showContours) contourInterval else null,
            onContours = { contourDrawn = it },
            viewKey = fileName,
        )

        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Card {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(summary(project.copy(points = vm.userPoints)), style = MaterialTheme.typography.labelMedium)
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
                            showProfile = false
                        }) { Text(if (showSection) "Fechar seção" else "Seção") }
                        TextButton(onClick = {
                            if (!showProfile) tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { profileCursor = it.station } }
                            showProfile = !showProfile
                            showSection = false
                            measuring = false
                            tapped = null
                        }) { Text(if (showProfile) "Fechar perfil" else "Perfil") }
                    }
                    if (!show3d) {
                        TextButton(onClick = { measuring = !measuring; showSection = false; showProfile = false; tapped = null }) {
                            Text(if (measuring) "Sair da medição" else "Medir")
                        }
                    }
                    if (project.layers.isNotEmpty() || vm.userPoints.isNotEmpty()) {
                        TextButton(onClick = { showLayers = true }) { Text("Camadas") }
                    }
                    if (project.surfaces.isNotEmpty()) {
                        TextButton(onClick = { if (vm.drape == null) drapePicker.launch(arrayOf("*/*")) else showDrape = true }) {
                            Text("Imagem")
                        }
                    }
                    TextButton(onClick = { showCompare = true }) { Text("Comparar") }
                    TextButton(onClick = { showPoints = true }) { Text("Pontos") }
                    if (project.surfaces.isNotEmpty()) {
                        TextButton(onClick = { showContourDialog = true }) { Text("Curvas") }
                        TextButton(onClick = { showPlatform = true }) { Text("Projeto") }
                    }
                    TextButton(onClick = { showGoTo = true }) { Text("Ir para") }
                    TextButton(onClick = { showExport = true }) { Text("Exportar") }
                    if (vm.hasReferences) {
                        TextButton(onClick = { showReferences = true }) { Text("Referências") }
                    }
                    if (project.warnings.isNotEmpty()) {
                        TextButton(onClick = { showWarnings = true }) { Text(plural(project.warnings.size, "aviso", "avisos")) }
                    }
                }
            }
            if (showContours && !show3d) {
                Card(Modifier.padding(top = 8.dp)) {
                    Text(
                        contourDrawn?.let { "Curvas de nível a cada ${StationFormat.decimal(it, if (it < 1) 2 else 0)} m (mestras a cada 5)" }
                            ?: "Curvas densas demais neste zoom: aproxime ou aumente o intervalo",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                    )
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
        } else if (drawingPlatform) {
            Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Toque os vértices da plataforma (${platformOutline.size}). O toque encaixa em pontos e vértices.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { platformOutline = platformOutline.dropLast(1) }, enabled = platformOutline.isNotEmpty()) { Text("Desfazer") }
                    TextButton(onClick = { platformOutline = emptyList() }, enabled = platformOutline.isNotEmpty()) { Text("Limpar") }
                    TextButton(onClick = { drawingPlatform = false; showPlatform = true }) { Text("Concluir") }
                }
            }
        } else if (addingPoints) {
            Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Toque na planta para criar um ponto (cota da superfície).", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { addingPoints = false }) { Text("Concluir") }
                }
            }
        } else if (selectedPoint != null) {
            val pt = selectedPoint!!
            val q = remember(project, pt, frozenLayers) { PointQuery.at(project, Point2(pt.x, pt.y), frozenLayers.toSet()) }
            PointCard(
                pt, q.alignment?.takeIf { !it.stationOffset.outside }, q.terrainElevation,
                onEdit = { editingPoint = pt to pt },
                onClose = { selectedPoint = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth(),
            )
        } else if (showProfile && longProfile != null) {
            ProfilePanel(
                profile = longProfile,
                cursor = profileCursor,
                onCursor = { profileCursor = it },
                range = profileRange,
                onRange = { profileRange = it },
                onClose = { showProfile = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().fillMaxHeight(0.45f),
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
            InfoCard(current, differenceAt(project, difference, current.position), markedElevations(project, vm.crossSelected, current.position), Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth())
        }
        BackgroundTasks(vm.tasks, Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 360.dp))
    }

    if (showDrape) {
        DrapeDialog(vm, onPick = { drapePicker.launch(arrayOf("*/*")) }, onDismiss = { showDrape = false })
    }

    if (showPoints) {
        PointsDialog(
            vm,
            onImport = { pointImporter.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            onExport = { pointExporter.launch("pontos.txt") },
            onCreate = { addingPoints = true; measuring = false; showSection = false; showPoints = false },
            onDismiss = { showPoints = false },
        )
    }

    editingPoint?.let { (old, initial) ->
        key(initial) {
            PointEditDialog(
                old = old,
                initial = initial,
                onSave = { p -> vm.savePoint(old, p); if (selectedPoint == old && old != null) selectedPoint = p; editingPoint = null },
                onDelete = old?.let { o -> { vm.deletePoint(o); if (selectedPoint == o) selectedPoint = null; editingPoint = null } },
                onDismiss = { editingPoint = null },
            )
        }
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
            onCross = { showCompare = false; showCross = true },
            onDismiss = { showCompare = false },
        )
    }

    if (showPlatform) {
        PlatformDialog(vm, project, onDraw = {
            showPlatform = false; measuring = false; showSection = false; showProfile = false; show3d = false
            drawingPlatform = true
        }, onDismiss = { showPlatform = false })
    }

    if (showContourDialog) {
        AlertDialog(
            onDismissRequest = { showContourDialog = false },
            confirmButton = { TextButton(onClick = { showContourDialog = false }) { Text("Fechar") } },
            dismissButton = { TextButton(onClick = { showContours = false; showContourDialog = false }) { Text("Desligar") } },
            title = { Text("Curvas de nível") },
            text = {
                Column {
                    Text("Intervalo")
                    Row {
                        listOf(0.0, 0.5, 1.0, 2.0, 5.0, 10.0).forEach { i ->
                            androidx.compose.material3.FilterChip(
                                selected = showContours && contourInterval == i,
                                onClick = { contourInterval = i; showContours = true },
                                label = { Text(if (i == 0.0) "Auto" else StationFormat.decimal(i, if (i < 1) 1 else 0) + " m") },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text("\"Auto\" escolhe o intervalo pelo zoom. As curvas são calculadas para a área visível.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }

    if (showGoTo) {
        GoToDialog(project.copy(points = vm.userPoints), onGo = { p ->
            showGoTo = false
            show3d = false
            measuring = false
            focus = p
            focusKey++
            selectedPoint = vm.userPoints.firstOrNull { it.x == p.x && it.y == p.y }
            tapped = if (selectedPoint == null) p else null
        }, onDismiss = { showGoTo = false })
    }

    if (showExport) {
        ExportDialog(
            vm,
            includeSurfaces = includeSurfaces,
            onIncludeSurfaces = { includeSurfaces = it },
            includeContours = includeContours,
            onIncludeContours = { includeContours = it },
            zone = utmZone,
            onZone = { utmZone = it.trim().uppercase() },
            onExport = { kind ->
                showExport = false
                if (kind == ExportKind.PNG || kind == ExportKind.PDF) captureFor = kind
                else { pendingExport = kind; exportSaver.launch(baseName + kind.suffix) }
            },
            onDismiss = { showExport = false },
        )
    }

    if (showCross) {
        CrossCompareDialog(vm, project, onShown = { showCross = false }, onDismiss = { showCross = false })
    }

    if (showReferences) {
        ReferencesDialog(vm, onDismiss = { showReferences = false })
    }

    if (showLayers) {
        LayersDialog(
            project = if (vm.userPoints.isEmpty()) project else project.copy(points = vm.userPoints),
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
private fun InfoCard(info: PointInfo, difference: String?, marked: List<Pair<String, String>>, modifier: Modifier) {
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
            marked.forEach { (name, z) -> Line(name.substringAfterLast('|'), z) }
            Line("Terreno no ponto", info.terrainElevation?.let { StationFormat.decimal(it, 3) + " m" } ?: "fora da superfície")
            Line("E / N", StationFormat.decimal(info.position.x, 3) + " / " + StationFormat.decimal(info.position.y, 3))
        }
    }
}

/** Ends of the cross section open on the plan, or null. */
private fun sectionLineFor(vm: ProjectViewModel, project: LandXmlProject): Pair<Point2, Point2>? {
    val v = vm.view ?: return null
    if (!v.showSection) return null
    val axis = project.alignments.getOrNull(v.sectionAlignment) ?: return null
    val s = CrossSection.at(axis, v.sectionStation, emptyList(), v.sectionHalfWidth) ?: return null
    return s.pointAt(-s.offsets.last()) to s.pointAt(s.offsets.last())
}

/** Copy of what the window shows, including the 3D view's GL surface. */
private suspend fun captureWindow(window: android.view.Window): android.graphics.Bitmap? {
    val view = window.decorView
    if (view.width <= 0 || view.height <= 0) return null
    val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        android.view.PixelCopy.request(window, bitmap, { result ->
            cont.resume(if (result == android.view.PixelCopy.SUCCESS) bitmap else null) {}
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
}

/** Elevation at [p] of each surface marked for the cross comparison. */
private fun markedElevations(project: LandXmlProject, marked: List<String>, p: Point2): List<Pair<String, String>> =
    marked.mapNotNull { n -> project.surfaces.firstOrNull { it.name == n } }
        .map { s -> s.name to (s.elevationAt(p)?.let { StationFormat.decimal(it, 3) + " m" } ?: "fora") }

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
    if (p.points.isNotEmpty()) add(plural(p.points.size, "ponto _POINTS", "pontos _POINTS"))
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
