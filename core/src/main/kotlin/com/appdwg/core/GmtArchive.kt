package com.appdwg.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Why a .gmt file was refused. [detail] is a short technical note (a version number, a path);
 * the app turns [code] into a sentence in the user's language.
 */
class GmtException(val code: String, val detail: String = "") : Exception(if (detail.isEmpty()) code else "$code: $detail")

/** One file stored inside a .gmt, as declared in the manifest. */
data class GmtAssetInfo(val id: String, val name: String, val role: String, val bytes: Long)

/**
 * Header of a .gmt container. [formatVersion] 1 is the first version; a reader accepts every
 * version up to [FORMAT_VERSION] and refuses anything newer so an old app does not misread a
 * future file. Unknown fields in the JSON are ignored.
 */
data class GmtManifest(
    val formatVersion: Int,
    val appVersion: String,
    val created: String,
    val modified: String,
    val assets: List<GmtAssetInfo> = emptyList(),
) {
    companion object {
        const val FORMAT = "gromatic.gmt"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val PROJECT = "project.json"
    }
}

/** A file to write into the container. [open] is called once (or twice for stored entries). */
class GmtAssetSource(
    val id: String,
    val name: String,
    val role: String,
    val size: Long,
    val open: () -> InputStream,
)

/**
 * .gmt is a zip. `manifest.json` and `project.json` sit at the root; every other file is
 * `assets/<id>/<name>`. Bytes are copied in chunks, so a large TIFF is never held whole in memory.
 */
object GmtArchive {
    const val MAX_ENTRIES = 4096
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    private const val BUFFER = 64 * 1024

    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    )

    fun assetPath(id: String, name: String): String {
        val safeId = id.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
            ?: throw GmtException("path", id)
        val safeName = safeFileName(name)
        return "assets/$safeId/$safeName"
    }

    /** File name with no directories. */
    fun safeFileName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        if (base.isEmpty() || base == "." || base == ".." || base.contains('\u0000')) throw GmtException("path", name)
        return base
    }

    fun write(
        out: OutputStream,
        manifest: GmtManifest,
        projectJson: ByteArray,
        assets: List<GmtAssetSource>,
        limits: Limits = Limits(),
        onBytes: (Long) -> Unit = {},
    ) {
        if (manifest.formatVersion !in 1..GmtManifest.FORMAT_VERSION) throw GmtException("unknown", manifest.formatVersion.toString())
        if (assets.size + 2 > limits.maxEntries) throw GmtException("too-large", "entries")
        var total = 0L
        fun count(n: Long) {
            if (n > limits.maxEntryBytes) throw GmtException("too-large", "entry")
            total += n
            if (total > limits.maxTotalBytes) throw GmtException("too-large", "total")
            onBytes(total)
        }
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val declared = assets.map { a ->
                GmtAssetInfo(a.id, safeFileName(a.name), a.role, a.size.coerceAtLeast(0))
            }
            val header = manifest.copy(assets = declared)
            val manifestBytes = manifestJson(header).toByteArray(Charsets.UTF_8)
            putBytes(zip, GmtManifest.MANIFEST, manifestBytes)
            count(manifestBytes.size.toLong())
            putBytes(zip, GmtManifest.PROJECT, projectJson)
            count(projectJson.size.toLong())
            for (asset in assets) {
                val path = assetPath(asset.id, asset.name)
                if (asset.size >= 0 && stored(asset.name)) putStored(zip, path, asset.size, asset.open, limits, ::count)
                else putStream(zip, path, asset.open, limits, ::count)
            }
        }
    }

    /** Opens a .gmt that is already a local file (callers stream a content URI to disk first). */
    class Reader(file: File, private val limits: Limits = Limits()) : AutoCloseable {
        private val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw GmtException("corrupt", e.message ?: "zip")
        }
        val manifest: GmtManifest
        val projectJson: String

        init {
            val entries = zip.entries().toList()
            if (entries.size > limits.maxEntries) throw GmtException("too-large", "entries")
            var total = 0L
            for (e in entries) {
                checkPath(e.name)
                if (e.size > limits.maxEntryBytes || e.size < -1L) throw GmtException("too-large", e.name)
                if (e.size > 0) {
                    total += e.size
                    if (total > limits.maxTotalBytes) throw GmtException("too-large", "total")
                }
            }
            val manifestEntry = zip.getEntry(GmtManifest.MANIFEST) ?: throw GmtException("unknown", "manifest")
            val manifestText = readSmall(manifestEntry)
            manifest = parseManifest(manifestText)
            val projectEntry = zip.getEntry(GmtManifest.PROJECT) ?: throw GmtException("corrupt", "project")
            projectJson = readSmall(projectEntry)
            if (!projectJson.trimStart().startsWith("{")) throw GmtException("corrupt", "project")
            for (asset in manifest.assets) {
                val path = assetPath(asset.id, asset.name)
                if (zip.getEntry(path) == null) throw GmtException("corrupt", path)
            }
        }

        fun extract(id: String, name: String, dest: File) {
            val path = assetPath(id, name)
            val entry = zip.getEntry(path) ?: throw GmtException("corrupt", path)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            try {
                zip.getInputStream(entry).use { inp ->
                    tmp.outputStream().use { out -> copyLimited(inp, out, limits.maxEntryBytes) }
                }
            } catch (e: GmtException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                throw GmtException("corrupt", e.message ?: path)
            }
            if (dest.exists() && !dest.delete()) throw GmtException("corrupt", dest.name)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }

        override fun close() {
            zip.close()
        }

        private fun readSmall(entry: ZipEntry): String {
            if (entry.size > 32L * 1024 * 1024) throw GmtException("too-large", entry.name)
            return try {
                zip.getInputStream(entry).use { inp ->
                    val out = java.io.ByteArrayOutputStream()
                    copyLimited(inp, out, minOf(limits.maxEntryBytes, 32L * 1024 * 1024))
                    out.toString(Charsets.UTF_8.name())
                }
            } catch (e: GmtException) {
                throw e
            } catch (e: Exception) {
                throw GmtException("corrupt", e.message ?: entry.name)
            }
        }
    }

    /** Replaces a JSON string value. Slashes and quotes are escaped the way org.json writes them. */
    fun replaceString(json: String, from: String, to: String): String {
        if (from == to) return json
        return json.replace(quoted(from), quoted(to))
    }

    fun quoted(s: String): String = "\"" + escapeJson(s) + "\""

    private fun stored(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("tif", "tiff", "jpg", "jpeg", "png", "webp", "zip", "gz")
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putStream(
        zip: ZipOutputStream,
        name: String,
        open: () -> InputStream,
        limits: Limits,
        count: (Long) -> Unit,
    ) {
        val entry = ZipEntry(name)
        entry.time = 0
        zip.putNextEntry(entry)
        open().use { inp -> count(copyLimited(inp, zip, limits.maxEntryBytes)) }
        zip.closeEntry()
    }

    private fun putStored(
        zip: ZipOutputStream,
        name: String,
        size: Long,
        open: () -> InputStream,
        limits: Limits,
        count: (Long) -> Unit,
    ) {
        if (size > limits.maxEntryBytes) throw GmtException("too-large", name)
        val crc = CRC32()
        var seen = 0L
        open().use { inp ->
            val buf = ByteArray(BUFFER)
            while (seen < size) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), size - seen).toInt())
                if (n < 0) throw GmtException("truncated", name)
                crc.update(buf, 0, n)
                seen += n
            }
        }
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = size
        entry.compressedSize = size
        entry.crc = crc.value
        entry.time = 0
        zip.putNextEntry(entry)
        open().use { inp ->
            val wrote = copyLimited(inp, zip, size)
            if (wrote != size) throw GmtException("truncated", name)
        }
        zip.closeEntry()
        count(size)
    }

    private fun copyLimited(inp: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = inp.read(buf)
            if (n < 0) return total
            total += n
            if (total > max) throw GmtException("too-large", "entry")
            out.write(buf, 0, n)
        }
    }

    private fun checkPath(name: String) {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains('\\') ||
            name.split('/').any { it == ".." }
        ) throw GmtException("path", name)
    }

    internal fun manifestJson(m: GmtManifest): String = buildString {
        append("{\"format\":").append(quoted(GmtManifest.FORMAT))
        append(",\"formatVersion\":").append(m.formatVersion)
        append(",\"app\":").append(quoted("Gromatic"))
        append(",\"appVersion\":").append(quoted(m.appVersion))
        append(",\"created\":").append(quoted(m.created))
        append(",\"modified\":").append(quoted(m.modified))
        append(",\"assets\":[")
        m.assets.forEachIndexed { i, a ->
            if (i > 0) append(',')
            append("{\"id\":").append(quoted(a.id))
            append(",\"name\":").append(quoted(a.name))
            append(",\"role\":").append(quoted(a.role))
            append(",\"bytes\":").append(a.bytes).append('}')
        }
        append("]}")
    }

    fun parseManifest(text: String): GmtManifest {
        val root = try {
            MiniJson.parse(text) as? MiniJson.Obj ?: throw GmtException("corrupt", "manifest")
        } catch (e: GmtException) {
            throw e
        } catch (e: Exception) {
            throw GmtException("corrupt", e.message ?: "manifest")
        }
        val format = root.str("format")
        if (format != GmtManifest.FORMAT) throw GmtException("unknown", format ?: "")
        val version = root.int("formatVersion") ?: throw GmtException("corrupt", "formatVersion")
        if (version > GmtManifest.FORMAT_VERSION) throw GmtException("newer", version.toString())
        if (version < 1) throw GmtException("unknown", version.toString())
        val assets = root.array("assets")?.map { item ->
            val o = item as? MiniJson.Obj ?: throw GmtException("corrupt", "asset")
            val id = o.str("id") ?: throw GmtException("corrupt", "asset id")
            val name = o.str("name") ?: throw GmtException("corrupt", "asset name")
            val role = o.str("role") ?: "file"
            val bytes = o.long("bytes") ?: 0L
            safeFileName(name)
            GmtAssetInfo(id, name, role, bytes)
        }.orEmpty()
        return GmtManifest(
            formatVersion = version,
            appVersion = root.str("appVersion") ?: "",
            created = root.str("created") ?: "",
            modified = root.str("modified") ?: "",
            assets = assets,
        )
    }

    private fun escapeJson(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** A small JSON reader for the manifest. It ignores unknown fields and does not depend on Android. */
private object MiniJson {
    class Obj(val map: Map<String, Any?>) {
        fun str(k: String) = map[k] as? String
        fun int(k: String): Int? = when (val v = map[k]) {
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Int -> v
            else -> null
        }
        fun long(k: String): Long? = when (val v = map[k]) {
            is Long -> v
            is Double -> v.toLong()
            is Int -> v.toLong()
            else -> null
        }
        fun array(k: String) = map[k] as? List<*>
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(val s: String) {
        var i = 0
        fun parseValue(): Any? {
            skip()
            if (i >= s.length) throw GmtException("corrupt", "json")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw GmtException("corrupt", "json")
            }
        }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun obj(): Obj {
            i++
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return Obj(map) }
            while (true) {
                skip()
                val key = str()
                skip()
                if (i >= s.length || s[i] != ':') throw GmtException("corrupt", "json")
                i++
                map[key] = parseValue()
                skip()
                if (i >= s.length) throw GmtException("corrupt", "json")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Obj(map) }
                    else -> throw GmtException("corrupt", "json")
                }
            }
        }
        private fun arr(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list += parseValue()
                skip()
                if (i >= s.length) throw GmtException("corrupt", "json")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw GmtException("corrupt", "json")
                }
            }
        }
        private fun str(): String {
            if (s[i] != '"') throw GmtException("corrupt", "json")
            i++
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw GmtException("corrupt", "json")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw GmtException("corrupt", "json")
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw GmtException("corrupt", "json")
                        }
                    }
                    else -> b.append(c)
                }
            }
            throw GmtException("corrupt", "json")
        }
        private fun lit(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw GmtException("corrupt", "json")
            i += word.length
            return value
        }
        private fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return if ('.' in t || 'e' in t || 'E' in t) t.toDouble() else t.toLong()
        }
    }
}
