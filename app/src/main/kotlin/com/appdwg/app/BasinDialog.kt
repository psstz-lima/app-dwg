package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.Basin
import com.appdwg.core.Hydrology
import com.appdwg.core.Idf
import com.appdwg.core.IdfCatalog
import com.appdwg.core.IdfEntry
import com.appdwg.core.LandXmlProject
import com.appdwg.core.OutletSuggestion
import com.appdwg.core.Point2
import com.appdwg.core.StationFormat

/** Line drawn over the plan by an analysis or a suggestion. */
class Guide(val points: List<Point2>, val color: Int, val closed: Boolean = false, val width: Float = 3f, val fillAlpha: Float = 0f, val dashed: Boolean = false)

/** Labelled dot drawn over the plan by an analysis or a suggestion. */
class GuideMark(val at: Point2, val color: Int, val label: String)

/**
 * Rain and runoff for the peak flow of a basin: runoff coefficient C, return period in years and
 * the local IDF curve i = K·Tᵃ / (t + b)ᶜ (mm/h, t in minutes). Empty IDF (K = 0) means none given.
 */
data class BasinParams(
    val runoff: Double = 0.5,
    val years: Double = 25.0,
    val k: Double = 0.0,
    val a: Double = 0.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    /** Smallest stream, m², for the suggested outlets. */
    val minArea: Double = 50_000.0,
    /** Place whose published equation the IDF came from, or empty when typed in. */
    val idfPlace: String = "",
) {
    val idf get() = if (k > 0) Idf(k, a, b, c) else null
}

/** Colours of the basin drawing on the plan. */
const val BASIN_COLOR = 0xFF00897B.toInt()
const val STREAM_COLOR = 0xFF1565C0.toInt()
const val OUTLET_COLOR = 0xFFFF6D00.toInt()
/** Colour of the measurements kept on the plan. */
const val SAVED_MEASURE_COLOR = 0xFF6D4C41.toInt()
/** Colour of the road axis while it is laid out, curves included. */
const val AXIS_COLOR = 0xFFD81B60.toInt()

/** What the analyses and suggestions draw over the plan: basin, outlets and suggested routes. */
fun designGuides(vm: ProjectViewModel): Pair<List<Guide>, List<GuideMark>> {
    val (lines, marks) = basinGuides(vm)
    val routes = vm.routeSuggestions.mapIndexed { i, r -> Guide(r.alignment.vertices, ROUTE_COLORS[i % ROUTE_COLORS.size], width = 4f, dashed = true) }
    val axis = vm.view?.takeIf { AxisInfo.LINE !in it.axisHidden }?.roadParams?.let { vm.axisPreview(it) }.orEmpty()
    val measures = vm.view?.savedMeasures.orEmpty()
    val measureLines = measures.filter { it.size >= 2 }.map { Guide(it, SAVED_MEASURE_COLOR, width = 3f) }
    val measureMarks = measures.mapIndexedNotNull { i, m ->
        val last = m.lastOrNull() ?: return@mapIndexedNotNull null
        var len = 0.0
        for (k in 1 until m.size) len += kotlin.math.hypot(m[k].x - m[k - 1].x, m[k].y - m[k - 1].y)
        GuideMark(last, SAVED_MEASURE_COLOR, "M${i + 1} · " + StationFormat.length(len, 2))
    }
    return (lines + routes + measureLines + listOfNotNull(axis.takeIf { it.size >= 2 }?.let { Guide(it, AXIS_COLOR, width = 4f) })) to (marks + measureMarks)
}

/** Basin outline, main stream and outlets to draw over the plan. */
fun basinGuides(vm: ProjectViewModel): Pair<List<Guide>, List<GuideMark>> {
    val b = vm.basin
    val lines = if (b == null) emptyList() else listOf(
        Guide(b.outline, BASIN_COLOR, closed = true, width = 3f, fillAlpha = 0.12f),
        Guide(b.mainStream.map { Point2(it.x, it.y) }, STREAM_COLOR, width = 4f),
    )
    val marks = vm.outlets.mapIndexed { i, s -> GuideMark(s.point, OUTLET_COLOR, "${i + 1}") } +
        listOfNotNull(b?.let { GuideMark(it.outlet, BASIN_COLOR, tr("Lançamento")) })
    return lines to marks
}

/**
 * Drainage basin from an outlet: tapped on the plan or picked from the outlets the app suggests
 * (stream crossings of the alignments and the road, where water leaves the platform outline, where
 * it leaves the terrain). Shows the basin figures and, with the local rain curve, the peak flow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BasinDialog(vm: ProjectViewModel, project: LandXmlProject, onPick: () -> Unit, onDismiss: () -> Unit) {
    val v = vm.view ?: return
    val names = project.surfaces.map { it.name }
    val current = vm.analysisSurface?.takeIf { it in names } ?: names.firstOrNull()
    val surface = project.surfaces.firstOrNull { it.name == current }
    val p0 = v.basinParams
    fun num(s: String) = StationFormat.number(s)
    var fields by remember {
        mutableStateOf(mapOf(
            "C" to StationFormat.decimal(p0.runoff, 2), "T" to StationFormat.decimal(p0.years, 0),
            "K" to if (p0.k > 0) StationFormat.decimal(p0.k, 3) else "", "a" to StationFormat.decimal(p0.a, 4),
            "b" to StationFormat.decimal(p0.b, 3), "c" to StationFormat.decimal(p0.c, 4),
        ))
    }
    var place by remember { mutableStateOf(p0.idfPlace) }
    var query by remember { mutableStateOf("") }
    fun params() = v.basinParams.copy(
        runoff = num(fields["C"]!!) ?: p0.runoff, years = num(fields["T"]!!) ?: p0.years,
        k = num(fields["K"]!!) ?: 0.0, a = num(fields["a"]!!) ?: 0.0, b = num(fields["b"]!!) ?: 0.0, c = num(fields["c"]!!) ?: 0.0,
        idfPlace = place,
    )
    fun pick(e: IdfEntry) {
        fields = fields + mapOf("K" to StationFormat.decimal(e.idf.k, 3), "a" to StationFormat.decimal(e.idf.a, 4),
            "b" to StationFormat.decimal(e.idf.b, 3), "c" to StationFormat.decimal(e.idf.c, 4))
        place = e.label
        query = ""
    }
    AlertDialog(
        onDismissRequest = { v.basinParams = params(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.fillMaxWidth(0.94f)),
        confirmButton = { OutlinedButton(onClick = { v.basinParams = params(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        dismissButton = {
            if (vm.basin != null || vm.outlets.isNotEmpty()) OutlinedButton(onClick = { vm.clearBasin() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Limpar")) }
        },
        title = { Text(tr("Bacia de contribuição")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Superfície"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { n -> FilterChip(n == current, { vm.analysisSurface = n }, { Text(n.substringAfterLast('|')) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = surface != null, onClick = { v.basinParams = params(); onPick() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Tocar no ponto de lançamento")) }
                    OutlinedButton(enabled = surface != null && vm.tasks.isEmpty(), onClick = { surface?.let { vm.suggestOutlets(it, project, v.basinParams.minArea) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tr("Sugerir pontos de lançamento"))
                    }
                }
                Text(tr("Talvegue mínimo para as sugestões"), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10_000.0, 50_000.0, 200_000.0, 1_000_000.0).forEach { a ->
                        FilterChip(v.basinParams.minArea == a, { v.basinParams = v.basinParams.copy(minArea = a) }, { Text(StationFormat.landArea(a, 1)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                TaskProgress(vm)
                vm.basinMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (vm.outlets.isNotEmpty()) {
                    HorizontalDivider()
                    Text(tr("Pontos sugeridos (numerados na planta)"), style = MaterialTheme.typography.labelLarge)
                    vm.outlets.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. " + kindText(s.kind) + " · " + StationFormat.landArea(s.area, 2), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = surface != null && vm.tasks.isEmpty(), onClick = { surface?.let { vm.basinAt(it, s) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Delimitar")) }
                        }
                    }
                }
                vm.basin?.let { b ->
                    HorizontalDivider()
                    BasinFigures(b)
                    HorizontalDivider()
                    Text(tr("Vazão de pico (método racional)"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("C" to tr("Coef. de escoamento C"), "T" to tr("Tempo de retorno (anos)")).forEach { (k, label) ->
                            OutlinedTextField(fields[k]!!, { fields = fields + (k to it) }, label = { Text(label) }, singleLine = true,
                                modifier = Modifier.widthIn(max = 190.dp).fillMaxWidth(), isError = num(fields[k]!!) == null)
                        }
                    }
                    Text(tr("Chuva local (IDF): i = K·Tᵃ / (t + b)ᶜ, em mm/h com t em minutos"), style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(query, { query = it }, label = { Text(tr("Buscar equação de chuva por cidade ou UF")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    if (query.isNotBlank()) {
                        val found = IdfCatalog.search(query)
                        found.take(8).forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.label + if (e.author.isNotEmpty()) " · " + e.author else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { pick(e) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Usar")) }
                            }
                        }
                        if (found.isEmpty()) Text(tr("Nenhuma equação para essa busca; digite K, a, b e c."), style = MaterialTheme.typography.bodySmall)
                        else if (found.size > 8) Text(tr("Mais {0} resultados; refine a busca.", found.size - 8), style = MaterialTheme.typography.bodySmall)
                    }
                    if (place.isNotEmpty()) Text(tr("Equação de {0} (Coletânea das equações de chuva do Brasil, ABRH). Confira com a do órgão local.", place),
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("K", "a", "b", "c").forEach { k ->
                            OutlinedTextField(fields[k]!!, { fields = fields + (k to it); place = "" }, label = { Text(k) }, singleLine = true,
                                modifier = Modifier.widthIn(max = 110.dp).fillMaxWidth(), isError = fields[k]!!.isNotBlank() && num(fields[k]!!) == null)
                        }
                    }
                    val p = params()
                    val idf = p.idf
                    if (idf == null) Text(tr("Escolha a cidade ou informe a equação de chuvas da região (K, a, b, c) para calcular a vazão."), style = MaterialTheme.typography.bodySmall)
                    else {
                        val q = Hydrology.rational(b, p.runoff, idf, p.years)
                        SpecRow(tr("Tempo de concentração (min)"), StationFormat.decimal(q.minutes, 1))
                        SpecRow(tr("Intensidade"), StationFormat.decimal(q.intensity, 1) + " mm/h")
                        SpecRow(tr("Vazão"), StationFormat.decimal(q.peak, 3) + " m³/s")
                        if (q.tooLarge) FactLine(tr("Bacia maior que 2 km²: o método racional tende a superestimar; prefira o hidrograma unitário (SCS)."))
                    }
                    Text(tr("Contorno e talvegue vão no DXF exportado (camadas _BACIA e _BACIA_TALVEGUE)."), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun BasinFigures(b: Basin) {
    SpecHeader(tr("Bacia"))
    SpecRow(tr("Área"), StationFormat.landArea(b.area, 3))
    SpecRow(tr("Perímetro"), StationFormat.length(b.perimeter, 0))
    SpecRow(tr("Talvegue"), StationFormat.length(b.streamLength, 0))
    SpecRow(tr("Desnível"), StationFormat.length(b.streamDrop, 2))
    SpecRow(tr("Declividade"), StationFormat.decimal(b.streamSlope * 100, 2) + " %")
    SpecRow(tr("Cota mínima"), StationFormat.lengthValue(b.minElevation, 2))
    SpecRow(tr("Cota máxima"), StationFormat.lengthValue(b.maxElevation, 2))
    SpecRow(tr("Declividade média"), StationFormat.decimal(b.meanSlope, 1) + " %")
    SpecRow("Kc", StationFormat.decimal(b.compactness, 2))
    SpecRow("Kf", StationFormat.decimal(b.formFactor, 2))
    SpecRow(tr("Tempo de concentração (min)"), StationFormat.decimal(b.kirpichMinutes, 1))
}

private fun kindText(k: OutletSuggestion.Kind) = when (k) {
    OutletSuggestion.Kind.CROSSING -> tr("Talvegue cruza o eixo (bueiro)")
    OutletSuggestion.Kind.LEAVES_AREA -> tr("Saída de água da plataforma")
    OutletSuggestion.Kind.LEAVES_TERRAIN -> tr("Saída do terreno")
}
