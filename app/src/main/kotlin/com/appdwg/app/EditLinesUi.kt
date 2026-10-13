package com.appdwg.app

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
import androidx.compose.material3.TextButton
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
enum class LineOp(val label: String) {
    MOVE("Mover"), COPY("Copiar"), OFFSET("Offset"), DZ("Deslocar na vertical (ΔZ)"),
    DRAPE("Projetar na superfície"), FLATTEN("Achatar numa cota"), ROTATE("Girar"), MIRROR("Espelhar"),
    REVERSE("Inverter sentido"), JOIN("Unir"), EXPLODE("Explodir"), DIVIDE("Dividir"), LAYER("Trocar camada/cor"), DELETE("Apagar"),
}

/** How a move or copy is given. */
enum class MoveBy(val label: String) { DELTA("ΔX / ΔY"), POLAR("Distância + azimute"), POINTS("Ponto a ponto") }

/** What the plan is picking for the line editor. */
enum class EditPick { SELECT, WINDOW, FROM_TO, CENTER, AXIS }

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
    var offsets by mutableStateOf("3,5")
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
    var preview by mutableStateOf<List<Polyline>>(emptyList())
    var message by mutableStateOf<String?>(null)
}

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

/**
 * Result of the chosen operation on the selected lines: new lines and whether they replace the
 * originals; or a reason why not.
 */
fun runLineOp(e: EditState, vm: ProjectViewModel, project: LandXmlProject): Triple<List<Polyline>?, Boolean, String?> {
    val lines = e.selected.mapNotNull { vm.shownLine(it) }
    if (lines.isEmpty()) return Triple(null, false, "Selecione linhas tocando na planta ou por janela.")
    val layer = e.layer.trim().ifEmpty { "_EDICAO" }
    fun bad(what: String) = Triple<List<Polyline>?, Boolean, String?>(null, false, "$what inválido.")
    return when (e.op) {
        LineOp.MOVE, LineOp.COPY -> {
            val d = when (e.moveBy) {
                MoveBy.DELTA -> Point2(num(e.dx) ?: return bad("ΔX"), num(e.dy) ?: return bad("ΔY"))
                MoveBy.POLAR -> LineOps.polar(num(e.distance) ?: return bad("Distância"), num(e.azimuth) ?: return bad("Azimute"))
                MoveBy.POINTS -> if (e.picked.size >= 2) Point2(e.picked[1].x - e.picked[0].x, e.picked[1].y - e.picked[0].y)
                    else return Triple(null, false, "Toque o ponto de origem e o de destino.")
            }
            val dz = num(e.dz) ?: return bad("ΔZ")
            if (e.op == LineOp.MOVE) Triple(lines.map { LineOps.move(it, d.x, d.y, dz) }, true, null)
            else {
                val n = e.count.trim().toIntOrNull()?.takeIf { it in 1..200 } ?: return bad("Número de cópias")
                Triple(lines.flatMap { LineOps.copies(it, d.x, d.y, dz, n, layer) }, false, null)
            }
        }
        LineOp.OFFSET -> {
            val ds = com.appdwg.core.PointGen.parseOffsets(e.offsets) ?: return bad("Lista de offsets")
            val dz = num(e.dz) ?: return bad("ΔZ")
            val g = num(e.grade) ?: return bad("Rampa")
            // H:V ratio becomes a percent grade (1,5:1 falls 66,7 %).
            val pct = if (e.gradeAsRatio) (if (g == 0.0) 0.0 else -100 / g) else g
            val signed = ds.flatMap { d -> when (e.side) { 1 -> listOf(abs(d)); -1 -> listOf(-abs(d)); else -> listOf(abs(d), -abs(d)) } }.distinct()
            Triple(lines.flatMap { l -> signed.map { d -> LineOps.offset(l, d, layer, dz, pct) } }, false, null)
        }
        LineOp.DZ -> Triple(lines.map { LineOps.move(it, 0.0, 0.0, num(e.dz) ?: return bad("ΔZ")) }, true, null)
        LineOp.DRAPE -> {
            val s = project.surfaces.firstOrNull { it.name == e.surface } ?: project.surfaces.firstOrNull()
                ?: return Triple(null, false, "Sem superfície para projetar.")
            val step = num(e.step)?.takeIf { it > 0 } ?: return bad("Espaçamento")
            Triple(lines.map { LineOps.drape(it, s, step) }, true, null)
        }
        LineOp.FLATTEN -> Triple(lines.map { LineOps.flatten(it, num(e.z) ?: return bad("Cota")) }, true, null)
        LineOp.ROTATE -> {
            val a = num(e.angle) ?: return bad("Ângulo")
            val c = e.picked.firstOrNull() ?: lines.first().let { l -> Point2(l.xs.average(), l.ys.average()) }
            Triple(lines.map { LineOps.rotate(it, c, a) }, true, null)
        }
        LineOp.MIRROR -> {
            if (e.picked.size < 2) return Triple(null, false, "Toque dois pontos do eixo de espelhamento.")
            Triple(lines.map { LineOps.mirror(it, e.picked[0], e.picked[1], if (e.keepOriginal) layer else it.layer) }, !e.keepOriginal, null)
        }
        LineOp.REVERSE -> Triple(lines.map { LineOps.reverse(it) }, true, null)
        LineOp.JOIN -> Triple(LineOps.join(lines), true, null)
        LineOp.EXPLODE -> Triple(lines.flatMap { LineOps.explode(it) }, true, null)
        LineOp.DIVIDE -> {
            val every = num(e.divideEvery)
            val parts = e.divideParts.trim().toIntOrNull()
            if (every == null && (parts == null || parts < 1)) return bad("Divisão")
            Triple(lines.flatMap { if (every != null && every > 0) LineOps.divide(it, every = every) else LineOps.divide(it, parts = parts) }, true, null)
        }
        LineOp.LAYER -> Triple(lines.map { LineOps.relayer(it, layer, e.color) }, true, null)
        LineOp.DELETE -> Triple(emptyList(), true, null)
    }
}

/** Bottom bar of the line editor: selection, properties, undo/redo and the operation dialog. */
@Composable
fun EditLinesBar(e: EditState, vm: ProjectViewModel, onOperation: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    Card(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val one = e.selected.singleOrNull()?.let { vm.shownLine(it) }
            Text(when (e.pick) {
                EditPick.WINDOW -> if (e.windowCorner == null) "Toque o 1º canto da janela." else "Toque o canto oposto da janela."
                EditPick.FROM_TO -> "Toque o ponto de origem e depois o de destino (${e.picked.size}/2)."
                EditPick.CENTER -> "Toque o centro da rotação."
                EditPick.AXIS -> "Toque dois pontos do eixo de espelhamento (${e.picked.size}/2)."
                EditPick.SELECT -> "${e.selected.size} linha(s) selecionada(s). Toque linhas para marcar ou desmarcar."
            }, style = MaterialTheme.typography.bodyMedium)
            one?.let { l ->
                val i = LineOps.info(l)
                Text("Camada ${l.layer} · ${i.vertices} vértices · 2D ${StationFormat.decimal(i.length2d, 3)} m" +
                    (i.length3d?.let { " · 3D ${StationFormat.decimal(it, 3)} m" } ?: "") +
                    (i.zStart?.let { " · cotas ${StationFormat.decimal(it, 3)} → ${StationFormat.decimal(i.zEnd!!, 3)}" } ?: "") +
                    (i.gradePercent?.let { " · rampa ${StationFormat.decimal(it, 2)} %" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { e.pick = EditPick.WINDOW; e.windowCorner = null }) { Text("Janela") }
                TextButton(onClick = { e.selected = emptyList() }, enabled = e.selected.isNotEmpty()) { Text("Limpar seleção") }
                TextButton(onClick = onOperation, enabled = e.selected.isNotEmpty()) { Text("Operação…") }
                TextButton(onClick = { vm.undoLines(); e.selected = emptyList() }, enabled = vm.canUndo) { Text("Desfazer") }
                TextButton(onClick = { vm.redoLines(); e.selected = emptyList() }, enabled = vm.canRedo) { Text("Refazer") }
                TextButton(onClick = onDone) { Text("Concluir") }
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
                TextButton(onClick = {
                    val (res, _, why) = runLineOp(e, vm, project)
                    e.preview = res.orEmpty(); e.message = why ?: "${res!!.size} linha(s) resultante(s) em ciano na planta."
                }) { Text("Pré-visualizar") }
                TextButton(onClick = {
                    val (res, replace, why) = runLineOp(e, vm, project)
                    if (res == null) { e.message = why; return@TextButton }
                    vm.editLines(e.selected, res, replace)
                    e.selected = emptyList(); e.preview = emptyList(); e.picked = emptyList(); e.message = null
                    onDismiss()
                }) { Text("Aplicar") }
            }
        },
        dismissButton = { TextButton(onClick = { e.preview = emptyList(); onDismiss() }) { Text("Fechar") } },
        title = { Text("Editar ${e.selected.size} linha(s)") },
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
                                MoveBy.DELTA -> { field(e.dx, { e.dx = it }, "ΔX (m)"); field(e.dy, { e.dy = it }, "ΔY (m)") }
                                MoveBy.POLAR -> { field(e.distance, { e.distance = it }, "Distância (m)"); field(e.azimuth, { e.azimuth = it }, "Azimute (°)") }
                                MoveBy.POINTS -> OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.FROM_TO) }) {
                                    Text(if (e.picked.size >= 2) "Pontos escolhidos (trocar)" else "Tocar origem e destino")
                                }
                            }
                            field(e.dz, { e.dz = it }, "ΔZ (m)")
                            if (e.op == LineOp.COPY) { field(e.count, { e.count = it }, "Nº de cópias", 130); field(e.layer, { e.layer = it }, "Camada nova", 170) }
                        }
                    }
                    LineOp.OFFSET -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            field(e.offsets, { e.offsets = it }, "Distâncias (ex.: 3,5; 7)", 220)
                            listOf(1 to "Direita", -1 to "Esquerda", 0 to "Ambos").forEach { (v, l) -> FilterChip(selected = e.side == v, onClick = { e.side = v }, label = { Text(l) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            field(e.dz, { e.dz = it }, "ΔZ (m)")
                            field(e.grade, { e.grade = it }, if (e.gradeAsRatio) "Talude H:V (desce)" else "Rampa % (− desce)", 190)
                            Checkbox(checked = e.gradeAsRatio, onCheckedChange = { e.gradeAsRatio = it })
                            Text("em H:V", style = MaterialTheme.typography.bodySmall)
                            field(e.layer, { e.layer = it }, "Camada nova", 170)
                        }
                        Text("Offset com rampa gera crista ou pé de talude: a cota muda pela distância × rampa, mais o ΔZ.", style = MaterialTheme.typography.bodySmall)
                    }
                    LineOp.DZ -> field(e.dz, { e.dz = it }, "ΔZ (m)")
                    LineOp.DRAPE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.step, { e.step = it }, "Densificar a cada (m)", 190)
                        var open by remember { mutableStateOf(false) }
                        val names = project.surfaces.map { it.name }
                        Box {
                            TextButton(onClick = { open = true }) { Text(e.surface ?: names.firstOrNull() ?: "sem superfície") }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { e.surface = n; open = false }) }
                            }
                        }
                    }
                    LineOp.FLATTEN -> field(e.z, { e.z = it }, "Cota (m)")
                    LineOp.ROTATE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.angle, { e.angle = it }, "Ângulo (°, anti-horário)", 200)
                        OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.CENTER) }) {
                            Text(if (e.picked.isNotEmpty()) "Centro escolhido (trocar)" else "Tocar centro (padrão: centro da linha)")
                        }
                    }
                    LineOp.MIRROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { e.picked = emptyList(); onPick(EditPick.AXIS) }) {
                            Text(if (e.picked.size >= 2) "Eixo escolhido (trocar)" else "Tocar dois pontos do eixo")
                        }
                        Checkbox(checked = e.keepOriginal, onCheckedChange = { e.keepOriginal = it })
                        Text("Manter original", style = MaterialTheme.typography.bodySmall)
                        if (e.keepOriginal) field(e.layer, { e.layer = it }, "Camada nova", 170)
                    }
                    LineOp.DIVIDE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(e.divideParts, { e.divideParts = it }, "Em N partes", 140)
                        field(e.divideEvery, { e.divideEvery = it }, "Ou a cada (m)", 140)
                    }
                    LineOp.LAYER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        field(e.layer, { e.layer = it }, "Camada", 200)
                        listOf(null to "Da camada", 1 to "Vermelho", 2 to "Amarelo", 3 to "Verde", 4 to "Ciano", 5 to "Azul", 6 to "Magenta").forEach { (aci, l) ->
                            val argb = aci?.let { com.appdwg.core.DxfReader.aciColor(it) }
                            FilterChip(selected = e.color == argb, onClick = { e.color = argb }, label = { Text(l) })
                        }
                    }
                    LineOp.REVERSE, LineOp.JOIN, LineOp.EXPLODE, LineOp.DELETE -> Text(when (e.op) {
                        LineOp.JOIN -> "Une as linhas cujas pontas se tocam (até 1 cm)."
                        LineOp.EXPLODE -> "Cada segmento vira uma linha."
                        LineOp.DELETE -> "Apaga as linhas selecionadas (pode desfazer)."
                        else -> "Inverte o sentido das linhas."
                    }, style = MaterialTheme.typography.bodySmall)
                }
                e.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
