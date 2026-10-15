package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appdwg.core.PointCode
import com.appdwg.core.PointCodes

/** Field codes table: what each point description draws (layer, colour, line or symbol, breakline). */
@Composable
fun CodesDialog(vm: ProjectViewModel, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val codes = vm.pointCodes
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        dismissButton = {
            Row {
                OutlinedButton(onClick = { vm.savePointCodes(codes + PointCode("NOVO", tr("Novo código"), listOf("NOVO"), "TOPO-NOVO", 0xFF607D8B.toInt())); editing = codes.size }) { Text(tr("Adicionar")) }
                OutlinedButton(onClick = { vm.savePointCodes(PointCodes.defaults) }) { Text(tr("Restaurar padrão")) }
            }
        },
        title = { Text(tr("Códigos de campo")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("A descrição de cada ponto escolhe o código (PE, PE1, \"pé talude 2\"…). Números separam linhas do mesmo código."), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                codes.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Color(c.color)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${c.code} · ${c.name}", maxLines = 1)
                            Text(listOfNotNull(c.layer, if (c.connect) tr("linha") else tr("ponto ({0})", c.symbol),
                                tr("obrigatória").takeIf { c.breakline && c.connect }, tr("fechada").takeIf { c.closed }).joinToString(" · ") +
                                " · " + c.aliases.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        },
    )
    editing?.let { i ->
        val c = codes.getOrNull(i) ?: return@let
        var code by remember(i) { mutableStateOf(c.code) }
        var name by remember(i) { mutableStateOf(c.name) }
        var aliases by remember(i) { mutableStateOf(c.aliases.joinToString(", ")) }
        var layer by remember(i) { mutableStateOf(c.layer) }
        var color by remember(i) { mutableStateOf(String.format("#%06X", c.color and 0xFFFFFF)) }
        var connect by remember(i) { mutableStateOf(c.connect) }
        var closed by remember(i) { mutableStateOf(c.closed) }
        var breakline by remember(i) { mutableStateOf(c.breakline) }
        var symbol by remember(i) { mutableStateOf(c.symbol) }
        val argb = color.trim().removePrefix("#").toLongOrNull(16)?.takeIf { color.trim().removePrefix("#").length == 6 }?.let { (0xFF000000 or it).toInt() }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                OutlinedButton(enabled = code.isNotBlank() && argb != null, onClick = {
                    val edited = PointCode(code.trim().uppercase(), name.trim(), aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(code.trim()) },
                        layer.trim().ifEmpty { "TOPO-" + code.trim().uppercase() }, argb!!, connect, closed, breakline, symbol)
                    vm.savePointCodes(codes.toMutableList().apply { set(i, edited) }); editing = null
                }) { Text(tr("OK")) }
            },
            dismissButton = {
                Row {
                    OutlinedButton(onClick = { vm.savePointCodes(codes.filterIndexed { k, _ -> k != i }); editing = null }) { Text(tr("Apagar")) }
                    OutlinedButton(onClick = { editing = null }) { Text(tr("Cancelar")) }
                }
            },
            title = { Text(tr("Código {0}", c.code)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it }, label = { Text(tr("Código")) }, singleLine = true, modifier = Modifier.width(110.dp))
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Nome")) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(aliases, { aliases = it }, label = { Text(tr("Descrições aceitas (separadas por vírgula)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(layer, { layer = it }, label = { Text(tr("Camada")) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(color, { color = it }, label = { Text(tr("Cor (#RRGGBB)")) }, singleLine = true, modifier = Modifier.width(140.dp))
                        Box(Modifier.size(24.dp).background(Color(argb ?: 0)))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(connect, { connect = true }, { Text(tr("Liga em linha")) })
                        FilterChip(!connect, { connect = false }, { Text(tr("Só ponto")) })
                    }
                    if (connect) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(breakline, { breakline = !breakline }, { Text(tr("Obrigatória da superfície")) })
                        FilterChip(closed, { closed = !closed }, { Text(tr("Fechada")) })
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("cruz" to tr("Cruz"), "poste" to tr("Poste"), "arvore" to tr("Árvore"), "caixa" to tr("Caixa")).forEach { (k, l) ->
                            FilterChip(symbol == k, { symbol = k }, { Text(l) })
                        }
                    }
                }
            },
        )
    }
}
