package com.appdwg.app

import android.content.Context
import android.net.Uri
import com.appdwg.core.GmtArchive
import com.appdwg.core.GmtAssetSource
import com.appdwg.core.GmtException
import com.appdwg.core.GmtManifest
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One file copied into a .gmt. [source] is the string stored in the project JSON today. */
internal class GmtPiece(
    val role: String,
    val source: String,
    val fileName: String,
    val size: Long,
    val open: () -> InputStream,
)

/** Reads and writes the .gmt container around the existing project JSON. */
internal object GmtIo {
    const val MIME = "application/vnd.gromatic.project"

    fun isGmt(name: String?, mime: String?, path: String? = null): Boolean =
        name?.endsWith(".gmt", true) == true ||
            path?.endsWith(".gmt", true) == true ||
            mime == MIME || mime == "application/gromatic"

    data class Written(val created: String, val modified: String, val embedded: Int, val skipped: Int)

    /**
     * Streams [projectJson] and [pieces] into [out]. Strings in the JSON that match a piece's
     * [GmtPiece.source] become `gmt-asset://id/name`. Pieces that cannot be opened are left as
     * they are and counted in [Written.skipped].
     */
    fun write(
        out: OutputStream,
        projectJson: String,
        pieces: List<GmtPiece>,
        appVersion: String,
        created: String?,
        onBytes: (Long) -> Unit = {},
    ): Written {
        val now = java.time.Instant.now().toString()
        var json = projectJson
        var skipped = 0
        val assets = ArrayList<GmtAssetSource>()
        pieces.forEachIndexed { index, piece ->
            val probe = runCatching { piece.open() }.getOrNull()
            if (probe == null) { skipped++; return@forEachIndexed }
            probe.close()
            val id = "a$index"
            val name = GmtArchive.safeFileName(piece.fileName.ifBlank { "file-$index" })
            json = GmtArchive.replaceString(json, piece.source, "gmt-asset://$id/$name")
            assets += GmtAssetSource(id, name, piece.role, piece.size) { piece.open() }
        }
        val manifest = GmtManifest(
            formatVersion = GmtManifest.FORMAT_VERSION,
            appVersion = appVersion,
            created = created?.ifBlank { now } ?: now,
            modified = now,
        )
        GmtArchive.write(out, manifest, json.toByteArray(Charsets.UTF_8), assets, onBytes = onBytes)
        return Written(manifest.created, manifest.modified, assets.size, skipped)
    }

    data class Opened(val manifest: GmtManifest, val projectJson: String)

    /**
     * Validates [zipFile] and streams each asset into [extractDir]. Note photos are also copied
     * into [notesDir] and the JSON keeps a file name; every other asset becomes a `file:` URI.
     */
    fun read(zipFile: File, extractDir: File, notesDir: File): Opened {
        GmtArchive.Reader(zipFile).use { reader ->
            val stamp = reader.manifest.modified + ":" + zipFile.length()
            val marker = File(extractDir, ".stamp")
            var json = reader.projectJson
            if (marker.isFile && marker.readText() == stamp && File(extractDir, ".ok").isFile) {
                json = localize(reader, extractDir, notesDir, json, extract = false)
                return Opened(reader.manifest, json)
            }
            extractDir.deleteRecursively()
            extractDir.mkdirs()
            notesDir.mkdirs()
            json = localize(reader, extractDir, notesDir, json, extract = true)
            marker.writeText(stamp)
            File(extractDir, ".ok").writeText("1")
            return Opened(reader.manifest, json)
        }
    }

    private fun localize(reader: GmtArchive.Reader, extractDir: File, notesDir: File, projectJson: String, extract: Boolean): String {
        var json = projectJson
        for (asset in reader.manifest.assets) {
            val token = "gmt-asset://${asset.id}/${GmtArchive.safeFileName(asset.name)}"
            val dest = File(extractDir, asset.id + "/" + GmtArchive.safeFileName(asset.name))
            if (extract || !dest.isFile) reader.extract(asset.id, asset.name, dest)
            if (asset.role == "note") {
                val photo = uniquePhoto(notesDir, asset.name, dest.length())
                val photoFile = File(notesDir, photo)
                if (!photoFile.isFile || photoFile.length() != dest.length()) dest.copyTo(photoFile, overwrite = true)
                json = GmtArchive.replaceString(json, token, photo)
            } else {
                json = GmtArchive.replaceString(json, token, dest.toURI().toString())
            }
        }
        return json
    }

    private fun uniquePhoto(dir: File, name: String, size: Long): String {
        val safe = GmtArchive.safeFileName(name)
        val existing = File(dir, safe)
        if (!existing.exists() || existing.length() == size) return safe
        var i = 1
        while (File(dir, "gmt$i-$safe").exists()) i++
        return "gmt$i-$safe"
    }

    /** Copies a content URI to [dest] in chunks. Returns the file. */
    fun materialize(context: Context, uri: Uri, dest: File, onBytes: (Long) -> Unit = {}): File {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw GmtException("corrupt", "file")
            return File(path)
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        (context.contentResolver.openInputStream(uri) ?: throw GmtException("corrupt", "open")).use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done % (256 * 1024) < 64 * 1024) onBytes(done)
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        return dest
    }
}
