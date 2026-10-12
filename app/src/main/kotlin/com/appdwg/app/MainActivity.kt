package com.appdwg.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                is ProjectState.Loaded -> ProjectScreen(state.project, state.terrain)
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
private fun ProjectScreen(project: LandXmlProject, terrain: List<TerrainImage>) {
    // The tapped position survives rotation; the info shown is recomputed from it.
    var tapped by rememberSaveable(project, stateSaver = Point2Saver) { mutableStateOf<Point2?>(null) }
    val info = remember(project, tapped) { tapped?.let { PointQuery.at(project, it) } }
    var showWarnings by remember(project) { mutableStateOf(false) }
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

    Box(Modifier.fillMaxSize()) {
        PlanView(
            project = project,
            terrain = terrain,
            colors = colors,
            marker = info?.position,
            markerOnAxis = info?.alignment?.stationOffset?.pointOnAlignment,
            onTap = { tapped = it },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Card {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(summary(project), style = MaterialTheme.typography.labelMedium)
                    if (project.warnings.isNotEmpty()) {
                        TextButton(onClick = { showWarnings = true }) { Text("${project.warnings.size} avisos") }
                    }
                }
            }
        }

        val current = info
        if (current == null) {
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
    p.coordinateSystem?.epsgCode?.let { add("EPSG $it") }
}.joinToString(" · ")

private val Point2Saver = Saver<Point2?, DoubleArray>(
    save = { p -> p?.let { doubleArrayOf(it.x, it.y) } },
    restore = { Point2(it[0], it[1]) },
)

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
