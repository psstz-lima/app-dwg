package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.LandXmlProject
import com.appdwg.core.LineOps
import com.appdwg.core.Point2
import com.appdwg.core.Polyline
import com.appdwg.core.StationFormat
import kotlin.math.abs

/** Line editing operations offered. */
enum class LineOp(private val labelPt: String) {
    MOVE("Mover"), COPY("Copiar"), OFFSET("Offset"), DZ("Deslocar na vertical (ΔZ)"),
    DRAPE("Projetar na superfície"), FLATTEN("Achatar numa cota"), ROTATE("Girar"), MIRROR("Espelhar"),
    REVERSE("Inverter sentido"), JOIN("Unir"), EXPLODE("Explodir"), DIVIDE("Dividir"), LAYER("Trocar camada/cor"), DELETE("Apagar");

    val label: String get() = tr(labelPt)
}

/** How a move or copy is given. */
enum class MoveBy(private val labelPt: String) { DELTA("ΔX / ΔY"), POLAR("Distância + azimute"), POINTS("Ponto a ponto"); val label: String get() = tr(labelPt) }

/** What the plan is picking for the line editor. */
enum class EditPick { SELECT, WINDOW, FROM_TO, CENTER, AXIS, DRAW }

/** Line editor settings and picks, alive while the editor is open. */
class EditState {
    var selected by mutableStateOf<List<Int>>(emptyList())
    var pick by mutableStateOf(EditPick.SELECT)
    var windowCorner by mutableStateOf<Point2?>(null)
    var picked by mutableStateOf<List<Point2>>(emptyList())
    var op by mutableStateOf(LineOp.OFFSET)
    var moveBy by mutableStateOf(MoveBy.DELTA)
    var dx by mutableStateOf("0"); var dy by mutableStateOf("0"); var dz by mutableStateOf("0")
    var distance by mutableStateOf("3,5"); var azimuth by mutableStateOf("0")
    var count by mutableStateOf("1")
    var offsets by mutableStateOf(StationFormat.decimal(3.5, 1))
    var side by mutableStateOf(1) // 1 right, −1 left, 0 both
    var grade by mutableStateOf("0")
    var gradeAsRatio by mutableStateOf(false)
    var step by mutableStateOf("5")
    var z by mutableStateOf("0")
    var angle by mutableStateOf("90")
    var keepOriginal by mutableStateOf(false)
    var divideParts by mutableStateOf("2"); var divideEvery by mutableStateOf("")
    var layer by mutableStateOf("_EDICAO")
    var color by mutableStateOf<Int?>(null)
    var surface by mutableStateOf<String?>(null)
    /** Elevation of a drawn line: 0 flat 2D, 1 on the surface, 2 at a fixed elevation. */
    var drawZ by mutableStateOf(0)
    var preview by mutableStateOf<List<Polyline>>(emptyList())
    var message by mutableStateOf<String?>(null)
}

private fun num(s: String) = StationFormat.number(s)
/** Lengths typed in the shown unit, in metres. */
private fun len(s: String) = StationFormat.parseLength(s)

/**
 * Result of the chosen operation on the selected lines: new lines and whether they replace the
 * originals; or a reason why not.
 */
fun runLineOp(e: EditState, vm: ProjectViewModel, project: LandXmlProject): Triple<List<Polyline>?, Boolean, String?> {
    val lines = e.selected.mapNotNull { vm.shownLine(it) }
    if (lines.isEmpty()) return Triple(null, false, tr("Selecione linhas tocando na planta ou por janela."))
    val layer = e.layer.trim().ifEmpty { "_EDICAO" }
    fun bad(what: String) = Triple<List<Polyline>?, Boolean, String?>(null, false, tr("{0} inválido.", what))
    return when (e.op) {
        LineOp.MOVE, LineOp.COPY -> {
            val d = when (e.moveBy) {
                MoveBy.DELTA -> Point2(len(e.dx) ?: return bad("ΔX"), len(e.dy) ?: return bad("ΔY"))
                MoveBy.POLAR -> LineOps.polar(len(e.distance) ?: return bad(tr("Distância")), num(e.azimuth) ?: return bad(tr("Azimute")))
                MoveBy.POINTS -> if (e.picked.size >= 2) Point2(e.picked[1].x - e.picked[0].x, e.picked[1].y - e.picked[0].y)
                    else return Triple(null, false, tr("Toque o ponto de origem e o de destino."))
            }
            val dz = len(e.dz) ?: return bad("ΔZ")
            if (e.op == LineOp.MOVE) Triple(lines.map { LineOps.move(it, d.x, d.y, dz) }, true, null)
            else {
                val n = e.count.trim().toIntOrNull()?.takeIf { it in 1..200 } ?: return bad(tr("Número de cópias"))
                Triple(lines.flatMap { LineOps.copies(it, d.x, d.y, dz, n, layer) }, false, null)
            }
        }
        LineOp.OFFSET -> {
            val ds = com.appdwg.core.PointGen.parseOffsets(e.offsets)?.map { StationFormat.fromLength(it) } ?: return bad(tr("Lista de offsets"))
            val dz = len(e.dz) ?: return bad("ΔZ")
            val g = num(e.grade) ?: return bad(tr("Rampa"))
            // H:V ratio becomes a percent grade (1,5:1 falls 66,7 %).
            val pct = if (e.gradeAsRatio) (if (g == 0.0) 0.0 else -100 / g) else g
            val signed = ds.flatMap { d -> when (e.side) { 1 -> listOf(abs(d)); -1 -> listOf(-abs(d)); else -> listOf(abs(d), -abs(d)) } }.distinct()
            Triple(lines.flatMap { l -> signed.map { d -> LineOps.offset(l, d, layer, dz, pct) } }, false, null)
        }
        LineOp.DZ -> Triple(lines.map { LineOps.move(it, 0.0, 0.0, len(e.dz) ?: return bad("ΔZ")) }, true, null)
        LineOp.DRAPE -> {
            val s = project.surfaces.firstOrNull { it.name == e.surface } ?: project.surfaces.firstOrNull()
                ?: return Triple(null, false, tr("Sem superfície para projetar."))
            val step = len(e.step)?.takeIf { it > 0 } ?: return bad(tr("Espaçamento"))
            Triple(lines.map { LineOps.drape(it, s, step) }, true, null)
        }
        LineOp.FLATTEN -> Triple(lines.map { LineOps.flatten(it, len(e.z) ?: return bad(tr("Cota"))) }, true, null)
        LineOp.ROTATE -> {
            val a = num(e.angle) ?: return bad(tr("Ângulo"))
            val c = e.picked.firstOrNull() ?: lines.first().let { l -> Point2(l.xs.average(), l.ys.average()) }
            Triple(lines.map { LineOps.rotate(it, c, a) }, true, null)
        }
        LineOp.MIRROR -> {
            if (e.picked.size < 2) return Triple(null, false, tr("Toque dois pontos do eixo de espelhamento."))
            Triple(lines.map { LineOps.mirror(it, e.picked[0], e.picked[1], if (e.keepOriginal) layer else it.layer) }, !e.keepOriginal, null)
        }
        LineOp.REVERSE -> Triple(lines.map { LineOps.reverse(it) }, true, null)
        LineOp.JOIN -> Triple(LineOps.join(lines), true, null)
        LineOp.EXPLODE -> Triple(lines.flatMap { LineOps.explode(it) }, true, null)
        LineOp.DIVIDE -> {
            val every = len(e.divideEvery)
            val parts = e.divideParts.trim().toIntOrNull()
            if (every == null && (parts == null || parts < 1)) return bad(tr("Divisão"))
            Triple(lines.flatMap { if (every != null && every > 0) LineOps.divide(it, every = every) else LineOps.divide(it, parts = parts) }, true, null)
        }
        LineOp.LAYER -> Triple(lines.map { LineOps.relayer(it, layer, e.color) }, true, null)
        LineOp.DELETE -> Triple(emptyList(), true, null)
    }
}

/** Bottom bar of the line editor: selection, properties, undo/redo and the operation dialog. */
@Composable
fun EditLinesBar(e: EditState, vm: ProjectViewModel, project: LandXmlProject, onOperation: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val one = e.selected.singleOrNull()?.let { vm.shownLine(it) }
            Text(when (e.pick) {
                EditPick.WINDOW -> if (e.windowCorner == null) tr("Toque o 1º canto da janela.") else tr("Toque o canto oposto da janela.")
                EditPick.FROM_TO -> tr("Toque o ponto de origem e depois o de destino ({0}/2).", e.picked.size)
                EditPick.CENTER -> tr("Toque o centro da rotação.")
                EditPick.AXIS -> tr("Toque dois pontos do eixo de espelhamento ({0}/2).", e.picked.size)
                EditPick.SELECT -> tr("{0} linha(s) selecionada(s). Toque linhas para marcar ou desmarcar.", e.selected.size)
                EditPick.DRAW -> tr("Nova linha: toque os vértices ({0}). O toque encaixa em pontos e vértices.", e.picked.size)
            }, style = MaterialTheme.typography.bodyMedium)
            if (e.pick == EditPick.DRAW) {
                val names = project.surfaces.map { it.name }
                fun create(closed: Boolean) {
                    val p = e.picked
                    val flat = Polyline(e.layer.ifBlank { "_EDICAO" }, DoubleArray(p.size) { p[it].x }, DoubleArray(p.size) { p[it].y }, closed, e.color)
                    val line = when (e.drawZ) {
                        1 -> {
                            val s = project.surfaces.firstOrNull { it.name == e.surface } ?: project.surfaces.firstOrNull()
                                ?: run { e.message = tr("Sem superfície para projetar."); return }
                            val step = len(e.step)?.takeIf { it > 0 } ?: run { e.message = tr("{0} inválido.", tr("Espaçamento")); return }
                            LineOps.drape(flat, s, step)
                        }
                        2 -> LineOps.flatten(flat, len(e.z) ?: run { e.message = tr("{0} inválido.", tr("Cota")); return })
                        else -> flat
                    }
                    e.message = null
                    vm.editLines(emptyList(), listOf(line), false)
                    e.picked = emptyList()
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = e.drawZ == 0, onClick = { e.drawZ = 0 }, label = { Text(tr("Linha 2D")) })
                    FilterChip(selected = e.drawZ == 1, onClick = { e.drawZ = 1 }, label = { Text(tr("3D sobre a superfície")) }, enabled = names.isNotEmpty())
                    FilterChip(selected = e.drawZ == 2, onClick = { e.drawZ = 2 }, label = { Text(tr("3D numa cota")) })
                    if (e.drawZ == 1) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { open = true }) { Text((e.surface ?: names.firstOrNull())?.substringAfterLast('|') ?: tr("sem superfície")) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                names.forEach { n -> DropdownMenuItem(text = { Text(n.substringAfterLast('|')) }, onClick = { e.surface = n; open = false }) }
                            }
                        }
                        OutlinedTextField(e.step, { e.step = it }, label = { Text(tr("Densificar a cada ({0})", StationFormat.lengthUnit)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(170.dp))
                    }
                    if (e.drawZ == 2) OutlinedTextField(e.z, { e.z = it }, label = { Text(tr("Cota ({0})", StationFormat.lengthUnit)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(140.dp))
                }
                e.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { e.picked = e.picked.dropLast(1) }, enabled = e.picked.isNotEmpty()) { Text(tr("Desfazer ponto")) }
                    OutlinedButton(onClick = { create(false) }, enabled = e.picked.size >= 2) { Text(tr("Criar linha")) }
                    OutlinedButton(onClick = { create(true) }, enabled = e.picked.size >= 3) { Text(tr("Criar polígono")) }
                    OutlinedButton(onClick = { vm.undoLines() }, enabled = vm.canUndo) { Text(tr("Desfazer")) }
                    OutlinedButton(onClick = { e.picked = emptyList(); e.pick = EditPick.SELECT }) { Text(tr("Selecionar linhas")) }
                    OutlinedButton(onClick = { e.picked = emptyList(); e.pick = EditPick.SELECT; onDone() }) { Text(tr("Concluir")) }
                }
                return@Column
            }
            one?.let { l ->
                val i = LineOps.info(l)
                Text(tr("Camada {0} · {1} vértices · 2D {2}", l.layer, i.vertices, StationFormat.length(i.length2d, 3)) +
                    (i.length3d?.let { " · 3D ${StationFormat.length(it, 3)}" } ?: "") +
                    (i.zStart?.let { " · cotas ${StationFormat.lengthValue(it)} → ${StationFormat.lengthValue(i.zEnd!!)}" } ?: "") +
                    (i.gradePercent?.let { " · rampa ${StationFormat.decimal(it, 2)} %" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { e.pick = EditPick.DRAW; e.picked = emptyList(); e.selected = emptyList() }) { Text(tr("Nova linha")) }
                OutlinedButton(onClick = { e.pick = EditPick.WINDOW; e.windowCorner = null }) { Text(tr("Janela")) }
                OutlinedButton(onClick = { e.selected = emptyList() }, enabled = e.selected.isNotEmpty()) { Text(tr("Limpar seleção")) }
                OutlinedButton(onClick = onOperation, enabled = e.selected.isNotEmpty()) { Text(tr("Operação…")) }
                OutlinedButton(onClick = { vm.undoLines(); e.selected = emptyList() }, enabled = vm.canUndo) { Text(tr("Desfazer")) }
                OutlinedButton(onClick = { vm.redoLines(); e.selected = emptyList() }, enabled = vm.canRedo) { Text(tr("Refazer")) }
                OutlinedButton(onClick = onDone) { Text(tr("Concluir")) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LineOpDialog(e: EditState, vm: ProjectViewModel, project: LandXmlProject, onPick: (EditPick) -> Unit, onDismiss: () -> Unit) {
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    @Composable
    fun field(value: String, onChange: (String) -> Unit, label: String, width: Int = 150) =
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.width(width.dp), keyboardOptions = text)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = {
            Row {
                OutlinedButton(onClick = {
                    val (res, _, why) = runLineOp(e, vm, project)
                    e.preview = res.orEmpty(); e.message = why ?: tr("{0} linha(s) resultante(s) em ciano na planta.", res!!.size)
                }) { Text(tr("Pré-visualizar")) }
                OutlinedButton(onClick = {
                    val (res, replace, why) = runLineOp(e, vm, project)
                    if (res == null) { e.message = why; return@OutlinedButton }
                    vm.editLines(e.selected, res, replace)
                    e.selected = emptyList(); e.preview = emptyList(); e.picked = emptyList(); e.message = null
                    onDismiss()
                }) { Text(tr("Aplicar")) }
            }
        },
        dismissButton = { OutlinedButton(onClick = { e.preview = emptyList(); onDismiss() }) { Text(tr("Fechar")) } },
        title = { Text(tr("Editar {0} linha(s)", e.selected.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // All operations visible at once, wrapping onto more lines.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineOp.entries.forEach { o -> FilterChip(selected = e.op == o, onClick = { e.op = o; e.preview = emptyList(); e.message = null }, label = { Text(o.label) }) }
                }
                when (e.op) {
                    LineOp.MOVE, LineOp.COPY -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MoveBy.entries.forEach { m -> FilterChip(selected = e.moveBy == m, onClick = { e.moveBy = m }, label = { Text(m.label) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (e.moveBy) {
                                MoveBy.DELTA -> { field(e.dx, { e.dx = it }, "ΔX (${StationFormat.lengthUnit})"); field(e.dy, { e.dy = it }, "ΔY (${StationFormat.lengthUnit})") }
                                MoveBy.POLAR -> { field(e.distance, { e.distance = it }, tr("Distância ({0})", StationFormat.lengthUnit)); field(e.azimuth, { e.azimuth = it }, tr("Azimute (°)")) }
                                MoveBy.POINTS -> OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.FROM_TO) }) {
                                    Text(if (e.picked.size >= 2) tr("Pontos escolhidos (trocar)") else tr("Tocar origem e destino"))
                                }
                            }
                            field(e.dz, { e.dz = it }, "ΔZ (${StationFormat.lengthUnit})")
                            if (e.op == LineOp.COPY) { field(e.count, { e.count = it }, tr("Nº de cópias"), 130); field(e.layer, { e.layer = it }, tr("Camada nova"), 170) }
                        }
                    }
                    LineOp.OFFSET -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            field(e.offsets, { e.offsets = it }, tr("Distâncias ({0}; ex.: {1}; 7)", StationFormat.lengthUnit, StationFormat.decimal(3.5, 1)), 220)
                            listOf(1 to tr("Direita"), -1 to tr("Esquerda"), 0 to tr("Ambos")).forEach { (v, l) -> FilterChip(selected = e.side == v, onClick = { e.side = v }, label = { Text(l) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            field(e.dz, { e.dz = it }, "ΔZ (${StationFormat.lengthUnit})")
                            field(e.grade, { e.grade = it }, if (e.gradeAsRatio) tr("Talude H:V (desce)") else tr("Rampa % (− desce)"), 190)
                            Checkbox(checked = e.gradeAsRatio, onCheckedChange = { e.gradeAsRatio = it })
                            Text(tr("em H:V"), style = MaterialTheme.typography.bodySmall)
                            field(e.layer, { e.layer = it }, tr("Camada nova"), 170)
                        }
                        Text(tr("Offset com rampa gera crista ou pé de talude: a cota muda pela distância × rampa, mais o ΔZ."), style = MaterialTheme.typography.bodySmall)
                    }
                    LineOp.DZ -> field(e.dz, { e.dz = it }, "ΔZ (${StationFormat.lengthUnit})")
                    LineOp.DRAPE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.step, { e.step = it }, tr("Densificar a cada ({0})", StationFormat.lengthUnit), 190)
                        var open by remember { mutableStateOf(false) }
                        val names = project.surfaces.map { it.name }
                        Box {
                            OutlinedButton(onClick = { open = true }) { Text(e.surface ?: names.firstOrNull() ?: tr("sem superfície")) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { e.surface = n; open = false }) }
                            }
                        }
                    }
                    LineOp.FLATTEN -> field(e.z, { e.z = it }, tr("Cota ({0})", StationFormat.lengthUnit))
                    LineOp.ROTATE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.angle, { e.angle = it }, tr("Ângulo (°, anti-horário)"), 200)
                        OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.CENTER) }) {
                            Text(if (e.picked.isNotEmpty()) tr("Centro escolhido (trocar)") else tr("Tocar centro (padrão: centro da linha)"))
                        }
                    }
                    LineOp.MIRROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.AXIS) }) {
                            Text(if (e.picked.size >= 2) tr("Eixo escolhido (trocar)") else tr("Tocar dois pontos do eixo"))
                        }
                        Checkbox(checked = e.keepOriginal, onCheckedChange = { e.keepOriginal = it })
                        Text(tr("Manter original"), style = MaterialTheme.typography.bodySmall)
                        if (e.keepOriginal) field(e.layer, { e.layer = it }, tr("Camada nova"), 170)
                    }
                    LineOp.DIVIDE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(e.divideParts, { e.divideParts = it }, tr("Em N partes"), 140)
                        field(e.divideEvery, { e.divideEvery = it }, tr("Ou a cada ({0})", StationFormat.lengthUnit), 140)
                    }
                    LineOp.LAYER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.layer, { e.layer = it }, tr("Camada"), 200)
                        listOf(null to tr("Da camada"), 1 to tr("Vermelho"), 2 to tr("Amarelo"), 3 to tr("Verde"), 4 to tr("Ciano"), 5 to tr("Azul"), 6 to tr("Magenta")).forEach { (aci, l) ->
                            val argb = aci?.let { com.appdwg.core.DxfReader.aciColor(it) }
                            FilterChip(selected = e.color == argb, onClick = { e.color = argb }, label = { Text(l) })
                        }
                    }
                    LineOp.REVERSE, LineOp.JOIN, LineOp.EXPLODE, LineOp.DELETE -> Text(when (e.op) {
                        LineOp.JOIN -> tr("Une as linhas cujas pontas se tocam (até 1 cm).")
                        LineOp.EXPLODE -> tr("Cada segmento vira uma linha.")
                        LineOp.DELETE -> tr("Apaga as linhas selecionadas (pode desfazer).")
                        else -> tr("Inverte o sentido das linhas.")
                    }, style = MaterialTheme.typography.bodySmall)
                }
                e.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
