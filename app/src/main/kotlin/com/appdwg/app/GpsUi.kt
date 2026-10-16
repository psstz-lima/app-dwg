package com.appdwg.app

import com.appdwg.core.tr

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.appdwg.core.FixQuality
import com.appdwg.core.NtripSettings
import com.appdwg.core.PointInfo
import com.appdwg.core.StationFormat
/** Colour of the position marker by fix quality. */
fun fixColor(q: FixQuality?): Color = when (q) {
    FixQuality.RTK_FIXED -> Color(0xFF2E7D32)
    FixQuality.RTK_FLOAT -> Color(0xFFF9A825)
    FixQuality.DGPS -> Color(0xFF1565C0)
    null, FixQuality.NONE -> Color.Gray
    else -> Color(0xFFD32F2F)
}

/** Connection to the receiver and to the NTRIP caster; settings are remembered on the device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpsDialog(
    gnss: GnssManager,
    crs: com.appdwg.core.Crs,
    onCrs: () -> Unit,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    onNeedPermissions: (GnssSource, () -> Unit) -> Unit,
    onPickReplay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = LocalContext.current.getSharedPreferences("gnss", Context.MODE_PRIVATE)
    var source by remember { mutableStateOf(GnssSource.entries.getOrElse(prefs.getInt("source", 0)) { GnssSource.BLUETOOTH }) }
    var device by remember { mutableStateOf(prefs.getString("device", null)) }
    var devices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", "2101") ?: "2101") }
    var mount by remember { mutableStateOf(prefs.getString("mount", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    fun save() {
        prefs.edit {
            putInt("source", source.ordinal)
            putString("device", device)
            putString("host", host)
            putString("port", port)
            putString("mount", mount)
            putString("user", user)
            putString("pass", pass)
        }
    }
    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().then(Modifier.fillMaxWidth(0.94f)),
        confirmButton = { OutlinedButton(onClick = { save(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Fechar")) } },
        dismissButton = { if (gnss.connected) OutlinedButton(onClick = { gnss.disconnect() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Desconectar")) } },
        title = { Text("GPS / GNSS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GnssSource.entries.forEach { s -> FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                when (source) {
                    GnssSource.BLUETOOTH -> {
                        Text(tr("Pareie a antena (ex.: Hi-Target V200) nas configurações de Bluetooth do Android e escolha-a aqui. Ela deve enviar NMEA (GGA, GST)."),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onNeedPermissions(GnssSource.BLUETOOTH) { devices = gnss.bondedDevices() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Listar antenas pareadas")) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            devices.forEach { (name, addr) -> FilterChip(selected = device == addr, onClick = { device = addr }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp)) }
                        }
                        OutlinedButton(enabled = device != null, onClick = { save(); onNeedPermissions(GnssSource.BLUETOOTH) { gnss.connectBluetooth(device!!) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Conectar à antena")) }
                    }
                    GnssSource.INTERNAL -> OutlinedButton(onClick = { save(); onNeedPermissions(GnssSource.INTERNAL) { gnss.connectInternal() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Usar o GPS do aparelho")) }
                    GnssSource.REPLAY -> OutlinedButton(onClick = { save(); onPickReplay() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Escolher arquivo NMEA gravado")) }
                }
                Text(gnss.status, style = MaterialTheme.typography.bodyMedium)
                FitRow() {
                    Text(tr("Sistema: {0}", crs.name), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCrs, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Trocar…")) }
                    Checkbox(checked = follow, onCheckedChange = onFollow)
                    Text(tr("Seguir a posição na planta"))
                }
                HorizontalDivider()
                Text(tr("Correções NTRIP (RTK pela internet)"), style = MaterialTheme.typography.labelLarge)
                FitRow() {
                    OutlinedTextField(host, { host = it }, label = { Text(tr("Caster (endereço)")) }, singleLine = true, modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
                    OutlinedTextField(port, { port = it }, label = { Text(tr("Porta")) }, singleLine = true, modifier = Modifier.widthIn(max = 100.dp).fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(mount, { mount = it }, label = { Text("Mountpoint") }, singleLine = true, modifier = Modifier.widthIn(max = 160.dp).fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text(tr("Usuário")) }, singleLine = true, modifier = Modifier.widthIn(max = 150.dp).fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(tr("Senha")) }, singleLine = true, modifier = Modifier.widthIn(max = 150.dp).fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation())
                }
                FitRow() {
                    val p = port.toIntOrNull()
                    OutlinedButton(enabled = host.isNotBlank() && mount.isNotBlank() && p != null, onClick = {
                        save(); gnss.startNtrip(NtripSettings(host.trim(), p!!, mount.trim(), user, pass))
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Iniciar NTRIP")) }
                    if (gnss.ntripStatus != null) OutlinedButton(onClick = { gnss.stopNtrip() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Parar")) }
                    gnss.ntripStatus?.let { Text("$it · ${gnss.rtcmBytes / 1024} kB", style = MaterialTheme.typography.bodySmall) }
                }
                Text(tr("As correções vão para a antena pelo Bluetooth; a posição (GGA) volta ao caster a cada 10 s."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** Live receiver status: fix, satellites, precision, coordinates and station/offset on the nearest alignment. */
@Composable
fun GpsCard(gnss: GnssManager, east: Double, north: Double, height: Double?, calibrated: Boolean, info: PointInfo?, modifier: Modifier) {
    val f = gnss.fix ?: return
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● ", color = fixColor(f.quality))
                Text(f.quality.label + (f.satellites?.let { tr(" · {0} satélites", it) } ?: "") + (f.correctionAge?.let { tr(" · idade {0} s", StationFormat.decimal(it, 0)) } ?: ""),
                    style = MaterialTheme.typography.labelLarge)
            }
            val prec = listOfNotNull(f.sigmaH?.let { "H ${StationFormat.length(it, 3)}" }, f.sigmaV?.let { "V ${StationFormat.length(it, 3)}" })
            if (prec.isNotEmpty()) SpecRow(tr("Precisão"), prec.joinToString(" · "))
            else f.hdop?.let { SpecRow("HDOP", StationFormat.decimal(it, 1)) }
            SpecRow("E", StationFormat.lengthValue(east))
            SpecRow("N", StationFormat.lengthValue(north))
            height?.let { SpecRow(tr("Cota"), StationFormat.lengthValue(it)) }
            if (calibrated) FactLine(tr("calibrado"))
            info?.alignment?.takeIf { !it.stationOffset.outside }?.let { a ->
                SpecRow(a.name, StationFormat.estaca(a.stationOffset.station) + " · " + StationFormat.offset(a.stationOffset.offset))
            }
            info?.terrainElevation?.let { t ->
                SpecRow(tr("Terreno no ponto"), StationFormat.length(t, 3))
                height?.let { z ->
                    val d = z - t
                    SpecRow(tr("Antena"), (if (d >= 0) "+" else "−") + StationFormat.length(kotlin.math.abs(d), 3))
                }
            }
        }
    }
}
