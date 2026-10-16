package com.appdwg.app

import com.appdwg.core.Point2
import com.appdwg.core.StationFormat
import com.appdwg.core.drainage.DeviceProfile
import com.appdwg.core.drainage.DrainAlignment
import com.appdwg.core.drainage.DrainCrossSection
import com.appdwg.core.drainage.BlanketTakeoff
import com.appdwg.core.drainage.DeviceDef
import com.appdwg.core.drainage.DeviceSide
import com.appdwg.core.drainage.DeviceTakeoff
import com.appdwg.core.drainage.DrainTakeoff
import com.appdwg.core.drainage.DrainageGraph
import com.appdwg.core.drainage.GradeMode
import com.appdwg.core.drainage.GradeSpec
import com.appdwg.core.drainage.DrainageNetwork
import com.appdwg.core.drainage.NetworkCheck
import com.appdwg.core.drainage.OutletProtection
import com.appdwg.core.drainage.OutletSoil
import com.appdwg.core.drainage.Parameter
import com.appdwg.core.drainage.PathSource
import com.appdwg.core.drainage.PlacedDevice
import com.appdwg.core.drainage.Subsurface
import com.appdwg.core.drainage.resolveSection
import com.appdwg.core.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Placement being drawn. Numbers stay text so a half-typed value is not rejected. */
data class PlaceDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val code: String,
    val name: String,
    val point: Boolean,
    val source: PathSource = PathSource.POLYLINE,
    val points: List<Point2> = emptyList(),
    val lineName: String? = null,
    val alignment: String? = null,
    val side: DeviceSide = DeviceSide.AXIS,
    val offset: String = "0",
    val start: String = "",
    val end: String = "",
    val grade: GradeMode = GradeMode.HYDRAULIC,
    val z0: String = "0",
    val z1: String = "0",
    val depth: String = "0",
    val slope: String = "0",
    val minSlope: String = "0.5",
    val minCover: String = "0.60",
    val maxDepth: String = "6",
    val buried: Boolean = false,
    val flowToStart: Boolean = false,
    val flowAuto: Boolean = true,
    val profile: String? = null,
    val rotation: String = "0",
    val skew: String = "0",
    val x: Double? = null,
    val y: Double? = null,
    val editingId: String? = null,
    val params: Map<String, String> = emptyMap(),
)

fun draftFor(device: DeviceDef): PlaceDraft {
    val buried = com.appdwg.core.drainage.deviceRunsBuried(device)
    return PlaceDraft(
        id = java.util.UUID.randomUUID().toString(),
        code = device.code,
        name = device.name,
        point = device.category == com.appdwg.core.drainage.DeviceCategory.POINT,
        grade = GradeMode.HYDRAULIC,
        buried = buried,
        minCover = if (buried) "0.60" else "0",
        params = device.parameters.mapNotNull { parameter ->
            parameter.number?.let { parameter.key to StationFormat.decimal(it, 3) }
        }.toMap(),
    )
}

fun PlacedDevice.toDraft(name: String): PlaceDraft = when (this) {
    is PlacedDevice.Linear -> PlaceDraft(
        id, code, name, point = false, source, points = xs.zip(ys).map { (x, y) -> Point2(x, y) },
        alignment = alignment, side = side, offset = num(offset),
        start = start?.let(::num) ?: "", end = end?.let(::num) ?: "",
        grade = grade.mode, z0 = num(grade.z0), z1 = num(grade.z1), depth = num(grade.depth),
        slope = num(grade.slope * 100.0), minSlope = num(grade.minSlope * 100.0), minCover = num(grade.minCover),
        maxDepth = num(grade.maxDepth), buried = grade.buried, flowToStart = grade.flowToStart, flowAuto = grade.flowAuto,
        profile = profile, editingId = id, params = params.mapValues { num(it.value) },
    )
    is PlacedDevice.Point -> PlaceDraft(
        id, code, name, point = true, alignment = alignment, grade = grade.mode,
        z0 = num(grade.z0), z1 = num(grade.z1), depth = num(grade.depth),
        minSlope = num(grade.minSlope * 100.0), minCover = num(grade.minCover), maxDepth = num(grade.maxDepth),
        buried = grade.buried, flowToStart = grade.flowToStart, flowAuto = grade.flowAuto,
        rotation = num(rotationDeg), skew = num(skewDeg), x = x, y = y, editingId = id, profile = profile,
        params = params.mapValues { num(it.value) },
    )
}

/** Null when the path or the point is still missing. Slope is entered in percent. */
fun PlaceDraft.placed(): PlacedDevice? {
    val id = editingId ?: this.id
    val numbers = params.mapNotNull { (key, text) -> StationFormat.number(text)?.let { key to it } }.toMap()
    val spec = GradeSpec(
        grade,
        z0 = StationFormat.number(z0) ?: 0.0,
        z1 = StationFormat.number(z1) ?: 0.0,
        depth = StationFormat.number(depth) ?: 0.0,
        slope = (StationFormat.number(slope) ?: 0.0) / 100.0,
        minSlope = (StationFormat.number(minSlope) ?: 0.5) / 100.0,
        minCover = StationFormat.number(minCover) ?: if (buried) 0.6 else 0.0,
        maxDepth = StationFormat.number(maxDepth) ?: 6.0,
        buried = buried,
        flowToStart = flowToStart,
        flowAuto = flowAuto,
    )
    if (point) {
        val px = x ?: return null
        val py = y ?: return null
        return PlacedDevice.Point(
            id, code, px, py, StationFormat.number(rotation) ?: 0.0, StationFormat.number(skew) ?: 0.0,
            spec, alignment, profile, numbers,
        )
    }
    if (source == PathSource.ALIGNMENT && alignment.isNullOrBlank()) return null
    if (source != PathSource.ALIGNMENT && points.size < 2) return null
    return PlacedDevice.Linear(
        id, code, source, alignment, points.map { it.x }, points.map { it.y },
        side, StationFormat.number(offset) ?: 0.0, StationFormat.number(start), StationFormat.number(end),
        spec, profile, numbers,
    )
}

private fun num(value: Double) = StationFormat.decimal(value, 3)

/** Network checks for one placed device, already in the display language. */
fun drainageNotes(graph: DrainageGraph?, id: String): List<String> {
    if (graph == null) return emptyList()
    val lines = ArrayList<String>()
    for (issue in graph.issues) {
        if (issue.deviceId != id) continue
        lines += when (issue.check) {
            NetworkCheck.REVERSE_SLOPE -> tr("Contracaída no trecho.")
            NetworkCheck.DROP -> tr("Queda de {0} m na conexão.", StationFormat.decimal(issue.drop ?: 0.0, 2))
            NetworkCheck.NO_OUTLET -> tr("Sem dispositivo de saída.")
            NetworkCheck.CAPACITY -> tr(
                "Vazão {0} m³/s acima da capacidade {1} m³/s.",
                StationFormat.decimal(issue.flow ?: 0.0, 3),
                StationFormat.decimal(issue.capacity ?: 0.0, 3),
            )
        }
    }
    if (graph.links.any { it.sectionChange && (it.upstreamId == id || it.downstreamId == id) }) {
        lines += tr("Transição de seção na conexão.")
    }
    return lines
}

/** Smallest DNIT barrel for [flow], with headwater and an overtopping note. Allowable head is 2 m. */
fun culvertLine(device: PlacedDevice?, flow: Double): String? {
    if (device == null || flow <= 0.0) return null
    val length = when (device) {
        is PlacedDevice.Linear -> {
            var span = 0.0
            for (i in 1 until device.xs.size) span += kotlin.math.hypot(device.xs[i] - device.xs[i - 1], device.ys[i] - device.ys[i - 1])
            if (span > 1.0) span else 15.0
        }
        is PlacedDevice.Point -> 15.0
    }
    val slope = when (device) {
        is PlacedDevice.Linear -> com.appdwg.core.drainage.Culvert.favorableSlope(device.grade, length)
        is PlacedDevice.Point -> 0.01
    }
    val (barrel, head) = com.appdwg.core.drainage.Culvert.size(flow, length, slope, 0.0, 2.0)
    val line = tr(
        "Bueiro {0} m × {1} m, {2}: lâmina {3} m.",
        StationFormat.decimal(barrel.span, 2),
        StationFormat.decimal(barrel.rise, 2),
        barrel.barrels,
        StationFormat.decimal(head.headwater, 2),
    )
    return if (head.overtopped) line + " " + tr("Transbordamento da rodovia.") else line
}

/** Outlet velocity against the soil limit, and the dumped-rock size when the water is moving. */
fun protectionLine(device: PlacedDevice?, catalog: com.appdwg.core.drainage.DeviceCatalog, flow: Double, soil: OutletSoil): String? {
    if (device == null || flow <= 0.0) return null
    val section = catalog.device(device.code)?.let { resolveSection(it, device.params) } ?: return null
    val area = DrainageNetwork.waterway(section)?.area ?: return null
    val advice = OutletProtection.advise(flow, area, soil)
    val line = tr(
        "Velocidade {0} m/s, limite do solo {1} m/s. Enrocamento D50 {2} m.",
        StationFormat.decimal(advice.velocity, 2),
        StationFormat.decimal(advice.limit, 2),
        StationFormat.decimal(advice.d50, 3),
    )
    return if (advice.dissipator) line + " " + tr("Velocidade acima do limite: sugerir dissipador DES.") else line
}

/** Drain trench or drainage blanket for a placed device, from the catalog dimensions. */
fun subsurfaceLine(device: DeviceDef?, placed: PlacedDevice?): String? {
    val linear = placed as? PlacedDevice.Linear ?: return null
    if (device == null) return null
    var length = 0.0
    for (i in 1 until linear.xs.size) length += kotlin.math.hypot(linear.xs[i] - linear.xs[i - 1], linear.ys[i] - linear.ys[i - 1])
    val qty = Subsurface.of(device, length) ?: return null
    return when (qty) {
        is DrainTakeoff -> tr(
            "Dreno: escavação {0} m³, filtrante {1} m³, tubo {2} m.",
            StationFormat.decimal(qty.excavation, 2),
            StationFormat.decimal(qty.filter, 2),
            StationFormat.decimal(qty.pipe, 2),
        )
        is BlanketTakeoff -> tr(
            "Colchão drenante: {0} m³ (espessura {1} m, talude {2} m).",
            StationFormat.decimal(qty.volume, 2),
            StationFormat.decimal(qty.thickness, 2),
            StationFormat.decimal(qty.slopeLength, 2),
        )
    }
}

@Composable
fun DrainagePlaceCard(
    draft: PlaceDraft,
    alignments: List<String>,
    profiles: List<String>,
    parameters: List<Parameter>,
    onChange: (PlaceDraft) -> Unit,
    onUndoPoint: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scroll only engages when this card is given a finite height (the phone test, a short dock).
    // The tablet dock already measures the card with unbounded height, so this scroll stays at rest
    // and the parent column is the one that moves. Buttons then keep their 48 dp target instead of
    // being crushed by the leftover height FlowRow would otherwise pass down.
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Lançar {0}", draft.code), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(draft.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (draft.point) {
            Text(
                if (draft.x == null) tr("Toque na planta ou no 3D para marcar o ponto. O toque encaixa em pontos e vértices próximos.")
                else tr("Ponto marcado."),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            NumberField(tr("Rotação (°)"), draft.rotation) { onChange(draft.copy(rotation = it)) }
            NumberField(tr("Esconsidade (°)"), draft.skew) { onChange(draft.copy(skew = it)) }
        } else {
            Text(tr("Caminho"), style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                PlaceChip(draft.source == PathSource.POLYLINE, tr("Desenhar")) { onChange(draft.copy(source = PathSource.POLYLINE)) }
                PlaceChip(draft.source == PathSource.ALIGNMENT, tr("Eixo")) { onChange(draft.copy(source = PathSource.ALIGNMENT)) }
                PlaceChip(draft.source == PathSource.LINE, tr("Linha existente")) { onChange(draft.copy(source = PathSource.LINE)) }
            }
            when (draft.source) {
                PathSource.POLYLINE -> Text(
                    tr("Toque na planta ou no 3D para marcar o caminho. O toque encaixa em pontos e vértices próximos.") +
                        " " + tr("Pontos do caminho: {0}", draft.points.size),
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                PathSource.ALIGNMENT -> {
                    if (alignments.isEmpty()) Text(tr("Nenhum eixo no projeto."), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ChoiceRow {
                        for (name in alignments) PlaceChip(draft.alignment == name, name) {
                            onChange(draft.copy(alignment = name, profile = draft.profile ?: name))
                        }
                    }
                }
                PathSource.LINE -> Text(
                    draft.lineName?.let { tr("Linha: {0}", it) } ?: tr("Toque numa linha existente."),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(tr("Lado"), style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                PlaceChip(draft.side == DeviceSide.LEFT, tr("Esquerda")) { onChange(draft.copy(side = DeviceSide.LEFT)) }
                PlaceChip(draft.side == DeviceSide.AXIS, tr("No eixo")) { onChange(draft.copy(side = DeviceSide.AXIS)) }
                PlaceChip(draft.side == DeviceSide.RIGHT, tr("Direita")) { onChange(draft.copy(side = DeviceSide.RIGHT)) }
            }
            NumberField(tr("Afastamento (m)"), draft.offset) { onChange(draft.copy(offset = it)) }
            NumberField(tr("Estaca inicial"), draft.start) { onChange(draft.copy(start = it)) }
            NumberField(tr("Estaca final"), draft.end) { onChange(draft.copy(end = it)) }
        }
        Text(tr("Cota"), style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            PlaceChip(draft.grade == GradeMode.HYDRAULIC, tr("Greide hidráulico")) { onChange(draft.copy(grade = GradeMode.HYDRAULIC)) }
            PlaceChip(draft.grade == GradeMode.SURFACE_TOP, tr("Terreno (topo)")) { onChange(draft.copy(grade = GradeMode.SURFACE_TOP)) }
            PlaceChip(draft.grade == GradeMode.SURFACE_INVERT, tr("Fundo no terreno")) { onChange(draft.copy(grade = GradeMode.SURFACE_INVERT)) }
            if (!draft.point) {
                PlaceChip(draft.grade == GradeMode.FIXED, tr("Greide fixo")) { onChange(draft.copy(grade = GradeMode.FIXED)) }
                PlaceChip(draft.grade == GradeMode.SLOPE, tr("Declividade constante")) { onChange(draft.copy(grade = GradeMode.SLOPE)) }
            }
            PlaceChip(draft.grade == GradeMode.PROFILE, tr("Acompanhar greide")) { onChange(draft.copy(grade = GradeMode.PROFILE)) }
        }
        when (draft.grade) {
            GradeMode.SURFACE_INVERT -> NumberField(tr("Profundidade (m)"), draft.depth) { onChange(draft.copy(depth = it)) }
            GradeMode.FIXED -> {
                NumberField(tr("Cota inicial"), draft.z0) { onChange(draft.copy(z0 = it)) }
                NumberField(tr("Cota final"), draft.z1) { onChange(draft.copy(z1 = it)) }
            }
            GradeMode.SLOPE -> {
                NumberField(tr("Cota inicial"), draft.z0) { onChange(draft.copy(z0 = it)) }
                NumberField(tr("Declividade (%)"), draft.slope) { onChange(draft.copy(slope = it)) }
            }
            GradeMode.PROFILE -> {
                if (profiles.isEmpty()) Text(tr("Nenhum greide no projeto."), maxLines = 2, overflow = TextOverflow.Ellipsis)
                ChoiceRow {
                    for (name in profiles) PlaceChip(draft.profile == name, name) { onChange(draft.copy(profile = name)) }
                }
            }
            GradeMode.HYDRAULIC -> {
                if (!draft.point) NumberField(tr("Declividade mínima (%)"), draft.minSlope) { onChange(draft.copy(minSlope = it)) }
                NumberField(tr("Cobertura mínima (m)"), draft.minCover) { onChange(draft.copy(minCover = it)) }
                NumberField(tr("Profundidade máxima (m)"), draft.maxDepth) { onChange(draft.copy(maxDepth = it)) }
                ChoiceRow {
                    PlaceChip(draft.buried, tr("Enterrado")) { onChange(draft.copy(buried = !draft.buried)) }
                    PlaceChip(draft.flowAuto, tr("Fluxo automático")) { onChange(draft.copy(flowAuto = !draft.flowAuto)) }
                    if (!draft.flowAuto) PlaceChip(draft.flowToStart, tr("Fluxo para o início")) { onChange(draft.copy(flowToStart = !draft.flowToStart)) }
                }
            }
            GradeMode.SURFACE_TOP -> Unit
        }
        if (parameters.isNotEmpty()) {
            Text(tr("Parâmetros"), style = MaterialTheme.typography.labelLarge)
            for (parameter in parameters) {
                if (parameter.number == null && draft.params[parameter.key] == null) continue
                NumberField(parameter.key, draft.params[parameter.key] ?: "") { text ->
                    onChange(draft.copy(params = draft.params + (parameter.key to text)))
                }
            }
        }
        ChoiceRow {
            if (!draft.point && draft.points.isNotEmpty()) PlaceChip(false, tr("Desfazer")) { onUndoPoint() }
            PlaceChip(false, tr("Cancelar")) { onCancel() }
            PlaceChip(true, tr("Concluir")) { onCommit() }
        }
    }
}

/** Essential controls while a path is being picked. The map stays free above this bar. */
@Composable
fun DrainagePickBar(
    title: String,
    detail: String,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    finishLabel: String = tr("Concluir"),
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Desfazer stays in the row even before the first point, so Concluir does not jump.
        // A selected device offers Editar and Excluir. The buttons wrap instead of clipping Mais.
        ActionWrap {
            if (onEdit != null || onDelete != null) {
                PlaceChip(true, finishLabel) { onFinish() }
                if (onEdit != null) PlaceChip(false, tr("Editar")) { onEdit() }
                if (onDelete != null) PlaceChip(false, tr("Excluir")) { onDelete() }
            } else {
                PlaceChip(false, tr("Desfazer"), enabled = canUndo) { if (canUndo) onUndo() }
                PlaceChip(true, finishLabel) { onFinish() }
                PlaceChip(false, tr("Cancelar")) { onCancel() }
            }
            PlaceChip(false, tr("Mais")) { onMore() }
        }
    }
}

@Composable
fun DrainageSelectionCard(
    code: String,
    name: String,
    simplified: Boolean,
    row: DeviceTakeoff?,
    total: DeviceTakeoff?,
    moving: Boolean,
    canUndo: Boolean,
    isPro: Boolean,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onPro: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    notes: List<String> = emptyList(),
    basinSummary: String? = null,
    onLinkBasin: ((String, String) -> Unit)? = null,
    soil: OutletSoil? = null,
    onSoil: ((OutletSoil) -> Unit)? = null,
    protection: String? = null,
    subsurface: String? = null,
    phase: Int = 0,
    phaseThrough: Int = 0,
    onPhase: ((Int) -> Unit)? = null,
    onPhaseThrough: ((Int) -> Unit)? = null,
    profile: DeviceProfile? = null,
    run: DrainAlignment? = null,
    section: DrainCrossSection? = null,
    connected: List<String> = emptyList(),
    onRename: ((String) -> Unit)? = null,
    onReverse: (() -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null,
    onOpenSection: (() -> Unit)? = null,
) {
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(code, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (simplified) Text(tr("Geometria simplificada"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (moving) Text(tr("Toque o novo lugar."), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!isPro) {
            Text(tr("O Pro libera o lançamento e os cálculos."), maxLines = 3, overflow = TextOverflow.Ellipsis)
            ChoiceRow {
                PlaceChip(true, tr("Conhecer o Gromatic Pro…")) { onPro() }
                PlaceChip(false, tr("Fechar")) { onClose() }
            }
            return
        }
        row?.let { TakeoffLines(tr("Este dispositivo"), it) }
        total?.let { TakeoffLines(tr("Total do projeto"), it) }
        profile?.let { HydraulicBlock(it, run, section, connected, onRename, onReverse, onExport, onOpenProfile, onOpenSection) }
        if (profile == null && row != null) {
            Text(tr("Este lançamento acompanha o terreno. O greide hidráulico faz a água descer."), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        for (note in notes) Text(note, maxLines = 3, overflow = TextOverflow.Ellipsis)
        basinSummary?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (onLinkBasin != null) BasinLink(onLinkBasin)
        if (onSoil != null && soil != null) {
            Text(tr("Solo da saída"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChoiceRow {
                PlaceChip(soil == OutletSoil.SILT, tr("Silte")) { onSoil(OutletSoil.SILT) }
                PlaceChip(soil == OutletSoil.SAND, tr("Areia")) { onSoil(OutletSoil.SAND) }
                PlaceChip(soil == OutletSoil.CLAY, tr("Argila")) { onSoil(OutletSoil.CLAY) }
                PlaceChip(soil == OutletSoil.GRAVEL, tr("Pedregulho")) { onSoil(OutletSoil.GRAVEL) }
                PlaceChip(soil == OutletSoil.ROCK, tr("Rocha")) { onSoil(OutletSoil.ROCK) }
            }
        }
        protection?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        subsurface?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (onPhase != null) {
            Text(tr("Fase"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChoiceRow {
                PlaceChip(phase <= 0, tr("Sem fase")) { onPhase(0) }
                for (n in 1..4) PlaceChip(phase == n, n.toString()) { onPhase(n) }
            }
        }
        if (onPhaseThrough != null) {
            Text(tr("Ver até a fase"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChoiceRow {
                PlaceChip(phaseThrough <= 0, tr("Todas")) { onPhaseThrough(0) }
                for (n in 1..4) PlaceChip(phaseThrough == n, n.toString()) { onPhaseThrough(n) }
            }
        }
        ChoiceRow {
            PlaceChip(false, tr("Editar")) { onEdit() }
            PlaceChip(moving, tr("Mover")) { onMove() }
            PlaceChip(false, tr("Apagar")) { onDelete() }
            if (canUndo) PlaceChip(false, tr("Desfazer")) { onUndo() }
            PlaceChip(false, tr("Fechar")) { onClose() }
        }
    }
}

@Composable
private fun BasinLink(onLink: (String, String) -> Unit) {
    var area by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("10") }
    NumberField(tr("Área da bacia (m²)"), area) { area = it }
    NumberField(tr("Tempo de concentração (min)"), minutes) { minutes = it }
    ChoiceRow {
        PlaceChip(true, tr("Ligar bacia")) { onLink(area, minutes) }
    }
}

@Composable
private fun TakeoffLines(title: String, row: DeviceTakeoff) {
    SpecHeader(title)
    if (row.length > 0.05) SpecRow(tr("Comprimento"), StationFormat.length(row.length, 2))
    else SpecRow(tr("Pontual"), "1")
    if (row.concrete > 0.0) SpecRow(tr("Concreto"), StationFormat.volume(row.concrete, 3))
    val dig = row.earthMoved()
    if (dig > 0.0) SpecRow(tr("Escavação"), StationFormat.volume(dig, 3))
    if (row.backfill > 0.0) SpecRow(tr("Reaterro"), StationFormat.volume(row.backfill, 3))
    if (row.bedding > 0.0) SpecRow(tr("Berço"), StationFormat.volume(row.bedding, 3))
    if (row.spoil > 0.0) SpecRow(tr("Bota-fora"), StationFormat.volume(row.spoil, 3))
    if (row.fill > 0.0) SpecRow(tr("Aterro"), StationFormat.volume(row.fill, 3))
    if (row.formworkKnown) SpecRow(tr("Formas"), StationFormat.area(row.formwork, 3))
    if (row.length > 0.05 && dig > 0.0) {
        SpecRow(tr("Escavação por metro"), StationFormat.volume(dig / row.length, 3))
    }
    if (row.shoring) {
        val depth = if (row.cutDepth.isFinite()) StationFormat.length(row.cutDepth, 2) else tr("sim")
        SpecRow(tr("Escoramento acima de 1,25 m"), depth)
    }
    if (row.tooDeep && row.cutDepth.isFinite()) {
        FactLine(tr("A vala passa de {0}: prever bombeamento ou queda.", StationFormat.length(row.cutDepth, 2)))
    }
}

@Composable
private fun HydraulicBlock(
    profile: DeviceProfile,
    run: DrainAlignment?,
    section: DrainCrossSection?,
    connected: List<String>,
    onRename: ((String) -> Unit)?,
    onReverse: (() -> Unit)?,
    onExport: ((String) -> Unit)?,
    onOpenProfile: (() -> Unit)?,
    onOpenSection: (() -> Unit)?,
) {
    val grade = profile.hydraulic
    Text(tr("Perfil hidráulico"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        tr(
            "Montante {0} · jusante {1}",
            if (grade.invertUpstream.isFinite()) StationFormat.length(grade.invertUpstream, 3) else "—",
            if (grade.invertDownstream.isFinite()) StationFormat.length(grade.invertDownstream, 3) else "—",
        ),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (grade.actualSlope.isFinite()) {
        Text(tr("Declividade {0} %", StationFormat.decimal(grade.actualSlope * 100.0, 2)), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (grade.minSlope.isFinite()) {
        Text(tr("Mínima do trecho {0} %", StationFormat.decimal(grade.minSlope * 100.0, 2)), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (grade.offSurface) {
        Text(tr("Fora da superfície: o trecho foi interpolado. Confira o terreno."), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    ProfileChart(grade.samples.map { it.distance to it.terrain to it.invert })
    if (grade.tooDeep && grade.maxCut.isFinite()) {
        Text(tr("A vala passa de {0}: prever bombeamento ou queda.", StationFormat.length(grade.maxCut, 2)), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    run?.let { alignment ->
        Text(alignment.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onOpenProfile != null || onOpenSection != null) {
            ChoiceRow {
                if (onOpenProfile != null) PlaceChip(false, tr("Perfil")) { onOpenProfile() }
                if (onOpenSection != null) PlaceChip(false, tr("Seção")) { onOpenSection() }
            }
        }
        if (onRename != null) {
            var name by remember(alignment.id) { mutableStateOf(alignment.name) }
            OutlinedTextField(
                name, { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Nome do eixo"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
            )
            PlaceChip(false, tr("Nome do eixo")) { onRename(name) }
        }
        if (onReverse != null) PlaceChip(false, tr("Inverter sentido")) { onReverse() }
    }
    section?.let { cut ->
        Text(tr("Seção na estaca {0}", StationFormat.decimal(cut.station, 1)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        SectionChart(cut)
    }
    if (connected.isNotEmpty()) {
        Text(tr("Conectado a {0}", connected.joinToString(", ")), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (onExport != null) {
        ChoiceRow {
            PlaceChip(false, tr("CSV")) { onExport("csv") }
            PlaceChip(false, tr("PDF")) { onExport("pdf") }
            PlaceChip(false, tr("LandXML")) { onExport("xml") }
            PlaceChip(false, tr("IFC")) { onExport("ifc") }
        }
    }
}

@Composable
private fun ProfileChart(samples: List<Pair<Pair<Double, Double>, Double>>) {
    if (samples.size < 2) return
    val terrainColor = MaterialTheme.colorScheme.tertiary
    val invertColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val ds = samples.map { it.first.first }
        val zs = samples.flatMap { listOf(it.first.second, it.second) }
        val d0 = ds.min(); val d1 = ds.max()
        val z0 = zs.min(); val z1 = zs.max()
        val spanD = (d1 - d0).takeIf { it > 1e-6 } ?: 1.0
        val spanZ = (z1 - z0).takeIf { it > 1e-6 } ?: 1.0
        fun at(distance: Double, z: Double) = Offset(
            ((distance - d0) / spanD * size.width).toFloat(),
            (size.height - (z - z0) / spanZ * size.height).toFloat(),
        )
        fun stroke(color: androidx.compose.ui.graphics.Color, pick: (Triple<Double, Double, Double>) -> Double) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val point = at(sample.first.first, pick(Triple(sample.first.first, sample.first.second, sample.second)))
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        stroke(terrainColor) { it.second }
        stroke(invertColor) { it.third }
    }
}

@Composable
private fun SectionChart(cut: DrainCrossSection) {
    val ground = MaterialTheme.colorScheme.tertiary
    val device = MaterialTheme.colorScheme.primary
    val trench = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val rings = listOf(cut.terrain, cut.trench, cut.device)
        val points = rings.flatten()
        if (points.isEmpty()) return@Canvas
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = (maxX - minX).takeIf { it > 1e-6 } ?: 1.0
        val spanY = (maxY - minY).takeIf { it > 1e-6 } ?: 1.0
        fun at(x: Double, y: Double) = Offset(
            ((x - minX) / spanX * size.width).toFloat(),
            (size.height - (y - minY) / spanY * size.height).toFloat(),
        )
        fun drawRing(ring: List<com.appdwg.core.drainage.SectionPoint>, color: androidx.compose.ui.graphics.Color, close: Boolean) {
            if (ring.size < 2) return
            val path = Path()
            val first = at(ring.first().x, ring.first().y)
            path.moveTo(first.x, first.y)
            for (point in ring.drop(1)) {
                val next = at(point.x, point.y)
                path.lineTo(next.x, next.y)
            }
            if (close) path.close()
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        drawRing(cut.terrain, ground, close = false)
        drawRing(cut.trench, trench, close = true)
        drawRing(cut.device, device, close = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    ActionWrap(content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionWrap(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun PlaceChip(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    GButton(label, onClick, primary = selected, outlined = !selected, enabled = enabled)
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value, onValue,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
    )
}
