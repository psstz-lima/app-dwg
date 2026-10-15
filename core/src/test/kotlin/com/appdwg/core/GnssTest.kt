package com.appdwg.core

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GnssTest {
    private fun withChecksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "$" + body + "*" + String.format("%02X", c)
    }

    @Test
    fun ggaAndGstGivePositionFixAndPrecision() {
        val p = NmeaParser()
        assertEquals(null, p.line("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00")) // bad checksum
        p.line(withChecksum("GNGST,024603.00,3.2,6.6,4.7,47.3,0.012,0.016,0.025"))
        val f = p.line(withChecksum("GNGGA,123519.00,1955.1460,S,04356.2830,W,4,18,0.6,850.123,M,-5.2,M,1.0,0001"))!!
        assertEquals(FixQuality.RTK_FIXED, f.quality)
        assertEquals(-(19 + 55.146 / 60), f.latitude, 1e-9)
        assertEquals(-(43 + 56.283 / 60), f.longitude, 1e-9)
        assertEquals(850.123, f.altitude!!, 1e-9)
        assertEquals(0.02, f.sigmaH!!, 1e-9)
        assertEquals(0.025, f.sigmaV!!, 1e-9)
        assertEquals(18, f.satellites)
        assertNull(p.line(withChecksum("GPGGA,123519,,,,,0,00,,,M,,M,,")))
        assertEquals(FixQuality.RTK_FLOAT, NmeaParser().line(withChecksum("GPGGA,1,1955.1,S,04356.2,W,5,10,0.8,850,M,0,M,,"))!!.quality)
    }

    @Test
    fun ggaBuiltForTheCasterReadsBack() {
        val s = NmeaParser.gga(-19.9191, -43.9386, 850.0)
        assertTrue(NmeaParser.checksumOk(s))
        val f = NmeaParser().line(s)!!
        assertEquals(-19.9191, f.latitude, 1e-7)
        assertEquals(-43.9386, f.longitude, 1e-7)
    }

    @Test
    fun utmForwardRoundTrips() {
        val e = UtmForward.fromGeographic(-19.9191, -43.9386, 23)
        val (lat, lon) = Utm.toGeographic(e.x, e.y, 23, south = true)
        assertEquals(-19.9191, lat, 1e-8)
        assertEquals(-43.9386, lon, 1e-8)
    }

    @Test
    fun ntripClientAuthenticatesStreamsAndSendsGga() {
        val server = ServerSocket(0)
        var request = ""
        var ggaSeen = ""
        val caster = thread {
            server.accept().use { c ->
                val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val sb = StringBuilder()
                while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; sb.append(l).append('\n') }
                request = sb.toString()
                val out = c.getOutputStream()
                out.write("ICY 200 OK\r\n".toByteArray()); out.flush()
                ggaSeen = inp.readLine() ?: ""
                out.write(ByteArray(1000) { 0xD3.toByte() }); out.flush()
            }
        }
        var received = 0
        val client = NtripClient(NtripSettings("127.0.0.1", server.localPort, "BHZ1", "paulo", "segredo"), { _, n -> received += n },
            { NmeaParser.gga(-19.9, -43.9, 850.0) })
        client.run()
        caster.join(5000)
        server.close()
        assertTrue(request.startsWith("GET /BHZ1 HTTP/1.1"))
        assertTrue(request.contains("Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("paulo:segredo".toByteArray())))
        assertTrue(ggaSeen.startsWith("\$GPGGA"))
        assertEquals(1000, received)
    }

    @Test
    fun ntripRefusalExplainsWhy() {
        val server = ServerSocket(0)
        val t = thread { server.accept().use { c -> c.getInputStream().read(ByteArray(1024)); c.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()) } }
        val msg = runCatching { NtripClient(NtripSettings("127.0.0.1", server.localPort, "X", "a", "b"), { _, _ -> }, { null }).run() }.exceptionOrNull()?.message
        t.join(5000); server.close()
        assertTrue(msg!!.contains("401"))
    }
}
