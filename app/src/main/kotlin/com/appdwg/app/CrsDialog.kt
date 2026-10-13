package com.appdwg.app

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.Crs
import com.appdwg.core.CrsCatalog
import com.appdwg.core.Datum
import com.appdwg.core.Ellipsoid
import com.appdwg.core.Helmert
import com.appdwg.core.Projection
import com.appdwg.core.StationFormat

/** Choice of the project's coordinate system: catalogue with search, LTM/RTM/local, and the user's own systems. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrsDialog(vm: ProjectViewModel, current: Crs, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Crs?>(null) }
    var mc by remember { mutableStateOf(StationFormat.decimal(((current.projection as? Projection.TransverseMercator)?.lon0 ?: -45.0), 1)) }
    var localZone by remember { mutableStateOf("23S") }
    val ed = editing
    if (ed != null) { CrsEditor(ed, onSave = { vm.saveCustomCrs(it); vm.chooseCrs(it); editing = null; onDismiss() }, onCancel = { editing = null }); return }
    fun use(c: Crs) { vm.chooseCrs(c); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f),
        title = { Text(stringResource(R.string.crs_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.crs_current, current.name + (current.epsg?.let { " · EPSG $it" } ?: "")), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.crs_change_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.crs_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (vm.customCrs.isNotEmpty()) {
                    Text(stringResource(R.string.crs_mine), style = MaterialTheme.typography.labelLarge)
                    for (c in CrsCatalog.search(query, vm.customCrs).filter { it.custom }) Row(verticalAlignment = Alignment.CenterVertically) {
                        CrsRow(c, c.id == current.id, Modifier.weight(1f)) { use(c) }
                        TextButton(onClick = { editing = c }) { Text(stringResource(R.string.crs_edit)) }
                        OutlinedButton(onClick = { vm.deleteCustomCrs(c) }) { Text(stringResource(R.string.crs_delete)) }
                    }
                }
                OutlinedButton(onClick = {
                    editing = current.copy(id = "custom_" + System.currentTimeMillis(), name = current.name + " (cópia)", epsg = null, custom = true, local = false)
                }) { Text(stringResource(R.string.crs_new)) }

                HorizontalDivider()
                Text(stringResource(R.string.crs_special), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mc, { mc = it }, label = { Text(stringResource(R.string.crs_mc)) }, singleLine = true, modifier = Modifier.width(170.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), isError = StationFormat.number(mc) == null)
                    OutlinedButton(enabled = StationFormat.number(mc) != null, onClick = { use(CrsCatalog.ltm(StationFormat.number(mc)!!)) }) { Text(stringResource(R.string.crs_ltm)) }
                    OutlinedButton(enabled = StationFormat.number(mc) != null, onClick = { use(CrsCatalog.rtm(StationFormat.number(mc)!!)) }) { Text(stringResource(R.string.crs_rtm)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val z = localZone.trim().dropLast(1).toIntOrNull()?.takeIf { it in 1..60 }
                    OutlinedTextField(localZone, { localZone = it.uppercase() }, label = { Text(stringResource(R.string.crs_zone)) }, singleLine = true,
                        modifier = Modifier.width(170.dp), isError = z == null)
                    OutlinedButton(enabled = z != null, onClick = { use(CrsCatalog.local(z!!, !localZone.trim().endsWith("N"))) }) { Text(stringResource(R.string.crs_local)) }
                }
                Text(stringResource(R.string.crs_local_hint), style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text(stringResource(R.string.crs_catalog), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.crs_ibge_note), style = MaterialTheme.typography.bodySmall)
                for (c in CrsCatalog.search(query).take(80)) CrsRow(c, c.id == current.id, Modifier.fillMaxWidth()) { use(c) }
            }
        },
    )
}

@Composable
private fun CrsRow(c: Crs, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (selected) "✓" else " ", fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
        Text(c.name, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        c.epsg?.let { Text("EPSG $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Form of a user's own system: datum (built-in or by parameters) and projection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrsEditor(start: Crs, onSave: (Crs) -> Unit, onCancel: () -> Unit) {
    fun f(v: Double, d: Int = 4) = StationFormat.decimal(v, d).trimEnd('0').trimEnd(',', '.')
    var name by remember { mutableStateOf(start.name) }
    var datumId by remember { mutableStateOf(Datum.all.firstOrNull { it == start.datum }?.id) }
    val d0 = start.datum; val h0 = d0.toSirgas
    var ell by remember { mutableStateOf(d0.ellipsoid) }
    var a by remember { mutableStateOf(f(d0.ellipsoid.a, 3)) }
    var invF by remember { mutableStateOf(f(d0.ellipsoid.invF, 9)) }
    val hp = remember { mutableStateOf(listOf(h0.dx, h0.dy, h0.dz, h0.rx, h0.ry, h0.rz, h0.ds).map { f(it, 6) }) }
    val tm0 = start.projection as? Projection.TransverseMercator
    var geographic by remember { mutableStateOf(tm0 == null) }
    val tp = remember { mutableStateOf(listOf(tm0?.lat0 ?: 0.0, tm0?.lon0 ?: -45.0, tm0?.k0 ?: 0.9996, tm0?.falseEasting ?: 500_000.0, tm0?.falseNorthing ?: 10_000_000.0).map { f(it, 9) }) }
    fun n(s: String) = StationFormat.number(s)
    val datum: Datum? = Datum.all.firstOrNull { it.id == datumId } ?: run {
        val aa = n(a); val ff = n(invF); val h = hp.value.map { n(it) }
        if (aa == null || ff == null || h.any { it == null }) null
        else Datum("custom_" + name.hashCode(), name, Ellipsoid(ell.name, aa, ff), Helmert(h[0]!!, h[1]!!, h[2]!!, h[3]!!, h[4]!!, h[5]!!, h[6]!!))
    }
    val t = tp.value.map { n(it) }
    val projection: Projection? = if (geographic) Projection.Geographic else if (t.any { it == null }) null
        else Projection.TransverseMercator(t[0]!!, t[1]!!, t[2]!!, t[3]!!, t[4]!!)
    val ok = name.isNotBlank() && datum != null && projection != null
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f),
        title = { Text(stringResource(R.string.crs_new)) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.crs_cancel)) } },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(start.copy(name = name.trim(), datum = datum!!, projection = projection!!, custom = true, local = false, epsg = null)) }) {
            Text(stringResource(R.string.crs_save)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.crs_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.crs_datum), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Datum.all.forEach { d -> FilterChip(datumId == d.id, { datumId = d.id }, { Text(d.name) }) }
                    FilterChip(datumId == null, { datumId = null }, { Text(stringResource(R.string.crs_datum_custom)) })
                }
                if (datumId == null) {
                    Text(stringResource(R.string.crs_ellipsoid), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ellipsoid.all.forEach { e -> FilterChip(ell == e, { ell = e; a = f(e.a, 3); invF = f(e.invF, 9) }, { Text(e.name) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(a, { a = it }, label = { Text(stringResource(R.string.crs_a)) }, singleLine = true, modifier = Modifier.width(200.dp), isError = n(a) == null)
                        OutlinedTextField(invF, { invF = it }, label = { Text(stringResource(R.string.crs_invf)) }, singleLine = true, modifier = Modifier.width(200.dp), isError = n(invF) == null)
                    }
                    Text(stringResource(R.string.crs_helmert), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val labels = listOf(stringResource(R.string.crs_t, "ΔX"), stringResource(R.string.crs_t, "ΔY"), stringResource(R.string.crs_t, "ΔZ"),
                            stringResource(R.string.crs_r, "rX"), stringResource(R.string.crs_r, "rY"), stringResource(R.string.crs_r, "rZ"), stringResource(R.string.crs_ds))
                        labels.forEachIndexed { i, l ->
                            OutlinedTextField(hp.value[i], { v -> hp.value = hp.value.toMutableList().also { it[i] = v } }, label = { Text(l) }, singleLine = true,
                                modifier = Modifier.width(140.dp), isError = n(hp.value[i]) == null)
                        }
                    }
                }
                Text(stringResource(R.string.crs_projection), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(geographic, { geographic = true }, { Text(stringResource(R.string.crs_geographic)) })
                    FilterChip(!geographic, { geographic = false }, { Text(stringResource(R.string.crs_tm)) })
                }
                if (!geographic) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(R.string.crs_lat0, R.string.crs_lon0, R.string.crs_k0, R.string.crs_fe, R.string.crs_fn).forEachIndexed { i, l ->
                        OutlinedTextField(tp.value[i], { v -> tp.value = tp.value.toMutableList().also { it[i] = v } }, label = { Text(stringResource(l)) },
                            singleLine = true, modifier = Modifier.width(180.dp), isError = n(tp.value[i]) == null)
                    }
                }
            }
        },
    )
}
