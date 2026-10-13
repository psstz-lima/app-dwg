package com.appdwg.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appdwg.core.GnssFix
import com.appdwg.core.NmeaParser
import com.appdwg.core.NtripClient
import com.appdwg.core.NtripSettings
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/** Where positions come from. */
enum class GnssSource(val label: String) { BLUETOOTH("Antena Bluetooth (NMEA)"), INTERNAL("GPS do aparelho"), REPLAY("Arquivo NMEA (simulação)") }

/**
 * Receiver connection: NMEA from a Bluetooth (SPP) antenna, the device's own GPS or a recorded file,
 * and an optional NTRIP client whose corrections go back to the antenna.
 */
class GnssManager(private val context: Context) {
    var fix by mutableStateOf<GnssFix?>(null)
        private set
    var status by mutableStateOf("Desconectado")
        private set
    var connected by mutableStateOf(false)
        private set

    fun permissionDenied() { status = "Permissão negada: libere em Configurações > Apps > Gromatic > Permissões" }
    var ntripStatus by mutableStateOf<String?>(null)
        private set
    var rtcmBytes by mutableStateOf(0L)
        private set

    private var btSocket: BluetoothSocket? = null
    @Volatile private var antennaOut: OutputStream? = null
    @Volatile private var running = false
    private var ntrip: NtripClient? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private val main = Handler(Looper.getMainLooper())
    private val parser = NmeaParser()

    private fun onLine(line: String) {
        parser.line(line)?.let { f -> main.post { fix = f } }
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> = runCatching {
        val bm = context.getSystemService(BluetoothManager::class.java)
        bm.adapter?.bondedDevices?.map { (it.name ?: it.address) to it.address }.orEmpty()
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun connectBluetooth(address: String) {
        disconnect()
        running = true
        status = "Conectando à antena…"
        thread(name = "gnss-bt") {
            try {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth indisponível")
                adapter.cancelDiscovery()
                val dev = adapter.getRemoteDevice(address)
                val sock = dev.createRfcommSocketToServiceRecord(SPP)
                sock.connect()
                btSocket = sock
                antennaOut = sock.outputStream
                main.post { connected = true; status = "Conectado a ${dev.name ?: address}" }
                sock.inputStream.bufferedReader(Charsets.ISO_8859_1).use { r ->
                    while (running) { val l = r.readLine() ?: break; onLine(l) }
                }
            } catch (e: Exception) {
                main.post { status = "Falha na antena: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post { connected = false }
                antennaOut = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectInternal() {
        disconnect()
        val lm = context.getSystemService(LocationManager::class.java)
        val l = OnNmeaMessageListener { msg, _ -> onLine(msg) }
        nmeaListener = l
        try {
            lm.addNmeaListener(l, main)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            connected = true
            status = "GPS do aparelho"
        } catch (e: Exception) {
            status = "GPS do aparelho indisponível: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private val locationListener = android.location.LocationListener { /* positions come from the NMEA listener */ }

    /** Replays a recorded NMEA file, one position a second, over and over. */
    fun replay(uri: Uri) {
        disconnect()
        running = true
        connected = true
        status = "Simulando com arquivo NMEA"
        thread(name = "gnss-replay") {
            try {
                val lines = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.ISO_8859_1).lines() } ?: error("arquivo indisponível")
                while (running) {
                    for (l in lines) {
                        if (!running) break
                        onLine(l)
                        if (l.contains("GGA")) Thread.sleep(1000)
                    }
                }
            } catch (e: Exception) {
                main.post { status = "Falha na simulação: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post { connected = false }
            }
        }
    }

    /** Corrections from an NTRIP caster, passed to the antenna when one is connected. */
    fun startNtrip(s: NtripSettings) {
        stopNtrip()
        ntripStatus = "Conectando a ${s.host}:${s.port}/${s.mountpoint}…"
        rtcmBytes = 0
        val client = NtripClient(s, { buf, n ->
            runCatching { antennaOut?.write(buf, 0, n) }
            main.post { rtcmBytes += n; if (ntripStatus?.startsWith("Recebendo") != true) ntripStatus = "Recebendo correções de ${s.mountpoint}" }
        }, { fix?.let { NmeaParser.gga(it.latitude, it.longitude, it.altitude ?: 0.0) } })
        ntrip = client
        thread(name = "ntrip") {
            try {
                client.run()
                main.post { if (ntrip === client) ntripStatus = "Caster encerrou a conexão" }
            } catch (e: Exception) {
                main.post { ntripStatus = "NTRIP: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    fun stopNtrip() {
        ntrip?.stop(); ntrip = null
        ntripStatus = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        running = false
        stopNtrip()
        runCatching { btSocket?.close() }; btSocket = null
        nmeaListener?.let { l ->
            runCatching {
                val lm = context.getSystemService(LocationManager::class.java)
                lm.removeNmeaListener(l); lm.removeUpdates(locationListener)
            }
        }
        nmeaListener = null
        connected = false
        fix = null
        status = "Desconectado"
    }

    companion object {
        /** Serial Port Profile, used by GNSS antennas over Bluetooth Classic. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
