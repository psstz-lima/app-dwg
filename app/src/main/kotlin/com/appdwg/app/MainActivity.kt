package com.appdwg.app

import com.appdwg.core.AreaOnSurface
import com.appdwg.core.TinMeasure
import com.appdwg.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.text.style.TextOverflow
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

class MainActivity : AppCompatActivity() {

    private val viewModel: ProjectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge before the first frame. Icon contrast is updated again once the theme is known.
        window.enableGromaticEdgeToEdge(lightStatus = false, lightNavigation = false)
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            // Number format from the device's language, units from Aparência; the screens are rebuilt when either changes.
            val locale = viewModel.languages.locale(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
            val system = remember(locale, viewModel.units.choice) { viewModel.units.apply(locale) }
            GromaticTheme(viewModel.themes.choice) { androidx.compose.runtime.key(locale, system) {
                val g = LocalG.current
                val full = viewModel.fullScreen && viewModel.state is ProjectState.Loaded
                // Dark icons on a light bar. SUN's top bar is black even though the plan is light.
                val lightStatus = g.onTopBar.luminance() < 0.5f
                val lightNav = g.bg.luminance() > 0.5f
                LaunchedEffect(full, lightStatus, lightNav) {
                    window.enableGromaticEdgeToEdge(lightStatus, lightNav)
                    val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    if (full) {
                        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    } else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                AppScreen(viewModel)
            } }
        }
    }

    override fun onResume() {
        super.onResume()
        // The language may have been changed in Android's per-app settings meanwhile.
        viewModel.languages.refresh()
        // A purchase finished in the Play sheet, or a license-tester subscription, is visible on return.
        viewModel.pro.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        // Drive's Open with delivers a .gmt as a content URI of type application/octet-stream.
        val claimedGmt = intent.action == Intent.ACTION_VIEW &&
            uri.scheme == "content" &&
            intent.resolveType(this) == "application/octet-stream"
        viewModel.open(uri, claimedGmt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(viewModel: ProjectViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // When set, the next picker starts in Google Drive.
    val inDrive = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun start(): android.net.Uri? = if (inDrive.getAndSet(false)) CloudDocs.driveStart(context) else null
    val hasDrive = remember { CloudDocs.driveInstalled(context) }
    val picker = rememberLauncherForActivityResult(OpenDocumentAt(::start)) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val state = viewModel.state
    val locator = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.relocate(uri) }
    // "Open" and back ask first when the project has unsaved changes.
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    var askCloud by remember { mutableStateOf(false) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saver = rememberLauncherForActivityResult(CreateDocumentAt("application/octet-stream", ::start)) { uri ->
        if (uri != null) viewModel.saveProject(uri) { afterSave?.invoke(); afterSave = null } else afterSave = null
    }
    fun projectName() = ((state as? ProjectState.Loaded)?.fileName ?: "projeto").substringBefore(".xml.aecc.pnt").substringBeforeLast('.') + ".gmt"
    fun save(then: () -> Unit = {}) {
        if (!ProAccess.allows(ProFeature.SAVE, viewModel.pro.isPro)) { viewModel.proRequested = true; return }
        val target = viewModel.projectUri
        if (target != null) viewModel.saveProject(target, then) else { afterSave = then; saver.launch(projectName()) }
    }
    fun guarded(action: () -> Unit) {
        if (state is ProjectState.Loaded && viewModel.isDirty()) confirm = action else action()
    }
    val openFile = { guarded { picker.launch(arrayOf("*/*")) } }
    val openFromDrive = { guarded { inDrive.set(true); picker.launch(arrayOf("*/*")) } }
    fun saveAs(drive: Boolean) { if (!ProAccess.allows(ProFeature.SAVE_AS, viewModel.pro.isPro)) { viewModel.proRequested = true; return }; afterSave = null; inDrive.set(drive); saver.launch(projectName()) }
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.activity.compose.BackHandler(enabled = state is ProjectState.Loaded) {
        if (viewModel.fullScreen) viewModel.fullScreen = false else guarded { activity?.finish() }
    }

    val g = LocalG.current
    val compact = windowWidthDp() < 600.dp
    Scaffold(
        containerColor = g.bg,
        // safeDrawing covers status bar, navigation bar, display cutout and the keyboard,
        // on phones and on tablets (where the bars can sit on the side).
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // The bar's colour runs under the status bar and side cutout; the buttons stay clear.
            // Padding is not consumed, so the content below still receives the horizontal insets.
            if (!(viewModel.fullScreen && state is ProjectState.Loaded)) Box(
                Modifier.background(g.topBar).padding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues(),
                ),
            ) {
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
                        // Phones: icon actions. The labels live in the content description and in the overflow menu.
                        if (state is ProjectState.Loaded) BarIcon(GIcon.SAVE, tr("Salvar"), onBar) { save() } else BarIcon(GIcon.OPEN, tr("Abrir"), onBar, openFile)
                        BarIcon(GIcon.SETTINGS, tr("Configurações"), onBar) { showAppearance = true }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            BarIcon(GIcon.MORE, tr("Mais opções"), onBar) { menu = true }
                            androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                @Composable fun item(label: String, action: () -> Unit) =
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                                if (state is ProjectState.Loaded) item(tr("Abrir"), openFile)
                                if (hasDrive) item(tr("Abrir do Google Drive"), openFromDrive)
                                if (state is ProjectState.Loaded && hasDrive) item(tr("Salvar no Google Drive")) { saveAs(true) }
                                if (viewModel.recent.isNotEmpty()) item(tr("Recentes")) { showRecent = true }
                                if (state is ProjectState.Loaded) item(tr("Salvar como")) { saveAs(false) }
                                item(tr("Configurações")) { showAppearance = true }
                            }
                        }
                    } else {
                        if (viewModel.recent.isNotEmpty()) GButton(tr("Recentes"), { showRecent = true })
                        // Abrir and Salvar como offer the device or Google Drive.
                        DropButton(tr("Abrir"), hasDrive, openFile, openFromDrive)
                        if (state is ProjectState.Loaded) {
                            DropButton(tr("Salvar como"), hasDrive, { saveAs(false) }, { saveAs(true) })
                            GButton(tr("Salvar"), { save() }, primary = true)
                        }
                        GButton(tr("Configurações"), { showAppearance = true }, icon = GIcon.SETTINGS, outlined = true)
                    }
                    if (state is ProjectState.Loaded) {
                        BarIcon(GIcon.CLOSE, tr("Fechar projeto"), onBar) {
                            guarded {
                                if (DownloadNames.shouldAsk(viewModel.unexportedCloudCount())) askCloud = true else viewModel.closeProject()
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
        if (showNewProject) PlacePicker(viewModel.basemap, onPick = { lat, lon, hw, hh -> showNewProject = false; viewModel.newProject(lat, lon, hw, hh) }, onDismiss = { showNewProject = false })
        if (showAppearance) {
            val langCtx = androidx.compose.ui.platform.LocalContext.current
            val langReady = remember { LanguageChoice.entries.associateWith { LanguagePacks.ready(langCtx, it) } }
            AppearanceDialog(viewModel.themes.choice, { viewModel.themes.choose(it) },
            onAbout = { showAppearance = false; showAbout = true }, onDismiss = { showAppearance = false },
            units = viewModel.units.choice, onUnits = { viewModel.units.choose(it) },
            language = viewModel.languages.choice, onLanguage = { viewModel.languages.choose(it) },
            languageReady = langReady,
            crsName = if (state is ProjectState.Loaded) viewModel.crs().name else null,
            onCrs = if (state is ProjectState.Loaded) ({ showAppearance = false; if (ProAccess.allows(ProFeature.CRS, viewModel.pro.isPro)) viewModel.crsDialogRequested = true else viewModel.proRequested = true }) else null,
            pro = viewModel.pro.isPro, onPro = { showAppearance = false; viewModel.proRequested = true })
        }
        if (askCloud) {
            AlertDialog(
                modifier = Modifier.safeBars(),
                onDismissRequest = { askCloud = false },
                title = { Text(tr("Salvar arquivos baixados?")) },
                text = { Text(tr("Este projeto tem arquivos baixados da nuvem (Google Drive ou outro). Salvar cópias na pasta Downloads do aparelho? O projeto em si continua no formato .gmt.")) },
                confirmButton = {
                    GButton(tr("Salvar"), {
                        askCloud = false
                        val ok = viewModel.exportCloudCopies()
                        if (ok) {
                            viewModel.closeProject()
                            viewModel.report(tr("Arquivos salvos na pasta Downloads."))
                        } else viewModel.report(tr("Não foi possível salvar os arquivos."))
                    }, primary = true)
                },
                dismissButton = { GButton(tr("Não salvar"), { askCloud = false; viewModel.closeProject() }) },
            )
        }
        if (viewModel.proRequested) ProDialog(viewModel.pro, onDismiss = { viewModel.proRequested = false })
        if (showRecent) {
            AlertDialog(
                modifier = Modifier.safeBars(),
                onDismissRequest = { showRecent = false },
                confirmButton = { GButton(tr("Fechar"), { showRecent = false }) },
                title = { Text(tr("Arquivos recentes")) },
                text = {
                    // Each file is a button that opens it, with its own Remover button.
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.recent.forEach { (name, uri) ->
                            FitRow {
                                RecentButton(name, Modifier.weight(1f), cloud = CloudDocs.isDrive(uri)) { showRecent = false; guarded { viewModel.open(uri.toUri()) } }
                                GButton(tr("Remover"), { viewModel.forgetRecent(uri) }, outlined = true)
                            }
                        }
                    }
                },
            )
        }
        confirm?.let { action ->
            AlertDialog(
                modifier = Modifier.safeBars(),
                onDismissRequest = { confirm = null },
                title = { Text(tr("Salvar alterações?")) },
                text = { Text(tr("O projeto tem alterações que ainda não foram salvas (camadas, pontos, medições, arquivos adicionados…).")) },
                confirmButton = { GButton(tr("Salvar"), { confirm = null; save(action) }, primary = true) },
                dismissButton = {
                    FitRow {
                        GButton(tr("Descartar"), { confirm = null; action() })
                        GButton(tr("Cancelar"), { confirm = null })
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
                ProjectState.Empty -> EmptyState(openFile, if (hasDrive) openFromDrive else null,
                    onNewProject = { if (ProAccess.allows(ProFeature.NEW_PROJECT, viewModel.pro.isPro)) showNewProject = true else viewModel.proRequested = true })
                is ProjectState.Loading -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    LoadProgress(state.task)
                }
                is ProjectState.Failed -> Message(state.message, openFile, viewModel.relocating?.let { { locator.launch(arrayOf("*/*")) } })
                is ProjectState.Loaded -> ProjectScreen(state.fileName, state.project, state.terrain, viewModel)
            }
        }
    }
}

/** Colour ramp of a surface's elevations, from its lowest to its highest point. */
@Composable
private fun ElevationLegend(sf: com.appdwg.core.Surface) {
    val min = sf.minElevation ?: return
    val max = sf.maxElevation ?: return
    Card {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(tr("Cotas · ") + sf.name.substringAfterLast('|'), style = MaterialTheme.typography.labelMedium)
            FitRow(spacedBy = 6.dp) {
                Text(StationFormat.lengthValue(min, 1), style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.Canvas(Modifier.width(160.dp).height(12.dp)) {
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(TerrainImage.RAMP.map { Color(0xFF000000.toInt() or it) }))
                }
                Text(StationFormat.lengthValue(max, 1) + " " + StationFormat.lengthUnit, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Free version: the plan is for viewing; a tap here opens the Gromatic Pro screen. */
@Composable
private fun FreeHint(modifier: Modifier, onPro: () -> Unit) {
    Card(modifier.widthIn(max = 480.dp).clickable(onClick = onPro)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Versão grátis: visualização da planta"), style = MaterialTheme.typography.titleSmall)
            Text(tr("Estaca, cotas, 3D, seções, medições e as demais ferramentas estão no Gromatic Pro. Toque para conhecer."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(openFile: () -> Unit, openDrive: (() -> Unit)?, onNewProject: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("Abra um arquivo LandXML ou DXF"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            tr("No Civil 3D, use Saída > Exportar para LandXML (.xml) ou salve como DXF (.dxf) e copie o arquivo para o aparelho."),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FitRow(spacedBy = 12.dp) {
            GButton(tr("Abrir arquivo"), openFile, primary = true)
            openDrive?.let { GButton(tr("☁ Abrir do Google Drive"), it, outlined = true) }
        }
        Spacer(Modifier.height(24.dp))
        Text(tr("Sem arquivo? Comece pelo mapa e baixe terreno e OpenStreetMap da área."), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onNewProject) { Text(tr("Novo projeto pelo mapa")) }
    }
}

/**
 * Coordinates typed in for the place picker: a latitude and longitude, filled with the device's
 * last known position when the app may read it.
 */
@Composable
fun CoordinatesDialog(onCreate: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val last = remember {
        runCatching {
            val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) null else {
                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                @Suppress("MissingPermission")
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }
        }.getOrNull()
    }
    var text by remember { mutableStateOf(last?.let { String.format(java.util.Locale.ROOT, "%.6f, %.6f", it.latitude, it.longitude) } ?: "") }
    // "lat, long" with decimal points; with decimal commas, a space or ";" between them.
    val separators = if ('.' in text) Regex("[,;\\s]+") else Regex("[;\\s]+")
    val parts = text.trim().split(separators).filter { it.isNotEmpty() }.map { it.replace(',', '.').toDoubleOrNull() }
    val parsed = parts.takeIf { it.size == 2 && it[0] != null && it[1] != null && it[0]!! in -84.0..84.0 && it[1]!! in -180.0..180.0 }?.let { it[0]!! to it[1]!! }
    AlertDialog(
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        title = { Text(tr("Digitar coordenadas")) },
        confirmButton = { OutlinedButton(enabled = parsed != null, onClick = { parsed?.let { (a, b) -> onCreate(a, b) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Ir para o ponto")) } },
        dismissButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text(tr("Latitude, longitude do centro")) },
                    placeholder = { Text("-19.920, -43.940") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(if (last != null) tr("Preenchido com a última posição do aparelho.") else tr("Digite em graus decimais, como aparece no Google Maps."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("O sistema de coordenadas será o UTM do local (dá para trocar depois) e o mapa de fundo fica ligado."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
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
        FitRow(spacedBy = 12.dp) {
            if (locate != null) GButton(tr("Localizar desenho"), locate, primary = true)
            GButton(tr("Abrir outro arquivo"), openFile, outlined = locate != null, primary = locate == null)
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
    // In 3D the picture is cut to the surfaces, at the detail chosen.
    var drape3d by remember { mutableStateOf<Drape?>(null) }
    var drapeLoading by remember { mutableStateOf(false) }
    LaunchedEffect(drape, v.drapeQuality, v.show3d) {
        if (!v.show3d || drape == null) { drape3d = null; return@LaunchedEffect }
        drapeLoading = true
        try { drape3d = vm.drapeFor3d(drape, v.drapeQuality) } finally { drapeLoading = false }
    }
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val langReady = remember { LanguageChoice.entries.associateWith { LanguagePacks.ready(ctx0, it) } }
    // Files laid over this one and the two surfaces compared.
    var showCompare by remember(fileName) { mutableStateOf(false) }
    var showCross by remember(fileName) { mutableStateOf(false) }
    var compareBase by v::compareBase
    var compareOther by v::compareOther
    // Files added to the project (a mosaic of LandXML/DXF), from the device or Google Drive.
    val addDrive = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val filePicker = rememberLauncherForActivityResult(OpenMultipleDocumentsAt { if (addDrive.getAndSet(false)) CloudDocs.driveStart(ctx0) else null }) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    // A measurement of the works: its LandXML/DXF and its picture, chosen together.
    val statePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addState(uris)
    }
    // Company logo for the title block of the section sheets.
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.setSheetLogo(uri) }
    val hasDriveApp = remember { CloudDocs.driveInstalled(ctx0) }
    fun addFiles(drive: Boolean) { addDrive.set(drive); filePicker.launch(arrayOf("*/*")) }
    // COGO points: dialog, tapped point, point being edited or created, and the "create" mode.
    var showPoints by remember(fileName) { mutableStateOf(false) }
    var selectedPoint by remember(fileName) { mutableStateOf<CogoPoint?>(null) }
    var editingPoint by remember(fileName) { mutableStateOf<Pair<CogoPoint?, CogoPoint>?>(null) }
    var addingPoints by remember(fileName) { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Exports and point imports can start in Google Drive (chosen in their dialogs).
    val driveNext = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun driveStart(): android.net.Uri? = if (driveNext.getAndSet(false)) CloudDocs.driveStart(ctx) else null
    val hasDrive = remember { CloudDocs.driveInstalled(ctx) }
    var exportToDrive by remember { mutableStateOf(false) }
    val pointImporter = rememberLauncherForActivityResult(OpenDocumentAt(::driveStart)) { uri ->
        if (uri != null) vm.importPoints(uri)
    }
    val pointExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportPoints(uri)
    }
    // Export: dialog, chosen kind waiting for the save picker, and the screen picture for PNG/PDF.
    var showExport by remember(fileName) { mutableStateOf(false) }
    var showGoTo by remember(fileName) { mutableStateOf(false) }
    var showArea by remember(fileName) { mutableStateOf(false) }
    LaunchedEffect(vm.areaRequested) { if (vm.areaRequested) { vm.areaRequested = false; showArea = true } }
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
    // Coordinate system of the project: GPS positions, background map and GIS files go through it.
    val crs = vm.crs()
    var showCrs by remember(fileName) { mutableStateOf(false) }
    LaunchedEffect(vm.crsDialogRequested) { if (vm.crsDialogRequested) { vm.crsDialogRequested = false; showCrs = true } }
    val gnssFix = vm.gnss.fix
    // GNSS position in the local grid: UTM, the chosen geoid height and the site calibration when on.
    val calibration = remember(v.calibrationPairs, v.calibrationFixScale, v.calibrationOn) {
        if (v.calibrationOn) com.appdwg.core.Calibration.fit(v.calibrationPairs, v.calibrationFixScale) else null
    }
    val gpsLocal = gnssFix?.let { f -> vm.geo.local(f, crs, calibration) }
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
    var showBasin by remember(fileName) { mutableStateOf(false) }
    var showAdvice by remember(fileName) { mutableStateOf(false) }
    var adviceCard by remember(fileName) { mutableStateOf(true) }
    var roadOnGrade by remember(fileName) { mutableStateOf(false) }
    // What the opened files hold, read once per project: the design tools that fit it.
    val advice = remember(project) {
        com.appdwg.core.ProjectAdvisor.advise(project, project.surfaces.filter { !it.name.startsWith(PLATFORM_LABEL) && !it.name.startsWith(ROAD_LABEL) })
    }
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
    // PI picked on the axis being edited, for its radius and transition.
    var selectedPi by remember(fileName) { mutableStateOf<Int?>(null) }
    var showElements by remember(fileName) { mutableStateOf(false) }
    var showAxisDisplay by remember(fileName) { mutableStateOf(false) }
    LaunchedEffect(drawingRoad) { if (!drawingRoad) selectedPi = null }
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
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    var screenShot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureFor by remember { mutableStateOf<ExportKind?>(null) }
    val exportSaver = rememberLauncherForActivityResult(CreateDocumentAt("application/octet-stream", ::driveStart)) { uri ->
        val kind = pendingExport
        pendingExport = null
        if (uri != null && kind != null) {
            val line = vm.extraLines(sectionLineFor(vm, project)) + vm.platformLines() + vm.roadLines() + vm.basinLines()
            when (kind) {
                ExportKind.DXF -> vm.exportDxf(uri, includeSurfaces, line, if (includeContours) contourDrawn ?: contourInterval.takeIf { it > 0 } ?: 1.0 else null, includeModels)
                ExportKind.LANDXML -> vm.exportLandXml(uri)
                ExportKind.POINTS -> vm.exportPoints(uri)
                ExportKind.KML -> vm.exportKml(uri, line)
                ExportKind.GEOJSON -> vm.exportGeoJson(uri, line)
                ExportKind.SHAPEFILE -> vm.exportShapefile(uri, line)
                ExportKind.VOLUMES -> vm.exportVolumes(uri)
                ExportKind.NOTES_KMZ -> vm.exportNotesKmz(uri)
                ExportKind.NOTES_CSV -> vm.exportNotesCsv(uri)
                ExportKind.PNG, ExportKind.PDF -> screenShot?.let { vm.exportImage(uri, it, kind == ExportKind.PDF) }
            }
            showExport = true
        }
    }
    val window = androidx.activity.compose.LocalActivity.current?.window
    val baseName = fileName.substringBefore(".xml.aecc.pnt").substringBeforeLast('.')
    LaunchedEffect(captureFor) {
        val kind = captureFor ?: return@LaunchedEffect
        // Waits for the dialog to leave the screen, then copies the window (3D included).
        kotlinx.coroutines.delay(400)
        screenShot = window?.let { captureWindow(it) }
        captureFor = null
        pendingExport = kind
        driveNext.set(exportToDrive); exportSaver.launch(baseName + kind.suffix)
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
                com.appdwg.core.Profile(tr("Greide"), com.appdwg.core.AlignmentBuilder.curvesFromK(roadPvis, v.roadParams.kValue).mapIndexed { i, pv ->
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
    // The polygon being drawn: measure, a platform outline, or a grid area. The same points in plan and 3D.
    val areaPoly = when {
        measuring -> measurePoints
        drawingPlatform -> platformOutline
        genPick == GenPick.AREA_DRAW -> gen.drawnArea
        else -> emptyList()
    }
    val areaClosed = measuring || drawingPlatform || genPick == GenPick.AREA_DRAW
    var groundLength by remember { mutableStateOf<Double?>(null) }
    var areaReport by remember { mutableStateOf<AreaOnSurface?>(null) }
    var drapeLines by remember { mutableStateOf(DoubleArray(0)) }
    var drapeTris by remember { mutableStateOf(DoubleArray(0)) }
    var surfacePending by remember { mutableStateOf(false) }
    LaunchedEffect(areaPoly, areaClosed, project, offLayers, frozenLayers) {
        if (areaPoly.size < 2) {
            groundLength = null
            areaReport = null
            drapeLines = DoubleArray(0)
            drapeTris = DoubleArray(0)
            surfacePending = false
            return@LaunchedEffect
        }
        surfacePending = true
        val poly = areaPoly
        val closed = areaClosed
        val surfaces = project.surfaces.filter { s ->
            s.name !in offLayers && s.name !in frozenLayers && s.triangleCount > 0 &&
                project.surfaces.none { it.name == s.name + EDITED_SUFFIX }
        }
        val built = withContext(Dispatchers.Default) { surfaceMeasure(surfaces, poly, closed) }
        groundLength = built.ground
        areaReport = built.area
        drapeLines = built.lines
        drapeTris = built.tris
        surfacePending = false
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
    val xdpi = androidx.compose.ui.platform.LocalResources.current.displayMetrics.xdpi

    var show3d by v::show3d
    var showAr by remember(fileName) { mutableStateOf(false) }
    var exaggeration by v::exaggeration
    val has3d = remember(project) { project.surfaces.isNotEmpty() || project.polylines.any { it.zs != null } || project.models.isNotEmpty() }
    // Back leaves the tool in use (one at a time) before it leaves the project.
    androidx.activity.compose.BackHandler(enabled = addingNote || addingPoints || measuring || editing || showAr || show3d || showSection || showProfile) {
        when {
            addingNote -> addingNote = false
            addingPoints -> addingPoints = false
            measuring -> measuring = false
            editing -> editing = false
            showAr -> showAr = false
            show3d -> show3d = false
            showSection -> showSection = false
            else -> showProfile = false
        }
    }

    val windowWidth = windowWidthDp()
    val wide = windowWidth >= 900.dp
    val compact = windowWidth < 600.dp
    val asBuiltLabel = androidx.compose.ui.res.stringResource(R.string.asbuilt_tool)
    val calibLabel = androidx.compose.ui.res.stringResource(R.string.calib_tool)
    val volumeLabel = androidx.compose.ui.res.stringResource(R.string.vol_tool)
    val analysisLabel = androidx.compose.ui.res.stringResource(R.string.ta_tool)
    val surfaceEditLabel = androidx.compose.ui.res.stringResource(R.string.se_tool)
    val bulletinLabel = androidx.compose.ui.res.stringResource(R.string.bm_tool)
    val isPro = vm.pro.isPro
    fun proOpen(feature: ProFeature) = ProAccess.allows(feature, isPro)
    fun allTools(): List<RailTool> = buildList {
        add(RailTool(GIcon.MEASURE, tr("Medir"), measuring, feature = ProFeature.MEASURE) { measuring = !measuring; showSection = false; showProfile = false; tapped = null })
        add(RailTool(GIcon.POINTS, tr("Pontos"), addingPoints, feature = ProFeature.POINTS) { showPoints = true })
        add(RailTool(GIcon.LINES, tr("Linhas"), editing, feature = ProFeature.LINES) {
            editing = !editing; measuring = false; showSection = false; showProfile = false; show3d = false
            // Without lines to pick, the editor starts drawing a new one.
            edit.pick = if (project.polylines.isEmpty()) EditPick.DRAW else EditPick.SELECT; edit.picked = emptyList()
        })
        if (project.layers.isNotEmpty() || vm.userPoints.isNotEmpty()) add(RailTool(GIcon.LAYERS, tr("Camadas"), feature = ProFeature.LAYERS) { showLayers = true })
        if (project.surfaces.isNotEmpty()) {
            add(RailTool(GIcon.CONTOURS, tr("Curvas"), showContours, feature = ProFeature.CONTOURS) { showContourDialog = true })
            add(RailTool(GIcon.IMAGE, tr("Imagem"), vm.drape != null && vm.drapeVisible, feature = ProFeature.IMAGE) { if (vm.drape == null) drapePicker.launch(arrayOf("*/*")) else showDrape = true })
        }
        add(RailTool(GIcon.COMPARE, tr("Comparar"), differenceOverlay != null, feature = ProFeature.COMPARE) { showCompare = true })
        if (project.surfaces.isNotEmpty()) {
            add(RailTool(GIcon.PLATFORM, tr("Plataforma"), drawingPlatform || vm.platform != null, feature = ProFeature.PLATFORM) { showPlatform = true })
            add(RailTool(GIcon.ROAD, tr("Estrada"), drawingRoad || v.roadBuilt, feature = ProFeature.ROAD) { showRoad = true })
        }
        if (vm.road != null || vm.stageProgress != null || v.bulletinItems.isNotEmpty()) add(RailTool(GIcon.BULLETIN, bulletinLabel, feature = ProFeature.BULLETIN) { showBulletin = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.EDIT_SURFACE, surfaceEditLabel, feature = ProFeature.SURFACE_EDIT) { showSurfaceEdit = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.SLOPE, analysisLabel, vm.analysisMode != 0, feature = ProFeature.ANALYSIS) { showAnalysis = true })
        if (advice.isNotEmpty()) add(RailTool(GIcon.IDEA, tr("Sugestões"), feature = ProFeature.ADVICE) { showAdvice = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.BASIN, tr("Bacias"), vm.basin != null || vm.outlets.isNotEmpty() || v.pickingOutlet, feature = ProFeature.BASIN) { showBasin = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.VOLUME, volumeLabel, vm.volume != null, feature = ProFeature.VOLUME) { showVolume = true })
        if (project.surfaces.isNotEmpty()) add(RailTool(GIcon.ASBUILT, asBuiltLabel, vm.asBuilt != null, feature = ProFeature.AS_BUILT) { showAsBuilt = true })
        add(RailTool(GIcon.MODELS, tr("Modelos"), feature = ProFeature.MODELS) { showModels = true })
        add(RailTool(GIcon.NOTE, tr("Anotar"), addingNote, feature = ProFeature.NOTES) { addingNote = true; measuring = false; show3d = false })
        add(RailTool(GIcon.GPS, "GPS", vm.gnss.connected, feature = ProFeature.GPS) { showGps = true })
        add(RailTool(GIcon.AR, tr("RA"), showAr, feature = ProFeature.AR) {
            showAr = !showAr
            if (showAr) { show3d = false; measuring = false }
        })
        add(RailTool(GIcon.CALIBRATE, calibLabel, v.calibrationOn, feature = ProFeature.CALIBRATE) { showCalibration = true })
        add(RailTool(GIcon.CALC, tr("Cálculos"), feature = ProFeature.CALC) { showCalc = true })
        add(RailTool(GIcon.MAP, tr("Mapa"), vm.basemap.enabled, feature = ProFeature.MAP) { showBasemap = true })
        add(RailTool(GIcon.DOWNLOAD, tr("Baixar dados"), feature = ProFeature.DOWNLOAD) { showArea = true })
        add(RailTool(GIcon.GOTO, tr("Ir para"), feature = ProFeature.GOTO) { showGoTo = true })
        add(RailTool(GIcon.EXPORT, tr("Exportar"), feature = ProFeature.EXPORT) { showExport = true })
        if (vm.hasReferences) add(RailTool(GIcon.REFS, tr("Referências"), feature = ProFeature.REFS) { showReferences = true })
        if (project.warnings.isNotEmpty()) add(RailTool(GIcon.WARNING, tr("Avisos"), feature = ProFeature.WARNINGS) { showWarnings = true })
    }
    // The free version shows every tool with a padlock; touching one opens the Gromatic Pro screen.
    // Pro leaves each tool on its own action: nothing on the rail stays locked.
    fun railTools(): List<RailTool> = allTools().map { tool ->
        val feature = tool.feature ?: return@map tool
        if (proOpen(feature)) tool else tool.copy(active = false, locked = true, onClick = { vm.proRequested = true })
    }
    val current = info
    @Composable
    fun BoxScope.NarrowCards() {
        if (show3d) {
            // A plan tap's card stays behind; 3D shows the measure or the area being drawn.
            if (measuring) {
                MeasureCard(
                    measurement,
                    onUndo = { measurePoints = measurePoints.dropLast(1) },
                    onClear = { measurePoints = emptyList() },
                    onDone = { measuring = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).widthIn(max = 380.dp).fillMaxWidth(),
                    onSurface = true, ground = groundLength, report = areaReport, pending = surfacePending,
                )
            } else if ((drawingPlatform || genPick == GenPick.AREA_DRAW) && areaPoly.size >= 3 && (areaReport != null || surfacePending)) {
                SurfaceFacts(areaReport, surfacePending, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 8.dp).widthIn(max = 320.dp))
            }
            return
        }
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
                ground = groundLength, report = areaReport, pending = surfacePending,
            )
        } else if (!proOpen(ProFeature.TAP_INFO)) {
            FreeHint(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { vm.proRequested = true }
        } else if (current == null) {
            Text(
                tr("Toque na planta para ver estaca, afastamento e cotas"),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            // Capped so the card stays readable instead of stretching across a tablet in landscape.
            modelHit?.let { m ->
                Card(Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 8.dp).widthIn(max = 380.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Line(tr("Camada"), m.layer)
                        m.props.forEach { (k, value) -> Line(k, value) }
                    }
                }
            }
            // The properties card folds into a small button, so the plan stays clear on a phone.
            // A folded tablet panel still shows the card: the tap has to reveal the location.
            if (ProAccess.tapInfoCardOnPlan(isPro, wide, vm.themes.panelOpen, vm.fullScreen)) Column(Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 480.dp).fillMaxWidth()) {
                if (vm.themes.panelOpen) OutlinedButton(onClick = { vm.themes.togglePanel() }, modifier = (Modifier.align(Alignment.End)).heightIn(min = 48.dp)) { Text(tr("Recolher") + " ▾") }
                InfoCard(current, differenceAt(project, difference, current.position), markedElevations(project, vm.crossSelected, current.position), Modifier.fillMaxWidth())
            } else GButton(tr("Propriedades") + " ▴", { vm.themes.togglePanel() }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
        if (gpsPoint != null && !show3d) {
            GpsCard(vm.gnss, gpsPoint.x, gpsPoint.y, gpsHeight, calibration != null, gpsInfo, Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 8.dp).widthIn(max = 420.dp))
        }
    }

    val onPlanTap: (Point2) -> Unit = {
                        // Tapping the same snapped point twice would add a zero-length leg.
                        when {
                            v.pickingOutlet -> {
                                v.pickingOutlet = false
                                val name = vm.analysisSurface ?: project.surfaces.firstOrNull()?.name
                                project.surfaces.firstOrNull { s -> s.name == name }?.let { s -> vm.basinAt(s, it) }
                                showBasin = true
                            }
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
                                    val hiddenHere = offLayers.toSet() + frozenLayers
                                    val inside = project.polylines.indices.filter { i ->
                                        val l = project.polylines[i]
                                        l.layer !in hiddenHere && l.xs.indices.any { k -> l.xs[k] in minX..maxX && l.ys[k] in minY..maxY }
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
                            editing && edit.pick == EditPick.DRAW -> if (it != edit.picked.lastOrNull()) edit.picked = edit.picked + it
                            editing && edit.pick == EditPick.CENTER -> { edit.picked = listOf(it); edit.pick = EditPick.SELECT; showLineOp = true }
                            genPick == GenPick.AREA_DRAW -> if (it != gen.drawnArea.lastOrNull()) gen.drawnArea = gen.drawnArea + it
                            drawingPlatform -> if (it != platformOutline.lastOrNull()) platformOutline = platformOutline + it
                            addingPoints -> {
                                val z = PointQuery.at(project, it, frozenLayers.toSet()).terrainElevation ?: Double.NaN
                                editingPoint = null to CogoPoint(PointFile.nextNumber(vm.userPoints), "", it.x, it.y, z)
                            }
                            measuring -> if (it != measurePoints.lastOrNull()) measurePoints = measurePoints + it
                            showSection && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> sectionStation = so.station }
                            showProfile && sectionAxis != null -> sectionAxis.stationOffset(it)?.let { so -> profileCursor = so.station }
                            !proOpen(ProFeature.TAP_INFO) -> {}
                            else -> {
                                tapped = it; selectedPoint = null
                                modelHit = if (project.models.isEmpty()) null else com.appdwg.core.ModelPick.at(project.models, it, offLayers.toSet() + frozenLayers)
                            }
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
                val pickingArea = measuring || drawingPlatform || genPick == GenPick.AREA_DRAW
                if (showAr) ArScreen(
                    project = project,
                    userPoints = vm.userPoints,
                    hidden = hidden,
                    origin = gpsLocal,
                    controls = v.arControls,
                    onControls = { v.arControls = it },
                    onClose = { showAr = false },
                ) else if (show3d) key(project) {
                    View3D(
                        project = project,
                        hiddenLayers = hidden,
                        layerColor = layerColor,
                        background = g.planBg,
                        foreground = g.text,
                        exaggeration = exaggeration.toFloat(),
                        drape = drape3d,
                        modifier = Modifier.fillMaxSize(),
                        surfaceStyle = { v.styleOf(it) },
                        onTap = if (pickingArea) onPlanTap else null,
                        snap = pickingArea,
                        userPoints = vm.userPoints,
                        overlayLines = drapeLines,
                        overlayTris = if (areaClosed && areaPoly.size >= 3) drapeTris else DoubleArray(0),
                        onDrapeFailed = {
                            val lower = GlTexture.nextQuality(v.drapeQuality) ?: return@View3D
                            v.drapeQuality = lower
                            vm.report(tr("Qualidade reduzida: memória do aparelho"))
                        },
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
                    tiledRasters = vm.tiledRasters.filterKeys { it !in vm.unloaded },
                    drape = drape,
                    imageQuality = v.drapeQuality,
                    onTap = onPlanTap, // defined above; 3D uses the same vertex flow
                    modifier = Modifier.fillMaxSize(),
                    measure = if (measuring) measurePoints else if (editing && edit.pick == EditPick.DRAW) edit.picked else if (drawingRoad) v.roadParams.pis else if (drawingPlatform) platformOutline
                        else if (genPick == GenPick.AREA_DRAW || (showGen && gen.grid && gen.area == GridArea.DRAWN)) gen.drawnArea else emptyList(),
                    measureClosed = measuring || !(drawingRoad || (editing && edit.pick == EditPick.DRAW)),
                    extraAlignments = listOfNotNull(vm.previewAlignment(v.roadParams)),
                    axisHidden = v.axisHidden,
                    measureIsAxis = drawingRoad && !measuring,
                    handles = if (drawingRoad) v.roadParams.pis else emptyList(),
                    selectedHandle = selectedPi,
                    onHandleDrag = if (drawingRoad) { i, p -> v.roadParams.let { rp -> if (i in rp.pis.indices) v.roadParams = rp.copy(pis = rp.pis.toMutableList().apply { set(i, p) }) } } else null,
                    onHandleTap = { i -> selectedPi = if (selectedPi == i) null else i },
                    snap = measuring || drawingPlatform || drawingRoad || genPick == GenPick.AREA_DRAW || modelPickFor != null ||
                        (editing && edit.pick in setOf(EditPick.FROM_TO, EditPick.CENTER, EditPick.AXIS, EditPick.DRAW)),
                    selectLine = genPick == GenPick.LINE || genPick == GenPick.AREA_LINE || genPick == GenPick.ROTATION || roadTargetPick != null ||
                        (editing && edit.pick == EditPick.SELECT),
                    selectedLines = if (editing) edit.selected else emptyList(),
                    previewLines = if (editing) edit.preview else emptyList(),
                    gps = gpsPoint,
                    gpsAccuracy = gnssFix?.sigmaH ?: gnssFix?.hdop?.let { it * 3 },
                    gpsColor = fixColor(gnssFix?.quality),
                    notes = vm.notes,
                    onNoteTap = if (measuring || addingPoints || editing || !proOpen(ProFeature.NOTE_OPEN)) null else { n -> openNote = n; tapped = null; selectedPoint = null },
                    basemap = vm.basemap.takeIf { it.enabled && !crs.isGeographic && !crs.local },
                    basemapCrs = crs,
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
                    guides = designGuides(vm).first,
                    guideMarks = designGuides(vm).second,
                    designLines = vm.platform?.let { r -> listOf(r.edge, r.daylight) + r.benches }?.map { l -> l.map { Point2(it.x, it.y) } }.orEmpty(),
                    sectionLine = section?.let { it.pointAt(-it.offsets.last()) to it.pointAt(it.offsets.last()) } ?: profileMark,
                    overlay = vm.slopeImage.takeIf { vm.analysisMode == 1 || vm.analysisMode == 3 } ?: differenceOverlay,
                    channels = if (vm.analysisMode >= 2) vm.flowChannels else emptyList(),
                    arrows = if (vm.analysisMode >= 2) vm.flowArrows else emptyList(),
                    points = vm.userPoints,
                    pointCodes = vm.pointCodes,
                    onPointTap = if (measuring || addingPoints || !proOpen(ProFeature.POINT_INFO)) null else { pt -> selectedPoint = pt; tapped = null },
                    selectedPoint = selectedPoint,
                    focus = focus,
                    focusKey = focusKey,
                    contourInterval = if (showContours) contourInterval else null,
                    onContours = { contourDrawn = it },
                    viewKey = fileName,
                    zoom = planZoom,
                    pointLabels = v.pointLabels,
                    surfaceStyle = { v.styleOf(it) },
                    fallbackBounds = v.area,
                    marks = vm.asBuilt?.let { r -> r.rows.map { Point2(it.point.x, it.point.y) to asBuiltColor(r.status(it)) } }.orEmpty(),
                )


            Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = if (compact) 56.dp else 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FitRow(Modifier.fillMaxWidth()) {
                    val views = buildList {
                        add(tr("Planta") to (!show3d && !showSection && !showProfile))
                        if (has3d) add("3D" to show3d)
                        if (project.alignments.isNotEmpty()) { add(tr("Seção") to (showSection && !show3d)); add(tr("Perfil") to (showProfile && !show3d)) }
                    }
                    fun viewFeature(label: String): ProFeature? = when (label) {
                        "3D" -> ProFeature.VIEW_3D
                        tr("Seção") -> ProFeature.VIEW_SECTION
                        tr("Perfil") -> ProFeature.VIEW_PROFILE
                        else -> null
                    }
                    ViewSwitch(views, locked = { i -> viewFeature(views[i].first)?.let { !proOpen(it) } == true }, onPick = { i ->
                        val feature = viewFeature(views[i].first)
                        if (feature != null && !proOpen(feature)) { vm.proRequested = true; return@ViewSwitch }
                        when (views[i].first) {
                            tr("Planta") -> { show3d = false; showSection = false; showProfile = false }
                            "3D" -> { show3d = true; showSection = false; showProfile = false }
                            tr("Seção") -> {
                                tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { sectionStation = it.station } }
                                show3d = false; showSection = true; showProfile = false; measuring = false; tapped = null
                            }
                            tr("Perfil") -> {
                                tapped?.let { t -> sectionAxis?.stationOffset(t)?.let { profileCursor = it.station } }
                                show3d = false; showProfile = true; showSection = false; measuring = false; tapped = null
                            }
                        }
                    })
                    // Vertical exaggeration helps read gentle terrain.
                    if (show3d) GButton("Z ×$exaggeration", { exaggeration = exaggeration % 3 + 1 })
                    if (drape != null || vm.tiledRasters.isNotEmpty()) Box {
                        // Same three steps in plan and in 3D. Máxima is the file's own pixels.
                        var open by remember { mutableStateOf(false) }
                        val names = listOf(tr("normal"), tr("alta"), tr("máxima"))
                        val details = listOf(
                            tr("Normal — um quarto dos pixels"),
                            tr("Alta — metade dos pixels"),
                            tr("Máxima — cada pixel do arquivo"),
                        )
                        val imageLabel = if (show3d && drapeLoading) tr("Imagem: carregando {0}…", names[v.drapeQuality]) else tr("Imagem: {0}", names[v.drapeQuality])
                        GButton(if (compact) names[v.drapeQuality] else imageLabel, { open = true }, icon = GIcon.IMAGE, description = imageLabel)
                        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
                            MenuTitle(tr("Detalhe da imagem"))
                            details.forEachIndexed { i, n -> MenuLine(n, checked = v.drapeQuality == i) { open = false; v.drapeQuality = i } }
                        }
                    }
                }
                if (showContours && !show3d) {
                    Card {
                        Text(
                            contourDrawn?.let { tr("Curvas de nível a cada {0} (mestras a cada 5)", StationFormat.length(it, if (it < 1) 2 else 0)) }
                                ?: tr("Curvas densas demais neste zoom: aproxime ou aumente o intervalo"),
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (differenceOverlay != null && difference != null && !show3d && !wide) DifferenceLegend(difference, Modifier)
                for (sf in project.surfaces) {
                    val st = v.styleOf(sf.name)
                    if (st.legend && st.elevation == ElevationPaint.COLORS && sf.name !in hidden) ElevationLegend(sf)
                }
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
                    modifier = if (compact) Modifier.align(Alignment.TopEnd).padding(top = 200.dp, end = 12.dp) else Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    plusMinus = !compact,
                )
            }
                if (show3d && !measuring && !drawingPlatform && genPick == null) {
                    Text(
                        tr("1 dedo gira · 2 dedos movem · pinça aproxima"),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        style = MaterialTheme.typography.labelMedium, color = g.text,
                    )
                } else if (editing) {
                    EditLinesBar(edit, vm, project, onOperation = { showLineOp = true }, onDone = { editing = false; edit.selected = emptyList(); edit.preview = emptyList() },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 900.dp))
                } else if (addingNote) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque na planta onde fica a anotação."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            if (gpsPoint != null) OutlinedButton(onClick = { addingNote = false; editingNote = newNote(gpsPoint, gpsHeight) to true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Na posição do GPS")) }
                            OutlinedButton(onClick = { addingNote = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) }
                        }
                    }
                } else if (openNote != null && !wide) {
                    NoteCard(vm, openNote!!, onEdit = { editingNote = openNote!! to false; openNote = null }, onClose = { openNote = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 520.dp))
                } else if (roadTargetPick != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque na linha que o bordo da pista deve seguir."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { roadTargetPick = null; showRoad = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) }
                        }
                    }
                } else if (drawingRoad) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        val rp = v.roadParams
                        selectedPi?.takeIf { it in rp.pis.indices }?.let { i ->
                            PiEditor(rp, i, onChange = { v.roadParams = it }, onSelect = { selectedPi = it })
                        }
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque os PIs do eixo, do início ao fim ({0}). Arraste um PI para movê-lo; toque nele para raio e transição.", v.roadParams.pis.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { v.roadParams = v.roadParams.copy(pis = v.roadParams.pis.dropLast(1)) }, enabled = v.roadParams.pis.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desfazer")) }
                            OutlinedButton(onClick = { v.roadParams = v.roadParams.copy(pis = emptyList()) }, enabled = v.roadParams.pis.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                            OutlinedButton(onClick = { showElements = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Elementos")) }
                            OutlinedButton(onClick = { showAxisDisplay = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Exibir")) }
                            OutlinedButton(onClick = { drawingRoad = false; showRoad = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Concluir")) }
                        }
                    }
                } else if (modelPickFor != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(when (alignPick) { 1 -> tr("Toque onde fica o ponto A do modelo."); 2 -> tr("Toque onde fica o ponto B do modelo.")
                                else -> tr("Toque o ponto de inserção do modelo.") } + tr(" O toque encaixa em pontos e vértices."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { modelPickFor = null; showModels = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) }
                        }
                    }
                } else if (genReview) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("{0} ponto(s) em ciano.", gen.preview.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { genReview = false; showGen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Ajustar")) }
                            OutlinedButton(onClick = { vm.addGenerated(gen.preview); gen.preview = emptyList(); genReview = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Criar")) }
                            OutlinedButton(onClick = { gen.preview = emptyList(); genReview = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) }
                        }
                    }
                } else if (genPick != null) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(when (genPick) {
                                GenPick.AREA_DRAW -> tr("Toque os vértices do polígono da malha ({0}).", gen.drawnArea.size)
                                GenPick.AREA_LINE -> tr("Toque numa polilinha fechada.")
                                GenPick.ROTATION -> tr("Toque na linha que dá a direção da malha.")
                                else -> tr("Toque na linha, polilinha 3D ou eixo.")
                            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            if (genPick == GenPick.AREA_DRAW) {
                                OutlinedButton(onClick = { gen.drawnArea = gen.drawnArea.dropLast(1) }, enabled = gen.drawnArea.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desfazer")) }
                                OutlinedButton(onClick = { gen.drawnArea = emptyList() }, enabled = gen.drawnArea.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                            }
                            OutlinedButton(onClick = { genPick = null; showGen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (genPick == GenPick.AREA_DRAW) tr("Concluir") else tr("Cancelar")) }
                        }
                    }
                } else if (v.pickingOutlet) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque no ponto de lançamento; ele encaixa no talvegue mais próximo."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { v.pickingOutlet = false; showBasin = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cancelar")) }
                        }
                    }
                } else if (drawingPlatform) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).pointerInput(Unit) { detectTapGestures { } }) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque os vértices da plataforma ({0}). O toque encaixa em pontos e vértices.", platformOutline.size),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { platformOutline = platformOutline.dropLast(1) }, enabled = platformOutline.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desfazer")) }
                            OutlinedButton(onClick = { platformOutline = emptyList() }, enabled = platformOutline.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                            OutlinedButton(onClick = { drawingPlatform = false; showPlatform = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Concluir")) }
                        }
                    }
                } else if (addingPoints) {
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        FitRow(Modifier.padding(horizontal = 12.dp)) {
                            Text(tr("Toque na planta para criar um ponto (cota da superfície)."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                            OutlinedButton(onClick = { addingPoints = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Concluir")) }
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
                        onPvis = { list -> v.roadParams = v.roadParams.copy(pvis = list); vm.redesignSoon() },
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
                } else if (!wide || full || !vm.themes.panelOpen) {
                    NarrowCards()
                }

            if (vm.timelineShown && vm.states.size > 1) TimelineBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 80.dp, start = 8.dp, end = 8.dp).widthIn(max = 900.dp))
            else if (adviceCard && advice.isNotEmpty() && vm.projectMessage == null) Card(Modifier.align(Alignment.TopCenter).padding(top = 80.dp).widthIn(max = 480.dp)) {
                FitRow(Modifier.padding(start = 12.dp)) {
                    Text(tr("Sugestão: {0}", advice.first().title), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { showAdvice = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Ver sugestões")) }
                    OutlinedButton(onClick = { adviceCard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) }
                }
            }
            if (vm.taskViewers == 0) BackgroundTasks(vm.tasks, Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 64.dp).widthIn(max = 360.dp))
        }
        // The side panel folds away to give the plan the whole width; a tab brings it back.
        if (wide && !full) PanelTab(vm.themes.panelOpen, { vm.themes.togglePanel() })
        if (wide && !full) androidx.compose.animation.AnimatedVisibility(vm.themes.panelOpen,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(180))) { SidePanel {
            Text(summary(project.copy(points = vm.userPoints)), style = MaterialTheme.typography.labelLarge, color = g.muted)
            if (project.warnings.isNotEmpty()) OutlinedButton(onClick = { showWarnings = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(plural(project.warnings.size, "aviso", "avisos") + tr(" da leitura")) }
            if (gpsPoint != null) GpsCard(vm.gnss, gpsPoint.x, gpsPoint.y, gpsHeight, calibration != null, gpsInfo, Modifier.fillMaxWidth())
            if (measuring) MeasureCard(measurement, onUndo = { measurePoints = measurePoints.dropLast(1) }, onClear = { measurePoints = emptyList() },
                onDone = { measuring = false }, modifier = Modifier.fillMaxWidth(),
                saved = v.savedMeasures.size, onSave = { v.savedMeasures = v.savedMeasures + listOf(measurePoints); measurePoints = emptyList() },
                onClearSaved = { v.savedMeasures = emptyList() },
                onSurface = show3d, ground = groundLength, report = areaReport, pending = surfacePending)
            if (show3d) {
                if (!measuring) {
                    val marking = drawingPlatform || genPick == GenPick.AREA_DRAW
                    Text(
                        tr(if (marking) "1 dedo gira · 2 dedos movem · pinça aproxima. Um toque curto marca um ponto na superfície."
                            else "1 dedo gira · 2 dedos movem · pinça aproxima"),
                        style = MaterialTheme.typography.bodyMedium, color = g.muted,
                    )
                    if (marking) SurfaceFacts(areaReport, surfacePending, Modifier.fillMaxWidth())
                }
            } else selectedPoint?.let { pt ->
                val q = remember(project, pt, frozenLayers) { PointQuery.at(project, Point2(pt.x, pt.y), frozenLayers.toSet()) }
                PointCard(pt, q.alignment?.takeIf { !it.stationOffset.outside }, q.terrainElevation,
                    onEdit = { editingPoint = pt to pt }, onClose = { selectedPoint = null }, modifier = Modifier.fillMaxWidth())
            }
            if (!show3d) openNote?.let { n ->
                NoteCard(vm, n, onEdit = { editingNote = n to false; openNote = null }, onClose = { openNote = null }, modifier = Modifier.fillMaxWidth())
            }
            if (!show3d) modelHit?.takeIf { current != null }?.let { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Line(tr("Camada"), m.layer)
                        m.props.forEach { (k, value) -> Line(k, value) }
                    }
                }
            }
            if (!show3d && current != null && !measuring && selectedPoint == null &&
                ProAccess.tapInfoInSidePanel(isPro, wide, vm.themes.panelOpen, full)) {
                InfoCard(current, differenceAt(project, difference, current.position), markedElevations(project, vm.crossSelected, current.position), Modifier.fillMaxWidth())
            }
            if (differenceOverlay != null && difference != null && !show3d) DifferenceLegend(difference, Modifier)
            if ((vm.analysisMode == 1 || vm.analysisMode == 3) && !show3d) SlopeLegend(vm, Modifier)
            if (!show3d && !proOpen(ProFeature.TAP_INFO)) FreeHint(Modifier.fillMaxWidth()) { vm.proRequested = true }
            else if (!show3d && current == null && !measuring && selectedPoint == null && openNote == null && gpsPoint == null) {
                Text(tr("Toque na planta para ver estaca, afastamento e cotas."), style = MaterialTheme.typography.bodyMedium, color = g.muted)
            }
        } }
    }
    // Footer: the tapped point, else the GPS position; station on the axis; plan scale; GPS fix.
    val shown = if (show3d) gpsInfo else (current ?: gpsInfo)
    val metersPerInch = viewport?.let { vp -> if (planWidthPx > 0) vp.width / planWidthPx * xdpi else null }
    if (!vm.fullScreen && compact) ToolBar(railTools())
    if (!vm.fullScreen) {
        val clipboard = androidx.compose.ui.platform.LocalClipboard.current
        val digits = v.coordDigits
        StatusFooter(
            items = buildList {
                shown?.let { p ->
                    val e = StationFormat.lengthValue(p.position.x, digits); val n = StationFormat.lengthValue(p.position.y, digits)
                    val z = p.terrainElevation?.let { StationFormat.lengthValue(it, minOf(digits, 3)) }
                    add(FooterItem("E $e   N $n" + (z?.let { "   Z $it" } ?: "")) { close ->
                        MenuTitle(tr("Coordenadas ({0})", StationFormat.lengthUnit))
                        MenuLine(tr("Copiar E N") + if (z != null) " Z" else "") {
                            clipboard.nativeClipboard.setPrimaryClip(android.content.ClipData.newPlainText("en", listOfNotNull(e, n, z).joinToString(" "))); close()
                        }
                        MenuTitle(tr("Casas decimais"))
                        for (d in listOf(0, 1, 2, 3, 4)) MenuLine(d.toString(), checked = d == digits) { v.coordDigits = d }
                        MenuLine(tr("Ir para coordenada ou estaca…")) { close(); showGoTo = true }
                    })
                    p.alignment?.takeIf { !it.stationOffset.outside }?.let { a ->
                        add(FooterItem(StationFormat.estaca(a.stationOffset.station) + " · " + StationFormat.offset(a.stationOffset.offset)) { close ->
                            MenuTitle(a.name)
                            MenuLine(tr("Ir para estaca…")) { close(); showGoTo = true }
                            MenuLine(tr("Seção nesta estaca")) {
                                close(); sectionAlignment = project.alignments.indexOfFirst { it.name == a.name }.coerceAtLeast(0)
                                sectionStation = a.stationOffset.station; showSection = true; showProfile = false; show3d = false
                            }
                            MenuLine(tr("Perfil neste ponto")) {
                                close(); profileCursor = a.stationOffset.station; showProfile = true; showSection = false; show3d = false
                            }
                        })
                    }
                }
                metersPerInch?.let { mpi ->
                    add(FooterItem("1:" + niceScale(mpi / 0.0254)) { close ->
                        MenuTitle(tr("Escala da planta"))
                        for (nScale in listOf(100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 25000)) MenuLine("1:" + StationFormat.grouped(nScale.toDouble(), 0)) {
                            close(); planZoom = ZoomRequest((planZoom?.id ?: 0) + 1, 1.0, scale = xdpi / (nScale * 0.0254))
                        }
                        MenuLine(tr("Ajustar à tela")) { close(); planZoom = ZoomRequest((planZoom?.id ?: 0) + 1, 0.0) }
                    })
                }
            },
            right = if (!listOf(ProFeature.FILES, ProFeature.SURFACES, ProFeature.POINT_LABELS).all { proOpen(it) }) buildList {
                add(FooterItem(StationFormat.lengthUnit) { close ->
                    MenuTitle(tr("Unidades"))
                    for (u in UnitChoice.entries) MenuLine(u.label, checked = vm.units.choice == u) { close(); vm.units.choose(u) }
                })
                add(FooterItem(vm.languages.choice.tag?.substringBefore('-')?.uppercase() ?: tr("Auto")) { close ->
                    MenuTitle(tr("Idioma"))
                    val missingLanguage = langReady.any { !it.value }
                    for (l in LanguageChoice.entries) {
                        val ready = langReady[l] != false
                        MenuLine(l.label, checked = vm.languages.choice == l, enabled = ready) { if (ready) { close(); vm.languages.choose(l) } }
                    }
                    if (missingLanguage) MenuTitle(tr("Este idioma não está instalado neste aparelho. Atualize o Gromatic pela Play Store."))
                })
                add(FooterItem(tr("Tema")) { close ->
                    for (t in ThemeChoice.entries) MenuLine(t.label, checked = vm.themes.choice == t) { close(); vm.themes.choose(t) }
                })
                add(FooterItem(tr("Grátis"), dot = g.accent) { close ->
                    MenuTitle(tr("Versão grátis: abrir e ver a planta"))
                    MenuLine(tr("Conhecer o Gromatic Pro…")) { close(); vm.proRequested = true }
                })
            } else buildList {
                // Files of the project (mosaic): each one on or off, and more added, without leaving the plan.
                val files = vm.sourceFiles
                add(FooterItem(if (files.isEmpty()) tr("Arquivos") else tr("Arquivos {0}/{1}", files.count { it.label !in vm.hiddenFiles } + 1, files.size + 1)) { close ->
                    MenuTitle(tr("Arquivos do projeto"))
                    MenuLine(fileName, checked = true, enabled = false) {}
                    for (f in files) MenuLine(f.label, checked = f.label !in vm.hiddenFiles) { vm.setFileVisible(f.label, f.label in vm.hiddenFiles) }
                    MenuLine(tr("Adicionar arquivos…")) { close(); addFiles(false) }
                    if (hasDriveApp) MenuLine(tr("Adicionar do Google Drive…")) { close(); addFiles(true) }
                    if (files.isNotEmpty()) MenuLine(tr("Remover ou comparar…")) { close(); showCompare = true }
                })
                // Drawing settings without leaving the plan.
                val layers = project.layers + (if (vm.userPoints.isNotEmpty()) listOf(com.appdwg.core.POINTS_LAYER) else emptyList())
                if (layers.isNotEmpty()) add(FooterItem(tr("Camadas {0}/{1}", layers.count { it !in hidden }, layers.size)) { close ->
                    MenuLine(tr("Ligar todas")) { offLayers = ArrayList(); frozenLayers = ArrayList() }
                    MenuLine(tr("Desligar todas")) { offLayers = ArrayList(layers) }
                    for (l in layers.take(60)) MenuLine(l.substringAfterLast('|'), checked = l !in hidden) {
                        offLayers = ArrayList(offLayers.toSet().toggle(l, l !in offLayers)); if (l in frozenLayers) frozenLayers = ArrayList(frozenLayers - l)
                    }
                    if (layers.size > 60) MenuLine(tr("Todas as camadas…")) { close(); showLayers = true }
                })
                if (project.surfaces.isNotEmpty()) {
                    val shownSurfaces = project.surfaces.map { it.name }.filter { it !in hidden }
                    add(FooterItem(if (shownSurfaces.size == 1) shownSurfaces.single().substringAfterLast('|') else tr("Superfícies {0}/{1}", shownSurfaces.size, project.surfaces.size)) { close ->
                        MenuTitle(tr("Superfície mostrada"))
                        MenuLine(tr("Todas"), checked = shownSurfaces.size == project.surfaces.size) {
                            offLayers = ArrayList(offLayers - project.surfaces.map { it.name }.toSet()); frozenLayers = ArrayList(frozenLayers - project.surfaces.map { it.name }.toSet())
                        }
                        for (sf in project.surfaces) MenuLine(tr("Só ") + sf.name.substringAfterLast('|'), checked = shownSurfaces == listOf(sf.name)) {
                            offLayers = ArrayList((offLayers.toSet() + project.surfaces.map { it.name }) - sf.name); frozenLayers = ArrayList(frozenLayers - sf.name)
                        }
                        // Display style of each surface, as in Civil 3D: the mesh off with the elevations still shown.
                        for (sf in project.surfaces) {
                            val st = v.styleOf(sf.name)
                            fun set(n: SurfaceStyle) = v.setStyle(sf.name, n)
                            MenuTitle(tr("Estilo · ") + sf.name.substringAfterLast('|'))
                            MenuLine(tr("Triângulos (malha)"), checked = st.triangles) { set(st.copy(triangles = !st.triangles)) }
                            MenuLine(tr("Borda"), checked = st.border) { set(st.copy(border = !st.border)) }
                            MenuLine(tr("Curvas de nível"), checked = st.contours && showContours) {
                                if (!showContours) { showContours = true; set(st.copy(contours = true)) } else set(st.copy(contours = !st.contours))
                            }
                            MenuLine(tr("Elevações em cores"), checked = st.elevation == ElevationPaint.COLORS) { set(st.copy(elevation = ElevationPaint.COLORS)) }
                            MenuLine(tr("Só sombreamento"), checked = st.elevation == ElevationPaint.SHADE) { set(st.copy(elevation = ElevationPaint.SHADE)) }
                            MenuLine(tr("Sem elevações"), checked = st.elevation == ElevationPaint.NONE) { set(st.copy(elevation = ElevationPaint.NONE)) }
                            MenuLine(tr("Legenda de cotas"), checked = st.legend) { set(st.copy(legend = !st.legend, elevation = if (!st.legend) ElevationPaint.COLORS else st.elevation)) }
                        }
                    })
                    add(FooterItem(if (showContours) tr("Curvas ") + (contourDrawn?.let { StationFormat.length(it, if (StationFormat.toLength(it) < 0.99) 1 else 0) } ?: "") else tr("Curvas: não")) { close ->
                        MenuLine(tr("Mostrar curvas de nível"), checked = showContours) { showContours = !showContours }
                        MenuTitle(tr("Intervalo"))
                        val steps = if (com.appdwg.core.Display.imperial) listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0).map { it * StationFormat.FOOT } else listOf(0.0, 0.5, 1.0, 2.0, 5.0, 10.0)
                        for (iv in steps) MenuLine(if (iv == 0.0) tr("Automático") else StationFormat.length(iv, if (StationFormat.toLength(iv) < 0.99) 1 else 0),
                            checked = showContours && contourInterval == iv) { contourInterval = iv; showContours = true }
                    })
                }
                if (project.cgPoints.isNotEmpty() || vm.userPoints.isNotEmpty()) add(FooterItem(if (v.pointLabels) tr("Rótulos: sim") else tr("Rótulos: não")) { _ ->
                    MenuLine(tr("Nome, descrição e cota dos pontos"), checked = v.pointLabels) { v.pointLabels = !v.pointLabels }
                })
                add(FooterItem(StationFormat.lengthUnit) { close ->
                    MenuTitle(tr("Unidades"))
                    for (u in UnitChoice.entries) MenuLine(u.label, checked = vm.units.choice == u) { close(); vm.units.choose(u) }
                })
                add(FooterItem(vm.languages.choice.tag?.substringBefore('-')?.uppercase() ?: tr("Auto")) { close ->
                    MenuTitle(tr("Idioma"))
                    val missingLanguage = langReady.any { !it.value }
                    for (l in LanguageChoice.entries) {
                        val ready = langReady[l] != false
                        MenuLine(l.label, checked = vm.languages.choice == l, enabled = ready) { if (ready) { close(); vm.languages.choose(l) } }
                    }
                    if (missingLanguage) MenuTitle(tr("Este idioma não está instalado neste aparelho. Atualize o Gromatic pela Play Store."))
                })
                add(FooterItem(tr("Tema")) { close ->
                    for (t in ThemeChoice.entries) MenuLine(t.label, checked = vm.themes.choice == t) { close(); vm.themes.choose(t) }
                })
                add(FooterItem(crs.epsg?.let { "EPSG $it" } ?: crs.name.take(28)) { close ->
                    MenuTitle(crs.name)
                    // Neighbouring zones of the same datum, for a quick change.
                    val tm = crs.projection as? com.appdwg.core.Projection.TransverseMercator
                    if (tm != null && tm.k0 == 0.9996 && !crs.local) {
                        val zone = ((tm.lon0 + 183) / 6).toInt(); val south = tm.falseNorthing > 0
                        for (zn in (zone - 1)..(zone + 1)) if (zn in 1..60) {
                            val c = com.appdwg.core.CrsCatalog.byId(com.appdwg.core.CrsCatalog.utm(crs.datum, zn, south, null).id)
                                ?: com.appdwg.core.CrsCatalog.utm(crs.datum, zn, south, null)
                            MenuLine(c.name, checked = c.id == crs.id) { close(); vm.chooseCrs(c) }
                        }
                    }
                    MenuLine(tr("Escolher sistema de coordenadas…")) { close(); showCrs = true }
                })
                val fix = gnssFix
                add(FooterItem(fix?.let { f -> f.quality.label + (f.sigmaH?.let { " · " + StationFormat.length(it, 3) } ?: "") } ?: "GPS",
                    dot = if (fix == null) g.muted else if (fix.quality == com.appdwg.core.FixQuality.RTK_FIXED) g.gps else fixColor(fix.quality)) { close ->
                    MenuLine(if (fix == null) tr("Conectar GPS…") else "GPS…") { close(); showGps = true }
                    MenuLine(tr("Calibração e geoide…")) { close(); showCalibration = true }
                    MenuLine(tr("Seguir a posição na planta"), checked = gpsFollow) { gpsFollow = !gpsFollow }
                    if (fix != null) MenuLine(tr("Desconectar")) { close(); vm.gnss.disconnect() }
                })
            },
        )
    }    }

    if (showBulletin) {
        BulletinDialog(vm, onPdf = { bulletinPdf.launch("$baseName-boletim-${v.bulletinNumber}.pdf") },
            onCsv = { bulletinCsv.launch("$baseName-boletim-${v.bulletinNumber}.csv") }, onDismiss = { showBulletin = false })
    }

    if (showSheets) {
        SheetsDialog(vm, project, fileName, onMake = { p ->
            sheetParams = p
            sheetsSaver.launch("$baseName-secoes-${(p.alignment ?: "eixo").replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')}.pdf")
        }, onDismiss = { showSheets = false },
            onDrawAxis = { showSheets = false; measuring = false; show3d = false; v.roadParams = v.roadParams.copy(alignmentIndex = -1); drawingRoad = true },
            onPickLogo = { logoPicker.launch(arrayOf("image/*")) })
    }

    if (showSurfaceEdit) {
        SurfaceEditDialog(vm, project, surfaceEditState, measurePoints, tapped, onDraw = {
            showSurfaceEdit = false; measuring = true; measurePoints = emptyList(); showSection = false; showProfile = false; tapped = null
        }, onDismiss = { showSurfaceEdit = false })
    }

    if (showAnalysis) AnalysisDialog(vm, project, onDismiss = { showAnalysis = false })

    if (showAdvice) AdviceDialog(advice, onDo = { a ->
        showAdvice = false; adviceCard = false
        val terrainName = project.surfaces.firstOrNull { !it.name.startsWith(PLATFORM_LABEL) && !it.name.startsWith(ROAD_LABEL) }?.name
        val terrainSurface = project.surfaces.firstOrNull { it.name == terrainName }
        val axes = project.alignments.filter { !it.name.endsWith(ROAD_SUFFIX) }
        fun linePoints(i: Int) = project.polylines.getOrNull(i)?.let { l -> List(l.xs.size) { k -> Point2(l.xs[k], l.ys[k]) } }.orEmpty()
        when (a.kind) {
            com.appdwg.core.Advice.Kind.CORRIDOR, com.appdwg.core.Advice.Kind.GRADE -> {
                v.roadParams = v.roadParams.copy(alignmentIndex = axes.indexOf(project.alignments[a.alignment]), terrain = terrainName)
                roadOnGrade = a.kind == com.appdwg.core.Advice.Kind.GRADE
                showRoad = true
            }
            com.appdwg.core.Advice.Kind.AXIS_FROM_LINE -> project.polylines.getOrNull(a.line)?.let { l ->
                val rp = v.roadParams
                val pis = com.appdwg.core.ProjectAdvisor.pisFromLine(l, rp.speed, rp.emax)
                v.roadParams = rp.copy(alignmentIndex = -1, pis = pis.map { it.position }, radii = pis.map { it.radius }, spirals = pis.map { 0.0 }, terrain = terrainName)
                roadOnGrade = true
                showRoad = true
            }
            com.appdwg.core.Advice.Kind.PLATFORM_FROM_OUTLINE -> {
                platformOutline = linePoints(a.line)
                v.platformTerrain = terrainName
                showPlatform = true
            }
            com.appdwg.core.Advice.Kind.CULVERTS -> {
                vm.analysisSurface = terrainName
                val extra = advice.filter { it.kind == com.appdwg.core.Advice.Kind.AXIS_FROM_LINE }.map { linePoints(it.line) }
                terrainSurface?.let { vm.suggestOutlets(it, project, v.basinParams.minArea, extra) }
                showBasin = true
            }
            com.appdwg.core.Advice.Kind.BASINS -> { vm.analysisSurface = terrainName; showBasin = true }
            com.appdwg.core.Advice.Kind.ROUTE -> {
                measuring = false; showSection = false; showProfile = false; show3d = false
                v.roadParams = v.roadParams.copy(alignmentIndex = -1, pis = emptyList(), radii = emptyList(), spirals = emptyList(), terrain = terrainName)
                drawingRoad = true
            }
            com.appdwg.core.Advice.Kind.COMPARE -> {
                val names = project.surfaces.filter { !it.name.startsWith(PLATFORM_LABEL) && !it.name.startsWith(ROAD_LABEL) }.map { it.name }
                v.compareBase = names.getOrNull(0); v.compareOther = names.getOrNull(1)
                showCompare = true
            }
            com.appdwg.core.Advice.Kind.SLOPES -> terrainSurface?.let { vm.analysisSurface = it.name; vm.analysisMode = 1; vm.runAnalysis(it) }
        }
    }, onDismiss = { showAdvice = false; adviceCard = false })

    if (showBasin) BasinDialog(vm, project, onPick = {
        showBasin = false; v.pickingOutlet = true; measuring = false; show3d = false; tapped = null
    }, onDismiss = { showBasin = false })

    if (showVolume) {
        VolumeDialog(vm, project, volumeState, measurePoints, onDraw = {
            showVolume = false; measuring = true; measurePoints = emptyList(); showSection = false; showProfile = false; tapped = null
        }, onDismiss = { showVolume = false })
    }

    if (showCalibration) {
        CalibrationDialog(vm, v, project, crs, onLoadGrid = { geoidPicker.launch(arrayOf("*/*")) }, onDismiss = { showCalibration = false })
    }

    if (showAsBuilt) {
        AsBuiltDialog(vm, project, asBuiltState,
            onImport = { pointImporter.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            onImportDrive = if (hasDrive) ({ driveNext.set(true); pointImporter.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) else null,
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
            onImportDrive = if (hasDrive) ({ driveNext.set(true); pointImporter.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) else null,
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
            onAddFile = { addFiles(false) },
            onAddDrive = if (hasDriveApp) ({ addFiles(true) }) else null,
            onCross = { showCompare = false; if (proOpen(ProFeature.CROSS_SECTION)) showCross = true else vm.proRequested = true },
            onAddState = { statePicker.launch(arrayOf("*/*")) },
            onTimeline = {
                showCompare = false
                if (proOpen(ProFeature.TIMELINE)) { vm.timelineShown = true; if (vm.activeState == null) vm.showState(0) } else vm.proRequested = true
            },
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
                showGen = false; measuring = false; showSection = false; showProfile = false
                if (pick != GenPick.AREA_DRAW) show3d = false
                if (pick == GenPick.AREA_DRAW) gen.drawnArea = emptyList()
                genPick = pick
            },
            onPreview = { showGen = false; measuring = false; showSection = false; showProfile = false; show3d = false; genReview = true },
            onConfirm = { pts -> vm.addGenerated(pts); showGen = false },
            onDismiss = { showGen = false },
        )
    }

    if (showAxisDisplay) AxisDisplayDialog(v, onDismiss = { showAxisDisplay = false })
    if (showElements) AlignmentElementsDialog(project.alignments + listOfNotNull(vm.previewAlignment(v.roadParams)), onDismiss = { showElements = false })

    if (showRoad) {
        RoadDialog(vm, project, onDrawAxis = {
            showRoad = false; measuring = false; showSection = false; showProfile = false; show3d = false
            v.roadParams = v.roadParams.copy(alignmentIndex = -1)
            drawingRoad = true
        }, onServiceNote = { noteSaver.launch("$baseName-nota-de-servico.csv") },
            onServiceNotePdf = { notePdfSaver.launch("$baseName-nota-de-servico.pdf") },
            onBulletin = { showRoad = false; showBulletin = true },
            onElements = { showElements = true },
            onDisplay = { showAxisDisplay = true },
            onPickTarget = { i, side -> showRoad = false; show3d = false; roadTargetPick = i to side },
            onDismiss = { showRoad = false; roadOnGrade = false }, startOnGrade = roadOnGrade)
    }

    if (showPlatform) {
        PlatformDialog(vm, project, onDraw = {
            showPlatform = false; measuring = false; showSection = false; showProfile = false
            drawingPlatform = true
        }, onDismiss = { showPlatform = false })
    }

    if (showContourDialog) {
        AlertDialog(
            modifier = Modifier.safeBars(),
            onDismissRequest = { showContourDialog = false },
            confirmButton = { OutlinedButton(onClick = { showContourDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
            dismissButton = { OutlinedButton(onClick = { showContours = false; showContourDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desligar")) } },
            title = { Text(tr("Curvas de nível")) },
            text = {
                Column {
                    Text(tr("Intervalo"))
                    Row {
                        // Round intervals in the shown unit (1, 2, 5… ft in imperial), kept in metres.
                        (if (com.appdwg.core.Display.imperial) listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0).map { it * StationFormat.FOOT }
                        else listOf(0.0, 0.5, 1.0, 2.0, 5.0, 10.0)).forEach { i ->
                            androidx.compose.material3.FilterChip(
                                selected = showContours && contourInterval == i,
                                onClick = { contourInterval = i; showContours = true },
                                label = { Text(if (i == 0.0) tr("Auto") else StationFormat.length(i, if (StationFormat.toLength(i) < 0.99) 1 else 0)) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text(tr("\"Auto\" escolhe o intervalo pelo zoom. As curvas são calculadas para a área visível."),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }

    editingNote?.let { (n, isNew) -> key(n.id) { NoteDialog(vm, n, isNew, onDismiss = { editingNote = null }, onSaveAndNew = { editingNote = null; addingNote = true }) } }

    if (showGps) {
        GpsDialog(vm.gnss, crs, { showGps = false; showCrs = true }, gpsFollow, { gpsFollow = it },
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
    if (showBasemap) BasemapDialog(vm.basemap, crs, { showBasemap = false; showCrs = true }, project.bounds, viewport, onDismiss = { showBasemap = false })
    if (showCrs) CrsDialog(vm, crs, onDismiss = { showCrs = false })

    if (showArea) {
        AreaDialog(vm, viewport, v.measurePoints, onDrawArea = {
            showArea = false; measuring = true; measurePoints = emptyList(); showSection = false; showProfile = false; tapped = null
        }, onDismiss = { showArea = false }, chosen = v.area)
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
            includeModels = includeModels,
            onIncludeModels = { includeModels = it },
            crs = crs,
            onCrs = { showExport = false; showCrs = true },
            onExport = { kind ->
                showExport = false
                if (kind == ExportKind.PNG || kind == ExportKind.PDF) captureFor = kind
                else { pendingExport = kind; driveNext.set(exportToDrive); exportSaver.launch(baseName + kind.suffix) }
            },
            toDrive = if (hasDrive) exportToDrive else null,
            onToDrive = { exportToDrive = it },
            onSheets = { showExport = false; if (proOpen(ProFeature.SHEETS)) showSheets = true else vm.proRequested = true },
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
            modifier = Modifier.safeBars(),
            onDismissRequest = { showWarnings = false },
            confirmButton = { OutlinedButton(onClick = { showWarnings = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
            title = { Text(tr("Avisos da leitura")) },
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
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        dismissButton = {
            FitRow {
                OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (d == null) tr("Escolher") else tr("Trocar"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (d != null) OutlinedButton(onClick = { vm.removeDrape(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remover"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        title = { Text(tr("Imagem sobre a superfície")) },
        text = {
            Column {
                if (d != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.drapeVisible, onCheckedChange = { vm.drapeVisible = it })
                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                } else if (vm.tasks.isEmpty()) {
                    Text(tr("Escolha um TIFF, JPG ou PNG. Para posicionar um JPG ou PNG, selecione junto o arquivo de georreferência (.jgw, .pgw ou .tfw)."))
                }
                vm.drapeMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.tasks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TaskProgress(vm)
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
                Line(tr("Estaca"), StationFormat.estaca(a.stationOffset.station) +
                    "  (${StationFormat.length(a.stationOffset.station)})")
                Line(tr("Afastamento"), StationFormat.offset(a.stationOffset.offset) +
                    if (a.stationOffset.outside) tr("  (fora do eixo)") else "")
                a.designElevation?.let { Line(tr("Greide no eixo"), StationFormat.length(it, 3)) }
                a.terrainOnAxis?.let { Line(tr("Terreno no eixo"), StationFormat.length(it, 3)) }
                a.cutFill?.let {
                    Line(if (it >= 0) tr("Aterro") else tr("Corte"), StationFormat.length(abs(it), 3))
                }
            }
            difference?.let { Line(tr("B − A no ponto"), it) }
            marked.forEach { (name, z) -> Line(name.substringAfterLast('|'), z) }
            Line(tr("Terreno no ponto"), info.terrainElevation?.let { StationFormat.length(it, 3) } ?: tr("fora da superfície"))
            Line("E / N", StationFormat.lengthValue(info.position.x) + " / " + StationFormat.lengthValue(info.position.y))
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

/** Window width in dp. Compact is under 600, wide is 900 and up. */
@Composable
private fun windowWidthDp(): androidx.compose.ui.unit.Dp {
    val px = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    return with(androidx.compose.ui.platform.LocalDensity.current) { px.toDp() }
}

/** Copy of what the window shows, including the 3D view's GL surface. */
private suspend fun captureWindow(window: android.view.Window): android.graphics.Bitmap? {
    val view = window.decorView
    if (view.width <= 0 || view.height <= 0) return null
    val bitmap = createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        android.view.PixelCopy.request(window, bitmap, { result ->
            cont.resume(if (result == android.view.PixelCopy.SUCCESS) bitmap else null) { _, _, _ -> }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
}

/** Elevation at [p] of each surface marked for the cross comparison. */
private fun markedElevations(project: LandXmlProject, marked: List<String>, p: Point2): List<Pair<String, String>> =
    marked.mapNotNull { n -> project.surfaces.firstOrNull { it.name == n } }
        .map { s -> s.name to (s.elevationAt(p)?.let { StationFormat.length(it, 3) } ?: "fora") }

/** B − A at [p] with the surfaces of the last comparison, as "+0,250 m (aterro)"; null without one. */
private fun differenceAt(project: LandXmlProject, d: SurfaceDifference?, p: Point2): String? {
    d ?: return null
    val za = project.surfaces.firstOrNull { it.name == d.baseName }?.elevationAt(p)
    val zb = project.surfaces.firstOrNull { it.name == d.otherName }?.elevationAt(p)
    if (za == null || zb == null) return tr("fora de uma das superfícies")
    val v = zb - za
    return signedLength(v) + when {
        v > 0.0005 -> " (aterro)"
        v < -0.0005 -> " (corte)"
        else -> ""
    }
}

/** Results of the measurement being taken, with undo, clear and done. */
@Composable
private fun MeasureCard(
    m: Measurement?, onUndo: () -> Unit, onClear: () -> Unit, onDone: () -> Unit, modifier: Modifier,
    saved: Int = 0, onSave: () -> Unit = {}, onClearSaved: () -> Unit = {},
    onSurface: Boolean = false,
    ground: Double? = null,
    report: AreaOnSurface? = null,
    pending: Boolean = false,
) {
    // Taps on the card must not reach the plan underneath and add a point.
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Medir"), style = MaterialTheme.typography.titleSmall)
            val leg = m?.legs?.lastOrNull()
            if (m == null || leg == null) {
                Text(
                    tr(if (onSurface) "Toque na superfície para marcar os pontos. O toque encaixa em pontos e vértices próximos."
                        else "Toque na planta para marcar os pontos. O toque encaixa em pontos e vértices próximos.") +
                        if (m != null) tr(" Marque o próximo ponto.") else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (m.legs.size > 1) Text(tr("Último trecho"), style = MaterialTheme.typography.labelMedium)
                Line(tr("Distância"), StationFormat.length(leg.distance, 3))
                Line(tr("Azimute"), Measure.dms(leg.azimuth))
                leg.rise?.let { r ->
                    Line(tr("Desnível"), signedLength(r))
                    leg.slopePercent?.let { Line(tr("Inclinação"), signed(it, 2) + " %") }
                }
                if (m.legs.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Text(tr("Total ({0} pontos)", m.legs.size + 1), style = MaterialTheme.typography.labelMedium)
                    Line(tr("Comprimento"), StationFormat.length(m.length, 3))
                    m.rise?.let { r -> Line(tr("Desnível 1º → último"), signedLength(r)) }
                }
                ground?.let { Line(tr("Comprimento no terreno"), StationFormat.length(it, 3)) }
                m.slopeLength?.let { Line(tr("Comprimento inclinado"), StationFormat.length(it, 3)) }
                m.area?.let { Line(tr("Área"), Measure.area(it)) }
                m.perimeter?.let { Line(tr("Perímetro"), StationFormat.length(it, 3)) }
                if (m.area != null) SurfaceFactLines(report, pending)
                else if (pending) Text(tr("Calculando na superfície…"), style = MaterialTheme.typography.bodySmall)
            }
            if (saved > 0) Text(tr("{0} medição(ões) salva(s) na planta (M1, M2…).", saved), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                if (m != null) {
                    OutlinedButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desfazer")) }
                    OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
                    if (m.legs.isNotEmpty()) OutlinedButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Salvar e nova")) }
                }
                if (saved > 0) OutlinedButton(onClick = onClearSaved, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Apagar salvas")) }
                OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Concluir")) }
            }
        }
    }
}

private fun signed(v: Double, digits: Int) = (if (v >= 0) "+" else "−") + StationFormat.decimal(abs(v), digits)

/** Signed length in the shown unit: "+0,250 m" or "+0.820 ft". */
private fun signedLength(v: Double, digits: Int = 3) = (if (v >= 0) "+" else "−") + StationFormat.length(abs(v), digits)

/** Area, elevations, slope and cut/fill of the polygon on the TIN. */
@Composable
private fun SurfaceFacts(report: AreaOnSurface?, pending: Boolean, modifier: Modifier) {
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Área"), style = MaterialTheme.typography.titleSmall)
            report?.let {
                Line(tr("Área"), Measure.area(it.planArea))
                Line(tr("Perímetro"), StationFormat.length(it.perimeterPlan, 3))
            }
            SurfaceFactLines(report, pending)
        }
    }
}

@Composable
private fun SurfaceFactLines(report: AreaOnSurface?, pending: Boolean) {
    if (report == null) {
        if (pending) Text(tr("Calculando na superfície…"), style = MaterialTheme.typography.bodySmall)
        return
    }
    Line(tr("Área na superfície"), Measure.area(report.surfaceArea))
    Line(tr("Perímetro na superfície"), StationFormat.length(report.perimeter3d, 3))
    Line(tr("Cota mínima"), StationFormat.length(report.minElevation, 3))
    Line(tr("Cota máxima"), StationFormat.length(report.maxElevation, 3))
    Line(tr("Cota média"), StationFormat.length(report.averageElevation, 3))
    Line(tr("Declividade média"), StationFormat.decimal(report.averageSlopePercent, 2) + " %")
    val cut = report.cut
    val fill = report.fill
    if (cut != null && fill != null) {
        Text(tr("Entre {0} e {1}", report.baseName ?: "", report.otherName ?: ""), style = MaterialTheme.typography.labelMedium)
        Line(tr("Corte"), StationFormat.volume(cut))
        Line(tr("Aterro"), StationFormat.volume(fill))
    }
    if (pending) Text(tr("Calculando na superfície…"), style = MaterialTheme.typography.bodySmall)
}

private class SurfaceBuilt(val ground: Double?, val area: AreaOnSurface?, val lines: DoubleArray, val tris: DoubleArray)

/** Ground length, draped segments and the area report. Runs off the UI thread. */
private fun surfaceMeasure(surfaces: List<com.appdwg.core.Surface>, poly: List<Point2>, closed: Boolean): SurfaceBuilt {
    val base = surfaces.getOrNull(0)
    val other = surfaces.getOrNull(1)
    var area: AreaOnSurface? = null
    var host = base
    if (closed && poly.size >= 3 && base != null) {
        area = TinMeasure.area(base, poly, other, geometry = true)
        if (area == null) {
            for (s in surfaces.drop(1)) {
                val found = TinMeasure.area(s, poly, null, geometry = true)
                if (found != null) { area = found; host = s; break }
            }
        }
    }
    var open = host?.let { TinMeasure.ground(it, poly, closed = false) }
    if (open == null) {
        for (s in surfaces) {
            if (s === host) continue
            val g = TinMeasure.ground(s, poly, closed = false) ?: continue
            open = g
            host = s
            break
        }
    }
    val surface = host
    val lines = if (surface != null && closed && poly.size >= 3)
        TinMeasure.ground(surface, poly, closed = true)?.segments ?: open?.segments ?: DoubleArray(0)
    else open?.segments ?: DoubleArray(0)
    return SurfaceBuilt(open?.length, area, lines, area?.facets ?: DoubleArray(0))
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
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onAllOn, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Ligar todas")) }
                OutlinedButton(onClick = onAllOff, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desligar todas")) }
            }
        },
        title = { Text(tr("Camadas")) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Cor"), Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
                    Text(tr("Nome"), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text(tr("Ligada"), Modifier.width(64.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                    Text(tr("Congelada"), Modifier.width(76.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
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
            modifier = Modifier.safeBars(),
            onDismissRequest = { editing = null },
            confirmButton = { OutlinedButton(onClick = { editing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
            dismissButton = {
                OutlinedButton(onClick = { onColor(layer, null); editing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Cor original")) }
            },
            title = { Text(tr("Cor de {0}", layer)) },
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

private fun plural(n: Int, one: String, many: String) = "$n ${tr(if (n == 1) one else many)}"

/** A round map scale near [ratio] (1:500, 1:1000, 1:2000, 1:2500, 1:5000…), with thousands grouped. */
private fun niceScale(ratio: Double): String {
    if (!ratio.isFinite() || ratio <= 0) return "?"
    val e = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(ratio)))
    val n = listOf(1.0, 2.0, 2.5, 5.0, 10.0).minByOrNull { kotlin.math.abs(it * e - ratio) }!! * e
    return StationFormat.grouped(Math.round(n).toDouble(), 0)
}

/** Top-bar button that asks where: this device or Google Drive (when Drive is installed). */
@Composable
private fun DropButton(text: String, drive: Boolean, onDevice: () -> Unit, onDrive: () -> Unit) {
    if (!drive) { GButton(text, onDevice); return }
    var open by remember { mutableStateOf(false) }
    Box {
        GButton("$text ▾", { open = true })
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Neste aparelho")) }, onClick = { open = false; onDevice() })
            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Google Drive")) }, leadingIcon = { Text("☁") }, onClick = { open = false; onDrive() })
        }
    }
}
