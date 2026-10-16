package com.appdwg.app

import com.appdwg.core.Point2
import com.appdwg.core.StationFormat
import com.appdwg.core.drainage.DeviceDef
import com.appdwg.core.drainage.DeviceSide
import com.appdwg.core.drainage.DeviceTakeoff
import com.appdwg.core.drainage.GradeMode
import com.appdwg.core.drainage.GradeSpec
import com.appdwg.core.drainage.Parameter
import com.appdwg.core.drainage.PathSource
import com.appdwg.core.drainage.PlacedDevice
import com.appdwg.core.tr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val grade: GradeMode = GradeMode.SURFACE_INVERT,
    val z0: String = "0",
    val z1: String = "0",
    val depth: String = "0",
    val slope: String = "0",
    val profile: String? = null,
    val rotation: String = "0",
    val skew: String = "0",
    val x: Double? = null,
    val y: Double? = null,
    val editingId: String? = null,
    val params: Map<String, String> = emptyMap(),
)

fun draftFor(device: DeviceDef): PlaceDraft = PlaceDraft(
    id = java.util.UUID.randomUUID().toString(),
    code = device.code,
    name = device.name,
    point = device.category == com.appdwg.core.drainage.DeviceCategory.POINT,
    params = device.parameters.mapNotNull { parameter ->
        parameter.number?.let { parameter.key to StationFormat.decimal(it, 3) }
    }.toMap(),
)

fun PlacedDevice.toDraft(name: String): PlaceDraft = when (this) {
    is PlacedDevice.Linear -> PlaceDraft(
        id, code, name, point = false, source, points = xs.zip(ys).map { (x, y) -> Point2(x, y) },
        alignment = alignment, side = side, offset = num(offset),
        start = start?.let(::num) ?: "", end = end?.let(::num) ?: "",
        grade = grade.mode, z0 = num(grade.z0), z1 = num(grade.z1), depth = num(grade.depth),
        slope = num(grade.slope * 100.0), profile = profile, editingId = id,
        params = params.mapValues { num(it.value) },
    )
    is PlacedDevice.Point -> PlaceDraft(
        id, code, name, point = true, alignment = alignment, grade = grade.mode,
        z0 = num(grade.z0), z1 = num(grade.z1), depth = num(grade.depth),
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
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Choice(draft.source == PathSource.POLYLINE, tr("Desenhar")) { onChange(draft.copy(source = PathSource.POLYLINE)) }
                Choice(draft.source == PathSource.ALIGNMENT, tr("Eixo")) { onChange(draft.copy(source = PathSource.ALIGNMENT)) }
                Choice(draft.source == PathSource.LINE, tr("Linha existente")) { onChange(draft.copy(source = PathSource.LINE)) }
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
                        for (name in alignments) Choice(draft.alignment == name, name) {
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
                Choice(draft.side == DeviceSide.LEFT, tr("Esquerda")) { onChange(draft.copy(side = DeviceSide.LEFT)) }
                Choice(draft.side == DeviceSide.AXIS, tr("No eixo")) { onChange(draft.copy(side = DeviceSide.AXIS)) }
                Choice(draft.side == DeviceSide.RIGHT, tr("Direita")) { onChange(draft.copy(side = DeviceSide.RIGHT)) }
            }
            NumberField(tr("Afastamento (m)"), draft.offset) { onChange(draft.copy(offset = it)) }
            NumberField(tr("Estaca inicial"), draft.start) { onChange(draft.copy(start = it)) }
            NumberField(tr("Estaca final"), draft.end) { onChange(draft.copy(end = it)) }
        }
        Text(tr("Cota"), style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            Choice(draft.grade == GradeMode.SURFACE_TOP, tr("Terreno (topo)")) { onChange(draft.copy(grade = GradeMode.SURFACE_TOP)) }
            Choice(draft.grade == GradeMode.SURFACE_INVERT, tr("Fundo no terreno")) { onChange(draft.copy(grade = GradeMode.SURFACE_INVERT)) }
            if (!draft.point) {
                Choice(draft.grade == GradeMode.FIXED, tr("Greide fixo")) { onChange(draft.copy(grade = GradeMode.FIXED)) }
                Choice(draft.grade == GradeMode.SLOPE, tr("Declividade constante")) { onChange(draft.copy(grade = GradeMode.SLOPE)) }
            }
            Choice(draft.grade == GradeMode.PROFILE, tr("Acompanhar greide")) { onChange(draft.copy(grade = GradeMode.PROFILE)) }
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
                    for (name in profiles) Choice(draft.profile == name, name) { onChange(draft.copy(profile = name)) }
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
            if (!draft.point && draft.points.isNotEmpty()) Choice(false, tr("Desfazer")) { onUndoPoint() }
            Choice(false, tr("Cancelar")) { onCancel() }
            Choice(true, tr("Concluir")) { onCommit() }
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
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(code, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (simplified) Text(tr("Geometria simplificada"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (moving) Text(tr("Toque o novo lugar."), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!isPro) {
            Text(tr("O Pro libera o lançamento e os cálculos."), maxLines = 3, overflow = TextOverflow.Ellipsis)
            ChoiceRow {
                Choice(true, tr("Conhecer o Gromatic Pro…")) { onPro() }
                Choice(false, tr("Fechar")) { onClose() }
            }
            return
        }
        row?.let { TakeoffLines(tr("Este dispositivo"), it) }
        total?.let { TakeoffLines(tr("Total do projeto"), it) }
        ChoiceRow {
            Choice(false, tr("Editar")) { onEdit() }
            Choice(moving, tr("Mover")) { onMove() }
            Choice(false, tr("Apagar")) { onDelete() }
            if (canUndo) Choice(false, tr("Desfazer")) { onUndo() }
            Choice(false, tr("Fechar")) { onClose() }
        }
    }
}

@Composable
private fun TakeoffLines(title: String, row: DeviceTakeoff) {
    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(tr("Comprimento: {0} m", StationFormat.decimal(row.length, 2)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(tr("Concreto: {0} m³", StationFormat.decimal(row.concrete, 3)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(tr("Escavação: {0} m³", StationFormat.decimal(row.excavation, 3)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (row.formworkKnown) Text(tr("Formas: {0} m²", StationFormat.decimal(row.formwork, 3)), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ChoiceRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    GButton(label, onClick, primary = selected, outlined = !selected)
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
