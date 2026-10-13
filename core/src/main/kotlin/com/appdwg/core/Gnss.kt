package com.appdwg.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** GNSS solution quality, from the GGA fix indicator. */
enum class FixQuality(val label: String) {
    NONE("sem posição"), GPS("autônomo"), DGPS("DGPS"), PPS("PPS"), RTK_FIXED("RTK fixo"), RTK_FLOAT("RTK flutuante"), ESTIMATED("estimado"), MANUAL("manual"), SIMULATED("simulado");

    companion object {
        fun of(code: Int) = when (code) { 1 -> GPS; 2 -> DGPS; 3 -> PPS; 4 -> RTK_FIXED; 5 -> RTK_FLOAT; 6 -> ESTIMATED; 7 -> MANUAL; 8 -> SIMULATED; else -> NONE }
    }
}

/** Position from the receiver: latitude/longitude in degrees, altitude above mean sea level. */
data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val quality: FixQuality,
    val satellites: Int?,
    val hdop: Double?,
    /** Horizontal and vertical standard deviations (m) from GST, when sent. */
    val sigmaH: Double? = null,
    val sigmaV: Double? = null,
    /** Seconds since the last differential correction, from GGA. */
    val correctionAge: Double? = null,
    val time: String? = null,
    /** Geoid separation the receiver used (GGA field 11): ellipsoidal height = altitude + this. */
    val geoidSeparation: Double? = null,
) {
    /** Height above the ellipsoid, when the receiver sends both parts. */
    val ellipsoidalHeight: Double? get() = altitude?.let { a -> geoidSeparation?.let { a + it } }
}

/**
 * NMEA 0183 reader: GGA gives the position and fix, GST the precision. Sentences with a bad
 * checksum are dropped. Feed it lines as they arrive.
 */
class NmeaParser {
    private var sigmaH: Double? = null
    private var sigmaV: Double? = null

    /** Position after this line, or null when the line carried none. */
    fun line(raw: String): GnssFix? {
        val s = raw.trim()
        if (!s.startsWith("$") || !checksumOk(s)) return null
        val body = s.substring(1).substringBefore('*')
        val f = body.split(',')
        val type = f[0].takeLast(3)
        return when (type) {
            "GST" -> {
                val sLat = f.getOrNull(6)?.toDoubleOrNull(); val sLon = f.getOrNull(7)?.toDoubleOrNull()
                sigmaH = if (sLat != null && sLon != null) sqrt(sLat * sLat + sLon * sLon) else sigmaH
                sigmaV = f.getOrNull(8)?.toDoubleOrNull() ?: sigmaV
                null
            }
            "GGA" -> {
                val lat = angle(f.getOrNull(2), f.getOrNull(3)) ?: return null
                val lon = angle(f.getOrNull(4), f.getOrNull(5)) ?: return null
                val q = FixQuality.of(f.getOrNull(6)?.toIntOrNull() ?: 0)
                if (q == FixQuality.NONE) return null
                GnssFix(lat, lon, f.getOrNull(9)?.toDoubleOrNull(), q, f.getOrNull(7)?.toIntOrNull(), f.getOrNull(8)?.toDoubleOrNull(),
                    sigmaH, sigmaV, f.getOrNull(13)?.toDoubleOrNull(), f.getOrNull(1), f.getOrNull(11)?.toDoubleOrNull())
            }
            else -> null
        }
    }

    companion object {
        fun checksumOk(s: String): Boolean {
            val star = s.lastIndexOf('*')
            if (star < 0) return true // no checksum sent
            val want = s.substring(star + 1).take(2).toIntOrNull(16) ?: return false
            var c = 0
            for (i in 1 until star) c = c xor s[i].code
            return c == want
        }

        private fun angle(v: String?, hemi: String?): Double? {
            val x = v?.toDoubleOrNull() ?: return null
            val deg = floor(x / 100)
            val r = deg + (x - deg * 100) / 60
            return if (hemi == "S" || hemi == "W") -r else r
        }

        /** GGA sentence for [fix], as NTRIP casters want for network corrections. */
        fun gga(lat: Double, lon: Double, alt: Double, time: String = "000000.00"): String {
            fun dm(v: Double, w: Int): String {
                val a = abs(v); val d = floor(a); val m = (a - d) * 60
                return String.format(Locale.ROOT, "%0${w}d%08.5f", d.toInt(), m)
            }
            val body = "GPGGA,$time,${dm(lat, 2)},${if (lat < 0) "S" else "N"},${dm(lon, 3)},${if (lon < 0) "W" else "E"},1,12,1.0," +
                String.format(Locale.ROOT, "%.3f", alt) + ",M,0.0,M,,"
            var c = 0
            for (ch in body) c = c xor ch.code
            return "$" + body + "*" + String.format("%02X", c)
        }
    }
}

/** UTM on GRS80 from latitude and longitude (the inverse is [Utm.toGeographic]). */
object UtmForward {
    fun fromGeographic(latDeg: Double, lonDeg: Double, zone: Int): Point2 {
        val f = 1 / 298.257222101
        val a = 6378137.0
        val n = f / (2 - f)
        val aa = a / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
        val al = doubleArrayOf(n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16, 13 * n * n / 48 - 3 * n * n * n / 5, 61 * n * n * n / 240)
        val e = sqrt(f * (2 - f))
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg - (zone * 6 - 183))
        fun atanh(v: Double) = 0.5 * Math.log((1 + v) / (1 - v))
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xi = Math.atan2(t, cos(lam))
        val eta = atanh(sin(lam) / sqrt(1 + t * t))
        var x = eta; var y = xi
        for (j in 1..3) {
            x += al[j - 1] * cos(2 * j * xi) * Math.sinh(2 * j * eta)
            y += al[j - 1] * sin(2 * j * xi) * Math.cosh(2 * j * eta)
        }
        val north = 0.9996 * aa * y
        return Point2(500000 + 0.9996 * aa * x, if (latDeg < 0) 10_000_000 + north else north)
    }
}

/** NTRIP caster connection settings. */
data class NtripSettings(val host: String, val port: Int, val mountpoint: String, val user: String, val password: String)

/**
 * NTRIP client (versions 1 and 2): asks the caster for [settings]'s mountpoint, then passes every
 * correction byte to [onData]. [gga] is called every [ggaSeconds] for the position sent back to
 * the caster (null skips it). Blocks until [stop] or the connection ends; throws with a message in
 * Portuguese when the caster refuses.
 */
class NtripClient(
    private val settings: NtripSettings,
    private val onData: (ByteArray, Int) -> Unit,
    private val gga: () -> String?,
    private val ggaSeconds: Int = 10,
) {
    @Volatile private var socket: Socket? = null
    @Volatile var bytes: Long = 0
        private set

    fun stop() { runCatching { socket?.close() } }

    fun run() {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(settings.host, settings.port), 10_000)
        s.soTimeout = 30_000
        val out: OutputStream = s.getOutputStream()
        val inp: InputStream = s.getInputStream()
        val auth = Base64.getEncoder().encodeToString("${settings.user}:${settings.password}".toByteArray())
        val req = "GET /${settings.mountpoint} HTTP/1.1\r\nHost: ${settings.host}\r\nNtrip-Version: Ntrip/2.0\r\n" +
            "User-Agent: NTRIP Gromatic/1.0\r\nAuthorization: Basic $auth\r\nConnection: close\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1)); out.flush()
        // Response header: "ICY 200 OK" (v1) or "HTTP/1.1 200 OK" (v2).
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n") && !(head.startsWith("ICY 200") && head.endsWith("\r\n"))) {
            val c = inp.read()
            if (c < 0) break
            head.append(c.toChar())
            if (head.length > 8192) break
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        when {
            status.startsWith("ICY 200") || status.contains(" 200") -> {}
            status.contains("401") -> error("caster recusou usuário/senha (401)")
            status.startsWith("SOURCETABLE") || head.contains("text/plain") -> error("mountpoint \"${settings.mountpoint}\" não existe no caster")
            else -> error("resposta inesperada do caster: ${status.take(60)}")
        }
        var lastGga = 0L
        val buf = ByteArray(4096)
        try {
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastGga >= ggaSeconds * 1000L) {
                    gga()?.let { out.write((it + "\r\n").toByteArray(Charsets.ISO_8859_1)); out.flush() }
                    lastGga = now
                }
                val n = try { inp.read(buf) } catch (e: java.net.SocketTimeoutException) { continue }
                if (n < 0) break
                bytes += n
                onData(buf, n)
            }
        } catch (e: java.net.SocketException) {
            // Closed by stop().
        } finally {
            runCatching { s.close() }
        }
    }
}
