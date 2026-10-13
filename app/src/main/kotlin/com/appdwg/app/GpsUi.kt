package com.appdwg.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
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
import kotlin.math.abs

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
    zone: String,
    onZone: (String) -> Unit,
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
    fun save() = prefs.edit().putInt("source", source.ordinal).putString("device", device).putString("host", host).putString("port", port)
        .putString("mount", mount).putString("user", user).putString("pass", pass).apply()
    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text("Fechar") } },
        dismissButton = { if (gnss.connected) TextButton(onClick = { gnss.disconnect() }) { Text("Desconectar") } },
        title = { Text("GPS / GNSS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GnssSource.entries.forEach { s -> FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) }) }
                }
                when (source) {
                    GnssSource.BLUETOOTH -> {
                        Text("Pareie a antena (ex.: Hi-Target V200) nas configurações de Bluetooth do Android e escolha-a aqui. Ela deve enviar NMEA (GGA, GST).",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onNeedPermissions(GnssSource.BLUETOOTH) { devices = gnss.bondedDevices() } }) { Text("Listar antenas pareadas") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            devices.forEach { (name, addr) -> FilterChip(selected = device == addr, onClick = { device = addr }, label = { Text(name) }) }
                        }
                        OutlinedButton(enabled = device != null, onClick = { save(); onNeedPermissions(GnssSource.BLUETOOTH) { gnss.connectBluetooth(device!!) } }) { Text("Conectar à antena") }
                    }
                    GnssSource.INTERNAL -> OutlinedButton(onClick = { save(); onNeedPermissions(GnssSource.INTERNAL) { gnss.connectInternal() } }) { Text("Usar o GPS do aparelho") }
                    GnssSource.REPLAY -> OutlinedButton(onClick = { save(); onPickReplay() }) { Text("Escolher arquivo NMEA gravado") }
                }
                Text(gnss.status, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(zone, onZone, label = { Text("Zona UTM do projeto") }, singleLine = true, modifier = Modifier.width(190.dp))
                    Checkbox(checked = follow, onCheckedChange = onFollow)
                    Text("Seguir a posição na planta")
                }
                HorizontalDivider()
                Text("Correções NTRIP (RTK pela internet)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Caster (endereço)") }, singleLine = true, modifier = Modifier.width(240.dp))
                    OutlinedTextField(port, { port = it }, label = { Text("Porta") }, singleLine = true, modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(mount, { mount = it }, label = { Text("Mountpoint") }, singleLine = true, modifier = Modifier.width(160.dp))
                    OutlinedTextField(user, { user = it }, label = { Text("Usuário") }, singleLine = true, modifier = Modifier.width(150.dp))
                    OutlinedTextField(pass, { pass = it }, label = { Text("Senha") }, singleLine = true, modifier = Modifier.width(150.dp),
                        visualTransformation = PasswordVisualTransformation())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = port.toIntOrNull()
                    OutlinedButton(enabled = host.isNotBlank() && mount.isNotBlank() && p != null, onClick = {
                        save(); gnss.startNtrip(NtripSettings(host.trim(), p!!, mount.trim(), user, pass))
                    }) { Text("Iniciar NTRIP") }
                    if (gnss.ntripStatus != null) TextButton(onClick = { gnss.stopNtrip() }) { Text("Parar") }
                    gnss.ntripStatus?.let { Text("$it · ${gnss.rtcmBytes / 1024} kB", style = MaterialTheme.typography.bodySmall) }
                }
                Text("As correções vão para a antena pelo Bluetooth; a posição (GGA) volta ao caster a cada 10 s.", style = MaterialTheme.typography.bodySmall)
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
                Text(f.quality.label + (f.satellites?.let { " · $it satélites" } ?: "") + (f.correctionAge?.let { " · idade ${StationFormat.decimal(it, 0)} s" } ?: ""),
                    style = MaterialTheme.typography.labelLarge)
            }
            val prec = listOfNotNull(f.sigmaH?.let { "H ${StationFormat.decimal(it, 3)}" }, f.sigmaV?.let { "V ${StationFormat.decimal(it, 3)}" })
            Text(if (prec.isNotEmpty()) "Precisão " + prec.joinToString(" · ") + " m" else f.hdop?.let { "HDOP ${StationFormat.decimal(it, 1)}" } ?: "",
                style = MaterialTheme.typography.bodySmall)
            Text("E ${StationFormat.decimal(east, 3)} · N ${StationFormat.decimal(north, 3)}" + (height?.let { " · cota ${StationFormat.decimal(it, 3)}" } ?: "") +
                if (calibrated) " · calibrado" else "",
                style = MaterialTheme.typography.bodySmall)
            info?.alignment?.takeIf { !it.stationOffset.outside }?.let { a ->
                Text("${a.name}: ${StationFormat.estaca(a.stationOffset.station)} · ${StationFormat.offset(a.stationOffset.offset)}", style = MaterialTheme.typography.bodySmall)
            }
            info?.terrainElevation?.let { t ->
                Text("Terreno ${StationFormat.decimal(t, 3)}" + (height?.let { z -> val d = z - t; " · antena ${if (d >= 0) "+" else "−"}${StationFormat.decimal(abs(d), 3)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
