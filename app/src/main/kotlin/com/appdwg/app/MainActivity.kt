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
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
            GromaticTheme(viewModel.themes.choice) {
                val full = viewModel.fullScreen && viewModel.state is ProjectState.Loaded
                LaunchedEffect(full) {
                    val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    if (full) {
                        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    } else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
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
    val locator = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.relocate(uri) }
    // "Open" and back ask first when the project has unsaved changes.
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.saveProject(uri) { afterSave?.invoke(); afterSave = null } else afterSave = null
    }
    fun projectName() = ((state as? ProjectState.Loaded)?.fileName ?: "projeto").substringBefore(".xml.aecc.pnt").substringBeforeLast('.') + ".gromatic"
    fun save(then: () -> Unit = {}) {
        val target = viewModel.projectUri
        if (target != null) viewModel.saveProject(target, then) else { afterSave = then; saver.launch(projectName()) }
    }
    fun guarded(action: () -> Unit) {
        if (state is ProjectState.Loaded && viewModel.isDirty()) confirm = action else action()
    }
    val openFile = { guarded { picker.launch(arrayOf("*/*")) } }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = state is ProjectState.Loaded) {
        if (viewModel.fullScreen) viewModel.fullScreen = false else guarded { activity?.finish() }
    }

    val g = LocalG.current
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    Scaffold(
        containerColor = g.bg,
        topBar = {
            if (!(viewModel.fullScreen && state is ProjectState.Loaded)) Box(Modifier.background(g.topBar).windowInsetsPadding(WindowInsets.statusBars)) {
                GTopBar(
                    when (state) {
                        is ProjectState.Loaded -> state.fileName
                        is ProjectState.Loading -> state.fileName
                        else -> null
                    },
                    compact = compact,
                ) {
                    val onBar = if (g.choice == ThemeChoice.SUN) Color.White else g.text
                    if (compact) {
                        // Phones: the main action plus a menu with the rest.
                        if (state is ProjectState.Loaded) GButton("Salvar", { save() }, primary = true) else GButton("Abrir", openFile)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            androidx.compose.material3.IconButton(onClick = { menu = true }) {
                                Text("⋮", color = onBar, fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                            androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                @Composable fun item(label: String, action: () -> Unit) =
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                                if (state is ProjectState.Loaded) item("Abrir", openFile)
                                if (viewModel.recent.isNotEmpty()) item("Recentes") { showRecent = true }
                                if (state is ProjectState.Loaded) item("Salvar como") { afterSave = null; saver.launch(projectName()) }
                                item("Aparência") { showAppearance = true }
                            }
                        }
                    } else {
                        if (viewModel.recent.isNotEmpty()) GButton("Recentes", { showRecent = true })
                        GButton("Abrir", openFile)
                        if (state is ProjectState.Loaded) {
                            GButton("Salvar como", { afterSave = null; saver.launch(projectName()) })
                            GButton("Salvar", { save() }, primary = true)
                        }
                        val gear = remember(g.iconStroke) { GIcon.SETTINGS.vector(g.iconStroke) }
                        androidx.compose.material3.IconButton(onClick = { showAppearance = true }) {
                            androidx.compose.material3.Icon(gear, "Configurações", tint = onBar)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
        if (showAppearance) AppearanceDialog(viewModel.themes.choice, { viewModel.themes.choose(it) },
            onAbout = { showAppearance = false; showAbout = true }, onDismiss = { showAppearance = false })
        if (showRecent) {
            AlertDialog(
                onDismissRequest = { showRecent = false },
                confirmButton = { GButton("Fechar", { showRecent = false }) },
                title = { Text("Arquivos recentes") },
                text = {
                    // Each file is a button that opens it, with its own Remover button.
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.recent.forEach { (name, uri) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RecentButton(name, Modifier.weight(1f)) { showRecent = false; guarded { viewModel.open(Uri.parse(uri)) } }
                                GButton("Remover", { viewModel.forgetRecent(uri) }, outlined = true)
                            }
                        }
                    }
                },
            )
        }
        confirm?.let { action ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Salvar alterações?") },
                text = { Text("O projeto tem alterações que ainda não foram salvas (camadas, pontos, medições, arquivos adicionados…).") },
                confirmButton = { GButton("Salvar", { confirm = null; save(action) }, primary = true) },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GButton("Descartar", { confirm = null; action() })
                        GButton("Cancelar", { confirm = null })
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
                is ProjectState.Failed -> Message(state.message, openFile, viewModel.relocating?.let { { locator.launch(arrayOf("*/*")) } })
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
private fun Message(text: String, openFile: () -> Unit, locate: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (locate != null) Button(onClick = locate) { Text("Localizar desenho") }
            if (locate != null) OutlinedButton(onClick = openFile) { Text("Abrir outro arquivo") }
            else Button(onClick = openFile) { Text("Abrir outro arquivo") }
        }
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
    var showCalc by remember(fileName) { mutableStateOf(false) }
    var showBasemap by remember { mutableStateOf(false) }
    // GNSS: dialog, following the position, the project's UTM zone and the position in project coordinates.
    var showGps by remember(fileName) { mutableStateOf(false) }
    // Field notes: placing mode, the note being edited (with whether it is new) and a tapped one.
    var addingNote by remember(fileName) { mutableStateOf(false) }
    var editingNote by remember(fileName) { mutableStateOf<Pair<com.appdwg.core.FieldNote, Boolean>?>(null) }
    var openNote by remember(fileName) { mutableStateOf<com.appdwg.core.FieldNote?>(null) }
    fun newNote(p: Point2, z: Double?) = com.appdwg.core.FieldNote(vm.nextNoteId(), p.x, p.y, z ?: Double.NaN, "", null,
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date()))
    var gpsFollow by remember(fileName) { mutableStateOf(true) }
    var gpsZone by remember(fileName) { mutableStateOf((com.appdwg.core.Utm.zoneOfEpsg(project.coordinateSystem?.epsgCode) ?: 23).toString() + "S") }
    val gnssFix = vm.gnss.fix
    // GNSS position in the local grid: UTM, the chosen geoid height and the site calibration when on.
    val calibration = remember(v.calibrationPairs, v.calibrationFixScale, v.calibrationOn) {
        if (v.calibrationOn) com.appdwg.core.Calibration.fit(v.calibrationPairs, v.calibrationFixScale) else null
    }
    val gpsZoneNumber = gpsZone.trimEnd('S', 's', 'N', 'n').toIntOrNull()
    val gpsLocal = gnssFix?.let { f -> gpsZoneNumber?.let { z -> vm.geo.local(f, z, calibration) } }
    val gpsPoint = gpsLocal?.let { Point2(it.x, it.y) }
    val gpsHeight = gpsLocal?.z?.takeIf { !it.isNaN() }
    var showCalibration by remember(fileName) { mutableStateOf(false) }
    val geoidPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.loadGeoidGrid(uri) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionAsker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) pendingPermission?.invoke()
        else vm.gnss.permissionDenied()
        pendingPermission = null
    }
    val replayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.gnss.replay(uri) }

    var showContourDialog by remember(fileName) { mutableStateOf(false) }
    var showPlatform by remember(fileName) { mutableStateOf(false) }
    var showRoad by remember(fileName) { mutableStateOf(false) }
    var showAsBuilt by remember(fileName) { mutableStateOf(false) }
    var showVolume by remember(fileName) { mutableStateOf(false) }
    var showAnalysis by remember(fileName) { mutableStateOf(false) }
    var showSurfaceEdit by remember(fileName) { mutableStateOf(false) }
    var showSheets by remember(fileName) { mutableStateOf(false) }
    var sheetParams by remember { mutableStateOf<SheetParams?>(null) }
    val sheetsSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val p = sheetParams
        if (uri != null && p != null) vm.exportSectionSheets(uri, p, fileName)
    }
    val surfaceEditState = remember(fileName) { SurfaceEditState() }
    var showBulletin by remember(fileName) { mutableStateOf(false) }
    val bulletinPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportBulletinPdf(uri, fileName)
    }
    val bulletinCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportBulletinCsv(uri, fileName)
    }
    val volumeState = remember(fileName) { VolumeState() }
    val asBuiltState = remember(fileName) { AsBuiltState() }
    val asBuiltCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportAsBuiltCsv(uri, fileName)
    }
    val asBuiltPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportAsBuiltPdf(uri, fileName)
    }
    var drawingRoad by v::drawingRoad
    // Region and side whose edge target line is being tapped.
    var roadTargetPick by remember(fileName) { mutableStateOf<Pair<Int, Int>?>(null) }
    val noteSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportServiceNote(uri)
    }
    val notePdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportServiceNotePdf(uri, fileName)
    }
    // Generate points: settings, dialog, what is being picked on the plan and the visible area.
    val gen = remember(fileName) { GenState() }
    // Line editor: on/off, its settings and the operation dialog.
    val edit = remember(fileName) { EditState() }
    // 3D models: dialog, the model whose insertion point is being tapped, the point, and a tapped element.
    var showModels by remember(fileName) { mutableStateOf(false) }
    var modelPickFor by remember(fileName) { mutableStateOf<Int?>(null) }
    var modelPoint by remember(fileName) { mutableStateOf<Point2?>(null) }
    // Two-point alignment: which plan point is being tapped (1 = A, 2 = B) and the ones tapped.
    var alignPick by remember(fileName) { mutableStateOf(0) }
    var alignA by remember(fileName) { mutableStateOf<Point2?>(null) }
    var alignB by remember(fileName) { mutableStateOf<Point2?>(null) }
    val alignInputs = remember(fileName) { androidx.compose.runtime.mutableStateMapOf<Int, List<String>>() }
    var modelHit by remember(fileName) { mutableStateOf<com.appdwg.core.ModelMesh?>(null) }
    var editing by remember(fileName) { mutableStateOf(false) }
    var showLineOp by remember(fileName) { mutableStateOf(false) }
    var showGen by remember(fileName) { mutableStateOf(false) }
    // The preview is drawn on the plan, so the dialog steps aside while it is checked.
    var genReview by remember(fileName) { mutableStateOf(false) }
    var genPick by remember(fileName) { mutableStateOf<GenPick?>(null) }
    var viewport by remember(fileName) { mutableStateOf<com.appdwg.core.Bounds?>(null) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addModel(uris, viewport?.center)
    }
    var drawingPlatform by v::drawingPlatform
    var platformOutline by v::platformOutline
    var showContours by v::showContours
    var contourInterval by v::contourInterval
    // Interval the plan drew (null: too dense at this zoom), for the status line.
    var contourDrawn by remember(fileName) { mutableStateOf<Double?>(null) }
    var includeContours by remember(fileName) { mutableStateOf(false) }
    var includeModels by remember(fileName) { mutableStateOf(true) }
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
            val line = vm.extraLines(sectionLineFor(vm, project)) + vm.platformLines() + vm.roadLines()
            when (kind) {
                ExportKind.DXF -> vm.exportDxf(uri, includeSurfaces, line, if (includeContours) contourDrawn ?: contourInterval.takeIf { it > 0 } ?: 1.0 else null, includeModels)
                ExportKind.LANDXML -> vm.exportLandXml(uri)
                ExportKind.POINTS -> vm.exportPoints(uri)
                ExportKind.KML -> vm.exportKml(uri, utmZone.trimEnd('S', 's', 'N', 'n').toInt(), !utmZone.endsWith("N", true), line)
                ExportKind.GEOJSON -> vm.exportGeoJson(uri, utmZone.trimEnd('S', 's', 'N', 'n').toInt(), !utmZone.endsWith("N", true), line)
                ExportKind.SHAPEFILE -> vm.exportShapefile(uri, utmZone.trimEnd('S', 's', 'N', 'n').toInt(), !utmZone.endsWith("N", true), line)
                ExportKind.VOLUMES -> vm.exportVolumes(uri)
                ExportKind.NOTES_KMZ -> vm.exportNotesKmz(uri, utmZone.trimEnd('S', 's', 'N', 'n').toInt(), !utmZone.endsWith("N", true))
                ExportKind.NOTES_CSV -> vm.exportNotesCsv(uri)
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
    val gpsInfo = remember(project, gpsPoint, frozenLayers) { gpsPoint?.let { PointQuery.at(project, it, frozenLayers.toSet()) } }
    LaunchedEffect(gpsPoint, gpsFollow) { if (gpsFollow && gpsPoint != null) { focus = gpsPoint; focusKey++ } }
    val sectionAxis = project.alignments.getOrNull(sectionAlignment)
    val section = remember(project, sectionAxis, sectionStation, sectionHalfWidth, frozenLayers, showSection) {
        if (!showSection || sectionAxis == null) null
        else CrossSection.at(sectionAxis, sectionStation, project.surfaces.filter { it.name !in frozenLayers }, sectionHalfWidth)
    }
    // On the road's own axis, the profile shows (and edits) the PVIs typed for the road.
    val roadPvis = v.roadParams.pvis.sortedBy { it.station }.takeIf { it.size >= 2 && sectionAxis?.name?.endsWith(ROAD_SUFFIX) == true }
    val longProfile = remember(project, sectionAxis, frozenLayers, showProfile, roadPvis, v.roadParams.kValue) {
        if (!showProfile || sectionAxis == null) null
        else {
            val axis = if (roadPvis == null) sectionAxis else com.appdwg.core.Alignment(sectionAxis.name, sectionAxis.startStation, sectionAxis.elements,
                com.appdwg.core.Profile("Greide", com.appdwg.core.AlignmentBuilder.curvesFromK(roadPvis, v.roadParams.kValue).mapIndexed { i, pv ->
                    roadPvis[i].takeIf { it.curveLength > 0 } ?: pv
                }))
            LongProfile.of(axis, project.surfaces.filter { it.name !in frozenLayers && !it.name.startsWith(ROAD_LABEL) })
        }
    }
    val profileMark = remember(sectionAxis, profileCursor, showProfile) {
        if (!showProfile || sectionAxis == null) null
        else CrossSection.at(sectionAxis, profileCursor, emptyList(), 8.0)?.let { it.pointAt(-8.0) to it.pointAt(8.0) }
    }
    val measurement = remember(project, measurePoints, frozenLayers) {
        if (measurePoints.isEmpty()) null
        else Measure.of(measurePoints, measurePoints.map { PointQuery.at(project, it, frozenLayers.toSet()).terrainElevation })
    }
    val g = LocalG.current
    val colors = PlanColors(
        background = g.planBg,
        mesh = if (g.dark) Color(0x66FFFFFF) else Color(0x66000000),
        alignment = g.axis,
        drawing = g.text,
        stations = g.text,
        points = g.points,
        marker = if (g.choice == ThemeChoice.SUN) Color(0xFFB23A0A) else Color(0xFFFF9800),
        contour = g.contour,
        contourMajor = g.contourMajor,
    )
    var planZoom by remember { mutableStateOf<ZoomRequest?>(null) }
    var planWidthPx by remember { mutableStateOf(0) }
    val xdpi = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.xdpi

    var show3d by v::show3d
    var exaggeration by v::exaggeration
    val has3d = remember(project) { project.surfaces.isNotEmpty() || project.polylines.any { it.zs != null } || project.models.isNotEmpty() }
    // Back leaves the tool in use (one at a time) before it leaves the project.
    androidx.activity.compose.BackHandler(enabled = addingNote || addingPoints || measuring || editing || show3d || showSection || showProfile) {
        when {
            addingNote -> addingNote = false
            addingPoints -> addingPoints = false
            measuring -> measuring = false
            editing -> editing = false
            show3d -> show3d = false
            showSection -> showSection = false
            else -> showProfile = false
        }
    }

    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 900
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    val asBuiltLabel = androidx.compose.ui.res.stringResource(R.string.asbuilt_tool)
    val calibLabel = androidx.compose.ui.res.stringResource(R.string.calib_tool)
    val volumeLabel = androidx.compose.ui.res.stringResource(R.string.vol_tool)
    val analysisLabel = androidx.compose.ui.res.stringResource(R.string.ta_tool)
    val surfaceEditLabel = androidx.compose.ui.res.stringResource(R.string.se_tool)
    val bulletinLabel = androidx.compose.ui.res.stringResource(R.string.bm_tool)
    fun railTools(): List<RailTool> = buildList {
        if (!show3d) add(RailTool(GIcon.MEASURE, "Medir", measuring) { measuring = !measuring; showSection = false; showProfile = false; tapped = null })
        add(RailTool(GIcon.POINTS, "Pontos", addingPoints) { showPoints = true })
        if (project.polylines.isNotEmpty() || vm.userLines.isNotEmpty()) add(RailTool(GIcon.LINES, "Linhas", editing) {
            editing = !editing; measuring = false; showSection = false; showProfile = false; show3d = false
            edit.pick = EditPick.SELECT
        })
        if (project.layers.isNotEmpty() || vm.userPoints.isNotEmpty()) add(RailTool(GIcon.LAYERS, "Camadas") { showLayers = true })
        if (project.surfaces.isNotEmpty()) {
            add(RailTool(GIcon.CONTOURS, "Curvas", showContours) { showContourDialog = true })
            add(RailTool(GIcon.IMAGE, "Imagem", vm.drape != null && vm.drapeVisible) { if (vm.drape == null) drapePicker.launch(arrayOf("*/*")) else showDrape = true })
        }
        add(RailTool(GIcon.COMPARE, "Comparar", differenceOverlay != null) { showCompare = true })
        if (project.surfaces.isNotEmpty()) {
            add(RailTool(GIcon.PLATFORM, "Plataforma", drawingPlatform || vm.platform != null) { showPlatform = true })
            add(RailTool(GIcon.ROAD, "Estrada", drawingRoad || v.roadBuilt) { showRoad = true })
        }
        if (vm.road != null || vm.stageProgress != null || v.bulletinItems.isNotEmpty()) add(RailTool(GIcon.BULLETIN, bulletinLabel) { showBulletin = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.EDIT_SURFACE, surfaceEditLabel) { showSurfaceEdit = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.SLOPE, analysisLabel, vm.analysisMode != 0) { showAnalysis = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.VOLUME, volumeLabel, vm.volume != null) { showVolume = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.ASBUILT, asBuiltLabel, vm.asBuilt != null) { showAsBuilt = true })
        add(RailTool(GIcon.MODELS, "Modelos") { showModels = true })
        add(RailTool(GIcon.NOTE, "Anotar", addingNote) { addingNote = true; measuring = false; show3d = false })
        add(RailTool(GIcon.GPS, "GPS", vm.gnss.connected) { showGps = true })
        add(RailTool(GIcon.CALIBRATE, calibLabel, v.calibrationOn) { showCalibration = true })
        add(RailTool(GIcon.CALC, "Cálculos") { showCalc = true })
        add(RailTool(GIcon.MAP, "Mapa", vm.basemap.enabled) { showBasemap = true })
        add(RailTool(GIcon.GOTO, "Ir para") { showGoTo = true })
        add(RailTool(GIcon.EXPORT, "Exportar") { showExport = true })
        if (vm.hasReferences) add(RailTool(GIcon.REFS, "Referências") { showReferences = true })
        if (project.warnings.isNotEmpty()) add(RailTool(GIcon.WARNING, "Avisos") { showWarnings = true })
    }
    val current = info
    @Composable
    fun BoxScope.NarrowCards() {
        if (selectedPoint != null) {
            val pt = selectedPoint!!
            val q = remember(project, pt, frozenLayers) { PointQuery.at(project, Point2(pt.x, pt.y), frozenLayers.toSet()) }
            PointCard(
                pt, q.alignment?.takeIf { !it.stationOffset.outside }, q.terrainElevation,
                onEdit = { editingPoint = pt to pt },
                onClose = { selectedPoint = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth(),
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
            modelHit?.let { m ->
                Card(Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 8.dp).widthIn(max = 380.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Line("Camada", m.layer)
                        m.props.forEach { (k, value) -> Line(k, value) }
                    }
                }
            }
            InfoCard(current, differenceAt(project, difference, current.position), markedElevations(project, vm.crossSelected, current.position), Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth())
        }
        if (gpsPoint != null && !show3d) {
            GpsCard(vm.gnss, gpsPoint.x, gpsPoint.y, gpsHeight, calibration != null, gpsInfo, Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 8.dp).widthIn(max = 420.dp))
        }
    }

    Column(Modifier.fillMaxSize().background(g.bg)) {
    Row(Modifier.weight(1f).fillMaxWidth().then(if (g.floating) Modifier.padding(top = 4.dp) else Modifier)) {
        val full = vm.fullScreen
        if (!full && !compact) ToolRail(railTools())
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (g.floating) Modifier.padding(start = 12.dp, end = if (wide) 12.dp else 12.dp, bottom = 12.dp).clip(RoundedCornerShape(20.dp)) else Modifier)
                .background(g.planBg)
                .onSizeChanged { planWidthPx = it.width },
        ) {
                if (show3d) key(project) {
                    View3D(
                        project = project,
                        hiddenLayers = hidden,
                        layerColor = layerColor,
                        background = g.planBg,
                        foreground = g.text,
                        exaggeration = exaggeration.toFloat(),
                        drape = drape,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else PlanView(
                    project = project,
                    // An edited surface stands in for its original on the plan, so its holes and changes show.
                    terrain = terrain.filter { t -> project.surfaces.none { it.name == t.name + EDITED_SUFFIX } },
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
                            addingNote -> { addingNote = false; editingNote = newNote(it, PointQuery.at(project, it).terrainElevation) to true }
                            drawingRoad -> v.roadParams = v.roadParams.copy(pis = (v.roadParams.pis + it).let { l -> if (l.size > 1 && l[l.size - 2] == it) l.dropLast(1) else l })
                            modelPickFor != null -> {
                                when (alignPick) { 1 -> alignA = it; 2 -> alignB = it; else -> modelPoint = it }
                                modelPickFor = null; alignPick = 0; showModels = true
                            }
                            editing && edit.pick == EditPick.WINDOW -> {
                                val c = edit.windowCorner
                                if (c == null) edit.windowCorner = it else {
                                    val minX = minOf(c.x, it.x); val maxX = maxOf(c.x, it.x); val minY = minOf(c.y, it.y); val maxY = maxOf(c.y, it.y)
                                    val hidden = offLayers.toSet() + frozenLayers
                                    val inside = project.polylines.indices.filter { i ->
                                        val l = project.polylines[i]
                                        l.layer !in hidden && l.xs.indices.any { k -> l.xs[k] in minX..maxX && l.ys[k] in minY..maxY }
                                    }
                                    edit.selected = (edit.selected + inside).distinct()
                                    edit.windowCorner = null; edit.pick = EditPick.SELECT
                                }
                            }
                            editing && edit.pick == EditPick.FROM_TO -> {
                                edit.picked = edit.picked + it
                                if (edit.picked.size >= 2) { edit.pick = EditPick.SELECT; showLineOp = true }
                            }
                            editing && edit.pick == EditPick.AXIS -> {
                                edit.picked = edit.picked + it
                                if (edit.picked.size >= 2) { edit.pick = EditPick.SELECT; showLineOp = true }
                            }
                            editing && edit.pick == EditPick.CENTER -> { edit.picked = listOf(it); edit.pick = EditPick.SELECT; showLineOp = true }
                            genPick == GenPick.AREA_DRAW -> if (it != gen.drawnArea.lastOrNull()) gen.drawnArea = gen.drawnArea + it
                            drawingPlatform -> if (it != platformOutline.lastOrNull()) platformOutline = platformOutline + it
                            addingPoints -> {
                                val z = PointQuery.at(project, it, frozenLayers.toSet()).terrainElevation ?: Double.NaN
                                editingPoint = null to CogoPoint(PointFile.nextNumber(vm.userPoints), "", it.x, it.y, z)
                            }
                            measuring -> if (it != measurePoints.lastOrNull()) measurePoints = measurePoints + it
                            // With the section open, a tap picks the station on the chosen alignment.
                            showSection && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> sectionStation = so.station }
                            showProfile && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> profileCursor = so.station }
                            else -> {
                                tapped = it; selectedPoint = null
                                modelHit = if (project.models.isEmpty()) null else com.appdwg.core.ModelPick.at(project.models, it, offLayers.toSet() + frozenLayers)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    measure = if (measuring) measurePoints else if (drawingRoad) v.roadParams.pis else if (drawingPlatform) platformOutline
                        else if (genPick == GenPick.AREA_DRAW || (showGen && gen.grid && gen.area == GridArea.DRAWN)) gen.drawnArea else emptyList(),
                    snap = measuring || drawingPlatform || drawingRoad || genPick == GenPick.AREA_DRAW || modelPickFor != null ||
                        (editing && edit.pick in setOf(EditPick.FROM_TO, EditPick.CENTER, EditPick.AXIS)),
                    selectLine = genPick == GenPick.LINE || genPick == GenPick.AREA_LINE || genPick == GenPick.ROTATION || roadTargetPick != null ||
                        (editing && edit.pick == EditPick.SELECT),
                    selectedLines = if (editing) edit.selected else emptyList(),
                    previewLines = if (editing) edit.preview else emptyList(),
                    gps = gpsPoint,
                    gpsAccuracy = gnssFix?.sigmaH ?: gnssFix?.hdop?.let { it * 3 },
                    gpsColor = fixColor(gnssFix?.quality),
                    notes = vm.notes,
                    onNoteTap = if (measuring || addingPoints || editing) null else { n -> openNote = n; tapped = null; selectedPoint = null },
                    basemap = vm.basemap.takeIf { it.enabled && gpsZone.trimEnd('S', 's', 'N', 'n').toIntOrNull() in 1..60 },
                    basemapZone = gpsZone.trimEnd('S', 's', 'N', 'n').toIntOrNull() ?: 23,
                    basemapSouth = !gpsZone.endsWith("N", true),
                    onLineSelected = { ref ->
                        roadTargetPick?.let { (i, side) ->
                            com.appdwg.core.PointGen.path(project, ref)?.let { pth ->
                                val line = List(pth.xs.size) { k -> Point2(pth.xs[k], pth.ys[k]) }
                                val rp = v.roadParams
                                v.roadParams = rp.copy(regions = rp.regions.toMutableList().also { l ->
                                    l[i] = if (side < 0) l[i].copy(leftTarget = line) else l[i].copy(rightTarget = line)
                                })
                            }
                            roadTargetPick = null
                            showRoad = true
                            return@PlanView
                        }
                        if (editing) {
                            if (ref is com.appdwg.core.LineRef.Drawing) {
                                edit.selected = if (ref.index in edit.selected) edit.selected - ref.index else edit.selected + ref.index
                                edit.preview = emptyList()
                            }
                            return@PlanView
                        }
                        when (genPick) {
                            GenPick.LINE -> gen.line = ref
                            GenPick.AREA_LINE -> gen.areaLine = ref
                            GenPick.ROTATION -> lineRotation(project, ref)?.let { gen.rotation = StationFormat.decimal(it, 3) }
                            else -> {}
                        }
                        genPick = null
                        showGen = true
                    },
                    selectedLine = if (genPick == null && !showGen && !genReview) null else if (gen.grid) gen.areaLine?.takeIf { gen.area == GridArea.LINE } else gen.line,
                    previewPoints = gen.preview.map { Point2(it.x, it.y) },
                    onViewport = { viewport = it },
                    designLines = vm.platform?.let { r -> listOf(r.edge, r.daylight) + r.benches }?.map { l -> l.map { Point2(it.x, it.y) } }.orEmpty(),
                    sectionLine = section?.let { it.pointAt(-it.offsets.last()) to it.pointAt(it.offsets.last()) } ?: profileMark,
                    overlay = vm.slopeImage.takeIf { vm.analysisMode == 1 || vm.analysisMode == 3 } ?: differenceOverlay,
                    channels = if (vm.analysisMode >= 2) vm.flowChannels else emptyList(),
                    arrows = if (vm.analysisMode >= 2) vm.flowArrows else emptyList(),
                    points = vm.userPoints,
                    onPointTap = if (measuring || addingPoints) null else { pt -> selectedPoint = pt; tapped = null },
                    selectedPoint = selectedPoint,
                    focus = focus,
                    focusKey = focusKey,
                    contourInterval = if (showContours) contourInterval else null,
                    onContours = { contourDrawn = it },
                    viewKey = fileName,
                    zoom = planZoom,
                    marks = vm.asBuilt?.let { r -> r.rows.map { Point2(it.point.x, it.point.y) to asBuiltColor(r.status(it)) } }.orEmpty(),
                )


            Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val views = buildList {
                        add("Planta" to (!show3d && !showSection && !showProfile))
                        if (has3d) add("3D" to show3d)
                        if (project.alignments.isNotEmpty()) { add("Seção" to (showSection && !show3d)); add("Perfil" to (showProfile && !show3d)) }
                    }
                    ViewSwitch(views, onPick = { i ->
                        when (views[i].first) {
                            "Planta" -> { show3d = false; showSection = false; showProfile = false }
                            "3D" -> { show3d = true; showSection = false; showProfile = false }
                            "Seção" -> {
                                tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { sectionStation = it.station } }
                                show3d = false; showSection = true; showProfile = false; measuring = false; tapped = null
                            }
                            "Perfil" -> {
                                tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { profileCursor = it.station } }
                                show3d = false; showProfile = true; showSection = false; measuring = false; tapped = null
                            }
                        }
                    })
                    // Vertical exaggeration helps read gentle terrain.
                    if (show3d) GButton("Z ×$exaggeration", { exaggeration = exaggeration % 3 + 1 })
                }
                if (showContours && !show3d) {
                    Card {
                        Text(
                            contourDrawn?.let { "Curvas de nível a cada ${StationFormat.decimal(it, if (it < 1) 2 else 0)} m (mestras a cada 5)" }
                                ?: "Curvas densas demais neste zoom: aproxime ou aumente o intervalo",
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (differenceOverlay != null && difference != null && !show3d && !wide) DifferenceLegend(difference, Modifier)
            }
            if (!show3d) {
                // On phones the view switch spans the top, so north and the buttons go below it.
                NorthArrow(Modifier.align(Alignment.TopEnd).padding(start = 16.dp, end = 16.dp, top = if (compact) 84.dp else 16.dp))
                ZoomButtons(
                    onIn = { planZoom = ZoomRequest((planZoom?.id ?: 0) + 1, 1.6) },
                    onOut = { planZoom = ZoomRequest((planZoom?.id ?: 0) + 1, 1 / 1.6) },
                    onFit = { planZoom = ZoomRequest((planZoom?.id ?: 0) + 1, 0.0) },
                    fullScreen = vm.fullScreen,
                    onFullScreen = { vm.fullScreen = !vm.fullScreen },
                    modifier = if (compact) Modifier.align(Alignment.TopEnd).padding(top = 150.dp, end = 12.dp) else Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    plusMinus = !compact,
                )
            }
                if (show3d) {
                    Text(
                        "1 dedo gira · 2 dedos movem · pinça aproxima",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        style = MaterialTheme.typography.labelMedium, color = g.text,
                    )
                } else if (editing) {
                    EditLinesBar(edit, vm, onOperation = { showLineOp = true }, onDone = { editing = false; edit.selected = emptyList(); edit.preview = emptyList() },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 900.dp))
                } else if (addingNote) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Toque na planta onde fica a anotação.", style = MaterialTheme.typography.bodyMedium)
                            if (gpsPoint != null) TextButton(onClick = { addingNote = false; editingNote = newNote(gpsPoint, gpsHeight) to true }) { Text("Na posição do GPS") }
                            TextButton(onClick = { addingNote = false }) { Text("Cancelar") }
                        }
                    }
                } else if (openNote != null && !wide) {
                    NoteCard(vm, openNote!!, onEdit = { editingNote = openNote!! to false; openNote = null }, onClose = { openNote = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 520.dp))
                } else if (roadTargetPick != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Toque na linha que o bordo da pista deve seguir.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { roadTargetPick = null; showRoad = true }) { Text("Cancelar") }
                        }
                    }
                } else if (drawingRoad) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Toque os PIs do eixo, do início ao fim (${v.roadParams.pis.size}).", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { v.roadParams = v.roadParams.copy(pis = v.roadParams.pis.dropLast(1)) }, enabled = v.roadParams.pis.isNotEmpty()) { Text("Desfazer") }
                            TextButton(onClick = { v.roadParams = v.roadParams.copy(pis = emptyList()) }, enabled = v.roadParams.pis.isNotEmpty()) { Text("Limpar") }
                            TextButton(onClick = { drawingRoad = false; showRoad = true }) { Text("Concluir") }
                        }
                    }
                } else if (modelPickFor != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (alignPick) { 1 -> "Toque onde fica o ponto A do modelo."; 2 -> "Toque onde fica o ponto B do modelo."
                                else -> "Toque o ponto de inserção do modelo." } + " O toque encaixa em pontos e vértices.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { modelPickFor = null; showModels = true }) { Text("Cancelar") }
                        }
                    }
                } else if (genReview) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${gen.preview.size} ponto(s) em ciano.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { genReview = false; showGen = true }) { Text("Ajustar") }
                            TextButton(onClick = { vm.addGenerated(gen.preview); gen.preview = emptyList(); genReview = false }) { Text("Criar") }
                            TextButton(onClick = { gen.preview = emptyList(); genReview = false }) { Text("Cancelar") }
                        }
                    }
                } else if (genPick != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (genPick) {
                                GenPick.AREA_DRAW -> "Toque os vértices do polígono da malha (${gen.drawnArea.size})."
                                GenPick.AREA_LINE -> "Toque numa polilinha fechada."
                                GenPick.ROTATION -> "Toque na linha que dá a direção da malha."
                                else -> "Toque na linha, polilinha 3D ou eixo."
                            }, style = MaterialTheme.typography.bodyMedium)
                            if (genPick == GenPick.AREA_DRAW) {
                                TextButton(onClick = { gen.drawnArea = gen.drawnArea.dropLast(1) }, enabled = gen.drawnArea.isNotEmpty()) { Text("Desfazer") }
                                TextButton(onClick = { gen.drawnArea = emptyList() }, enabled = gen.drawnArea.isNotEmpty()) { Text("Limpar") }
                            }
                            TextButton(onClick = { genPick = null; showGen = true }) { Text(if (genPick == GenPick.AREA_DRAW) "Concluir" else "Cancelar") }
                        }
                    }
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
                } else if (showProfile && longProfile != null) {
                    ProfilePanel(
                        profile = longProfile,
                        cursor = profileCursor,
                        onCursor = { profileCursor = it },
                        range = profileRange,
                        onRange = { profileRange = it },
                        onClose = { showProfile = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().fillMaxHeight(if (compact) 0.6f else 0.45f),
                        editPvis = roadPvis,
                        onPvis = { list -> v.roadParams = v.roadParams.copy(pvis = list) },
                        onRecalc = { vm.designRoad() },
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
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().fillMaxHeight(if (compact) 0.6f else 0.45f),
                    )
                } else if (!wide || full) {
                    NarrowCards()
                }

            BackgroundTasks(vm.tasks, Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 64.dp).widthIn(max = 360.dp))
        }
        if (wide && !full) SidePanel {
            Text(summary(project.copy(points = vm.userPoints)), style = MaterialTheme.typography.labelLarge, color = g.muted)
            if (project.warnings.isNotEmpty()) TextButton(onClick = { showWarnings = true }) { Text(plural(project.warnings.size, "aviso", "avisos") + " da leitura") }
            if (gpsPoint != null) GpsCard(vm.gnss, gpsPoint.x, gpsPoint.y, gpsHeight, calibration != null, gpsInfo, Modifier.fillMaxWidth())
            if (measuring) MeasureCard(measurement, onUndo = { measurePoints = measurePoints.dropLast(1) }, onClear = { measurePoints = emptyList() },
                onDone = { measuring = false }, modifier = Modifier.fillMaxWidth())
            selectedPoint?.let { pt ->
                val q = remember(project, pt, frozenLayers) { PointQuery.at(project, Point2(pt.x, pt.y), frozenLayers.toSet()) }
                PointCard(pt, q.alignment?.takeIf { !it.stationOffset.outside }, q.terrainElevation,
                    onEdit = { editingPoint = pt to pt }, onClose = { selectedPoint = null }, modifier = Modifier.fillMaxWidth())
            }
            openNote?.let { n ->
                NoteCard(vm, n, onEdit = { editingNote = n to false; openNote = null }, onClose = { openNote = null }, modifier = Modifier.fillMaxWidth())
            }
            modelHit?.takeIf { current != null }?.let { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Line("Camada", m.layer)
                        m.props.forEach { (k, value) -> Line(k, value) }
                    }
                }
            }
            if (current != null && !measuring && selectedPoint == null) {
                InfoCard(current, differenceAt(project, difference, current.position), markedElevations(project, vm.crossSelected, current.position), Modifier.fillMaxWidth())
            }
            if (differenceOverlay != null && difference != null && !show3d) DifferenceLegend(difference, Modifier)
            if ((vm.analysisMode == 1 || vm.analysisMode == 3) && !show3d) SlopeLegend(vm, Modifier)
            if (current == null && !measuring && selectedPoint == null && openNote == null && gpsPoint == null) {
                Text("Toque na planta para ver estaca, afastamento e cotas.", style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        }
    }
    // Footer: the tapped point, else the GPS position; station on the axis; plan scale; GPS fix.
    val shown = current ?: gpsInfo
    val metersPerInch = viewport?.let { vp -> if (planWidthPx > 0) vp.width / planWidthPx * xdpi else null }
    if (!vm.fullScreen && compact) ToolBar(railTools())
    if (!vm.fullScreen) StatusFooter(
        items = buildList {
            shown?.let { p ->
                add("E " + StationFormat.decimal(p.position.x, 3)); add("N " + StationFormat.decimal(p.position.y, 3))
                p.terrainElevation?.let { add("Z " + StationFormat.decimal(it, 2)) }
                p.alignment?.takeIf { !it.stationOffset.outside }?.let { a -> add(StationFormat.estaca(a.stationOffset.station) + " · " + StationFormat.offset(a.stationOffset.offset)) }
            }
            metersPerInch?.let { add("1:" + niceScale(it / 0.0254)) }
        },
        gpsText = gnssFix?.let { f -> f.quality.label + (f.sigmaH?.let { " · " + StationFormat.decimal(it, 3) + " m" } ?: "") },
        gpsColor = if (gnssFix?.quality == com.appdwg.core.FixQuality.RTK_FIXED) g.gps else fixColor(gnssFix?.quality),
    )
    }

    if (showBulletin) {
        BulletinDialog(vm, onPdf = { bulletinPdf.launch("$baseName-boletim-${v.bulletinNumber}.pdf") },
            onCsv = { bulletinCsv.launch("$baseName-boletim-${v.bulletinNumber}.csv") }, onDismiss = { showBulletin = false })
    }

    if (showSheets) {
        SheetsDialog(vm, project, fileName, onMake = { p ->
            sheetParams = p
            sheetsSaver.launch("$baseName-secoes-${(p.alignment ?: "eixo").replace(Regex("[^A-Za-z0-9_-]+"), "_")}.pdf")
        }, onDismiss = { showSheets = false })
    }

    if (showSurfaceEdit) {
        SurfaceEditDialog(vm, project, surfaceEditState, measurePoints, tapped, onDraw = {
            showSurfaceEdit = false; measuring = true; measurePoints = emptyList(); showSection = false; showProfile = false; show3d = false; tapped = null
        }, onDismiss = { showSurfaceEdit = false })
    }

    if (showAnalysis) AnalysisDialog(vm, project, onDismiss = { showAnalysis = false })

    if (showVolume) {
        VolumeDialog(vm, project, volumeState, measurePoints, onDraw = {
            showVolume = false; measuring = true; measurePoints = emptyList(); showSection = false; showProfile = false; show3d = false; tapped = null
        }, onDismiss = { showVolume = false })
    }

    if (showCalibration) {
        CalibrationDialog(vm, v, project, gpsZoneNumber, onLoadGrid = { geoidPicker.launch(arrayOf("*/*")) }, onDismiss = { showCalibration = false })
    }

    if (showAsBuilt) {
        AsBuiltDialog(vm, project, asBuiltState,
            onImport = { pointImporter.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            onCsv = { asBuiltCsv.launch("$baseName-as-built.csv") },
            onPdf = { asBuiltPdf.launch("$baseName-as-built.pdf") },
            onDismiss = { showAsBuilt = false })
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
            onGenerate = { showPoints = false; showGen = true },
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

    if (showModels) {
        ModelsDialog(vm, modelPoint, alignA to alignB, onAdd = { modelPicker.launch(arrayOf("*/*")) },
            onPickPoint = { i -> showModels = false; show3d = false; alignPick = 0; modelPickFor = i },
            onPickAlign = { i, which -> showModels = false; show3d = false; alignPick = which; modelPickFor = i },
            alignInputs = alignInputs,
            onDismiss = { showModels = false })
    }

    if (showLineOp) {
        LineOpDialog(edit, vm, project, onPick = { p -> showLineOp = false; edit.pick = p }, onDismiss = { showLineOp = false })
    }

    if (showGen) {
        GeneratePointsDialog(
            gen, project, vm.userPoints, viewport,
            onPick = { pick ->
                showGen = false; measuring = false; showSection = false; showProfile = false; show3d = false
                if (pick == GenPick.AREA_DRAW) gen.drawnArea = emptyList()
                genPick = pick
            },
            onPreview = { showGen = false; measuring = false; showSection = false; showProfile = false; show3d = false; genReview = true },
            onConfirm = { pts -> vm.addGenerated(pts); showGen = false },
            onDismiss = { showGen = false },
        )
    }

    if (showRoad) {
        RoadDialog(vm, project, onDrawAxis = {
            showRoad = false; measuring = false; showSection = false; showProfile = false; show3d = false
            v.roadParams = v.roadParams.copy(alignmentIndex = -1)
            drawingRoad = true
        }, onServiceNote = { noteSaver.launch("$baseName-nota-de-servico.csv") },
            onServiceNotePdf = { notePdfSaver.launch("$baseName-nota-de-servico.pdf") },
            onBulletin = { showRoad = false; showBulletin = true },
            onPickTarget = { i, side -> showRoad = false; show3d = false; roadTargetPick = i to side },
            onDismiss = { showRoad = false })
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

    editingNote?.let { (n, isNew) -> key(n.id) { NoteDialog(vm, n, isNew, onDismiss = { editingNote = null }) } }

    if (showGps) {
        GpsDialog(vm.gnss, gpsZone, { gpsZone = it.trim().uppercase() }, gpsFollow, { gpsFollow = it },
            onNeedPermissions = { src, then ->
                // Only what the chosen source needs: the antenna sends its own position over Bluetooth.
                val perms = when {
                    src == GnssSource.BLUETOOTH && android.os.Build.VERSION.SDK_INT >= 31 -> arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
                    src == GnssSource.BLUETOOTH -> emptyArray()
                    else -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                val missing = perms.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) then() else { pendingPermission = then; permissionAsker.launch(missing.toTypedArray()) }
            },
            onPickReplay = { replayPicker.launch(arrayOf("*/*")) },
            onDismiss = { showGps = false })
    }

    if (showCalc) CalcDialog(vm, project, onDismiss = { showCalc = false })
    if (showBasemap) BasemapDialog(vm.basemap, gpsZone, { gpsZone = it.trim().uppercase() }, project.bounds, viewport, onDismiss = { showBasemap = false })

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
            includeModels = includeModels,
            onIncludeModels = { includeModels = it },
            zone = utmZone,
            onZone = { utmZone = it.trim().uppercase() },
            onExport = { kind ->
                showExport = false
                if (kind == ExportKind.PNG || kind == ExportKind.PDF) captureFor = kind
                else { pendingExport = kind; exportSaver.launch(baseName + kind.suffix) }
            },
            onSheets = if (project.alignments.isNotEmpty()) ({ showExport = false; showSheets = true }) else null,
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
                if (d != null) OutlinedButton(onClick = { vm.removeDrape(); onDismiss() }) { Text("Remover") }
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

/** A round map scale near [ratio] (1:500, 1:1000, 1:2000, 1:2500, 1:5000…), with thousands grouped. */
private fun niceScale(ratio: Double): String {
    if (!ratio.isFinite() || ratio <= 0) return "?"
    val e = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(ratio)))
    val n = listOf(1.0, 2.0, 2.5, 5.0, 10.0).minByOrNull { kotlin.math.abs(it * e - ratio) }!! * e
    return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,d", Math.round(n))
}
