package com.appdwg.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picture picked while a project is open is laid on that project. Cloud documents (Google Drive
 * and any other SAF provider) are streamed to a local file first, and that copy is what a .gmt keeps.
 */
class PickedFileTest {
    @Test
    fun imageMagicCoversTiffJpegPngAndWebp() {
        assertTrue(PickedFile.isImageHead(byteArrayOf(0x49, 0x49, 0x2A, 0x00)))
        assertTrue(PickedFile.isImageHead(byteArrayOf(0x49, 0x49, 0x2B, 0x00)))
        assertTrue(PickedFile.isImageHead(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)))
        assertTrue(PickedFile.isImageHead(byteArrayOf(0x4D, 0x4D, 0x00, 0x2B)))
        assertTrue(PickedFile.isImageHead(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(PickedFile.isImageHead(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        val webp = ByteArray(12)
        "RIFF".toByteArray(Charsets.ISO_8859_1).copyInto(webp, 0)
        "WEBP".toByteArray(Charsets.ISO_8859_1).copyInto(webp, 8)
        assertTrue(PickedFile.isImageHead(webp))
        assertFalse(PickedFile.isImageHead("0\nSECTION".toByteArray()))
        assertFalse(PickedFile.isImageHead(ByteArray(0)))
    }

    @Test
    fun nameAndMimeDecideAnImageAndLeaveDrawingsAndWorldFilesAlone() {
        assertTrue(PickedFile.isImage("ortho.tif", null, ByteArray(0)))
        assertTrue(PickedFile.isImage("ORTHO.TIFF", "application/octet-stream", ByteArray(0)))
        assertTrue(PickedFile.isImage("photo.jpg", null, ByteArray(0)))
        assertTrue(PickedFile.isImage("scan", "image/tiff", ByteArray(0)))
        assertTrue(PickedFile.isImage("scan", "image/png", ByteArray(0)))
        assertFalse(PickedFile.isImage("place.tfw", null, ByteArray(0)))
        assertFalse(PickedFile.isImage("place.pgw", "application/octet-stream", ByteArray(0)))
        assertFalse(PickedFile.isImage("place.jgw", null, ByteArray(0)))
        assertFalse(PickedFile.isImage("pending.dxf", "image/vnd.dxf", ByteArray(0)))
        assertFalse(PickedFile.isImage("lot.xml", "text/xml", ByteArray(0)))
        assertFalse(PickedFile.isImage("lot.xml", null, "<?xml".toByteArray()))
    }

    @Test
    fun anOpenProjectKeepsAnImageAndADrawingStillOpens() {
        assertEquals(PickRoute.ATTACH_IMAGE, PickedFile.route(projectOpen = true, image = true))
        assertEquals(PickRoute.NEW_PROJECT, PickedFile.route(projectOpen = false, image = true))
        assertEquals(PickRoute.NEW_PROJECT, PickedFile.route(projectOpen = true, image = false))
        assertEquals(PickRoute.NEW_PROJECT, PickedFile.route(projectOpen = false, image = false))
    }

    @Test
    fun driveAndOtherCloudProvidersAreCopiedLocalFilesAreNot() {
        assertFalse(PickedFile.needsStreamedCopy(null, null))
        assertFalse(PickedFile.needsStreamedCopy("file", null))
        assertFalse(PickedFile.needsStreamedCopy("content", "com.android.externalstorage.documents"))
        assertFalse(PickedFile.needsStreamedCopy("content", "com.android.providers.downloads.documents"))
        assertFalse(PickedFile.needsStreamedCopy("content", "com.android.providers.media.documents"))
        assertFalse(PickedFile.needsStreamedCopy("content", "com.android.providers.media"))
        assertTrue(PickedFile.needsStreamedCopy("content", "com.google.android.apps.docs.storage"))
        assertTrue(PickedFile.needsStreamedCopy("content", "com.google.android.apps.docs.storage.legacy"))
        assertTrue(PickedFile.needsStreamedCopy("content", "com.microsoft.skydrive"))
        assertTrue(PickedFile.needsStreamedCopy("content", null))
        assertTrue(PickedFile.needsStreamedCopy("https", "drive.google.com"))
    }

    @Test
    fun openAttachesAPickedImageWithoutReplacingTheProject() {
        val model = File(root(), "app/src/main/kotlin/com/appdwg/app/ProjectViewModel.kt").readText()
        val attach = model.indexOf(
            "if (PickedFile.route(projectOpen, image) == PickRoute.ATTACH_IMAGE) {\n                attachImage(uri)\n                return@launch",
        )
        val cleared = model.lastIndexOf("gmtCreated = null")
        assertTrue(attach > 0)
        assertTrue(cleared > attach)
        assertTrue(model.contains("PickedFile.needsStreamedCopy(uri.scheme, uri.authority)"))
        assertTrue(model.contains("openLocal(uri)"))
        assertFalse(model.contains("openFileDescriptor(uri, \"r\")!!"))
    }

    @Test
    fun aDriveDrapeUriIsEmbeddedInTheGmtAndReadBackAsALocalFile() {
        val dir = java.nio.file.Files.createTempDirectory("gromatic-drape").toFile()
        try {
            val bytes = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 1, 2, 3, 4)
            val tif = File(dir, "ortho.tif").apply { writeBytes(bytes) }
            val drive = "content://com.google.android.apps.docs.storage/document/abc"
            val json = """{"drape":["$drive"],"main":"content://example/land.xml"}"""
            val gmt = File(dir, "obra.gmt")
            val written = gmt.outputStream().use {
                GmtIo.write(
                    it,
                    json,
                    listOf(GmtPiece("drape", drive, "ortho.tif", bytes.size.toLong()) { tif.inputStream() }),
                    "0.1.2",
                    null,
                )
            }
            assertEquals(1, written.embedded)
            assertEquals(0, written.skipped)
            val opened = GmtIo.read(gmt, File(dir, "extract"), File(dir, "notes"))
            assertFalse(opened.projectJson.contains("docs.storage"))
            assertTrue(opened.projectJson.contains("file:"))
            assertTrue(opened.projectJson.contains("ortho.tif"))
            val extracted = File(dir, "extract").walk().first { it.isFile && it.name == "ortho.tif" }
            assertTrue(extracted.readBytes().contentEquals(bytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
}
