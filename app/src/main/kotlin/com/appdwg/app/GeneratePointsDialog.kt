package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.Bounds
import com.appdwg.core.CogoPoint
import com.appdwg.core.LandXmlProject
import com.appdwg.core.LineRef
import com.appdwg.core.Point2
import com.appdwg.core.PointFile
import com.appdwg.core.PointGen
import com.appdwg.core.StationFormat
import kotlin.math.atan2

/** Where the elevation of generated points comes from. */
enum class ZSource(val label: String) { LINE("Da linha 3D"), SURFACE("Da superfície"), FIXED("Fixa") }

/** Where the grid is laid. */
enum class GridArea(val label: String) { DRAWN("Polígono desenhado"), LINE("Polilinha fechada"), VIEW("Área visível"), SURFACE("Limite da superfície") }

/** Settings of "generate points", kept while the user picks things on the plan. */
class GenState {
    var grid by mutableStateOf(false)
    var line by mutableStateOf<LineRef?>(null)
    var spacing by mutableStateOf("10")
    var includeVertices by mutableStateOf(true)
    var offsets by mutableStateOf("0")
    var from by mutableStateOf("")
    var to by mutableStateOf("")
    var zSource by mutableStateOf(ZSource.SURFACE)
    var surface by mutableStateOf<String?>(null)
    var fixedZ by mutableStateOf("0")
    var refHeight by mutableStateOf("0")
    var prefix by mutableStateOf("")
    var area by mutableStateOf(GridArea.DRAWN)
    var areaLine by mutableStateOf<LineRef?>(null)
    var drawnArea by mutableStateOf<List<Point2>>(emptyList())
    var dx by mutableStateOf("10")
    var dy by mutableStateOf("10")
    var rotation by mutableStateOf("0")
    var preview by mutableStateOf<List<CogoPoint>>(emptyList())
    var message by mutableStateOf<String?>(null)
}

/** What the dialog asks the plan to let the user pick. */
enum class GenPick { LINE, AREA_LINE, AREA_DRAW, ROTATION }

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

/** Builds the points the settings describe, numbered after the existing ones; null with a reason. */
fun generatePoints(g: GenState, project: LandXmlProject, existing: List<CogoPoint>, viewport: Bounds?): Pair<List<CogoPoint>?, String?> {
    val surface = project.surfaces.firstOrNull { it.name == g.surface } ?: project.surfaces.firstOrNull()
    val ref = num(g.refHeight) ?: return null to "Altura referencial inválida."
    val fixed = num(g.fixedZ)
    fun z(p: Point2, lineZ: Double?): Double? = when (g.zSource) {
        ZSource.LINE -> lineZ
        ZSource.SURFACE -> surface?.elevationAt(p)
        ZSource.FIXED -> fixed
    }?.plus(ref)
    var next = PointFile.nextNumber(existing).toLong()
    val out = ArrayList<CogoPoint>()
    if (!g.grid) {
        val lineRef = g.line ?: return null to "Toque numa linha ou eixo na planta."
        val path = PointGen.path(project, lineRef) ?: return null to "Linha não encontrada."
        val spacing = num(g.spacing)?.takeIf { it > 0 } ?: return null to "Espaçamento inválido."
        val offsets = PointGen.parseOffsets(g.offsets) ?: return null to "Offsets inválidos (ex.: -3,5; 0; 3,5)."
        if (g.zSource == ZSource.LINE && path.zs == null) return null to "A linha não é 3D: escolha cota da superfície ou fixa."
        val isAxis = lineRef is LineRef.Axis
        val from = if (isAxis) g.from.takeIf { it.isNotBlank() }?.let { StationFormat.parse(it) ?: return null to "Estaca inicial inválida." } else null
        val to = if (isAxis) g.to.takeIf { it.isNotBlank() }?.let { StationFormat.parse(it) ?: return null to "Estaca final inválida." } else null
        for (lp in PointGen.alongLine(path, spacing, offsets, g.includeVertices, from, to)) {
            val zz = z(lp.position, lp.lineZ) ?: Double.NaN
            val where = if (isAxis) "E ${StationFormat.estaca(lp.distance)}" + (if (lp.offset != 0.0) " " + StationFormat.offset(lp.offset) else "")
            else "D ${StationFormat.decimal(lp.distance)}" + (if (lp.offset != 0.0) " " + StationFormat.offset(lp.offset) else "")
            out += CogoPoint((next++).toString(), listOf(g.prefix.trim(), where).filter { it.isNotEmpty() }.joinToString(" "), lp.position.x, lp.position.y, zz)
        }
    } else {
        val dx = num(g.dx)?.takeIf { it > 0 } ?: return null to "Espaçamento X inválido."
        val dy = num(g.dy)?.takeIf { it > 0 } ?: return null to "Espaçamento Y inválido."
        val rot = num(g.rotation) ?: return null to "Rotação inválida."
        if (g.zSource == ZSource.LINE) return null to "Na malha, use cota da superfície ou fixa."
        val area: List<Point2> = when (g.area) {
            GridArea.DRAWN -> g.drawnArea.takeIf { it.size >= 3 } ?: return null to "Desenhe o polígono da malha."
            GridArea.LINE -> {
                val pth = g.areaLine?.let { PointGen.path(project, it) } ?: return null to "Toque numa polilinha fechada."
                List(pth.xs.size) { Point2(pth.xs[it], pth.ys[it]) }.takeIf { it.size >= 3 } ?: return null to "A polilinha precisa de 3 vértices."
            }
            GridArea.VIEW -> viewport?.let { b -> listOf(Point2(b.minX, b.minY), Point2(b.maxX, b.minY), Point2(b.maxX, b.maxY), Point2(b.minX, b.maxY)) }
                ?: return null to "Área visível indisponível."
            GridArea.SURFACE -> surface?.bounds?.let { b -> listOf(Point2(b.minX, b.minY), Point2(b.maxX, b.minY), Point2(b.maxX, b.maxY), Point2(b.minX, b.maxY)) }
                ?: return null to "Escolha uma superfície."
        }
        val pts = try { PointGen.grid(area, dx, dy, rot) } catch (e: IllegalArgumentException) { return null to e.message }
        for (p in pts) {
            // Only where the surface is, when it gives the elevation or is the limit.
            if ((g.zSource == ZSource.SURFACE || g.area == GridArea.SURFACE) && surface?.elevationAt(p) == null) continue
            out += CogoPoint((next++).toString(), g.prefix.trim(), p.x, p.y, z(p, null) ?: Double.NaN)
        }
    }
    if (out.isEmpty()) return null to "Nenhum ponto gerado com essas opções."
    return out to null
}

@Composable
fun GeneratePointsDialog(
    g: GenState,
    project: LandXmlProject,
    existing: List<CogoPoint>,
    viewport: Bounds?,
    onPick: (GenPick) -> Unit,
    onPreview: () -> Unit,
    onConfirm: (List<CogoPoint>) -> Unit,
    onDismiss: () -> Unit,
) {
    val text = KeyboardOptions(keyboardType = KeyboardType.Text)
    val path = g.line?.let { PointGen.path(project, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val (pts, why) = generatePoints(g, project, existing, viewport)
                    g.preview = pts.orEmpty(); g.message = why
                    if (pts != null) onPreview()
                }) { Text("Pré-visualizar") }
                TextButton(enabled = g.preview.isNotEmpty(), onClick = { onConfirm(g.preview); g.preview = emptyList() }) { Text("Criar ${g.preview.size} pontos") }
            }
        },
        dismissButton = { TextButton(onClick = { g.preview = emptyList(); onDismiss() }) { Text("Fechar") } },
        title = { Text("Gerar pontos (camada _POINTS)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !g.grid, onClick = { g.grid = false; g.preview = emptyList() }, label = { Text("Sobre linha") })
                    FilterChip(selected = g.grid, onClick = { g.grid = true; g.preview = emptyList(); if (g.zSource == ZSource.LINE) g.zSource = ZSource.SURFACE }, label = { Text("Malha") })
                }
                if (!g.grid) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPick(GenPick.LINE) }) { Text(if (g.line == null) "Tocar na linha" else "Trocar linha") }
                        Text(when (val l = g.line) {
                            null -> "nenhuma linha escolhida"
                            is LineRef.Axis -> "Eixo ${project.alignments.getOrNull(l.index)?.name}"
                            is LineRef.Drawing -> project.polylines.getOrNull(l.index)?.let { "Linha na camada ${it.layer}" + if (it.zs != null) " (3D)" else "" } ?: "?"
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(g.spacing, { g.spacing = it }, label = { Text("Distância entre pontos (m)") }, singleLine = true, modifier = Modifier.width(220.dp), keyboardOptions = text)
                        OutlinedTextField(g.offsets, { g.offsets = it }, label = { Text("Offsets (+D / −E), ex.: -3,5; 0; 3,5") }, singleLine = true, modifier = Modifier.width(330.dp), keyboardOptions = text)
                        Checkbox(checked = g.includeVertices, onCheckedChange = { g.includeVertices = it })
                        Text("Incluir vértices", style = MaterialTheme.typography.bodySmall)
                    }
                    if (g.line is LineRef.Axis) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(g.from, { g.from = it }, label = { Text("Estaca inicial (opcional)") }, singleLine = true, modifier = Modifier.width(220.dp))
                            OutlinedTextField(g.to, { g.to = it }, label = { Text("Estaca final (opcional)") }, singleLine = true, modifier = Modifier.width(220.dp))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GridArea.entries.forEach { a -> FilterChip(selected = g.area == a, onClick = { g.area = a }, label = { Text(a.label) }) }
                    }
                    when (g.area) {
                        GridArea.DRAWN -> OutlinedButton(onClick = { onPick(GenPick.AREA_DRAW) }) {
                            Text(if (g.drawnArea.size >= 3) "Redesenhar polígono (${g.drawnArea.size} vértices)" else "Desenhar polígono na planta")
                        }
                        GridArea.LINE -> OutlinedButton(onClick = { onPick(GenPick.AREA_LINE) }) {
                            Text(if (g.areaLine == null) "Tocar na polilinha fechada" else "Trocar polilinha")
                        }
                        else -> {}
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(g.dx, { g.dx = it }, label = { Text("Espaçamento X (m)") }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
                        OutlinedTextField(g.dy, { g.dy = it }, label = { Text("Espaçamento Y (m)") }, singleLine = true, modifier = Modifier.width(170.dp), keyboardOptions = text)
                        OutlinedTextField(g.rotation, { g.rotation = it }, label = { Text("Rotação (°)") }, singleLine = true, modifier = Modifier.width(130.dp), keyboardOptions = text)
                        TextButton(onClick = { onPick(GenPick.ROTATION) }) { Text("Alinhar a uma linha") }
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cota", Modifier.width(50.dp))
                    ZSource.entries.forEach { z ->
                        if (z == ZSource.LINE && (g.grid || path?.zs == null)) return@forEach
                        FilterChip(selected = g.zSource == z, onClick = { g.zSource = z }, label = { Text(z.label) })
                    }
                    if (g.zSource == ZSource.SURFACE || (g.grid && g.area == GridArea.SURFACE)) {
                        var open by remember { mutableStateOf(false) }
                        val names = project.surfaces.map { it.name }
                        Box {
                            TextButton(onClick = { open = true }) { Text(g.surface ?: names.firstOrNull() ?: "sem superfície", maxLines = 1) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { g.surface = n; open = false }) }
                            }
                        }
                    }
                    if (g.zSource == ZSource.FIXED) {
                        OutlinedTextField(g.fixedZ, { g.fixedZ = it }, label = { Text("Cota (m)") }, singleLine = true, modifier = Modifier.width(140.dp), keyboardOptions = text)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(g.refHeight, { g.refHeight = it }, label = { Text("Altura referencial (+/− m)") }, singleLine = true, modifier = Modifier.width(220.dp), keyboardOptions = text)
                    OutlinedTextField(g.prefix, { g.prefix = it }, label = { Text("Prefixo da descrição") }, singleLine = true, modifier = Modifier.width(220.dp))
                }
                Text("Numeração continua a partir do ponto ${PointFile.nextNumber(existing)}.", style = MaterialTheme.typography.bodySmall)
                g.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

/** Rotation in degrees (counterclockwise from east) of the first segment of [ref]. */
fun lineRotation(project: LandXmlProject, ref: LineRef): Double? {
    val p = PointGen.path(project, ref) ?: return null
    if (p.xs.size < 2) return null
    return Math.toDegrees(atan2(p.ys[1] - p.ys[0], p.xs[1] - p.xs[0]))
}
