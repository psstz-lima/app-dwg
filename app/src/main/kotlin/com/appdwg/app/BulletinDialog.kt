package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.MassHaul
import com.appdwg.core.StationFormat

/** Items of the bulletin taken from the road design: cut by haul band, lateral, fill, spoil, borrow. */
fun bulletinFromRoad(ctx: android.content.Context, vm: ProjectViewModel, swell: Double): List<BulletinItem> {
    val r = vm.road ?: return emptyList()
    val m = MassHaul.of(r.volumes, swell)
    return buildList {
        m.bands.forEach { b -> add(BulletinItem(ctx.getString(R.string.bm_item_cut, b.label), contracted = b.volume)) }
        if (m.lateral > 0) add(BulletinItem(ctx.getString(R.string.bm_item_lateral), contracted = m.lateral))
        if (r.totalFill > 0) add(BulletinItem(ctx.getString(R.string.bm_item_fill), contracted = r.totalFill))
        if (m.spoil > 0) add(BulletinItem(ctx.getString(R.string.bm_item_spoil), contracted = m.spoil))
        if (m.borrow > 0) add(BulletinItem(ctx.getString(R.string.bm_item_borrow), contracted = m.borrow))
    }
}

/** "1.234,56" or "1234.56"; with a comma, dots are thousands separators. */
private fun num(s: String): Double? {
    val t = s.trim()
    return (if (',' in t) t.replace(".", "").replace(',', '.') else t).toDoubleOrNull()?.takeIf { it.isFinite() }
}

@Composable
private fun NumField(value: Double, label: String, width: Dp, onValue: (Double) -> Unit) {
    // The text stays as typed; it is rewritten only when the value changes from outside (another button).
    var text by remember { mutableStateOf(StationFormat.decimal(value, 2)) }
    androidx.compose.runtime.LaunchedEffect(value) { if (num(text) != value) text = StationFormat.decimal(value, 2) }
    OutlinedTextField(text, { t -> text = t; num(t)?.let(onValue) }, label = { Text(label, maxLines = 1) }, singleLine = true,
        modifier = Modifier.width(width), isError = num(text) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulletinDialog(vm: ProjectViewModel, onPdf: () -> Unit, onCsv: () -> Unit, onDismiss: () -> Unit) {
    val v = vm.view ?: return
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    fun set(i: Int, item: BulletinItem) { v.bulletinItems = v.bulletinItems.toMutableList().also { it[i] = item } }
    fun money(x: Double) = StationFormat.decimal(x, 2)
    val items = v.bulletinItems

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.96f),
        title = { Text(stringResource(R.string.bm_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(v.bulletinNumber, { v.bulletinNumber = it }, label = { Text(stringResource(R.string.bm_number)) }, singleLine = true,
                        modifier = Modifier.width(140.dp))
                    OutlinedTextField(v.bulletinPeriod, { v.bulletinPeriod = it }, label = { Text(stringResource(R.string.bm_period)) }, singleLine = true,
                        modifier = Modifier.width(260.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = vm.road != null, onClick = {
                        // Keep prices and progress of items with the same description.
                        val old = items.associateBy { it.description }
                        v.bulletinItems = bulletinFromRoad(ctx, vm, v.roadParams.swell).map { n ->
                            old[n.description]?.copy(contracted = n.contracted) ?: n
                        } + items.filter { o -> o.description !in bulletinFromRoad(ctx, vm, v.roadParams.swell).map { it.description } }
                    }) { Text(stringResource(R.string.bm_from_road)) }
                    OutlinedButton(onClick = {
                        val p = vm.stageProgress?.periods?.lastOrNull()
                        if (p == null) note = ctx.getString(R.string.bm_stages_none) else {
                            note = null
                            // Cut of the period spread over the cut items by their contracted share; fill to the fill item.
                            val fillName = ctx.getString(R.string.bm_item_fill)
                            val cutItems = items.filter { it.description != fillName && it.unit == "m³" }
                            val cutTotal = cutItems.sumOf { it.contracted }
                            v.bulletinItems = items.map { it ->
                                when {
                                    it.description == fillName -> it.copy(period = p.fill)
                                    it in cutItems && cutTotal > 0 -> it.copy(period = p.cut * it.contracted / cutTotal)
                                    else -> it
                                }
                            }
                        }
                    }) { Text(stringResource(R.string.bm_from_stages)) }
                    OutlinedButton(onClick = { v.bulletinItems = items + BulletinItem("") }) { Text(stringResource(R.string.bm_add)) }
                    OutlinedButton(enabled = items.any { it.period != 0.0 }, onClick = {
                        v.bulletinItems = items.map { it.copy(previous = it.accumulated, period = 0.0) }
                        v.bulletinNumber = ((v.bulletinNumber.trim().toIntOrNull() ?: 0) + 1).toString()
                    }) { Text(stringResource(R.string.bm_close_period)) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (items.isEmpty()) Text(stringResource(R.string.bm_empty), style = MaterialTheme.typography.bodySmall)
                items.forEachIndexed { i, it ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(it.description, { d -> set(i, it.copy(description = d)) }, label = { Text(stringResource(R.string.bm_desc)) },
                                    singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(it.unit, { u -> set(i, it.copy(unit = u)) }, label = { Text(stringResource(R.string.bm_unit)) },
                                    singleLine = true, modifier = Modifier.width(80.dp), textStyle = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { v.bulletinItems = items.filterIndexed { j, _ -> j != i } }) { Text(stringResource(R.string.bm_remove)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                NumField(it.contracted, stringResource(R.string.bm_contracted), 150.dp) { x -> set(i, it.copy(contracted = x)) }
                                NumField(it.previous, stringResource(R.string.bm_previous), 150.dp) { x -> set(i, it.copy(previous = x)) }
                                NumField(it.period, stringResource(R.string.bm_this), 150.dp) { x -> set(i, it.copy(period = x)) }
                                NumField(it.price, stringResource(R.string.bm_price), 130.dp) { x -> set(i, it.copy(price = x)) }
                                Column {
                                    Text("${stringResource(R.string.bm_accum)} ${StationFormat.decimal(it.accumulated, 2)} · ${StationFormat.decimal(it.percent, 1)} %",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${stringResource(R.string.bm_value)} R$ ${money(it.periodValue)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    val acc = items.sumOf { it.accumulatedValue }; val con = items.sumOf { it.contractedValue }
                    Text(stringResource(R.string.bm_totals, money(items.sumOf { it.periodValue }), money(acc), money(con),
                        StationFormat.decimal(if (con > 0) acc / con * 100 else 0.0, 1)), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPdf) { Text(stringResource(R.string.bm_pdf)) }
                        OutlinedButton(onClick = onCsv) { Text(stringResource(R.string.bm_csv)) }
                    }
                    vm.exportMessage?.let { m -> Text(m, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
    )
}
