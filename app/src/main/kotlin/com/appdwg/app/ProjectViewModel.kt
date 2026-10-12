package com.appdwg.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appdwg.core.AeccPointCache
import com.appdwg.core.Bounds
import com.appdwg.core.DxfReader
import com.appdwg.core.LandXmlProject
import com.appdwg.core.LandXmlReader
import com.appdwg.core.RandomInput
import com.appdwg.core.Raster
import com.appdwg.core.TiffReader
import com.appdwg.core.WorldFile
import com.appdwg.core.Xrefs
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

sealed interface ProjectState {
    data object Empty : ProjectState
    data class Loading(val fileName: String, val task: LoadTask) : ProjectState
    data class Loaded(val fileName: String, val project: LandXmlProject, val terrain: List<TerrainImage>) : ProjectState
    data class Failed(val fileName: String, val message: String) : ProjectState
}

private const val HEAD_SIZE = 64

/** Picture laid over the surfaces ("imagem sobre a superfície"), placed at [bounds] in plan. */
class Drape(val name: String, val bounds: Bounds, val image: ImageBitmap, val placement: String)

/** Decoded picture, with its plan extent when the file carries one and its size in the file. */
private class Picture(val image: ImageBitmap, val extent: Bounds?, val sourceWidth: Int, val sourceHeight: Int)

/** Extensions of world files, which place a JPG, PNG or TIFF picture in plan. */
private val WORLD_FILE_EXTENSIONS = setOf("jgw", "jpgw", "jpegw", "pgw", "pngw", "tfw", "tifw", "tiffw", "wld", "bpw", "gfw")

/** Longest side of a loaded raster image; larger pictures are downsampled to save memory. */
private const val MAX_RASTER = 4096

class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<ProjectState>(ProjectState.Empty)
        private set

    /** Raster files loaded for DXF images, by the path the drawing stores. */
    var rasters by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set

    /** Files loading in the background (images, xrefs), shown with their progress. */
    var tasks by mutableStateOf<List<LoadTask>>(emptyList())
        private set

    private suspend fun <T> tracked(label: String, block: suspend (LoadTask) -> T): T {
        val task = LoadTask(label)
        tasks = tasks + task
        try {
            return block(task)
        } finally {
            tasks = tasks - task
        }
    }

    /** Picture laid over the surfaces, in plan and in 3D, and whether it is shown. */
    var drape by mutableStateOf<Drape?>(null)
        private set
    var drapeVisible by mutableStateOf(true)
    /** Result or error of the last picture chosen for the surfaces. */
    var drapeMessage by mutableStateOf<String?>(null)
        private set

    fun removeDrape() {
        drape = null
        drapeMessage = null
    }

    /**
     * Lays the chosen picture over the surfaces. [uris] holds the picture and, optionally, its world
     * file (.jgw, .pgw, .tfw). A GeoTIFF places itself; a picture with no placement is stretched over
     * the surfaces' extent.
     */
    fun loadDrape(uris: List<Uri>) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val named = uris.map { it to displayName(it) }
        val world = named.firstOrNull { it.second.substringAfterLast('.', "").lowercase() in WORLD_FILE_EXTENSIONS }
        val picture = named.firstOrNull { it !== world }
        drapeMessage = null
        if (picture == null) {
            drapeMessage = "Escolha também a imagem (TIFF, JPG ou PNG), não só o arquivo de georreferência."
            return
        }
        val (uri, name) = picture
        viewModelScope.launch {
            try {
                val pic = tracked("Imagem $name") { t -> withContext(Dispatchers.IO) { decodePicture(uri, t) } }
                    ?: error("formato de imagem não reconhecido")
                val surfaces = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                val area = surfaces ?: project.bounds ?: error("projeto sem área")
                val worldText = world?.let { (w, _) ->
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(w)?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    }
                }
                val (bounds, placement) = when {
                    worldText != null -> WorldFile.extent(worldText, pic.sourceWidth, pic.sourceHeight) to "posicionada pelo arquivo ${world?.second}"
                    pic.extent != null -> pic.extent to "posicionada pelo GeoTIFF"
                    else -> area to "esticada sobre a superfície (a imagem não tem georreferência)"
                }
                drape = Drape(name, bounds, pic.image, placement)
                drapeVisible = true
                drapeMessage = if (!overlaps(bounds, area)) {
                    "A imagem foi $placement, mas não cai sobre a superfície. Confira se as duas estão no mesmo sistema de coordenadas."
                } else {
                    "Imagem $placement."
                }
            } catch (e: OutOfMemoryError) {
                drapeMessage = "Imagem grande demais para a memória deste aparelho."
            } catch (e: Exception) {
                drapeMessage = "Não foi possível usar a imagem: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun overlaps(a: Bounds, b: Bounds) = a.minX < b.maxX && b.minX < a.maxX && a.minY < b.maxY && b.minY < a.maxY

    /** Why the last picture could not be loaded, for the images dialog. */
    var rasterError by mutableStateOf<String?>(null)
        private set

    private val prefs = app.getSharedPreferences("rasters", Context.MODE_PRIVATE)

    // ---- External references (images and xref drawings) ----

    private var sourceUri: Uri? = null
    private var base: LandXmlProject? = null
    private var baseTerrain: List<TerrainImage> = emptyList()
    private var xrefDrawings: Map<String, LandXmlProject> = emptyMap()

    /** References turned off ("descarregadas") or removed ("desanexadas"), by name or image path. */
    var unloaded by mutableStateOf<Set<String>>(emptySet())
        private set
    var detached by mutableStateOf<Set<String>>(emptySet())
        private set

    /** New paths typed for the references (original path to new one), for saving a DXF copy. */
    var pathEdits by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Names of the xref drawings loaded, and a message about the last reference action. */
    var loadedXrefs by mutableStateOf<Set<String>>(emptySet())
        private set
    var referenceMessage by mutableStateOf<String?>(null)
        private set

    /** True when the open drawing has images or xrefs, detached ones included. */
    val hasReferences get() = base?.let { it.images.isNotEmpty() || it.xrefs.isNotEmpty() } ?: false

    /** All references of the open drawing, detached ones included, for the references dialog. */
    val allImages get() = base?.images.orEmpty()
    val allXrefs get() = base?.xrefs.orEmpty()

    fun editPath(original: String, newPath: String) {
        pathEdits = if (newPath == original) pathEdits - original else pathEdits + (original to newPath)
    }

    fun setUnloaded(key: String, value: Boolean) {
        unloaded = if (value) unloaded + key else unloaded - key
        refreshProject()
    }

    fun setDetached(key: String, value: Boolean) {
        detached = if (value) detached + key else detached - key
        refreshProject()
    }

    /** Reads the DXF chosen for xref [name] and draws it at its placements. */
    fun loadXref(name: String, path: String, uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        referenceMessage = null
        viewModelScope.launch {
            try {
                val drawing = tracked("XREF ${displayName(uri)}") { t -> withContext(Dispatchers.IO) { read(uri, t) } }
                xrefDrawings = xrefDrawings + (name to drawing)
                prefs.edit().putString("xref:$path", uri.toString()).apply()
                refreshProject()
            } catch (e: OutOfMemoryError) {
                referenceMessage = "Desenho grande demais para a memória deste aparelho."
            } catch (e: Exception) {
                referenceMessage = "Não foi possível ler ${displayName(uri)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Writes a copy of the open DXF with the edited reference paths to [target]. */
    fun saveDxfCopy(target: Uri) {
        val source = sourceUri ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            referenceMessage = try {
                withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(source) ?: error("arquivo original indisponível")
                    val output = resolver.openOutputStream(target, "wt") ?: error("destino indisponível")
                    input.bufferedReader(Charsets.UTF_8).use { r ->
                        output.bufferedWriter(Charsets.UTF_8).use { w -> Xrefs.rewritePaths(r, w, pathEdits) }
                    }
                }
                "Cópia salva em ${displayName(target)} com ${pathEdits.size} caminho(s) alterado(s)."
            } catch (e: Exception) {
                "Não foi possível salvar: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Rebuilds the shown project from the base drawing and the loaded, attached xrefs. */
    private fun refreshProject() {
        val b = base ?: return
        val current = state as? ProjectState.Loaded ?: return
        viewModelScope.launch {
            val (project, terrain) = tracked("Atualizando referências") { withContext(Dispatchers.Default) {
                var p = b.copy(
                    images = b.images.filter { it.file !in detached },
                    xrefs = b.xrefs.filter { it.name !in detached },
                )
                for (x in p.xrefs) {
                    if (x.name in unloaded) continue
                    xrefDrawings[x.name]?.let { p = Xrefs.attach(p, x, it) }
                }
                val extra = p.surfaces.filter { s -> baseTerrain.none { it.name == s.name } }
                extra.forEach { s -> s.bounds?.let { s.elevationAt(it.center) } }
                p to baseTerrain + extra.mapNotNull { TerrainImage.of(it) }
            } }
            loadedXrefs = xrefDrawings.keys
            state = current.copy(project = project, terrain = terrain)
        }
    }

    private fun restoreXrefs(project: LandXmlProject) {
        for (x in project.xrefs) {
            val uri = prefs.getString("xref:${x.path}", null)?.let(Uri::parse) ?: continue
            loadXref(x.name, x.path, uri)
        }
    }

    /** Loads the picture for the drawing's image [file] and remembers the choice for next time. */
    fun loadRaster(file: String, uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        rasterError = null
        viewModelScope.launch {
            try {
                val bitmap = tracked("Imagem ${displayName(uri)}") { t -> withContext(Dispatchers.IO) { decodeRaster(uri, t) } }
                if (bitmap != null) {
                    rasters = rasters + (file to bitmap)
                    prefs.edit().putString(file, uri.toString()).apply()
                } else {
                    rasterError = "Formato de imagem não reconhecido."
                }
            } catch (e: OutOfMemoryError) {
                rasterError = "Imagem grande demais para a memória deste aparelho."
            } catch (e: Exception) {
                rasterError = "Não foi possível ler a imagem: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Reloads pictures chosen earlier for the images of [project]. */
    private fun restoreRasters(project: LandXmlProject) {
        for (image in project.images) {
            val uri = prefs.getString(image.file, null)?.let(Uri::parse) ?: continue
            viewModelScope.launch {
                val bitmap = tracked("Imagem ${image.fileName}") { t ->
                    withContext(Dispatchers.IO) { runCatching { decodeRaster(uri, t) }.getOrNull() }
                }
                if (bitmap != null) rasters = rasters + (image.file to bitmap)
            }
        }
    }

    /**
     * Decodes a picture to at most [MAX_RASTER] pixels per side. TIFF goes through the app's own
     * reader (Android has none); JPEG, PNG and WebP through the platform.
     */
    private fun decodeRaster(uri: Uri, task: LoadTask): ImageBitmap? = decodePicture(uri, task)?.image

    private fun decodePicture(uri: Uri, task: LoadTask): Picture? {
        val resolver = getApplication<Application>().contentResolver
        val head = ByteArray(8)
        resolver.openInputStream(uri)?.use { it.read(head) }
        val text = String(head, Charsets.ISO_8859_1)
        when {
            text.startsWith("II*\u0000") || text.startsWith("MM\u0000*") || text.startsWith("II+\u0000") || text.startsWith("MM\u0000+") ->
                return decodeTiff(uri, task)
            displayName(uri).endsWith(".ecw", ignoreCase = true) ->
                error("ECW é um formato fechado (Hexagon) sem leitor livre para Android. Converta para TIFF ou JPG no QGIS (Exportar > Salvar como)")
            displayName(uri).endsWith(".jp2", ignoreCase = true) ->
                error("JPEG 2000 não é suportado. Converta para TIFF ou JPG")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_RASTER) sample *= 2
        task.step("Decodificando imagem")
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return Picture(bitmap.asImageBitmap(), null, bounds.outWidth, bounds.outHeight)
    }

    fun open(uri: Uri) {
        val name = displayName(uri)
        val task = LoadTask("Abrindo $name")
        state = ProjectState.Loading(name, task)
        viewModelScope.launch {
            state = try {
                val (project, terrain) = withContext(Dispatchers.IO) {
                    val p = read(uri, task)
                    // Builds each surface's lookup index now, so the first tap on the plan is instant.
                    if (p.surfaces.isNotEmpty()) task.step("Preparando consulta de cotas")
                    p.surfaces.forEach { s -> s.bounds?.let { s.elevationAt(it.center) } }
                    if (p.surfaces.isNotEmpty()) task.step("Gerando imagem do terreno")
                    p to p.surfaces.mapNotNull { TerrainImage.of(it) }
                }
                if (project.surfaces.isEmpty() && project.alignments.isEmpty() && project.cgPoints.isEmpty() &&
                    project.polylines.isEmpty()
                ) {
                    ProjectState.Failed(name, "Nenhuma superfície, alinhamento ou ponto encontrado. O arquivo é um LandXML, DXF ou cache de pontos do Civil 3D (.aecc.pnt)?")
                } else {
                    rasters = emptyMap()
                    drape = null
                    drapeMessage = null
                    sourceUri = uri
                    base = project
                    baseTerrain = terrain
                    xrefDrawings = emptyMap()
                    loadedXrefs = emptySet()
                    unloaded = emptySet()
                    detached = emptySet()
                    pathEdits = emptyMap()
                    referenceMessage = null
                    restoreRasters(project)
                    restoreXrefs(project)
                    ProjectState.Loaded(name, project, terrain)
                }
            } catch (e: OutOfMemoryError) {
                ProjectState.Failed(name, "Arquivo grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                ProjectState.Failed(name, "Não foi possível ler o arquivo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun read(uri: Uri, task: LoadTask? = null): LandXmlProject {
        val resolver = getApplication<Application>().contentResolver
        val size = fileSize(uri)
        val raw = resolver.openInputStream(uri) ?: error("arquivo indisponível")
        val input = if (task == null) raw else CountingInputStream(raw, size) { task.step("Lendo arquivo", it) }
        return input.buffered().use { stream ->
            // Sniffs the content rather than trusting the file name, which e-mail apps often drop.
            stream.mark(HEAD_SIZE)
            val head = ByteArray(HEAD_SIZE)
            val n = stream.readNBytesCompat(head)
            stream.reset()
            val text = String(head, 0, n, Charsets.ISO_8859_1)
            when {
                text.startsWith("AC10") -> error("arquivo DWG. No AutoCAD/Civil 3D, salve como DXF (Salvar como > DXF) ou exporte para LandXML")
                text.startsWith("AutoCAD Binary DXF") -> error("DXF binário. Salve o DXF no formato ASCII")
                DxfReader.looksLikeDxf(text) ->
                    return@use DxfReader(stream.reader(Charsets.UTF_8).buffered(1 shl 16)).read { task?.step(it) }
                AeccPointCache.looksLikePointCache(head, size) ->
                    return@use AeccPointCache.read(stream, size, displayName(uri).substringBefore('.')) { task?.step(it) }
            }
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            LandXmlReader(parser).read()
        }
    }

    private fun decodeTiff(uri: Uri, task: LoadTask): Picture {
        val resolver = getApplication<Application>().contentResolver
        val fd = resolver.openFileDescriptor(uri, "r") ?: error("arquivo indisponível")
        return fd.use {
            FileInputStream(it.fileDescriptor).channel.use { channel ->
                val input = object : RandomInput {
                    override val size = channel.size()
                    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                        val bb = ByteBuffer.wrap(buffer, offset, length)
                        var pos = position
                        while (bb.hasRemaining()) {
                            val n = channel.read(bb, pos)
                            if (n < 0) error("fim inesperado do arquivo")
                            pos += n
                        }
                    }
                }
                val raster = TiffReader(input) { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                        val px = IntArray(b.width * b.height)
                        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                        Raster(b.width, b.height, px).also { b.recycle() }
                    }
                }.read(MAX_RASTER) { task.step("Decodificando TIFF", it) }
                val bitmap = Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
                Picture(bitmap.asImageBitmap(), raster.extent, raster.sourceWidth, raster.sourceHeight)
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = read(buffer, total, buffer.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun fileSize(uri: Uri): Long =
        runCatching { getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }
            .getOrNull() ?: -1L

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment ?: "arquivo"
    }
}
