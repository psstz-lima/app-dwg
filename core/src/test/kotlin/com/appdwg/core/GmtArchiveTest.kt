package com.appdwg.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GmtArchiveTest {
    private fun manifest(version: Int = 1) = GmtManifest(version, "0.1.2", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")

    private fun writeSample(extra: String = "", assets: List<GmtAssetSource> = emptyList(), limits: GmtArchive.Limits = GmtArchive.Limits()): File {
        val file = File.createTempFile("gmt", ".gmt")
        file.outputStream().use { out ->
            GmtArchive.write(out, manifest(), """{"app":"Gromatic","main":"content://draw.xml","note":"extra $extra"}""".toByteArray(), assets, limits)
        }
        return file
    }

    @Test
    fun roundTripKeepsTheProjectAndTheAssetBytes() {
        val payload = ByteArray(2_000_000) { (it % 251).toByte() }
        val file = writeSample(assets = listOf(
            GmtAssetSource("a1", "surface.tif", "main", payload.size.toLong()) { ByteArrayInputStream(payload) },
            GmtAssetSource("n1", "foto.jpg", "note", 4) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
        ))
        GmtArchive.Reader(file).use { r ->
            assertEquals(1, r.manifest.formatVersion)
            assertEquals("0.1.2", r.manifest.appVersion)
            assertEquals("2026-01-01T00:00:00Z", r.manifest.created)
            assertTrue(r.projectJson.contains("content://draw.xml"))
            assertEquals(listOf("a1", "n1"), r.manifest.assets.map { it.id })
            val dest = File.createTempFile("tif", ".tif")
            r.extract("a1", "surface.tif", dest)
            assertTrue(dest.readBytes().contentEquals(payload))
            val photo = File.createTempFile("foto", ".jpg")
            r.extract("n1", "foto.jpg", photo)
            assertEquals(listOf<Byte>(1, 2, 3, 4), photo.readBytes().toList())
        }
    }

    @Test
    fun extraManifestFieldsStayCompatible() {
        val json = GmtArchive.manifestJson(manifest()).dropLast(1) + ",\"future\":true,\"note\":\"ok\"}"
        val parsed = GmtArchive.parseManifest(json)
        assertEquals(1, parsed.formatVersion)
        assertEquals("0.1.2", parsed.appVersion)
    }

    @Test
    fun newerFormatIsRefused() {
        val err = assertFailsWith<GmtException> { GmtArchive.parseManifest("""{"format":"gromatic.gmt","formatVersion":2,"appVersion":"9"}""") }
        assertEquals("newer", err.code)
        assertEquals("2", err.detail)
    }

    @Test
    fun unknownAndCorruptFilesAreRefused() {
        assertEquals("unknown", assertFailsWith<GmtException> {
            GmtArchive.parseManifest("""{"format":"other","formatVersion":1}""")
        }.code)
        val junk = File.createTempFile("bad", ".gmt").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        assertEquals("corrupt", assertFailsWith<GmtException> { GmtArchive.Reader(junk).close() }.code)
        val empty = File.createTempFile("empty", ".gmt")
        empty.outputStream().use { ZipOutputStream(it).close() }
        assertEquals("unknown", assertFailsWith<GmtException> { GmtArchive.Reader(empty).close() }.code)
    }

    @Test
    fun pathTraversalIsRefused() {
        val evil = File.createTempFile("slip", ".zip")
        evil.outputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals("path", assertFailsWith<GmtException> { GmtArchive.Reader(evil).close() }.code)
        val nested = File.createTempFile("nested", ".zip")
        nested.outputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("assets/a/../../../evil.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals("path", assertFailsWith<GmtException> { GmtArchive.Reader(nested).close() }.code)
        // A directory prefix is reduced to the file name. A bare ".." or a bad id is refused.
        assertEquals("assets/a/x.xml", GmtArchive.assetPath("a", "../x.xml"))
        assertFailsWith<GmtException> { GmtArchive.safeFileName("..") }
        assertFailsWith<GmtException> { GmtArchive.assetPath("a", "..") }
        assertFailsWith<GmtException> { GmtArchive.assetPath("../a", "x.xml") }
    }

    @Test
    fun entryLargerThanTheLimitIsRefused() {
        val big = ByteArray(200) { 7 }
        val err = assertFailsWith<GmtException> {
            writeSample(
                assets = listOf(GmtAssetSource("a", "big.bin", "file", big.size.toLong()) { ByteArrayInputStream(big) }),
                limits = GmtArchive.Limits(maxEntryBytes = 50, maxTotalBytes = 10_000),
            )
        }
        assertEquals("too-large", err.code)
    }

    @Test
    fun missingProjectIsCorrupt() {
        val file = File.createTempFile("nproject", ".gmt")
        file.outputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(GmtArchive.manifestJson(manifest()).toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals("corrupt", assertFailsWith<GmtException> { GmtArchive.Reader(file).close() }.code)
    }

    @Test
    fun stringReplaceKeepsJsonEscaping() {
        val json = """{"main":"content://a/b","text":"content://a/b and more"}"""
        val out = GmtArchive.replaceString(json, "content://a/b", "gmt-asset://0/b.xml")
        // Only a complete JSON string is replaced, so a longer value that starts the same way stays.
        assertEquals("""{"main":"gmt-asset://0/b.xml","text":"content://a/b and more"}""", out)
        val quoted = GmtArchive.replaceString("""{"q":"say \"hi\""}""", "say \"hi\"", "ok")
        assertEquals("""{"q":"ok"}""", quoted)
    }

    @Test
    fun streamingDoesNotNeedTheWholeAssetUpFront() {
        val file = File.createTempFile("stream", ".gmt")
        var opened = 0
        file.outputStream().use { out ->
            GmtArchive.write(
                out,
                manifest(),
                "{}".toByteArray(),
                listOf(GmtAssetSource("s", "dem.tif", "raster", 3_000_000L) {
                    opened++
                    object : java.io.InputStream() {
                        var left = 3_000_000
                        override fun read(): Int = if (left-- > 0) 9 else -1
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (left <= 0) return -1
                            val n = minOf(len, left)
                            left -= n
                            java.util.Arrays.fill(b, off, off + n, 9.toByte())
                            return n
                        }
                    }
                }),
            )
        }
        assertTrue(opened >= 2, "stored entries are read twice (crc, then copy), never buffered whole")
        GmtArchive.Reader(file).use { r ->
            val dest = File.createTempFile("dem", ".tif")
            r.extract("s", "dem.tif", dest)
            assertEquals(3_000_000L, dest.length())
        }
    }
}
