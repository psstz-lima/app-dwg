package com.appdwg.app

import com.appdwg.core.GmtArchive
import com.appdwg.core.GmtException
import com.appdwg.core.tr

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
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
import com.appdwg.core.Placement
import com.appdwg.core.Surface
import com.appdwg.core.CogoPoint
import com.appdwg.core.POINTS_LAYER
import com.appdwg.core.PointFile
import com.appdwg.core.DxfWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.appdwg.core.Platform
import com.appdwg.core.Alignment
import com.appdwg.core.AlignmentBuilder
import com.appdwg.core.Corridor
import com.appdwg.core.CorridorResult
import com.appdwg.core.Pi
import com.appdwg.core.Profile
import com.appdwg.core.Region
import com.appdwg.core.Template
import com.appdwg.core.PlatformDesign
import com.appdwg.core.PlatformResult
import com.appdwg.core.Point3
import com.appdwg.core.Polyline
import com.appdwg.core.FieldNote
import com.appdwg.core.NotesExport
import com.appdwg.core.Model3D
import com.appdwg.core.ModelPlacement
import com.appdwg.core.ModelReader
import com.appdwg.core.SlopeRule
import com.appdwg.core.SurfaceSource
import com.appdwg.core.Contours
import com.appdwg.core.ExportLine
import com.appdwg.core.KmlWriter
import com.appdwg.core.LandXmlWriter
import com.appdwg.core.Point2
import com.appdwg.core.Utm
import com.appdwg.core.UtmForward
import com.appdwg.core.StationFormat
import com.appdwg.core.SurfaceCompare
import com.appdwg.core.SurfaceDifference
import com.appdwg.core.StageProgress
import com.appdwg.core.XrefDrawing
import com.appdwg.core.Xrefs
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject

sealed interface ProjectState {
    data object Empty : ProjectState
    data class Loading(val fileName: String, val task: LoadTask) : ProjectState
    data class Loaded(val fileName: String, val project: LandXmlProject, val terrain: List<TerrainImage>) : ProjectState
    data class Failed(val fileName: String, val message: String) : ProjectState
}

private const val HEAD_SIZE = 64

/** Extensions read as 3D models. */
val MODEL_EXTENSIONS = setOf("obj", "stl", "ply", "gltf", "glb", "ifc", "fbx", "dwg")

/** Most triangles a model may have on a tablet. */
private const val MAX_MODEL_TRIANGLES = 3_000_000

/** Model added to the project: file name, the files chosen, what was read and where it sits. */
data class ModelEntry(val name: String, val uris: List<Uri>, val model: Model3D, val placement: ModelPlacement)

/** Name of the designed platform surface. */
const val PLATFORM_LABEL = "Plataforma"

/** Name of the designed road surfaces and the suffix of its axis. */
const val ROAD_LABEL = "Corredor"
const val ROAD_SUFFIX = " (projeto)"
/** Name the section sheets give the axis drawn with PIs (road axis not built yet). */
const val PIS_AXIS = "\u0001PIs"

/** File laid over the open project; [uri] is null for a surface the app generated. */
data class AddedFile(val label: String, val project: LandXmlProject, val uri: Uri?)

/** Suffix of a surface made by editing another. */
const val EDITED_SUFFIX = " (editada)"

/** Placement that leaves a drawing where it is. */
private val IDENTITY = Placement(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, "0")

/** Picture laid over the surfaces ("imagem sobre a superfície"), placed at [bounds] in plan. */
/** Picture over the surfaces; a very large one also comes in [tiles], drawn on top of [image] (its overview). */
class Drape(
    val name: String,
    val bounds: Bounds,
    val image: ImageBitmap,
    val placement: String,
    val tiles: TiledPicture? = null,
    /** 0 normal, 1 high, 2 highest. The 3D upload reads this. */
    val quality: Int = 2,
    /** Plan window to upload. Null uploads [bounds]. */
    val focus: Bounds? = null,
)

/** Decoded picture, with its plan extent when the file carries one and its size in the file. */
private class Picture(val image: ImageBitmap, val extent: Bounds?, val sourceWidth: Int, val sourceHeight: Int)

/** Extensions of world files, which place a JPG, PNG or TIFF picture in plan. */
private val WORLD_FILE_EXTENSIONS = setOf("jgw", "jpgw", "jpegw", "pgw", "pngw", "tfw", "tifw", "tiffw", "wld", "bpw", "gfw")

/** A cloud file copied into the cache, under the name the provider gave it. */
private class CloudCopy(val file: java.io.File, val name: String, var exported: Boolean)

class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<ProjectState>(ProjectState.Empty)
        private set

    /** Plan only: no top bar, rail, side panel, footer or system bars. */
    var fullScreen by mutableStateOf(false)

    /** Raster files loaded for DXF images, by the path the drawing stores. */
    var rasters by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set
    /** Very large pictures of the drawing's images, drawn in tiles (placed in the image's own pixels), by file path. */
    var tiledRasters by mutableStateOf<Map<String, TiledPicture>>(emptyMap())
        private set

    /** Files loading in the background (images, xrefs), shown with their progress. */
    var tasks by mutableStateOf<List<LoadTask>>(emptyList())
        private set

    // ---- Google Drive: files are downloaded once to a local copy, with progress, then read from it ----

    /** Local copies of cloud files opened in this session, by their document. */
    private val cloudCopies = java.util.concurrent.ConcurrentHashMap<Uri, CloudCopy>()
    private val cacheMutex = Mutex()

    private fun cached(uri: Uri): java.io.File? = cloudCopies[uri]?.file?.takeIf { it.isFile }

    /** Cloud copies in the cache that have not been written to Downloads yet. */
    fun unexportedCloudCount(): Int = cloudCopies.values.count { !it.exported && it.file.isFile }

    /**
     * Copies those files into the public Downloads folder, keeping their names.
     * False when any copy fails. A file that did land is not asked about again.
     */
    fun exportCloudCopies(): Boolean {
        val pending = cloudCopies.values.filter { !it.exported && it.file.isFile }
        if (pending.isEmpty()) return true
        val taken = HashSet<String>()
        var ok = true
        val app = getApplication<Application>()
        for (copy in pending) {
            val saved = PublicDownloads.save(app, copy.name, DownloadNames.mime(copy.name), copy.file, taken)
            if (saved) copy.exported = true else ok = false
        }
        return ok
    }

    /** The file's content: its downloaded copy when there is one. */
    private fun input(uri: Uri): java.io.InputStream? =
        cached(uri)?.inputStream() ?: getApplication<Application>().contentResolver.openInputStream(uri)

    /** Size the provider reports without downloading the file, or null. */
    private fun reportedSize(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /**
     * Copies a cloud document into the cache so TIFF readers can seek it. A second call for the
     * same URI does nothing once the copy is in place. Local files are left where they are.
     */
    private suspend fun ensureCached(uri: Uri, onCancel: (() -> Unit)? = null) {
        if (!PickedFile.needsStreamedCopy(uri.scheme, uri.authority)) return
        cacheMutex.withLock {
            if (cached(uri) != null) return@withLock
            tracked(tr("Abrindo {0}", displayName(uri)), onCancel) { download(uri, it) }
        }
    }

    /** The cached copy when this URI was streamed, otherwise the provider's own descriptor. */
    private fun openLocal(uri: Uri): ParcelFileDescriptor? =
        cached(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
            ?: getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")

    private fun mimeOf(uri: Uri): String? =
        runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()

    private fun headBytes(uri: Uri): ByteArray = runCatching {
        input(uri)?.use { s ->
            val b = ByteArray(16)
            val n = s.read(b)
            if (n <= 0) ByteArray(0) else b.copyOf(n)
        }
    }.getOrNull() ?: ByteArray(0)

    /** Lays [uri] on the open project. Does not replace the drawing. */
    private fun attachImage(uri: Uri) {
        if (state !is ProjectState.Loaded) return
        loadDrape(listOf(uri))
    }

    /**
     * Copies a cloud file into the app's cache, showing the bytes received on [task].
     * Throws CancellationException when the user cancels. A copy already in place is left as it is.
     */
    private suspend fun download(uri: Uri, task: LoadTask) = withContext(Dispatchers.IO) {
        if (cached(uri) != null) return@withContext
        val drive = CloudDocs.isDrive(uri.toString())
        val total = reportedSize(uri)?.takeIf { it > 0 }
        val dir = java.io.File(getApplication<Application>().cacheDir, "cloud").apply { mkdirs() }
        val file = java.io.File(dir, Integer.toHexString(uri.toString().hashCode()))
        val display = displayName(uri).ifBlank { "arquivo" }
        task.step(if (drive) tr("Aguardando o Google Drive…") else tr("Aguardando o arquivo…"))
        val job = coroutineContext[Job]!!
        try {
            (getApplication<Application>().contentResolver.openInputStream(uri) ?: error(tr("arquivo indisponível"))).use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var shown = -1L
                    while (true) {
                        if (!job.isActive) throw kotlinx.coroutines.CancellationException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // Updates about every 256 kB, so the screen keeps up without slowing the copy.
                        if (done - shown >= 1 shl 18) {
                            shown = done
                            fun mb(b: Long) = StationFormat.decimal(b / 1048576.0, 1)
                            task.step((if (drive) tr("Baixando do Google Drive · ") else tr("Baixando · ")) + mb(done) + (total?.let { " de " + mb(it) } ?: "") + " MB",
                                total?.let { (done.toFloat() / it).coerceAtMost(1f) })
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        cloudCopies[uri] = CloudCopy(file, display, exported = false)
    }

    /** Dialogs showing [tasks] right now; the card over the plan hides while there is one. */
    var taskViewers by mutableStateOf(0)

    /** Processed copies of large files, so they reopen fast. */
    private val openCache = OpenCache(app)

    /** The open, or the file being opened, so a download can be cancelled. */
    private var openJob: kotlinx.coroutines.Job? = null

    private suspend fun <T> tracked(label: String, onCancel: (() -> Unit)? = null, block: suspend (LoadTask) -> T): T {
        val task = LoadTask(label, onCancel)
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

    /** Files of the picture over the surfaces, for saving the project. */
    var drapeUris: List<Uri> = emptyList()
        private set

    /**
     * [d] for the 3D view at [quality] (0 normal, 1 high, 2 highest).
     * A tiled picture keeps its tiles: the GPU reads native (or half, or quarter) tiles itself,
     * cropped to the surfaces. A picture already decoded is cropped, and Alta or Normal scales it.
     * Nothing is reduced ahead of an out-of-memory failure.
     */
    suspend fun drapeFor3d(d: Drape, quality: Int): Drape = withContext(Dispatchers.IO) {
        val q = quality.coerceIn(0, 2)
        val focus = surfaceFocus(d.bounds)
        if (d.tiles != null) {
            return@withContext Drape(d.name, d.bounds, d.image, d.placement, d.tiles, q, focus)
        }
        val src = d.image.asAndroidBitmap()
        if (src.isRecycled || src.width <= 0 || src.height <= 0) {
            return@withContext Drape(d.name, d.bounds, d.image, d.placement, null, q, focus)
        }
        val cut = try {
            cropForQuality(d, src, focus ?: d.bounds, DrapeQuality.reduce(q))
        } catch (_: OutOfMemoryError) {
            null
        }
        cut?.let { (bmp, b) -> Drape(d.name, b, bmp.asImageBitmap(), d.placement, null, q) }
            ?: Drape(d.name, d.bounds, d.image, d.placement, null, q, focus)
    }

    /** Surfaces, plus a small margin, when the picture actually covers them. */
    private fun surfaceFocus(picture: Bounds): Bounds? {
        val project = (state as? ProjectState.Loaded)?.project ?: return null
        val area = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return null
        val m = maxOf(area.width, area.height) * 0.02
        val grown = Bounds(area.minX - m, area.minY - m, area.maxX + m, area.maxY + m)
        return if (overlaps(grown, picture)) grown else null
    }

    /**
     * [src] cut to [area], then scaled by [reduce] (1 keeps every pixel). Null when the crop is the
     * whole picture and [reduce] is 1, so the caller keeps the bitmap it already has.
     */
    private fun cropForQuality(d: Drape, src: Bitmap, area: Bounds, reduce: Int): Pair<Bitmap, Bounds>? {
        val px = d.bounds.width / src.width
        val py = d.bounds.height / src.height
        if (px <= 0.0 || py <= 0.0) return null
        val x0 = ((area.minX - d.bounds.minX) / px).toInt().coerceIn(0, src.width)
        val x1 = kotlin.math.ceil((area.maxX - d.bounds.minX) / px).toInt().coerceIn(0, src.width)
        val y0 = ((d.bounds.maxY - area.maxY) / py).toInt().coerceIn(0, src.height)
        val y1 = kotlin.math.ceil((d.bounds.maxY - area.minY) / py).toInt().coerceIn(0, src.height)
        if (x1 <= x0 || y1 <= y0) return null
        val full = x0 == 0 && y0 == 0 && x1 == src.width && y1 == src.height
        val k = reduce.coerceAtLeast(1)
        if (full && k == 1) return null
        val cropped = if (full) src else Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        val plan = Bounds(
            d.bounds.minX + x0 * px, d.bounds.maxY - y1 * py,
            d.bounds.minX + x1 * px, d.bounds.maxY - y0 * py,
        )
        if (k == 1) return cropped to plan
        val w = ((cropped.width + k - 1) / k).coerceAtLeast(1)
        val h = ((cropped.height + k - 1) / k).coerceAtLeast(1)
        val config = if (cropped.hasAlpha()) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val out = try {
            createBitmap(w, h, config)
        } catch (e: OutOfMemoryError) {
            if (cropped !== src && !cropped.isRecycled) cropped.recycle()
            throw e
        }
        android.graphics.Canvas(out).drawBitmap(
            cropped,
            android.graphics.Rect(0, 0, cropped.width, cropped.height),
            android.graphics.Rect(0, 0, w, h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        if (cropped !== src && !cropped.isRecycled) cropped.recycle()
        return out to plan
    }

    fun removeDrape() {
        drapeUris = emptyList()
        drape?.tiles?.close()
        drape = null
        drapeMessage = null
    }

    /**
     * Lays the chosen picture over the surfaces. [uris] holds the picture and, optionally, its world
     * file (.jgw, .pgw, .tfw). A GeoTIFF places itself; a picture with no placement is stretched over
     * the surfaces' extent.
     */
    fun loadDrape(uris: List<Uri>, fromState: Boolean = false) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        // A picture chosen while a state is shown becomes that state's picture.
        if (!fromState) activeState?.let { a -> states.getOrNull(a)?.let { st ->
            states = states.toMutableList().apply { set(a, st.copy(image = uris.map { it.toString() }, imageName = pictureName(uris.map { displayName(it) }))) }
        } }
        val named = uris.map { it to displayName(it) }
        val world = named.firstOrNull { it.second.substringAfterLast('.', "").lowercase() in WORLD_FILE_EXTENSIONS }
        val picture = named.firstOrNull { it !== world }
        drapeMessage = null
        if (picture == null) {
            drapeMessage = tr("Escolha também a imagem (TIFF, JPG ou PNG), não só o arquivo de georreferência.")
            projectMessage = drapeMessage
            return
        }
        val (uri, name) = picture
        uris.forEach { u ->
            runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        viewModelScope.launch { drapeNow(project, uri, name, world, uris) }
    }

    private suspend fun drapeNow(project: LandXmlProject, uri: Uri, name: String, world: Pair<Uri, String>?, uris: List<Uri>) {
        run {
            try {
                for (u in uris) ensureCached(u)
                // Very large TIFFs are shown in tiles, never decoded whole.
                tiledDrape(project, uri, name, world, uris)?.let { projectMessage = drapeMessage; return }
                val t0 = System.nanoTime()
                val pic = tracked(tr("Imagem {0}", name)) { t -> withContext(Dispatchers.IO) { decodePicture(uri, t) } }
                    ?: error(tr("formato de imagem não reconhecido"))
                android.util.Log.i("Gromatic", "imagem $name: ${(System.nanoTime() - t0) / 1_000_000} ms, ${pic.sourceWidth}x${pic.sourceHeight}")
                val surfaces = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                val area = surfaces ?: project.bounds ?: error(tr("projeto sem área"))
                val worldText = world?.let { (w, _) ->
                    withContext(Dispatchers.IO) {
                        input(w)?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    }
                }
                val (bounds, placement) = when {
                    worldText != null -> WorldFile.extent(worldText, pic.sourceWidth, pic.sourceHeight) to tr("posicionada pelo arquivo {0}", world?.second)
                    pic.extent != null -> pic.extent to tr("posicionada pelo GeoTIFF")
                    else -> area to tr("esticada sobre a superfície (a imagem não tem georreferência)")
                }
                drape = Drape(name, bounds, pic.image, placement)
                drapeUris = uris
                drapeVisible = true
                drapeMessage = if (!overlaps(bounds, area)) {
                    tr("A imagem foi {0}, mas não cai sobre a superfície. Confira se as duas estão no mesmo sistema de coordenadas.", placement)
                } else {
                    tr("Imagem {0}.", placement)
                }
                projectMessage = drapeMessage
            } catch (e: OutOfMemoryError) {
                drapeMessage = tr("Imagem grande demais para a memória deste aparelho.")
                projectMessage = drapeMessage
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                drapeMessage = tr("Não foi possível usar a imagem: {0}", errorText(e))
                projectMessage = drapeMessage
            }
        }
    }

    private fun overlaps(a: Bounds, b: Bounds) = a.minX < b.maxX && b.minX < a.maxX && a.minY < b.maxY && b.minY < a.maxY

    // ---- Files added to the open project and surface comparison ----

    /** Other files laid over the open one (name shown, read project), in the order added. */
    var addedFiles by mutableStateOf<List<AddedFile>>(emptyList())
        private set
    var compareMessage by mutableStateOf<String?>(null)
        private set
    /** Added files switched off: kept in the project but left out of the drawing and the tools. */
    var hiddenFiles by mutableStateOf<Set<String>>(emptySet())
        private set

    fun setFileVisible(label: String, visible: Boolean) {
        hiddenFiles = if (visible) hiddenFiles - label else hiddenFiles + label
        refreshProject()
    }

    /** Files read from disk or the cloud and laid over the open one (a mosaic of projects). */
    val sourceFiles get() = addedFiles.filter { it.uri != null }

    /** Adds several files at once, one after the other, for a mosaic of projects. */
    fun addFiles(uris: List<Uri>) {
        for (uri in uris) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        compareMessage = null
        viewModelScope.launch { for (uri in uris) addFileNow(uri) }
    }

    /** B − A of the last comparison and its color picture, shown while [differenceVisible]. */
    var difference by mutableStateOf<SurfaceDifference?>(null)
        private set
    var differenceImage by mutableStateOf<TerrainImage?>(null)
        private set
    var differenceVisible by mutableStateOf(true)

    /** Reads another file and lays it over the open project; its surfaces are named after the file. */
    fun addFile(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        compareMessage = null
        viewModelScope.launch { addFileNow(uri) }
    }

    private suspend fun addFileNow(uri: Uri, wantedLabel: String? = null) {
        val name = displayName(uri)
        try {
            ensureCached(uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            compareMessage = tr("Não foi possível baixar o arquivo: {0}", errorText(e))
            projectMessage = compareMessage
            return
        }
        val image = withContext(Dispatchers.IO) { PickedFile.isImage(name, mimeOf(uri), headBytes(uri)) }
        if (image) {
            if (state is ProjectState.Loaded) attachImage(uri)
            else projectMessage = tr("{0} é uma imagem: use Imagem > Trocar para colocá-la na planta.", name)
            return
        }
        try { addFileRead(uri, name, wantedLabel) } finally { compareMessage?.let { projectMessage = it } }
    }

    private suspend fun addFileRead(uri: Uri, name: String, wantedLabel: String?) {
        run {
            try {
                val drawing = tracked(tr("Adicionando {0}", name)) { t ->
                    withContext(Dispatchers.IO) { read(uri, t) }
                }
                if (drawing.surfaces.isEmpty() && drawing.alignments.isEmpty() && drawing.cgPoints.isEmpty() && drawing.polylines.isEmpty()) {
                    compareMessage = tr("{0} não tem superfície, alinhamento, ponto nem linha.", name)
                    return
                }
                // Unique label, so the same file name added twice still reads apart.
                var label = wantedLabel ?: name.substringBefore(".xml.aecc.pnt").substringBeforeLast('.')
                var k = 2
                while (addedFiles.any { it.label == label }) label = name.substringBeforeLast('.') + " ($k)".also { k++ }
                addedFiles = addedFiles + AddedFile(label, drawing, uri)
                refreshJob?.cancel()
                refreshNow()
                compareMessage = "$name adicionado: " + listOfNotNull(
                    drawing.surfaces.size.takeIf { it > 0 }?.let { tr("{0} superfície(s)", it) },
                    drawing.alignments.size.takeIf { it > 0 }?.let { "$it alinhamento(s)" },
                    drawing.cgPoints.size.takeIf { it > 0 }?.let { "$it ponto(s)" },
                    drawing.polylines.size.takeIf { it > 0 }?.let { "${it} linha(s)" },
                ).joinToString(", ") + "."
            } catch (e: OutOfMemoryError) {
                compareMessage = tr("Arquivo grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                compareMessage = tr("Não foi possível ler {0}: {1}", name, errorText(e))
            }
        }
    }

    fun removeFile(label: String) {
        if (addedFiles.none { it.label == label }) return
        if (states.any { it.file == label }) {
            activeState = null; hideMain = false
            hiddenFiles = hiddenFiles - states.map { it.file }.toSet()
            states = states.filter { it.file != label }.takeUnless { it.size == 1 && it[0].file.isEmpty() }.orEmpty()
            statePeriods = emptyList(); stateTotal = null
        }
        addedFiles = addedFiles.filter { it.label != label }
        hiddenFiles = hiddenFiles - label
        fun fromFile(n: String) = n == label || n.startsWith("$label|")
        crossSelected = crossSelected.filterNot(::fromFile)
        matrix = matrix.filterKeys { (a, b) -> !fromFile(a) && !fromFile(b) }
        stageProgress = null
        val d = difference
        if (d != null && (fromFile(d.baseName) || fromFile(d.otherName))) clearDifference()
        refreshProject()
    }

    fun clearDifference() {
        difference = null
        differenceImage = null
    }

    // ---- Survey states: the works timeline (primitiva, medição 1, 2…) ----

    /** States of the works in date order; the first holds the project's own drawing once a measurement is added. */
    var states by mutableStateOf<List<SurveyState>>(emptyList())
        private set
    /** State shown alone on the plan and in 3D; null shows every file together. */
    var activeState by mutableStateOf<Int?>(null)
        private set
    /** Volumes between each state and the one before, and from the first to the last. */
    var statePeriods by mutableStateOf<List<SurfaceDifference>>(emptyList())
        private set
    var stateTotal by mutableStateOf<SurfaceDifference?>(null)
        private set
    /** True while the timeline bar is on the plan. */
    var timelineShown by mutableStateOf(false)
    /** Set while a state of an added file is shown: the drawing's own surfaces step aside. */
    private var hideMain = false

    private fun today(): String = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ROOT).format(java.util.Date())

    /** Name of the picture among chosen files (not its world file). */
    private fun pictureName(names: List<String>) = names.firstOrNull { it.substringAfterLast('.', "").lowercase() !in WORLD_FILE_EXTENSIONS }.orEmpty()

    private fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase().let { it in WORLD_FILE_EXTENSIONS || it in setOf("tif", "tiff", "jpg", "jpeg", "png", "webp") }

    /**
     * Adds a measurement: its LandXML/DXF and, optionally, its picture (TIFF and world file) chosen
     * together. The first one also turns the project's own drawing into the primitiva.
     */
    fun addState(uris: List<Uri>) {
        val named = uris.map { it to displayName(it) }
        val survey = named.firstOrNull { !isPicture(it.second) }
        if (survey == null) {
            compareMessage = tr("Escolha o XML ou DXF da medição junto com a imagem.")
            return
        }
        uris.forEach { u -> runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val pictures = named.filter { it !== survey && isPicture(it.second) }
        val image = pictures.map { it.first.toString() }
        val imageName = pictureName(pictures.map { it.second })
        compareMessage = null
        viewModelScope.launch {
            if (states.isEmpty()) states = listOf(SurveyState(tr("Primitiva"), "", "", drapeUris.map { it.toString() }, drape?.name.orEmpty()))
            val name = tr("Medição {0}", states.size)
            val before = addedFiles.size
            addFileNow(survey.first, name)
            if (addedFiles.size == before) return@launch
            val added = SurveyState(name, today(), addedFiles.last().label, image, imageName)
            states = (states + added).sortedBy { it.sortKey }
            showState(states.indexOf(added))
        }
    }

    /** Renames or re-dates state [i]; the timeline keeps date order. */
    fun editState(i: Int, name: String, date: String) {
        val old = states.getOrNull(i) ?: return
        val shown = activeState?.let { states.getOrNull(it) }
        val edited = old.copy(name = name.trim().ifEmpty { old.name }, date = date.trim())
        states = states.toMutableList().apply { set(i, edited) }.sortedBy { it.sortKey }
        activeState = shown?.let { s -> states.indexOfFirst { it.file == s.file } }?.takeIf { it >= 0 }
        statePeriods = emptyList(); stateTotal = null
    }

    /** Takes state [i] out of the timeline, and its file out of the project (the primitiva's drawing stays). */
    fun removeState(i: Int) {
        val s = states.getOrNull(i) ?: return
        if (activeState != null) showAllStates()
        states = states.filterIndexed { k, _ -> k != i }
        if (states.size == 1 && states[0].file.isEmpty()) states = emptyList()
        statePeriods = emptyList(); stateTotal = null
        if (s.file.isNotEmpty()) removeFile(s.file)
    }

    /** Shows state [i] alone: its surfaces and lines, and its picture over them. */
    fun showState(i: Int) {
        val s = states.getOrNull(i) ?: return
        activeState = i
        val files = states.map { it.file }.filter { it.isNotEmpty() }.toSet()
        hiddenFiles = hiddenFiles - files + (files - s.file)
        hideMain = s.file.isNotEmpty()
        refreshProject()
        if (s.image.isNotEmpty()) loadDrape(s.image.map(Uri::parse), fromState = true) else removeDrape()
    }

    /** Every state together again, as plain files. */
    fun showAllStates() {
        activeState = null
        hiddenFiles = hiddenFiles - states.map { it.file }.toSet()
        hideMain = false
        refreshProject()
    }

    /** The surface standing for state [s]: the largest of its file, named after the state. */
    private fun stateSurface(s: SurveyState): Surface? {
        val surfaces = if (s.file.isEmpty()) base?.surfaces.orEmpty() else addedFiles.firstOrNull { it.label == s.file }?.project?.surfaces.orEmpty()
        val big = surfaces.maxByOrNull { it.triangleCount } ?: return null
        return Surface(s.name, big.xs, big.ys, big.zs, big.triangles)
    }

    /** Cut and fill from each state to the next, and from the first to the last. */
    fun computeStatePeriods() {
        val surfaces = states.map { stateSurface(it) }
        val missing = states.filterIndexed { k, _ -> surfaces[k] == null }.map { it.name }
        if (missing.isNotEmpty()) {
            compareMessage = tr("Sem superfície em: {0}.", missing.joinToString(", "))
            return
        }
        val list = surfaces.filterNotNull()
        if (list.size < 2) return
        compareMessage = null
        viewModelScope.launch {
            try {
                val (periods, total) = tracked(tr("Volumes entre estados")) { t ->
                    withContext(Dispatchers.Default) {
                        val out = ArrayList<SurfaceDifference>()
                        for (k in 1 until list.size) {
                            t.step(tr("{0} → {1}", list[k - 1].name, list[k].name), k.toFloat() / list.size)
                            out += SurfaceCompare.difference(list[k - 1], list[k], maxCells = 1_000_000) ?: error(tr("{0} e {1} não se sobrepõem.", list[k - 1].name, list[k].name))
                        }
                        out to (if (list.size > 2) SurfaceCompare.difference(list.first(), list.last(), maxCells = 1_000_000) else out.first())
                    }
                }
                statePeriods = periods
                stateTotal = total
            } catch (e: OutOfMemoryError) {
                compareMessage = tr("Superfícies grandes demais para comparar neste aparelho.")
            } catch (e: IllegalStateException) {
                compareMessage = e.message
            }
        }
    }

    /** Shows on the plan the color map of a difference between states. */
    fun showStateDifference(d: SurfaceDifference) {
        viewModelScope.launch {
            differenceImage = withContext(Dispatchers.Default) { DifferenceImage.of(d) }
            difference = d
            differenceVisible = true
        }
    }

    // ---- Cross comparison of several surfaces ----

    /** Surfaces marked for the cross comparison, in stage order (original ground first, design last). */
    var crossSelected by mutableStateOf<List<String>>(emptyList())
        private set
    /** B − A for every pair of marked surfaces, keyed (A, B) with A before B in [crossSelected]. */
    var matrix by mutableStateOf<Map<Pair<String, String>, SurfaceDifference>>(emptyMap())
        private set
    var stageProgress by mutableStateOf<StageProgress?>(null)
        private set

    fun toggleCross(name: String) {
        crossSelected = if (name in crossSelected) crossSelected - name else crossSelected + name
    }

    /** Moves a marked surface [delta] places in the stage order. */
    fun moveCross(name: String, delta: Int) {
        val i = crossSelected.indexOf(name)
        val j = (i + delta).coerceIn(0, crossSelected.size - 1)
        if (i < 0 || i == j) return
        crossSelected = crossSelected.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Compares every pair of marked surfaces, on a coarser grid so many pairs stay quick. */
    fun computeMatrix() {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val names = crossSelected
        val surfaces = names.mapNotNull { n -> project.surfaces.firstOrNull { it.name == n } }
        compareMessage = null
        viewModelScope.launch {
            try {
                matrix = tracked(tr("Comparação cruzada")) { t ->
                    withContext(Dispatchers.Default) {
                        val out = LinkedHashMap<Pair<String, String>, SurfaceDifference>()
                        val pairs = surfaces.size * (surfaces.size - 1) / 2
                        var k = 0
                        for (i in surfaces.indices) for (j in i + 1 until surfaces.size) {
                            t.step(tr("Par {0} de {1}", ++k, pairs), k.toFloat() / pairs)
                            SurfaceCompare.difference(surfaces[i], surfaces[j], maxCells = 500_000)
                                ?.let { out[surfaces[i].name to surfaces[j].name] = it }
                        }
                        out
                    }
                }
            } catch (e: OutOfMemoryError) {
                compareMessage = tr("Superfícies grandes demais para comparar neste aparelho.")
            }
        }
    }

    /** row − column of the matrix, or null when the two do not overlap. */
    fun matrixCell(row: String, column: String): SurfaceDifference? =
        matrix[column to row] ?: matrix[row to column]?.inverted()

    /** Shows on the plan the B − A map of a matrix cell (B = [row], A = [column]). */
    fun showCell(row: String, column: String) {
        val d = matrixCell(row, column) ?: return
        viewModelScope.launch {
            differenceImage = withContext(Dispatchers.Default) { DifferenceImage.of(d) }
            difference = d
            differenceVisible = true
        }
    }

    /** Volumes per period through the marked surfaces in order, and what is left to the last one. */
    fun computeStages() {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val surfaces = crossSelected.mapNotNull { n -> project.surfaces.firstOrNull { it.name == n } }
        if (surfaces.size < 2) return
        compareMessage = null
        viewModelScope.launch {
            try {
                val p = tracked(tr("Comparação por etapas")) { withContext(Dispatchers.Default) { SurfaceCompare.stages(surfaces) } }
                stageProgress = p
                if (p == null) compareMessage = tr("Há etapas que não se sobrepõem.")
            } catch (e: OutOfMemoryError) {
                compareMessage = tr("Superfícies grandes demais para comparar neste aparelho.")
            }
        }
    }

    /** Compares surface [other] (B) against [base] (A): colored B − A and cut/fill volumes. */
    fun compare(base: String, other: String) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val a = project.surfaces.firstOrNull { it.name == base } ?: return
        val b = project.surfaces.firstOrNull { it.name == other } ?: return
        compareMessage = null
        viewModelScope.launch { compareNow(a, b) }
    }

    private suspend fun compareNow(a: Surface, b: Surface) {
        run {
            try {
                val result = tracked(tr("Comparando superfícies")) { t ->
                    withContext(Dispatchers.Default) {
                        t.step(tr("Calculando diferença e volumes"))
                        SurfaceCompare.difference(a, b)?.let { it to DifferenceImage.of(it) }
                    }
                }
                if (result == null) {
                    compareMessage = tr("As duas superfícies não se sobrepõem.")
                    clearDifference()
                } else {
                    difference = result.first
                    differenceImage = result.second
                    differenceVisible = true
                }
            } catch (e: OutOfMemoryError) {
                compareMessage = tr("Superfícies grandes demais para comparar neste aparelho.")
            }
        }
    }

    // ---- COGO points (layer _POINTS) ----

    /** Points imported from PDENZ files or created on the plan. */
    var userPoints by mutableStateOf<List<CogoPoint>>(emptyList())
        private set
    var pointsMessage by mutableStateOf<String?>(null)
        private set

    /** Reads a PDENZ point file; a point whose number is already in use replaces the old one. */
    fun importPoints(uri: Uri) {
        val name = displayName(uri)
        pointsMessage = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(tr("arquivo indisponível"))
                    // Civil 3D writes ANSI on Windows; UTF-8 when the bytes are valid UTF-8.
                    val text = runCatching {
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
                    PointFile.read(text)
                }
                val byNumber = LinkedHashMap<String, CogoPoint>()
                userPoints.forEach { byNumber[it.number] = it }
                result.points.forEach { byNumber[it.number] = it }
                userPoints = byNumber.values.toList()
                val skipped = result.skipped
                pointsMessage = "$name: ${result.points.size} ponto(s) importado(s)." + if (skipped.isEmpty()) "" else
                    tr(" {0} linha(s) ignorada(s) por não estarem no formato número, descrição, E, N, cota: ", skipped.size) +
                        skipped.take(8).joinToString(", ") { "linha ${it.first}" } + if (skipped.size > 8) "…" else ""
            } catch (e: Exception) {
                pointsMessage = tr("Não foi possível ler {0}: {1}", name, errorText(e))
            }
        }
    }

    /** Writes the points as PDENZ (comma separated, 3 decimals) to [target]. */
    fun exportPoints(target: Uri) {
        val points = userPoints
        viewModelScope.launch {
            pointsMessage = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.use {
                        it.write(PointFile.write(points).toByteArray(Charsets.UTF_8))
                    } ?: error(tr("destino indisponível"))
                }
                "${points.size} ponto(s) exportado(s) para ${displayName(target)}."
            } catch (e: Exception) {
                tr("Não foi possível exportar: {0}", errorText(e))
            }
        }
    }

    /** Points of the last "generate points", for undoing it. */
    var lastGenerated by mutableStateOf<List<CogoPoint>>(emptyList())
        private set

    fun addGenerated(points: List<CogoPoint>) {
        userPoints = userPoints + points
        lastGenerated = points
        pointsMessage = tr("{0} ponto(s) gerado(s) na camada {1}.", points.size, POINTS_LAYER)
    }

    fun undoGenerated() {
        val batch = lastGenerated.toSet()
        userPoints = userPoints.filter { it !in batch }
        pointsMessage = "${batch.size} ponto(s) gerado(s) removido(s)."
        lastGenerated = emptyList()
    }

    /** Adds [point], or replaces [old] with it when editing. */
    fun savePoint(old: CogoPoint?, point: CogoPoint) {
        val rest = userPoints.filter { it != old && it.number != point.number }
        val i = old?.let { userPoints.indexOf(it) } ?: -1
        userPoints = if (i >= 0) rest.toMutableList().apply { add(i.coerceAtMost(size), point) } else rest + point
    }

    fun deletePoint(point: CogoPoint) {
        userPoints = userPoints - point
    }

    /** Triangulates the points that have an elevation into a surface laid over the project. */
    // ---- Automatic drawing of survey points ----

    private val codePrefs = app.getSharedPreferences("pointCodes", Context.MODE_PRIVATE)

    /** Field codes of the device, editable; the defaults until the user changes them. */
    var pointCodes by mutableStateOf(
        codePrefs.getString("table", null)?.let { runCatching { com.appdwg.core.PointCodes.fromTsv(it) }.getOrNull()?.takeIf { l -> l.isNotEmpty() } }
            ?: com.appdwg.core.PointCodes.defaults)
        private set

    fun savePointCodes(codes: List<com.appdwg.core.PointCode>) {
        pointCodes = codes
        codePrefs.edit { putString("table", com.appdwg.core.PointCodes.toTsv(codes)) }
    }

    /** Points the automatic drawing works on: the imported ones, or else those of the drawing. */
    private fun surveyPoints(): List<CogoPoint> = userPoints.ifEmpty {
        (state as? ProjectState.Loaded)?.project?.cgPoints.orEmpty().map { c -> CogoPoint(c.name, c.code ?: "", c.position.x, c.position.y, c.position.z) }
    }

    /**
     * Joins the survey points of each line code into 3D polylines, as editable lines; a new run
     * replaces the lines of the previous one (undo brings them back).
     */
    fun autoDraw() {
        val pts = surveyPoints()
        val lines = com.appdwg.core.Linework.build(pts, pointCodes)
        if (lines.isEmpty()) {
            pointsMessage = tr("Nenhum ponto com descrição de linha (PE, CR, BORDO…). Confira a tabela de códigos.")
            return
        }
        val layers = pointCodes.filter { it.connect }.map { it.layer }.toSet()
        val previous = userLines.indices.filter { userLines[it].layer in layers }.map { it + keptOriginals.size }
        editLines(previous, lines.map { it.polyline() }, replace = true)
        val byCode = lines.groupBy { it.code.name }.map { (n, l) -> "$n ${l.size}" }
        pointsMessage = tr("Desenho automático: {0} linha(s) ({1}). Ajuste no editor de linhas se preciso.", lines.size, byCode.joinToString(", "))
    }

    /** Lines that the surface must follow: the drawn lines of breakline codes, with heights. */
    private fun breaklines(): List<Polyline> {
        val layers = pointCodes.filter { it.breakline && it.connect }.map { it.layer }.toSet()
        return userLines.filter { it.layer in layers && it.zs != null }
    }

    fun surfaceFromPoints() {
        val pts = userPoints.filter { !it.z.isNaN() }
        if (pts.size < 3) {
            pointsMessage = tr("São precisos 3 pontos com cota para gerar uma superfície.")
            return
        }
        viewModelScope.launch { surfaceFromPointsNow() }
    }

    private suspend fun surfaceFromPointsNow() {
        val pts = userPoints.filter { !it.z.isNaN() }
        if (pts.size < 3) return
        run {
            try {
                val label = tr("{0} superfície", POINTS_LAYER)
                val breaks = breaklines()
                val surface = withContext(Dispatchers.Default) {
                    // Breaklines: extra points along them, a third of their usual segment apart, keep the triangles off them.
                    val segs = breaks.flatMap { l -> (1 until l.xs.size).map { kotlin.math.hypot(l.xs[it] - l.xs[it - 1], l.ys[it] - l.ys[it - 1]) } }.sorted()
                    val step = (segs.getOrNull(segs.size / 2) ?: 3.0).div(3).coerceIn(0.25, 5.0)
                    val all = pts + com.appdwg.core.Linework.densify(breaks, step)
                    // Named like its entry, so it shows as just the label.
                    AeccPointCache.triangulate(
                        label,
                        DoubleArray(all.size) { all[it].x }, DoubleArray(all.size) { all[it].y }, DoubleArray(all.size) { all[it].z },
                    )
                }
                addedFiles = addedFiles.filter { it.label != label } +
                    AddedFile(label, LandXmlProject(null, null, listOf(surface), emptyList(), emptyList(), emptyList()), null)
                refreshJob?.cancel()
                refreshNow()
                pointsMessage = tr("Superfície \"{0}\" gerada com {1} pontos ({2} triângulos).", label, pts.size, surface.triangleCount)
                if (breaks.isNotEmpty()) pointsMessage = pointsMessage.orEmpty() + " " + tr("{0} linha(s) obrigatória(s) respeitada(s).", breaks.size)
            } catch (e: Exception) {
                pointsMessage = tr("Não foi possível gerar a superfície: {0}", errorText(e))
            }
        }
    }

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
                prefs.edit { putString("xref:$path", uri.toString()) }
                refreshProject()
            } catch (e: OutOfMemoryError) {
                referenceMessage = tr("Desenho grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                referenceMessage = tr("Não foi possível ler {0}: {1}", displayName(uri), errorText(e))
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
                    val input = resolver.openInputStream(source) ?: error(tr("arquivo original indisponível"))
                    val output = resolver.openOutputStream(target, "wt") ?: error(tr("destino indisponível"))
                    input.bufferedReader(Charsets.UTF_8).use { r ->
                        output.bufferedWriter(Charsets.UTF_8).use { w -> Xrefs.rewritePaths(r, w, pathEdits) }
                    }
                }
                tr("Cópia salva em {0} com {1} caminho(s) alterado(s).", displayName(target), pathEdits.size)
            } catch (e: Exception) {
                tr("Não foi possível salvar: {0}", errorText(e))
            }
        }
    }

    /** Rebuilds the shown project from the base drawing and the loaded, attached xrefs. */
    private var refreshJob: kotlinx.coroutines.Job? = null
    private val terrainCache = java.util.IdentityHashMap<DoubleArray, TerrainImage>()

    // ---- Line editing ----

    /** Lines created or changed by the user, drawn after the drawing's own. */
    var userLines by mutableStateOf<List<Polyline>>(emptyList())
        private set
    /** Indices (in the drawing before edits) of lines removed or replaced by an edit. */
    var hiddenOriginals by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** Drawing line kept at each shown index before the user lines; filled on each rebuild. */
    private var keptOriginals: List<Int> = emptyList()
    private val undoStack = ArrayDeque<Pair<List<Polyline>, Set<Int>>>()
    private val redoStack = ArrayDeque<Pair<List<Polyline>, Set<Int>>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Placed drainage devices, with their own undo. */
    var drainage by mutableStateOf<List<com.appdwg.core.drainage.PlacedDevice>>(emptyList())
        private set
    /** Empty until the album is parsed off the main thread. A failure leaves this empty and sets [drainageCatalogNote]. */
    var drainageCatalog by mutableStateOf(com.appdwg.core.drainage.emptyDeviceCatalog())
        private set
    var drainageCatalogReady by mutableStateOf(false)
        private set
    var drainageCatalogNote by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching { com.appdwg.core.drainage.CatalogLoader.bundled() }
            }
            loaded.onSuccess { bundled ->
                val saved = drainageUserLibrary
                drainageCatalog = if (saved.isNullOrBlank()) bundled
                else runCatching { com.appdwg.core.drainage.CatalogLibraries.apply(bundled, saved) }.getOrDefault(bundled)
                android.util.Log.i("Gromatic", "biblioteca de drenagem: ${drainageCatalog.devices.size} dispositivos")
            }.onFailure { error ->
                android.util.Log.e("Gromatic", "biblioteca de drenagem", error)
                drainageCatalogNote = tr("A biblioteca de drenagem não pôde ser aberta.")
            }
            drainageCatalogReady = true
        }
    }
    var drainageBasins by mutableStateOf<List<com.appdwg.core.drainage.DeviceBasin>>(emptyList())
        private set
    var drainagePhases by mutableStateOf<List<com.appdwg.core.drainage.DevicePhase>>(emptyList())
        private set
    /** User names of drainage alignments. Geometry is derived from the devices. */
    var drainageNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val drainageUndo = ArrayDeque<DrainageHistory>()
    var canUndoDrainage by mutableStateOf(false)
        private set

    /** JSON of the user libraries merged into [drainageCatalog]. Saved inside the project. */
    var drainageUserLibrary by mutableStateOf<String?>(null)
        private set

    fun useDrainageCatalog(catalog: com.appdwg.core.drainage.DeviceCatalog, userJson: String? = null) {
        if (!userJson.isNullOrBlank()) {
            drainageUserLibrary = com.appdwg.core.drainage.CatalogLibraries.append(drainageUserLibrary, userJson)
        }
        drainageCatalog = catalog
        drainageCatalogReady = true
        if (catalog.devices.isNotEmpty()) drainageCatalogNote = null
    }

    /** Drops a project-specific library and returns to the bundled album. */
    private fun clearDrainageLibrary() {
        drainageUserLibrary = null
        if (!drainageCatalogReady) return
        val bundled = runCatching { com.appdwg.core.drainage.CatalogLoader.bundled() }.getOrNull() ?: return
        drainageCatalog = bundled
    }

    /** Merges the library saved in the project. A blank value keeps the bundled album. */
    private suspend fun applySavedDrainageLibrary(json: String?) {
        val saved = json?.takeIf { it.isNotBlank() }
        drainageUserLibrary = saved
        val merged = withContext(Dispatchers.Default) {
            val bundled = runCatching { com.appdwg.core.drainage.CatalogLoader.bundled() }.getOrNull() ?: return@withContext null
            if (saved == null) bundled
            else runCatching { com.appdwg.core.drainage.CatalogLibraries.apply(bundled, saved) }.getOrDefault(bundled)
        } ?: return
        drainageCatalog = merged
        drainageCatalogReady = true
        if (merged.devices.isNotEmpty()) drainageCatalogNote = null
    }

    fun putDrainage(device: com.appdwg.core.drainage.PlacedDevice) {
        val next = if (drainage.any { it.id == device.id }) drainage.map { if (it.id == device.id) device else it } else drainage + device
        pushDrainage(next)
    }

    fun deleteDrainage(id: String) {
        pushDrainage(
            drainage.filter { it.id != id },
            drainageBasins.filter { it.deviceId != id },
            drainagePhases.filter { it.deviceId != id },
        )
    }

    /** Assigns [phase] to a device. Phase 0 clears it, so the device stays visible in every stage. */
    fun setDrainagePhase(id: String, phase: Int) {
        val next = drainagePhases.filter { it.deviceId != id }
        val stored = if (phase <= 0) next else next + com.appdwg.core.drainage.DevicePhase(id, phase)
        pushDrainage(drainage, drainageBasins, stored)
    }

    /**
     * Places a catalog culvert on every thalweg that crosses [line].
     * Does nothing and calls [onEmpty] when the flow grid is missing or no stream crosses.
     * The grid walk runs off the UI thread.
     */
    fun placeThalwegCulverts(
        line: List<com.appdwg.core.Point2>,
        minArea: Double,
        runoff: Double,
        idf: com.appdwg.core.Idf,
        years: Double,
        minutes: Double,
        gutterCode: String? = null,
        onDone: (Int) -> Unit,
    ) {
        val grid = basinFlow?.second ?: flowGrid
        if (line.size < 2) {
            onDone(0)
            return
        }
        viewModelScope.launch {
            val culverts = if (grid == null) emptyList() else withContext(Dispatchers.Default) {
                com.appdwg.core.drainage.ThalwegCulverts.suggest(
                    com.appdwg.core.Hydrology.crossings(grid, line, minArea),
                    runoff, idf, years, minutes,
                ).map { crossing ->
                    com.appdwg.core.drainage.ThalwegCulverts.device(
                        crossing, line, java.util.UUID.randomUUID().toString(),
                    )
                }
            }
            val gutter = gutterCode?.let { code ->
                com.appdwg.core.drainage.PlacedDevice.Linear(
                    java.util.UUID.randomUUID().toString(),
                    code,
                    com.appdwg.core.drainage.PathSource.POLYLINE,
                    null,
                    line.map { it.x },
                    line.map { it.y },
                    com.appdwg.core.drainage.DeviceSide.RIGHT,
                    3.0, null, null,
                    com.appdwg.core.drainage.GradeSpec(com.appdwg.core.drainage.GradeMode.HYDRAULIC, buried = false),
                    null,
                )
            }
            val devices = culverts + listOfNotNull(gutter)
            if (devices.isEmpty()) onDone(0) else {
                pushDrainage(drainage + devices)
                onDone(devices.size)
            }
        }
    }

    fun setDrainageBasin(id: String, areaM2: Double, runoff: Double, minutes: Double) {
        val next = drainageBasins.filter { it.deviceId != id } + com.appdwg.core.drainage.DeviceBasin(id, areaM2, runoff, minutes)
        pushDrainage(drainage, next)
    }

    fun replaceDrainage(next: List<com.appdwg.core.drainage.PlacedDevice>) {
        pushDrainage(next)
    }

    fun moveDrainage(id: String, to: com.appdwg.core.Point2, links: List<com.appdwg.core.drainage.NetworkLink> = emptyList()) {
        val device = drainage.find { it.id == id } ?: return
        val from = when (device) {
            is com.appdwg.core.drainage.PlacedDevice.Point -> com.appdwg.core.Point2(device.x, device.y)
            is com.appdwg.core.drainage.PlacedDevice.Linear -> if (device.xs.isEmpty()) return else com.appdwg.core.Point2(device.xs.average(), device.ys.average())
        }
        pushDrainage(com.appdwg.core.drainage.DrainageNetwork.chainTranslate(drainage, links, id, to.x - from.x, to.y - from.y))
    }

    fun undoDrainage() {
        val prev = drainageUndo.removeLastOrNull() ?: return
        drainage = prev.devices
        drainageBasins = prev.basins
        drainagePhases = prev.phases
        drainageNames = prev.names
        canUndoDrainage = drainageUndo.isNotEmpty()
    }

    /** Renames a drainage alignment. An empty name returns to the automatic one. */
    fun renameDrainageAlignment(id: String, name: String) {
        val next = drainageNames.toMutableMap()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) next.remove(id) else next[id] = trimmed
        pushDrainage(drainage, drainageBasins, drainagePhases, next)
    }

    /**
     * Reverses flow on the linear devices of one alignment.
     * [currentlyDownstreamAtStart] is the direction the profile is using now.
     */
    fun reverseDrainageFlow(ids: Set<String>, currentlyDownstreamAtStart: Boolean) {
        val next = drainage.map { device ->
            if (device !is com.appdwg.core.drainage.PlacedDevice.Linear || device.id !in ids) device
            else device.copy(grade = device.grade.copy(flowAuto = false, flowToStart = !currentlyDownstreamAtStart))
        }
        pushDrainage(next)
    }

    private fun pushDrainage(
        next: List<com.appdwg.core.drainage.PlacedDevice>,
        basins: List<com.appdwg.core.drainage.DeviceBasin> = drainageBasins,
        phases: List<com.appdwg.core.drainage.DevicePhase> = drainagePhases,
        names: Map<String, String> = drainageNames,
    ) {
        drainageUndo.addLast(DrainageHistory(drainage, drainageBasins, drainagePhases, drainageNames))
        if (drainageUndo.size > 50) drainageUndo.removeFirst()
        drainage = next
        drainageBasins = basins
        drainagePhases = phases
        drainageNames = names
        canUndoDrainage = true
    }

    private class DrainageHistory(
        val devices: List<com.appdwg.core.drainage.PlacedDevice>,
        val basins: List<com.appdwg.core.drainage.DeviceBasin>,
        val phases: List<com.appdwg.core.drainage.DevicePhase>,
        val names: Map<String, String>,
    )

    /** Shown line at [index] (as [LineRef.Drawing] numbers them). */
    fun shownLine(index: Int): Polyline? = (state as? ProjectState.Loaded)?.project?.polylines?.getOrNull(index)

    /**
     * Applies an edit: the shown lines at [sources] are removed when [replace], and [results] added.
     * Returns how many lines were added.
     */
    fun editLines(sources: List<Int>, results: List<Polyline>, replace: Boolean) {
        undoStack.addLast(userLines to hiddenOriginals)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        if (replace) {
            val users = ArrayList(userLines)
            val hidden = HashSet(hiddenOriginals)
            val userIdx = sources.filter { it >= keptOriginals.size }.map { it - keptOriginals.size }.toSet()
            sources.filter { it < keptOriginals.size }.forEach { hidden += keptOriginals[it] }
            userLines = users.filterIndexed { i, _ -> i !in userIdx } + results
            hiddenOriginals = hidden
        } else {
            userLines = userLines + results
        }
        canUndo = true; canRedo = false
        refreshProject()
    }

    fun undoLines() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(userLines to hiddenOriginals)
        userLines = prev.first; hiddenOriginals = prev.second
        canUndo = undoStack.isNotEmpty(); canRedo = true
        refreshProject()
    }

    fun redoLines() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(userLines to hiddenOriginals)
        userLines = next.first; hiddenOriginals = next.second
        canUndo = true; canRedo = redoStack.isNotEmpty()
        refreshProject()
    }

    /** Rebuilds the project in the background; a newer rebuild replaces one still running. */
    private fun refreshProject() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val b = base ?: return
        val current = state as? ProjectState.Loaded ?: return
        run {
            val (project, terrain) = tracked(tr("Atualizando referências")) { withContext(Dispatchers.Default) {
                var p = b.copy(
                    images = b.images.filter { it.file !in detached },
                    xrefs = b.xrefs.filter { it.name !in detached },
                )
                for (x in p.xrefs) {
                    if (x.name in unloaded) continue
                    xrefDrawings[x.name]?.let { p = Xrefs.attach(p, x, it) }
                }
                // Added files sit where they are: an identity placement names their layers and
                // surfaces "file|name".
                for ((label, drawing) in addedFiles) {
                    if (label in hiddenFiles) continue
                    p = Xrefs.attach(p, XrefDrawing(label, label, listOf(IDENTITY), overlay = true), drawing)
                    // Alignments keep their names; one already used gets the file name added.
                    val taken = p.alignments.map { it.name }.toMutableSet()
                    val named = drawing.alignments.map { a ->
                        if (taken.add(a.name)) a else com.appdwg.core.Alignment("${a.name} ($label)", a.startStation, a.elements, a.profile).also { taken += it.name }
                    }
                    // A surface named like its file (point caches) reads better as just the file name.
                    p = p.copy(
                        alignments = p.alignments + named,
                        surfaces = p.surfaces.map { s ->
                            // "Corredor|Corredor (terraplenagem)" reads better as "Corredor (terraplenagem)".
                            val inner = s.name.removePrefix("$label|")
                            if (s.name.startsWith("$label|") && inner.startsWith(label)) Surface(inner, s.xs, s.ys, s.zs, s.triangles) else s
                        },
                    )
                }
                // A measurement shown alone hides the primitiva's surfaces from the drawing itself.
                if (hideMain) p = p.copy(surfaces = p.surfaces.filter { s -> b.surfaces.none { it.name == s.name } })
                p = p.copy(models = models.flatMap { e -> e.model.meshes.map { e.placement.apply(it) } })
                // Line edits: originals the user removed or changed are left out, edited lines added.
                keptOriginals = p.polylines.indices.filter { it !in hiddenOriginals }
                p = p.copy(polylines = keptOriginals.map { p.polylines[it] } + userLines)
                val extra = p.surfaces.filter { s -> baseTerrain.none { it.name == s.name } }
                // Pictures of added surfaces are kept by their point arrays, so an edit does not redraw them.
                val images = extra.mapNotNull { s ->
                    synchronized(terrainCache) { terrainCache[s.xs] }?.takeIf { it.name == s.name } ?: run {
                        s.bounds?.let { s.elevationAt(it.center) }
                        TerrainImage.of(s)?.also { synchronized(terrainCache) { terrainCache[s.xs] = it } }
                    }
                }
                p to (if (hideMain) baseTerrain.filter { t -> p.surfaces.any { it.name == t.name } } else baseTerrain) + images
            } }
            loadedXrefs = xrefDrawings.keys
            state = (state as? ProjectState.Loaded ?: current).copy(project = project, terrain = terrain)
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
                ensureCached(uri)
                if ((state as? ProjectState.Loaded)?.project?.images?.firstOrNull { it.file == file }?.let { tiledRaster(it, uri) } == true) { prefs.edit { putString(file, uri.toString()) }; return@launch }
                val bitmap = tracked(tr("Imagem {0}", displayName(uri))) { t -> withContext(Dispatchers.IO) { decodeRaster(uri, t) } }
                if (bitmap != null) {
                    tiledRasters[file]?.close()
                    tiledRasters = tiledRasters - file
                    rasters = rasters + (file to bitmap)
                    prefs.edit { putString(file, uri.toString()) }
                } else {
                    rasterError = tr("Formato de imagem não reconhecido.")
                }
            } catch (e: OutOfMemoryError) {
                rasterError = tr("Imagem grande demais para a memória deste aparelho.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                rasterError = tr("Não foi possível ler a imagem: {0}", errorText(e))
            }
        }
    }

    /** Reloads pictures chosen earlier for the images of [project]. */
    private fun restoreRasters(project: LandXmlProject) {
        for (image in project.images) {
            val uri = prefs.getString(image.file, null)?.let(Uri::parse) ?: continue
            viewModelScope.launch {
                try {
                    ensureCached(uri)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                if (runCatching { tiledRaster(image, uri) }.getOrDefault(false)) return@launch
                val bitmap = tracked(tr("Imagem {0}", image.fileName)) { t ->
                    withContext(Dispatchers.IO) { runCatching { decodeRaster(uri, t) }.getOrNull() }
                }
                if (bitmap != null) rasters = rasters + (image.file to bitmap)
            }
        }
    }

    /**
     * Opens a TIFF larger than [TILED_FROM] pixels for the drawing's image [img] as a [TiledPicture]
     * laid in the image's own pixels (x right, y up), so a rotated image is drawn tile by tile like a
     * drape. Its overview stands in [rasters]; the pyramid is built once in the background. False
     * when the picture is not such a TIFF.
     */
    private suspend fun tiledRaster(img: com.appdwg.core.PlanImage, uri: Uri): Boolean {
        val file = img.file
        val app = getApplication<Application>()
        val name = displayName(uri)
        val t0 = System.nanoTime()
        val tiles = withContext(Dispatchers.IO) {
            val head = ByteArray(4).also { b -> input(uri)?.use { it.read(b) } }
            val text = String(head, Charsets.ISO_8859_1)
            if (!(text.startsWith("II*") || text.startsWith("II+") || text.startsWith("MM\u0000*") || text.startsWith("MM\u0000+"))) return@withContext null
            val key = openCache.keyOf(uri, fileSize(uri)) { input(uri) } ?: return@withContext null
            val fd = openLocal(uri) ?: return@withContext null
            val tp = runCatching {
                TiledPicture.open(name, fd, java.io.File(app.cacheDir, TiledPicture.PYRAMID_DIR), key, Bounds(0.0, 0.0, img.widthPx.toDouble(), img.heightPx.toDouble()))
            }.getOrNull()
            when {
                tp == null -> { fd.close(); null }
                !RasterBudget.tileTiff(tp.width, tp.height) -> { tp.close(); null }
                else -> tp
            }
        } ?: return false
        tiledRasters[file]?.close()
        tiledRasters = tiledRasters + (file to tiles)
        val ready = tiles.ready
        android.util.Log.i("Gromatic", "imagem do desenho $name (em blocos): ${(System.nanoTime() - t0) / 1_000_000} ms, ${tiles.width}x${tiles.height}, pirâmide ${if (ready) "pronta" else "a fazer"}")
        if (ready) {
            withContext(Dispatchers.IO) { tiles.overview() }?.let { rasters = rasters + (file to it.asImageBitmap()) }
            return true
        }
        // The close-up tiles show at once; the reduced copies for zooming out are made once, here.
        viewModelScope.launch {
            val t1 = System.nanoTime()
            tracked(tr("Preparando {0} para o zoom", name)) { task ->
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val watcher = viewModelScope.launch { while (true) { task.step(tr("Reduções da imagem (feito uma vez)"), tiles.built); kotlinx.coroutines.delay(500) } }
                    tiles.buildPyramid { ov ->
                        watcher.cancel()
                        if (tiledRasters[file] === tiles && ov != null) rasters = rasters + (file to ov.asImageBitmap())
                        android.util.Log.i("Gromatic", "pirâmide $name: ${(System.nanoTime() - t1) / 1_000_000} ms")
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }
        return true
    }

    /**
     * Decodes a picture at full resolution when it fits the memory budget. TIFF goes through the
     * app's own reader (Android has none); JPEG, PNG and WebP go through Coil.
     */
    private fun decodeRaster(uri: Uri, task: LoadTask): ImageBitmap? = decodePicture(uri, task)?.image

    private fun decodePicture(uri: Uri, task: LoadTask): Picture? {
        val head = ByteArray(8)
        input(uri)?.use { it.read(head) }
        val text = String(head, Charsets.ISO_8859_1)
        when {
            text.startsWith("II*\u0000") || text.startsWith("MM\u0000*") || text.startsWith("II+\u0000") || text.startsWith("MM\u0000+") ->
                return decodeTiff(uri, task)
            displayName(uri).endsWith(".ecw", ignoreCase = true) ->
                error(tr("ECW é um formato fechado (Hexagon) sem leitor livre para Android. Converta para TIFF ou JPG no QGIS (Exportar > Salvar como)"))
            displayName(uri).endsWith(".jp2", ignoreCase = true) ->
                error(tr("JPEG 2000 não é suportado. Converta para TIFF ou JPG"))
        }
        val (width, height, mime) = CoilPictures.sizeOf { input(uri) } ?: return null
        task.step(tr("Decodificando imagem"))
        val data: Any = cached(uri) ?: uri
        val bitmap = CoilPictures.decode(getApplication(), data, RasterBudget.maxSide(width, height), BitmapDecode.configForMime(mime)) ?: return null
        return Picture(bitmap.asImageBitmap(), null, width, height)
    }

    /**
     * Lays a large TIFF over the surfaces as a [TiledPicture]; null when the picture is small enough
     * to decode whole, or is not a TIFF. Its pyramid is built in the background the first time.
     */
    private suspend fun tiledDrape(project: LandXmlProject, uri: Uri, name: String, world: Pair<Uri, String>?, uris: List<Uri>): Unit? {
        val app = getApplication<Application>()
        val head = withContext(Dispatchers.IO) { ByteArray(4).also { b -> input(uri)?.use { it.read(b) } } }
        val text = String(head, Charsets.ISO_8859_1)
        if (!(text.startsWith("II*") || text.startsWith("II+") || text.startsWith("MM\u0000*") || text.startsWith("MM\u0000+"))) return null
        val t0 = System.nanoTime()
        val opened = withContext(Dispatchers.IO) {
            val size = fileSize(uri)
            val key = openCache.keyOf(uri, size) { input(uri) } ?: return@withContext null
            val fd = openLocal(uri) ?: return@withContext null
            val probe = runCatching { TiledPicture.open(name, fd, java.io.File(app.cacheDir, TiledPicture.PYRAMID_DIR), key, null) }.getOrNull()
            if (probe == null || !RasterBudget.tileTiff(probe.width, probe.height)) { probe?.close() ?: fd.close(); return@withContext null }
            val worldText = world?.let { (w, _) -> input(w)?.use { it.readBytes().toString(Charsets.ISO_8859_1) } }
            val placed = worldText?.let { WorldFile.extent(it, probe.width, probe.height) }
            val tiles = if (placed != null) {
                probe.close()
                val again = openLocal(uri) ?: return@withContext null
                TiledPicture.open(name, again, java.io.File(app.cacheDir, TiledPicture.PYRAMID_DIR), key, placed)
            } else probe
            Triple(tiles, if (placed != null) tr("posicionada pelo arquivo {0}", world?.second) else tr("posicionada pelo GeoTIFF"), tiles?.ready == true)
        } ?: return null
        val (tiles, placement, ready) = opened
        tiles ?: return null
        val area = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: project.bounds
        drape?.tiles?.close()
        val overview = if (ready) withContext(Dispatchers.IO) { tiles.overview() } else null
        drape = Drape(name, tiles.bounds, (overview ?: createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImageBitmap(), placement, tiles)
        drapeUris = uris
        drapeVisible = true
        drapeMessage = if (area != null && !overlaps(tiles.bounds, area)) {
            tr("A imagem foi {0}, mas não cai sobre a superfície. Confira se as duas estão no mesmo sistema de coordenadas.", placement)
        } else tr("Imagem {0}.", placement)
        android.util.Log.i("Gromatic", "imagem $name (em blocos): ${(System.nanoTime() - t0) / 1_000_000} ms, ${tiles.width}x${tiles.height}, pirâmide ${if (ready) "pronta" else "a fazer"}")
        if (!ready) {
            // The close-up levels show at once; the reduced copies for zooming out are made once, here.
            viewModelScope.launch {
                val t1 = System.nanoTime()
                tracked(tr("Preparando {0} para o zoom", name)) { task ->
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                        val watcher = viewModelScope.launch { while (true) { task.step(tr("Reduções da imagem (feito uma vez)"), tiles.built); kotlinx.coroutines.delay(500) } }
                        tiles.buildPyramid { ov ->
                            watcher.cancel()
                            if (drape?.tiles === tiles && ov != null) drape = Drape(name, tiles.bounds, ov.asImageBitmap(), placement, tiles)
                            android.util.Log.i("Gromatic", "pirâmide $name: ${(System.nanoTime() - t1) / 1_000_000} ms")
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
        }
        return Unit
    }

    // ---- Area data: terrain (Copernicus DEM) and OpenStreetMap ----

    /**
     * Starts an empty project around ([lat], [lon]): its coordinate system is the UTM zone there
     * (SIRGAS 2000 in South America, WGS 84 elsewhere), the background map is on, and the area
     * download opens, to bring in terrain and OpenStreetMap data.
     */
    fun newProject(lat: Double, lon: Double, halfWidth: Double = 1000.0, halfHeight: Double = 1000.0) {
        val zone = com.appdwg.core.Gis.utmZoneOf(lon)
        val crs = if (lon in -92.0..-25.0 && lat in -60.0..15.0) com.appdwg.core.CrsCatalog.utmFor(lon, lat < 0)
            else com.appdwg.core.CrsCatalog.byEpsg(((if (lat < 0) 32700 else 32600) + zone).toString()) ?: com.appdwg.core.CrsCatalog.utmFor(lon, lat < 0)
        val app = getApplication<Application>()
        val dir = java.io.File(app.filesDir, "projetos").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        val file = java.io.File(dir, "novo-projeto-$stamp.xml")
        file.writer(Charsets.UTF_8).use { w -> com.appdwg.core.LandXmlWriter.write(emptyList(), emptyList(), emptyList(), w, crs.epsg) }
        val uri = Uri.fromFile(file)
        projectMessage = null
        viewModelScope.launch {
            if (!openNow(uri, allowEmpty = true)) return@launch
            val c = crs.fromWgs84(lat, lon)
            view?.let { v -> v.crs = crs; v.area = Bounds(c.x - halfWidth, c.y - halfHeight, c.x + halfWidth, c.y + halfHeight) }
            basemap.enable(true)
            // Kept as a project file in the app from the start, so what is downloaded stays with it.
            saveProject(Uri.fromFile(java.io.File(dir, "novo-projeto-$stamp.gromatic"))) { projectMessage = null }
            areaRequested = true
        }
    }

    /** Whether the open project has a surface to lay pictures on. */
    val hasSurfaces get() = (state as? ProjectState.Loaded)?.project?.surfaces?.isNotEmpty() == true

    /** Set to open the area download (a new project from the map starts there). */
    var areaRequested by mutableStateOf(false)

    /** Result or error of the last area download. */
    var areaMessage by mutableStateOf<String?>(null)
        private set
    private var areaJob: Job? = null

    /**
     * Downloads data for the plan rectangle [area] (project coordinates): the terrain as a surface
     * (TIN within [tolerance] m, optionally also the grid as a GeoTIFF) and OpenStreetMap [osm]
     * kinds as layered lines. Each result is saved in the app's storage and added to the project as
     * a file, like any other, so it is kept, styled and exported as usual.
     */
    fun downloadArea(area: Bounds, dem: Boolean, tolerance: Double, keepTiff: Boolean, osm: Set<com.appdwg.core.Overpass.Kind>,
                     image: Boolean = false, scene: com.appdwg.core.Sentinel2.Scene? = null) {
        val crs = crs()
        if (crs.local) { areaMessage = tr("Escolha um sistema de coordenadas projetado (UTM, LTM…): o sistema local não tem latitude e longitude."); return }
        // The rectangle in latitude and longitude, from its corners.
        val corners = listOf(area.minX to area.minY, area.minX to area.maxY, area.maxX to area.minY, area.maxX to area.maxY).map { (x, y) -> crs.toWgs84(x, y) }
        val south = corners.minOf { it.first }; val north = corners.maxOf { it.first }
        val west = corners.minOf { it.second }; val east = corners.maxOf { it.second }
        if ((north - south) * (east - west) > 0.25) { areaMessage = tr("Área grande demais (máximo de cerca de 50 × 50 km). Aproxime a planta ou desenhe uma área menor."); return }
        areaMessage = null
        areaJob?.cancel()
        areaJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val dir = java.io.File(app.filesDir, "area").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            val added = ArrayList<Uri>()
            val notes = ArrayList<String>()
            val job = coroutineContext[Job]!!
            try {
                if (dem) tracked(tr("Baixando terreno (Copernicus)"), onCancel = { job.cancel() }) { task ->
                    run {
                        withContext(Dispatchers.IO) {
                            val inputs = ArrayList<HttpRandomInput>()
                            val grid = com.appdwg.core.CopernicusDem.grid(south, west, north, east, 2400, { name ->
                                HttpRandomInput.open(com.appdwg.core.CopernicusDem.url(name)) { !job.isActive }?.also { inputs += it }
                            }, { !job.isActive }) { i, n -> task.step(tr("Lendo tile {0} de {1}", i + 1, n), i.toFloat() / n) }
                            if (grid.values.all { it.isNaN() }) error(tr("sem dados de terreno nesta área (mar ou fora da cobertura)"))
                            task.step(tr("Montando a superfície (tolerância {0})", StationFormat.length(tolerance)))
                            val name = "TERRENO COPERNICUS"
                            val surface = com.appdwg.core.GridSurface.build(name, grid, tolerance, { lat, lon -> crs.fromWgs84(lat, lon) })
                                ?: error(tr("sem dados de terreno nesta área (mar ou fora da cobertura)"))
                            val f = java.io.File(dir, "terreno-copernicus-$stamp.xml")
                            f.writer(Charsets.UTF_8).use { w -> com.appdwg.core.LandXmlWriter.write(listOf(surface), emptyList(), emptyList(), w, crs.epsg) }
                            added += Uri.fromFile(f)
                            if (keepTiff) {
                                val tif = java.io.File(dir, "terreno-copernicus-$stamp.tif")
                                tif.outputStream().buffered().use { com.appdwg.core.GeoTiffWriter.writeFloat(grid, it) }
                                notes += tr("Grade salva como GeoTIFF: {0}", tif.name)
                            }
                            notes += tr("Terreno: {0} pontos ({1} triângulos), de {2} MB baixados. Copernicus GLO-30 é um modelo de superfície (DSM) de 30 m: inclui prédios e árvores; use para estudo preliminar.",
                                surface.vertexCount, surface.triangleCount, StationFormat.decimal(inputs.sumOf { it.received } / 1048576.0, 1))
                        }
                    }
                }
                if (osm.isNotEmpty()) tracked(tr("Baixando OpenStreetMap"), onCancel = { job.cancel() }) { task ->
                    run {
                        withContext(Dispatchers.IO) {
                            val blocks = com.appdwg.core.Overpass.blocks(south, west, north, east)
                            if (blocks.size > 60) error(tr("Área grande demais para o OpenStreetMap ({0} blocos). Desenhe uma área menor.", blocks.size))
                            // A busy OpenStreetMap server leaves the rest of the download alone.
                            val answers = try {
                                blocks.mapIndexed { i, b ->
                                    task.step(tr("Bloco {0} de {1}", i + 1, blocks.size), i.toFloat() / blocks.size)
                                    overpass(com.appdwg.core.Overpass.query(b[0], b[1], b[2], b[3], osm)) { !job.isActive }
                                }
                            } catch (e: java.io.IOException) {
                                notes += tr("OpenStreetMap não baixado: {0}", errorText(e)); return@withContext
                            }
                            val (json, count) = com.appdwg.core.Overpass.toGeoJson(answers)
                            if (count == 0) { notes += tr("OpenStreetMap: nada desses tipos nesta área."); return@withContext }
                            val f = java.io.File(dir, "osm-$stamp.geojson")
                            f.writeText(json)
                            added += Uri.fromFile(f)
                            notes += tr("OpenStreetMap: {0} linhas.", count)
                        }
                    }
                }
                if (added.isNotEmpty()) for (u in added) addFileNow(u)
                // The picture goes over the terrain, so it comes after it.
                if (image) tracked(tr("Baixando imagem de satélite (Sentinel-2)"), onCancel = { job.cancel() }) { task ->
                    val result = withContext(Dispatchers.IO) { satelliteImage(area, south, west, north, east, scene, dir, stamp, task) { !job.isActive } }
                    val sc = result.first
                    notes += sc.areaCloud?.let { tr("Imagem Sentinel-2 de {0} ({1}% de nuvens na área), colada sobre o terreno.", sc.date.take(10), StationFormat.decimal(it * 100, 0)) }
                        ?: tr("Imagem Sentinel-2 de {0} ({1}% de nuvens na cena), colada sobre o terreno.", sc.date.take(10), StationFormat.decimal(sc.cloud, 0))
                    notes += com.appdwg.core.Sentinel2.attribution(result.first.year)
                    drapeNow(project = (state as? ProjectState.Loaded)?.project ?: return@tracked, uri = result.second, name = result.second.lastPathSegment ?: "sentinel2.jpg",
                        world = result.third to (result.third.lastPathSegment ?: "sentinel2.jgw"), uris = listOf(result.second, result.third))
                }
                notes += listOfNotNull(
                    if (dem) com.appdwg.core.CopernicusDem.ATTRIBUTION + ". " + com.appdwg.core.CopernicusDem.DISCLAIMER + "." else null,
                    if (osm.isNotEmpty()) com.appdwg.core.Overpass.ATTRIBUTION else null,
                )
                areaMessage = notes.joinToString("\n")
                // A project kept in the app (one started from the map) is saved with what came in.
                projectUri?.takeIf { it.scheme == "file" && it.path?.startsWith(app.filesDir.path) == true }?.let { saveProject(it) { projectMessage = null } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                areaMessage = tr("Download cancelado.")
            } catch (e: java.io.IOException) {
                areaMessage = tr("Sem conexão com o servidor: {0}. Confira a internet e tente de novo.", errorText(e))
            } catch (e: OutOfMemoryError) {
                areaMessage = tr("Memória insuficiente: use uma área menor ou uma tolerância maior.")
            } catch (e: Exception) {
                areaMessage = tr("Não foi possível baixar: {0}", errorText(e))
            }
        }
    }

    fun cancelAreaDownload() { areaJob?.cancel() }

    /** Sentinel-2 pictures that cover [area], newest first, for choosing the date. */
    var scenes by mutableStateOf<List<com.appdwg.core.Sentinel2.Scene>>(emptyList())
        private set
    var scenesMessage by mutableStateOf<String?>(null)
        private set

    fun searchScenes(area: Bounds) {
        val crs = crs()
        if (crs.local) return
        val corners = listOf(area.minX to area.minY, area.minX to area.maxY, area.maxX to area.minY, area.maxX to area.maxY).map { (x, y) -> crs.toWgs84(x, y) }
        scenesMessage = tr("Buscando imagens…")
        viewModelScope.launch {
            scenes = try {
                val found = withContext(Dispatchers.IO) { findScenes(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second }, 60.0) }
                if (found.isEmpty()) scenesMessage = tr("Nenhuma imagem cobre a área inteira no último ano.")
                else scenesMessage = tr("Conferindo as nuvens na área…")
                // The scene's own cloud cover says little about a small area: measured there for each date.
                val measured = found.take(12).map { sc -> async(Dispatchers.IO) { sc.withAreaCloud(runCatching { areaCloud(sc, area) }.getOrNull()) } }.awaitAll()
                if (found.isNotEmpty()) scenesMessage = null
                measured
            } catch (e: Exception) {
                scenesMessage = tr("Não foi possível buscar: {0}", errorText(e)); emptyList()
            }
        }
    }

    /**
     * Share of cloud over [area] in [scene], from its scene classification (20 m), sampled on a
     * grid of up to 100 x 100 points; only the blocks of that file under the area are read.
     */
    private fun areaCloud(scene: com.appdwg.core.Sentinel2.Scene, area: Bounds): Double? {
        val href = scene.scl ?: return null
        val input = HttpRandomInput.open(href) ?: return null
        val reader = remoteTiff(input)
        val layout = reader.blocks()
        val pixel = scene.pixel * scene.width / layout.width
        val zone = scene.epsg.takeLast(2).toInt()
        val s2 = com.appdwg.core.CrsCatalog.byEpsg(scene.epsg) ?: com.appdwg.core.CrsCatalog.utm(com.appdwg.core.Datum.WGS84, zone, scene.epsg.startsWith("327"), scene.epsg)
        val crs = crs()
        val n = (maxOf(area.width, area.height) / pixel).toInt().coerceIn(2, 100)
        val read = HashMap<Int, com.appdwg.core.Raster?>()
        val classes = IntArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val (lat, lon) = crs.toWgs84(area.minX + area.width * (i + 0.5) / n, area.minY + area.height * (j + 0.5) / n)
            val p = s2.fromWgs84(lat, lon)
            val c = ((p.x - scene.originX) / pixel).toInt(); val r = ((scene.originY - p.y) / pixel).toInt()
            if (c !in 0 until layout.width || r !in 0 until layout.height) continue
            val b = (r / layout.blockHeight) * layout.across + c / layout.blockWidth
            val raster = read.getOrPut(b) { reader.readBlock(b) } ?: continue
            val x = c % layout.blockWidth; val y = r % layout.blockHeight
            if (x < raster.width && y < raster.height) classes[j * n + i] = raster.pixels[y * raster.width + x] and 0xFF
        }
        return com.appdwg.core.Sentinel2.cloudShare(classes)
    }

    /** Pictures over the box from the last year with less than [maxCloud]% clouds, newest first. */
    private fun findScenes(south: Double, west: Double, north: Double, east: Double, maxCloud: Double): List<com.appdwg.core.Sentinel2.Scene> {
        val to = java.time.LocalDate.now(); val from = to.minusYears(1)
        val body = com.appdwg.core.Sentinel2.searchBody(south, west, north, east, from.toString(), to.toString(), maxCloud, 60)
        val request = Request.Builder()
            .url(com.appdwg.core.Sentinel2.SEARCH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (response.code != 200) throw java.io.IOException(tr("resposta {0} do servidor", response.code))
            val text = response.body?.string() ?: throw java.io.IOException(tr("resposta {0} do servidor", response.code))
            return com.appdwg.core.Sentinel2.scenes(text, south, west, north, east)
        }
    }

    /** A remote TIFF whose JPEG tiles are decoded by Coil, never by a bitmap factory on the stream. */
    private fun remoteTiff(input: RandomInput) = TiffReader(
        input,
        jpeg = { CoilPictures.raster(getApplication(), it, 1) },
        jpegReduced = { bytes, reduce -> CoilPictures.raster(getApplication(), bytes, reduce) },
    )

    /**
     * The chosen picture (or the newest one with few clouds) over [area]: the window of its file
     * that covers the area is read, brought to the project's coordinate system, given automatic
     * contrast, and saved as a JPEG with its world file. Returns the scene, picture and world file.
     */
    private fun satelliteImage(area: Bounds, south: Double, west: Double, north: Double, east: Double, chosen: com.appdwg.core.Sentinel2.Scene?,
                               dir: java.io.File, stamp: String, task: LoadTask, cancelled: () -> Boolean): Triple<com.appdwg.core.Sentinel2.Scene, Uri, Uri> {
        task.step(tr("Buscando a imagem mais recente com poucas nuvens"))
        val scene = chosen ?: run {
            // Newest first; the first one clear over the area wins, else the clearest of those seen.
            var best: com.appdwg.core.Sentinel2.Scene? = null
            for (sc in findScenes(south, west, north, east, 60.0).take(10)) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException()
                task.step(tr("Conferindo as nuvens na área: {0}", sc.date.take(10)))
                val m = sc.withAreaCloud(runCatching { areaCloud(sc, area) }.getOrNull() ?: (sc.cloud / 100))
                if (best == null || m.areaCloud!! < best.areaCloud!!) best = m
                if (m.areaCloud!! < 0.01) break
            }
            best
        } ?: error(tr("Nenhuma imagem cobre a área inteira no último ano."))
        val zone = scene.epsg.takeLast(2).toInt()
        val s2 = com.appdwg.core.CrsCatalog.byEpsg(scene.epsg) ?: com.appdwg.core.CrsCatalog.utm(com.appdwg.core.Datum.WGS84, zone, scene.epsg.startsWith("327"), scene.epsg)
        val crs = crs()
        val input = HttpRandomInput.open(scene.href, cancelled) ?: error(tr("imagem não encontrada no servidor"))
        val reader = remoteTiff(input)
        val layout = reader.blocks()
        // The picture's pixels the area needs (with a margin), from its corners in the picture's UTM zone.
        val px = (0..8).flatMap { i -> (0..8).map { j -> area.minX + area.width * i / 8 to area.minY + area.height * j / 8 } }.map { (x, y) ->
            val (lat, lon) = crs.toWgs84(x, y); val p = s2.fromWgs84(lat, lon)
            (p.x - scene.originX) / scene.pixel to (scene.originY - p.y) / scene.pixel
        }
        val c0 = (px.minOf { it.first }.toInt() - 2).coerceIn(0, layout.width - 1); val c1 = (px.maxOf { it.first }.toInt() + 2).coerceIn(0, layout.width - 1)
        val r0 = (px.minOf { it.second }.toInt() - 2).coerceIn(0, layout.height - 1); val r1 = (px.maxOf { it.second }.toInt() + 2).coerceIn(0, layout.height - 1)
        var reduce = 1
        while (maxOf(c1 - c0, r1 - r0) / reduce > 4096) reduce *= 2
        val ww = (c1 - c0) / reduce + 1; val wh = (r1 - r0) / reduce + 1
        val win = IntArray(ww * wh)
        val blocks = (r0 / layout.blockHeight..r1 / layout.blockHeight).flatMap { by -> (c0 / layout.blockWidth..c1 / layout.blockWidth).map { bx -> by * layout.across + bx } }
        blocks.forEachIndexed { k, b ->
            if (cancelled()) throw kotlinx.coroutines.CancellationException()
            task.step(tr("Lendo bloco {0} de {1} · {2} MB", k + 1, blocks.size, StationFormat.decimal(input.received / 1048576.0, 1)), k.toFloat() / blocks.size)
            val raster = reader.readBlock(b, reduce) ?: return@forEachIndexed
            val bx = (b % layout.across) * layout.blockWidth; val by = (b / layout.across) * layout.blockHeight
            for (y in 0 until raster.height) {
                val wy = (by + y * reduce - r0) / reduce
                if (wy !in 0 until wh) continue
                for (x in 0 until raster.width) {
                    val wx = (bx + x * reduce - c0) / reduce
                    if (wx in 0 until ww) win[wy * ww + wx] = raster.pixels[y * raster.width + x]
                }
            }
        }
        // The output picture in the project's system, about the source's pixel size.
        task.step(tr("Ajustando a imagem ao sistema do projeto"))
        val step = scene.pixel * reduce
        val ow = (area.width / step).toInt().coerceIn(1, 4096); val oh = (area.height / step).toInt().coerceIn(1, 4096)
        val sx = area.width / ow; val sy = area.height / oh
        val out = IntArray(ow * oh)
        // Exact positions on a coarse grid, interpolated in between: the map is smooth over an area this size.
        val g = 32
        val gw = ow / g + 2; val gh = oh / g + 2
        val gx = DoubleArray(gw * gh); val gy = DoubleArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val (lat, lon) = crs.toWgs84(area.minX + (i * g + 0.5) * sx, area.maxY - (j * g + 0.5) * sy)
            val p = s2.fromWgs84(lat, lon)
            gx[j * gw + i] = ((p.x - scene.originX) / scene.pixel - c0) / reduce; gy[j * gw + i] = ((scene.originY - p.y) / scene.pixel - r0) / reduce
        }
        for (y in 0 until oh) {
            val j = y / g; val ty = (y % g).toDouble() / g
            for (x in 0 until ow) {
                val i = x / g; val tx = (x % g).toDouble() / g
                val k = j * gw + i
                val fx = (gx[k] * (1 - tx) + gx[k + 1] * tx) * (1 - ty) + (gx[k + gw] * (1 - tx) + gx[k + gw + 1] * tx) * ty
                val fy = (gy[k] * (1 - tx) + gy[k + 1] * tx) * (1 - ty) + (gy[k + gw] * (1 - tx) + gy[k + gw + 1] * tx) * ty
                val ix = fx.toInt(); val iy = fy.toInt()
                if (ix in 0 until ww && iy in 0 until wh) out[y * ow + x] = win[iy * ww + ix]
            }
        }
        com.appdwg.core.Sentinel2.autoContrast(out)
        val img = java.io.File(dir, "sentinel2-${scene.date.take(10)}-$stamp.jpg")
        val bmp = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
        img.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        val world = java.io.File(dir, img.nameWithoutExtension + ".jgw")
        fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.6f", v)
        world.writeText(listOf(n(sx), "0", "0", n(-sy), n(area.minX + sx / 2), n(area.maxY - sy / 2)).joinToString("\n") + "\n")
        return Triple(scene, Uri.fromFile(img), Uri.fromFile(world))
    }

    /** One Overpass request, retried a few times when the server is busy (429/504). */
    private fun overpass(query: String, cancelled: () -> Boolean): String {
        var wait = 2000L
        repeat(4) { attempt ->
            if (cancelled()) throw kotlinx.coroutines.CancellationException()
            val payload = "data=" + java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(com.appdwg.core.Overpass.ENDPOINT)
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            Net.client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> return response.body?.string() ?: throw java.io.IOException(tr("resposta {0} do servidor", response.code))
                    429, 502, 503, 504 -> if (attempt < 3) { Thread.sleep(wait); wait *= 2 } else throw java.io.IOException(tr("servidor do OpenStreetMap ocupado; tente mais tarde"))
                    else -> throw java.io.IOException(tr("resposta {0} do servidor", response.code))
                }
            }
        }
        throw java.io.IOException(tr("servidor do OpenStreetMap ocupado; tente mais tarde"))
    }

    // ---- Field notes with photos ----

    var notes by mutableStateOf<List<FieldNote>>(emptyList())
        private set

    /** Folder of the notes' photos, inside the app's storage. */
    val notesDir: java.io.File get() = java.io.File(getApplication<Application>().filesDir, "notes").also { it.mkdirs() }

    /** New, empty photo file for the camera to fill. */
    fun newPhotoFile(): java.io.File = java.io.File(notesDir, "foto_" + System.currentTimeMillis() + ".jpg")

    /** Copies a picture chosen from the gallery into the notes folder; returns its file name. */
    fun importPhoto(uri: Uri): String? = runCatching {
        val f = newPhotoFile()
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        f.name
    }.getOrNull()

    fun saveNote(note: FieldNote) {
        notes = notes.filter { it.id != note.id } + note
    }

    fun deleteNote(note: FieldNote) {
        notes = notes - note
    }

    fun nextNoteId(): String = ((notes.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()

    fun exportNotesCsv(target: Uri) {
        val list = notes
        writeTo(target, tr("CSV de anotações")) { it.write(("\uFEFF" + NotesExport.csv(list)).toByteArray(Charsets.UTF_8)) }
    }

    fun exportNotesKmz(target: Uri) {
        val crs = crs()
        val list = notes
        val dir = notesDir
        val name = (state as? ProjectState.Loaded)?.fileName ?: "Gromatic"
        writeTo(target, tr("KMZ de anotações")) { out -> NotesExport.kmz(list, crs, name, out) { f -> java.io.File(dir, f).takeIf { it.exists() }?.readBytes() } }
    }

    /** Receiver connection; lives with the view model so rotating the screen keeps it. */
    val gnss = GnssManager(app)
    val geo = Geodesy(app).also { g -> g.gridUri?.let { u -> viewModelScope.launch { readGeoidGrid(u.toUri(), quiet = true) } } }

    /** Loads a geoid grid (MAPGEO/hgeoHNOR as text "lat lon N", or GTX) and keeps it for the next sessions. */
    fun loadGeoidGrid(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch { if (readGeoidGrid(uri, quiet = false)) geo.rememberGrid(uri.toString()) }
    }

    private suspend fun readGeoidGrid(uri: Uri, quiet: Boolean): Boolean {
        val app = getApplication<Application>()
        val name = displayName(uri)
        return try {
            val g = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { s ->
                    if (name.endsWith(".gtx", true)) com.appdwg.core.GeoidGrid.readGtx(name, s)
                    else com.appdwg.core.GeoidGrid.readText(name, s.readBytes().toString(Charsets.ISO_8859_1))
                } ?: error(app.getString(R.string.calib_unavailable))
            }
            geo.grid = g
            geo.gridMessage = app.getString(R.string.calib_grid_loaded, name,
                StationFormat.decimal(g.south, 2), StationFormat.decimal(g.north, 2), StationFormat.decimal(g.west, 2), StationFormat.decimal(g.east, 2))
            true
        } catch (e: OutOfMemoryError) {
            if (!quiet) geo.gridMessage = app.getString(R.string.calib_grid_memory); false
        } catch (e: Exception) {
            if (!quiet) geo.gridMessage = app.getString(R.string.calib_grid_error, errorText(e)); false
        }
    }
    val basemap = BasemapStore(app)
    val themes = ThemeStore(app)
    val units = UnitStore(app)
    val languages = LanguageStore(app)
    /** Gromatic Pro subscription; checked with Play at start. */
    val pro = ProStore(app).also { it.refresh() }
    /** Set to show the Gromatic Pro screen (a locked tool was touched). */
    var proRequested by mutableStateOf(false)

    // ---- Coordinate system ----

    private val crsPrefs = app.getSharedPreferences("crs", android.content.Context.MODE_PRIVATE)
    /** The user's own coordinate systems, kept on the device. */
    var customCrs by mutableStateOf(readCustomCrs())
        private set
    /** Asks the open project screen to show the coordinate system dialog (from Configurações). */
    var crsDialogRequested by mutableStateOf(false)

    private fun readCustomCrs(): List<com.appdwg.core.Crs> = runCatching {
        val a = org.json.JSONArray(crsPrefs.getString("custom", "[]"))
        List(a.length()) { CrsJson.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveCustomCrs(c: com.appdwg.core.Crs) {
        customCrs = customCrs.filter { it.id != c.id } + c
        crsPrefs.edit { putString("custom", org.json.JSONArray(customCrs.map { CrsJson.toJson(it) }).toString()) }
    }

    fun deleteCustomCrs(c: com.appdwg.core.Crs) {
        customCrs = customCrs.filter { it.id != c.id }
        crsPrefs.edit { putString("custom", org.json.JSONArray(customCrs.map { CrsJson.toJson(it) }).toString()) }
    }

    /** Coordinate system in use: the project's choice, else the drawing's EPSG, else SIRGAS 2000 / UTM 23S. */
    fun crs(): com.appdwg.core.Crs {
        view?.crs?.let { return it }
        val p = (state as? ProjectState.Loaded)?.project
        return com.appdwg.core.CrsCatalog.byEpsg(p?.coordinateSystem?.epsgCode) ?: com.appdwg.core.CrsCatalog.byEpsg("31983")!!
    }

    fun chooseCrs(c: com.appdwg.core.Crs) {
        view?.crs = c
        recentCrs = (listOf(c.id) + recentCrs.filter { it != c.id }).take(6)
        crsPrefs.edit { putString("recent", recentCrs.joinToString(",")) }
    }

    /** Ids of the systems chosen lately, newest first. */
    var recentCrs by mutableStateOf(crsPrefs.getString("recent", "")!!.split(',').filter { it.isNotBlank() })
        private set

    var crsOnlineMessage by mutableStateOf<String?>(null)
        private set

    /** Reads a system from epsg.io by its code and keeps it in "Meus sistemas". */
    fun fetchCrs(code: String) {
        val app = getApplication<Application>()
        crsOnlineMessage = null
        viewModelScope.launch {
            crsOnlineMessage = try {
                val c = withContext(Dispatchers.IO) {
                    fun get(url: String) = Net.client.newCall(Request.Builder().url(url).header("User-Agent", "Gromatic").build()).execute().use { response ->
                        if (response.code != 200) error("HTTP ${response.code}")
                        response.body?.string() ?: error("HTTP ${response.code}")
                    }
                    val proj4 = get("https://epsg.io/$code.proj4")
                    val name = runCatching { Regex("\"([^\"]+)\"").find(get("https://epsg.io/$code.wkt"))?.groupValues?.get(1) }.getOrNull() ?: "EPSG $code"
                    com.appdwg.core.Proj4.parse(code, name, proj4)
                }
                saveCustomCrs(c)
                app.getString(R.string.crs_online_saved, c.name)
            } catch (e: com.appdwg.core.Proj4.Unsupported) {
                app.getString(R.string.crs_online_unsupported, e.message ?: "")
            } catch (e: Throwable) {
                android.util.Log.w("Gromatic", "epsg.io", e)
                app.getString(R.string.crs_online_failed, errorText(e))
            }
        }
    }

    override fun onCleared() { gnss.disconnect() }

    // ---- Road (corridor) design ----

    var road by mutableStateOf<CorridorResult?>(null)
        private set
    var roadMessage by mutableStateOf<String?>(null)
        private set

    /** Designs the road of the view settings and lays its surfaces and axis over the project. */
    fun designRoad() { viewModelScope.launch { designRoadNow() } }

    private var redesignJob: kotlinx.coroutines.Job? = null

    /** Builds the road again once edits pause (a grade being dragged), when one is already built. */
    fun redesignSoon() {
        if (road == null) return
        redesignJob?.cancel()
        redesignJob = viewModelScope.launch { kotlinx.coroutines.delay(800); designRoadNow() }
    }

    private var previewKey: RoadParams? = null
    private var previewAlign: Alignment? = null

    /**
     * The axis drawn with PIs as the plan shows it while it is laid out: tangents with their curves
     * and spirals from the current radius and Ls, with the typed grade. Null when the axis comes
     * from the file, has fewer than 2 PIs, or the road was already built (it then shows its own axis).
     */
    fun previewAlignment(rp: RoadParams): Alignment? {
        // A built road shows its own axis, except while its PIs are being edited.
        if (rp.alignmentIndex >= 0 || rp.pis.size < 2 || (road != null && view?.drawingRoad != true)) return null
        if (rp != previewKey) {
            val project = (state as? ProjectState.Loaded)?.project
            previewAlign = project?.let { runCatching { roadAxis(rp, it) }.getOrNull() }?.let { a ->
                if (rp.pvis.size >= 2) Alignment(a.name, a.startStation, a.elements, Profile(tr("Greide"), AlignmentBuilder.curvesFromK(rp.pvis.sortedBy { it.station }, rp.kValue)
                    .mapIndexed { i, p -> rp.pvis.sortedBy { it.station }[i].takeIf { it.curveLength > 0 } ?: p })) else a
            }
            previewKey = rp
        }
        return previewAlign
    }

    fun axisPreview(rp: RoadParams): List<Point2> = previewAlignment(rp)?.vertices.orEmpty()

    fun roadAxis(rp: RoadParams, project: LandXmlProject): Alignment? {
        if (rp.alignmentIndex >= 0) return project.alignments.filter { !it.name.endsWith(ROAD_SUFFIX) }.getOrNull(rp.alignmentIndex)
        if (rp.pis.size < 2) return null
        return AlignmentBuilder.fromPis(tr("Eixo"), AlignmentBuilder.fitRadii(typedPis(rp)))
    }

    private fun typedPis(rp: RoadParams) = rp.pis.mapIndexed { i, p ->
        if (i == 0 || i == rp.pis.size - 1) Pi(p) else Pi(p, rp.radii.getOrNull(i)?.takeIf { it > 0 } ?: rp.radius, rp.spirals.getOrNull(i) ?: rp.spiral)
    }

    /** PIs (numbered from 1) whose radius had to shrink to fit its tangents, with the radius used. */
    fun shrunkRadii(rp: RoadParams): List<Pair<Int, Double>> {
        if (rp.alignmentIndex >= 0 || rp.pis.size < 3) return emptyList()
        val typed = typedPis(rp)
        return AlignmentBuilder.fitRadii(typed).mapIndexedNotNull { i, p -> if (p.radius < typed[i].radius - 1e-6) (i + 1) to p.radius else null }
    }

    private suspend fun designRoadNow() {
        val v = view ?: return
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val rp = v.roadParams
        roadMessage = null
        val terrain = project.surfaces.firstOrNull { it.name == rp.terrain }
            ?: project.surfaces.firstOrNull { !it.name.startsWith(ROAD_LABEL) && !it.name.startsWith(PLATFORM_LABEL) }
            ?: run { roadMessage = tr("Abra ou adicione uma superfície de terreno."); return }
        val axis0 = roadAxis(rp, project) ?: run {
            roadMessage = if (rp.alignmentIndex >= 0) tr("Alinhamento escolhido não existe mais.") else tr("Desenhe o eixo com 2 PIs ou mais.")
            return
        }
        val typed = if (rp.pvis.size >= 2) rp.pvis else null
        val fromFile = if (typed == null) axis0.profile?.pvis?.takeIf { it.size >= 2 } else null
        val pvis = typed ?: fromFile ?: terrainGradePvis(axis0) { terrain.elevationAt(it) }
        if (pvis.size < 2) {
            roadMessage = tr("Defina o greide: ao menos 2 PVIs (estaca e cota).")
            return
        }
        val profile = when {
            typed != null -> Profile(tr("Greide"), AlignmentBuilder.curvesFromK(typed.sortedBy { it.station }, rp.kValue).mapIndexed { i, p ->
                // A curve length typed by the user wins over K.
                typed.sortedBy { it.station }[i].takeIf { it.curveLength > 0 } ?: p
            })
            axis0.profile != null && fromFile != null -> axis0.profile
            else -> Profile(tr("Greide"), pvis)
        }
        val axis = Alignment(axis0.name + ROAD_SUFFIX, axis0.startStation, axis0.elements, profile)
        try {
            val r = tracked(tr("Projetando estrada")) { t ->
                withContext(Dispatchers.Default) {
                    t.step(tr("Seções e taludes"))
                    val tpl = Template(rp.left, rp.right, rp.cut, rp.fill, rp.pavement)
                    val regions = listOf(Region(axis.startStation, axis.endStation, tpl)) + rp.regions.sortedBy { it.from }.map { r ->
                        Region(r.from, axis.endStation, tpl.copy(left = r.left, right = r.right), r.transition, r.leftTarget, r.rightTarget)
                    }
                    Corridor.build(axis, terrain, regions, rp.superelevation, rp.step, rp.swell, ROAD_LABEL)
                }
            }
            road = r
            v.roadBuilt = true
            v.roadParams = rp.copy(terrain = terrain.name, pvis = if (rp.pvis.size >= 2) rp.pvis else pvis)
            addedFiles = addedFiles.filter { it.label != ROAD_LABEL } +
                AddedFile(ROAD_LABEL, LandXmlProject(null, null, listOf(r.top, r.datum), listOf(r.alignment), emptyList(), emptyList()), null)
            refreshJob?.cancel(); refreshNow()
            // Earthwork against the terrain, as a map.
            withContext(Dispatchers.Default) { SurfaceCompare.difference(terrain, r.datum) }?.let { d ->
                difference = d
                differenceImage = withContext(Dispatchers.Default) { DifferenceImage.of(d) }
                differenceVisible = true
            }
            roadMessage = if (r.unresolved > 0) tr("{0} seção(ões) com talude que não encontrou o terreno.", r.unresolved) else null
        } catch (e: OutOfMemoryError) {
            roadMessage = tr("Memória insuficiente para projetar.")
        } catch (e: Exception) {
            roadMessage = tr("Não foi possível projetar: {0}", errorText(e))
        }
    }

    /** Routes the app suggests between the first and last PI of the road. */
    var routeSuggestions by mutableStateOf<List<com.appdwg.core.RouteSuggestion>>(emptyList())
        private set

    /**
     * Suggests routes from the first to the last PI of [rp] on the terrain, keeping off buildings
     * and water bodies downloaded from OpenStreetMap, each with its least-earthwork grade.
     */
    fun suggestRoute(rp: RoadParams, maxGrade: Double) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val terrain = project.surfaces.firstOrNull { it.name == rp.terrain }
            ?: project.surfaces.firstOrNull { !it.name.startsWith(ROAD_LABEL) && !it.name.startsWith(PLATFORM_LABEL) }
            ?: run { roadMessage = tr("Abra ou adicione uma superfície de terreno."); return }
        if (rp.pis.size < 2) { roadMessage = tr("Marque na planta o ponto de saída e o de chegada."); return }
        val avoid = project.polylines.filter { it.closed && it.layer in setOf("OSM_CONSTRUCOES", "OSM_HIDROGRAFIA") && it.xs.size >= 3 }
            .map { l -> List(l.xs.size) { Point2(l.xs[it], l.ys[it]) } }
        roadMessage = null
        viewModelScope.launch {
            try {
                routeSuggestions = tracked(tr("Estudando o traçado")) {
                    withContext(Dispatchers.Default) {
                        com.appdwg.core.RouteDesign.suggest(rp.pis.first(), rp.pis.last(), terrain, Template(rp.left, rp.right, rp.cut, rp.fill, rp.pavement),
                            com.appdwg.core.RouteDesign.RouteRules(maxGrade, rp.speed, rp.emax, rp.spiral, avoid, rp.kValue)) { !isActive }
                    }
                }
                if (routeSuggestions.isEmpty()) roadMessage = tr("Nenhum caminho pelo terreno entre a saída e a chegada.")
            } catch (e: Exception) {
                roadMessage = tr("Não foi possível projetar: {0}", errorText(e))
            }
        }
    }

    fun clearRouteSuggestions() { routeSuggestions = emptyList() }

    /** Grades the app suggests for the road's axis. */
    var gradeSuggestions by mutableStateOf<List<com.appdwg.core.GradeSuggestion>>(emptyList())
        private set

    /** Suggests grades for the axis of [rp]: least earthwork and cut balanced with fill. */
    fun suggestGrade(rp: RoadParams, maxGrade: Double, spacing: Double) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val terrain = project.surfaces.firstOrNull { it.name == rp.terrain }
            ?: project.surfaces.firstOrNull { !it.name.startsWith(ROAD_LABEL) && !it.name.startsWith(PLATFORM_LABEL) }
            ?: run { roadMessage = tr("Abra ou adicione uma superfície de terreno."); return }
        val axis = roadAxis(rp, project) ?: run { roadMessage = tr("Desenhe o eixo com 2 PIs ou mais."); return }
        roadMessage = null
        viewModelScope.launch {
            try {
                gradeSuggestions = tracked(tr("Estudando o greide")) {
                    withContext(Dispatchers.Default) {
                        com.appdwg.core.GradeDesign.suggest(axis, terrain, Template(rp.left, rp.right, rp.cut, rp.fill, rp.pavement),
                            com.appdwg.core.GradeDesign.GradeRules(maxGrade, spacing, rp.kValue, swell = rp.swell)) { !isActive }
                    }
                }
                if (gradeSuggestions.isEmpty()) roadMessage = tr("O eixo está fora do terreno.")
            } catch (e: Exception) {
                roadMessage = tr("Não foi possível projetar: {0}", errorText(e))
            }
        }
    }

    fun removeRoad() {
        road = null
        view?.roadBuilt = false
        if (difference?.otherName?.startsWith(ROAD_LABEL) == true) clearDifference()
        addedFiles = addedFiles.filter { it.label != ROAD_LABEL }
        refreshProject()
    }

    /** Edges, shoulders, crest and toe lines of the road for DXF, in 3D. */
    fun roadLines(): List<ExportLine> = road?.features?.flatMap { (code, runs) ->
        runs.map { pts -> ExportLine("_ESTRADA_" + code.uppercase().replace(' ', '_'), pts.map { Point2(it.x, it.y) }, false, 3, zs = pts.map { it.z }) }
    }.orEmpty()

    /** Service note ("nota de serviço") per station, CSV with semicolons and decimal commas. */
    fun exportServiceNote(target: Uri) {
        val r = road ?: return
        fun n(v: Double?) = v?.let { StationFormat.decimal(it, 3) } ?: ""
        val text = buildString {
            // CSV stays in metres (for spreadsheets and other programs), as the header says.
            append(tr("Estaca;Estaca (m);Terreno eixo (m);Greide (m);Corte/aterro eixo (m);Bordo E afast. (m);Bordo E cota (m);Bordo D afast. (m);Bordo D cota (m);") +
                tr("Offset E tipo;Offset E afast. (m);Offset E cota (m);Offset D tipo;Offset D afast. (m);Offset D cota (m);Área corte (m2);Área aterro (m2)") + "\r\n")
            for (s in r.sections) {
                val be = s.top.firstOrNull { it.code == "bordo" && it.offset < 0 }; val bd = s.top.lastOrNull { it.code == "bordo" && it.offset > 0 }
                val oe = s.top.first(); val od = s.top.last()
                val ca = s.terrainAxis?.let { s.grade - it }
                append(listOf(StationFormat.estacaMetric(s.station), n(s.station), n(s.terrainAxis), n(s.grade),
                    ca?.let { (if (it >= 0) "A " else "C ") + StationFormat.decimal(kotlin.math.abs(it), 3) } ?: "",
                    n(be?.offset?.let { -it }), n(be?.z), n(bd?.offset), n(bd?.z),
                    oe.code, n(-oe.offset), n(oe.z), od.code, n(od.offset), n(od.z), n(s.cutArea), n(s.fillArea)).joinToString(";"))
                append("\r\n")
            }
            append("\r\n" + tr("Estaca;Corte (m3);Aterro (m3);Acumulado (Brückner, m3)") + "\r\n")
            for (row in r.volumes) append("${StationFormat.estacaMetric(row.station)};${n(row.cut)};${n(row.fill)};${n(row.accumulated)}\r\n")
            append(tr("Total") + ";${n(r.totalCut)};${n(r.totalFill)};\r\n")
        }
        writeTo(target, tr("nota de serviço")) { it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)) }
    }

    /** Service note as a PDF table, A4 landscape, a page per 30 stations. */
    fun exportServiceNotePdf(target: Uri, title: String) {
        val r = road ?: return
        val u = com.appdwg.core.ReportUnits()
        fun n(v: Double?) = v?.let { u.l(it) } ?: ""
        writeTo(target, tr("nota de serviço (PDF)")) { out ->
            val doc = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 7.5f; isAntiAlias = true }
            val bold = android.graphics.Paint(paint).apply { isFakeBoldText = true }
            val head = listOf(tr("Estaca"), tr("Terreno ({0})", u.len), tr("Greide ({0})", u.len), tr("C/A eixo ({0})", u.len), tr("Bordo E (af./cota)"), tr("Bordo D (af./cota)"),
                tr("Offset E (tipo af./cota)"), tr("Offset D (tipo af./cota)"), tr("Área C ({0})", u.area), tr("Área A ({0})", u.area))
            val colX = floatArrayOf(20f, 80f, 130f, 180f, 230f, 330f, 430f, 570f, 710f, 760f)
            val rows = r.sections.map { s ->
                val be = s.top.firstOrNull { it.code == "bordo" && it.offset < 0 }; val bd = s.top.lastOrNull { it.code == "bordo" && it.offset > 0 }
                val oe = s.top.first(); val od = s.top.last(); val ca = s.terrainAxis?.let { s.grade - it }
                listOf(u.station(s.station), n(s.terrainAxis), n(s.grade), ca?.let { (if (it >= 0) "A " else "C ") + n(kotlin.math.abs(it)) } ?: "",
                    "${n(be?.offset?.let { -it })} / ${n(be?.z)}", "${n(bd?.offset)} / ${n(bd?.z)}",
                    "${oe.code} ${n(-oe.offset)} / ${n(oe.z)}", "${od.code} ${n(od.offset)} / ${n(od.z)}", u.a(s.cutArea), u.a(s.fillArea))
            }
            rows.chunked(30).forEachIndexed { pi, chunk ->
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pi + 1).create())
                val c = page.canvas
                c.drawText(tr("Nota de serviço — {0} — eixo {1}", title, r.alignment.name), 20f, 30f, android.graphics.Paint(bold).apply { textSize = 11f })
                c.drawText(tr("Corte total {0} · Aterro total {1} · página {2}", u.vu(r.totalCut), u.vu(r.totalFill), pi + 1), 20f, 45f, paint)
                var y = 65f
                head.forEachIndexed { k, h -> c.drawText(h, colX[k], y, bold) }
                y += 14f
                for (row in chunk) { row.forEachIndexed { k, v -> c.drawText(v, colX[k], y, paint) }; y += 15f }
                doc.finishPage(page)
            }
            doc.writeTo(out)
            doc.close()
        }
    }

    // ---- Surface editing ----

    var surfaceEditMessage by mutableStateOf<String?>(null)
        private set
    private val surfaceUndo = ArrayDeque<Pair<String, AddedFile?>>()
    var canUndoSurface by mutableStateOf(false)
        private set

    /** Name of the surface an edit of [name] produces: the same when it is already an edited one. */
    fun editedName(name: String) = if (name.endsWith(EDITED_SUFFIX)) name else name + EDITED_SUFFIX

    /** Applies [op] to [source]; the result becomes (or replaces) the edited surface, kept as an added file. */
    fun editSurface(source: Surface, op: (Surface, String) -> Surface?) {
        val app = getApplication<Application>()
        val name = editedName(source.name)
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { op(source, name) }.getOrNull() }
            if (r == null || r.triangleCount == 0) { surfaceEditMessage = app.getString(R.string.se_failed); return@launch }
            surfaceUndo.addLast(name to addedFiles.firstOrNull { it.label == name })
            if (surfaceUndo.size > 30) surfaceUndo.removeFirst()
            canUndoSurface = true
            addedFiles = addedFiles.filter { it.label != name } +
                AddedFile(name, LandXmlProject(null, null, listOf(r), emptyList(), emptyList(), emptyList()), null)
            surfaceEditMessage = app.getString(
                R.string.se_done, name,
                app.resources.getQuantityString(R.plurals.se_done_points, r.vertexCount, r.vertexCount),
                app.resources.getQuantityString(R.plurals.se_done_triangles, r.triangleCount, r.triangleCount),
            )
            refreshJob?.cancel(); refreshNow()
        }
    }

    fun undoSurfaceEdit() {
        val (name, prev) = surfaceUndo.removeLastOrNull() ?: return
        addedFiles = addedFiles.filter { it.label != name } + listOfNotNull(prev)
        canUndoSurface = surfaceUndo.isNotEmpty()
        surfaceEditMessage = null
        refreshProject()
    }

    // ---- Slope and flow maps ----

    /** 0 off, 1 slope, 2 flow, 3 both. */
    var analysisMode by mutableStateOf(0)
    var analysisSurface by mutableStateOf<String?>(null)
    var slopeImage by mutableStateOf<TerrainImage?>(null)
        private set
    var slopeClasses by mutableStateOf<List<com.appdwg.core.SlopeClass>>(emptyList())
        private set
    var flowArrows by mutableStateOf<List<Pair<com.appdwg.core.Point2, Double>>>(emptyList())
        private set
    var flowChannels by mutableStateOf<List<Triple<com.appdwg.core.Point2, com.appdwg.core.Point2, Double>>>(emptyList())
        private set
    var flowMinArea by mutableStateOf(10_000.0)
        private set
    var analysisCell by mutableStateOf(0.0)
        private set
    private var flowGrid: com.appdwg.core.FlowGrid? = null
    private var analysisJob: Job? = null

    fun runAnalysis(surface: Surface) {
        analysisJob?.cancel()
        val app = getApplication<Application>()
        analysisJob = viewModelScope.launch {
            val result = tracked(app.getString(R.string.ta_running)) {
                withContext(Dispatchers.Default) {
                    val g = surface.rasterize(512) ?: return@withContext null
                    val slopes = com.appdwg.core.TerrainAnalysis.slopes(g)
                    val px = IntArray(slopes.size) { i -> val s = slopes[i]; if (s.isNaN()) 0 else
                        (com.appdwg.core.TerrainAnalysis.CLASSES[com.appdwg.core.TerrainAnalysis.classOf(s.toDouble())].third and 0x00FFFFFF) or (0xD8 shl 24) }
                    val bmp = android.graphics.Bitmap.createBitmap(px, g.cols, g.rows, android.graphics.Bitmap.Config.ARGB_8888)
                    val img = TerrainImage(surface.name, g.bounds, bmp.asImageBitmap())
                    // About 128 arrows across the longer side; the plan thins them out by the zoom.
                    val step = maxOf(1, maxOf(g.cols, g.rows) / 128)
                    val arrows = ArrayList<Pair<com.appdwg.core.Point2, Double>>()
                    for (r in step / 2 until g.rows step step) for (c in step / 2 until g.cols step step) {
                        val a = com.appdwg.core.TerrainAnalysis.aspect(g, c, r)
                        if (!a.isNaN()) arrows += com.appdwg.core.Point2(g.bounds.minX + (c + 0.5) * g.cellSize, g.bounds.maxY - (r + 0.5) * g.cellSize) to a
                    }
                    val flow = com.appdwg.core.TerrainAnalysis.flow(g) { !isActive }
                    Triple(Triple(img, com.appdwg.core.TerrainAnalysis.classes(g, slopes), arrows), flow, g.cellSize)
                }
            } ?: return@launch
            slopeImage = result.first.first; slopeClasses = result.first.second; flowArrows = result.first.third
            flowGrid = result.second; analysisCell = result.third
            analysisSurface = surface.name
            flowChannels = flowGrid?.channels(flowMinArea).orEmpty()
        }
    }

    fun changeFlowMinArea(a: Double) {
        flowMinArea = a
        flowChannels = flowGrid?.channels(a).orEmpty()
    }

    // ---- Drainage basins ----

    /** Basin of the last outlet chosen, or null. */
    var basin by mutableStateOf<com.appdwg.core.Basin?>(null)
        private set
    /** Outlets the app suggests: stream crossings of the alignments, exits of the work area, terrain outlets. */
    var outlets by mutableStateOf<List<com.appdwg.core.OutletSuggestion>>(emptyList())
        private set
    var basinMessage by mutableStateOf<String?>(null)
        private set
    /** Pit-filled flow of the surface last used, kept so each outlet is quick. */
    private var basinFlow: Pair<Surface, com.appdwg.core.FlowGrid>? = null

    private suspend fun basinFlow(surface: Surface): com.appdwg.core.FlowGrid? {
        basinFlow?.takeIf { it.first === surface }?.let { return it.second }
        val f = tracked(tr("Escoamento da superfície")) {
            withContext(Dispatchers.Default) {
                val g = surface.rasterize(1024) ?: return@withContext null
                com.appdwg.core.Hydrology.flow(g) { !isActive }
            }
        } ?: return null
        basinFlow = surface to f
        return f
    }

    /** Basin draining through the stream nearest to [p] (within about 30 m). */
    fun basinAt(surface: Surface, p: Point2) {
        viewModelScope.launch {
            val f = basinFlow(surface) ?: run { basinMessage = tr("A superfície não tem área para analisar."); return@launch }
            val cell = com.appdwg.core.Hydrology.snap(f, p, maxOf(30.0, 3 * f.grid.cellSize))
            val b = cell?.let { c -> tracked(tr("Delimitando a bacia")) { withContext(Dispatchers.Default) { com.appdwg.core.Hydrology.basin(f, c) } } }
            basin = b
            basinMessage = if (b == null) tr("O ponto está fora da superfície.") else null
        }
    }

    fun basinAt(surface: Surface, s: com.appdwg.core.OutletSuggestion) {
        viewModelScope.launch {
            val f = basinFlow(surface) ?: return@launch
            basin = tracked(tr("Delimitando a bacia")) { withContext(Dispatchers.Default) { com.appdwg.core.Hydrology.basin(f, s.cell) } }
        }
    }

    /**
     * Suggests outlets for the project: where streams of at least [minArea] m² cross the alignments
     * and the road, where water leaves the platform outline, and where it leaves the terrain.
     */
    fun suggestOutlets(surface: Surface, project: LandXmlProject, minArea: Double, extraLines: List<List<Point2>> = emptyList()) {
        viewModelScope.launch {
            val f = basinFlow(surface) ?: return@launch
            val lines = project.alignments.map { a -> a.vertices } + listOfNotNull(road?.alignment?.vertices) + extraLines
            val area = view?.platformOutline?.takeIf { it.size >= 3 }
            outlets = withContext(Dispatchers.Default) {
                val crossings = lines.flatMap { com.appdwg.core.Hydrology.crossings(f, it, minArea) }
                val exits = area?.let { com.appdwg.core.Hydrology.exits(f, it, minArea) }.orEmpty()
                (crossings + exits + com.appdwg.core.Hydrology.terrainOutlets(f).filter { it.area >= minArea }).take(30)
            }
            val axes = lines.count { it.size >= 2 }
            basinMessage = when {
                outlets.isNotEmpty() -> null
                axes == 0 && area == null -> tr("Não há eixo no projeto. Trace uma estrada ou toque o ponto de lançamento.")
                axes == 0 -> tr("Não há eixo de estrada. Os pontos, se houver, são onde a água sai da plataforma ou do terreno.")
                else -> tr("Nenhum talvegue com essa área cruza os eixos ou sai da área.")
            }
        }
    }

    fun clearBasin() { basin = null; outlets = emptyList(); basinMessage = null }

    /** Basin outline and main stream for DXF. */
    fun basinLines(): List<ExportLine> {
        val b = basin ?: return emptyList()
        return listOf(
            ExportLine("_BACIA", b.outline, true, 5),
            ExportLine("_BACIA_TALVEGUE", b.mainStream.map { Point2(it.x, it.y) }, false, 4, zs = b.mainStream.map { it.z }),
        )
    }

    // ---- Cross-section sheets ----

    /** Title block fields kept on the device, so a new project starts with them. */
    fun sheetDefaults(p: SheetParams): SheetParams {
        val prefs = getApplication<Application>().getSharedPreferences("sheets", android.content.Context.MODE_PRIVATE)
        return p.copy(company = p.company.ifBlank { prefs.getString("company", "") ?: "" },
            responsible = p.responsible.ifBlank { prefs.getString("responsible", "") ?: "" },
            registration = p.registration.ifBlank { prefs.getString("registration", "") ?: "" })
    }

    fun exportSectionSheets(target: Uri, p: SheetParams, title: String) {
        val app = getApplication<Application>()
        app.getSharedPreferences("sheets", android.content.Context.MODE_PRIVATE).edit {
            putString("company", p.company)
            putString("responsible", p.responsible)
            putString("registration", p.registration)
        }
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val axis = sheetAxis(p.alignment, project) ?: return
        val hidden = hiddenLayers()
        // Measurement sheets take every survey state, shown or not, in date order.
        val measurement = p.measurement && states.size >= 2
        val surfaces = if (measurement) states.mapNotNull { stateSurface(it) } else project.surfaces.filter { it.name !in hidden }
        val roadSections = road?.takeIf { it.alignment.name == axis.name }?.sections
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, com.appdwg.core.Display.locale).format(java.util.Date())
        val logoUri = p.logo
        writeTo(target, app.getString(R.string.sh_export)) { out ->
            val logo = logoUri?.let { runCatching { readLogo(it.toUri()) }.getOrNull() }
            val doc = android.graphics.pdf.PdfDocument()
            val built = SectionSheets.render(doc, p, axis, surfaces, roadSections, title, date, measurement, logo)
            doc.writeTo(out); doc.close()
            if (built.sections == 0) error(app.getString(R.string.sh_none))
        }
    }

    /** Logo picture for the title block, decoded small (it is printed a few centimetres wide). */
    private fun readLogo(uri: Uri): android.graphics.Bitmap? {
        val cr = getApplication<Application>().contentResolver
        val info = ImageHeader.parse { cr.openInputStream(uri) } ?: return null
        return CoilPictures.decode(getApplication(), uri, 800, BitmapDecode.configForMime(info.mime))
    }

    fun setSheetLogo(uri: Uri?) {
        val v = view ?: return
        uri?.let { runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        v.sheetParams = v.sheetParams.copy(logo = uri?.toString())
    }

    /** Axis of the sheets: an alignment of the project by name, or the axis drawn with PIs. */
    /** Drainage alignments currently on screen, so profile, section and sheets can draw them. */
    var drainageAxes by mutableStateOf<List<Alignment>>(emptyList())

    fun sheetAxis(name: String?, project: LandXmlProject): Alignment? {
        val rp = view?.roadParams
        if (name == PIS_AXIS) return rp?.takeIf { it.pis.size >= 2 }?.let { roadAxis(it.copy(alignmentIndex = -1), project) }
        return project.alignments.firstOrNull { it.name == name }
            ?: drainageAxes.firstOrNull { it.name == name }
            ?: project.alignments.firstOrNull()
            ?: drainageAxes.firstOrNull()
            ?: rp?.takeIf { it.pis.size >= 2 }?.let { roadAxis(it.copy(alignmentIndex = -1), project) }
    }

    /**
     * Straight axis through the middle of the measured area, along its longest side, laid as the two
     * PIs of the road axis so it can be edited there. False when there is no surface to follow.
     */
    fun generateAutoAxis(): Boolean {
        val v = view ?: return false
        val project = (state as? ProjectState.Loaded)?.project ?: return false
        val surface = states.firstOrNull()?.let { stateSurface(it) }
            ?: project.surfaces.filter { !it.name.startsWith(ROAD_LABEL) && !it.name.startsWith(PLATFORM_LABEL) }.maxByOrNull { it.triangleCount } ?: return false
        val axis = com.appdwg.core.SurveySections.autoAxis(surface, tr("Eixo")) ?: return false
        val line = axis.elements.single()
        v.roadParams = v.roadParams.copy(alignmentIndex = -1, pis = listOf(line.start, line.end), radii = emptyList(), spirals = emptyList())
        return true
    }

    // ---- Measurement bulletin ----

    private fun bulletinRows(): Pair<List<String>, List<List<String>>> {
        val app = getApplication<Application>()
        val items = view?.bulletinItems.orEmpty()
        fun q(x: Double) = grouped(x)
        val head = listOf("Item", app.getString(R.string.bm_desc), app.getString(R.string.bm_unit), app.getString(R.string.bm_contracted),
            app.getString(R.string.bm_previous), app.getString(R.string.bm_this), app.getString(R.string.bm_accum), app.getString(R.string.bm_percent),
            app.getString(R.string.bm_price), app.getString(R.string.bm_value), app.getString(R.string.bm_value_acc))
        val rows = items.mapIndexed { i, it ->
            listOf((i + 1).toString(), it.description, it.unit, q(it.contracted), q(it.previous), q(it.period), q(it.accumulated),
                StationFormat.decimal(it.percent, 1), q(it.price), q(it.periodValue), q(it.accumulatedValue))
        }
        return head to rows
    }

    /** 1.234.567,89 */
    private fun grouped(x: Double): String = StationFormat.grouped(x, 2)

    private fun bulletinTotals(): String {
        val items = view?.bulletinItems.orEmpty()
        val acc = items.sumOf { it.accumulatedValue }; val con = items.sumOf { it.contractedValue }
        return getApplication<Application>().getString(R.string.bm_totals, grouped(items.sumOf { it.periodValue }),
            grouped(acc), grouped(con), StationFormat.decimal(if (con > 0) acc / con * 100 else 0.0, 1))
    }

    fun exportBulletinCsv(target: Uri, title: String) {
        val v = view ?: return
        val app = getApplication<Application>()
        val (head, rows) = bulletinRows()
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, com.appdwg.core.Display.locale).format(java.util.Date())
        val text = buildString {
            append(app.getString(R.string.bm_title)).append("\r\n")
            append(app.getString(R.string.bm_pdf_head, title, v.bulletinNumber, v.bulletinPeriod, date)).append("\r\n\r\n")
            append(head.joinToString(";")).append("\r\n")
            rows.forEach { append(it.joinToString(";") { c -> c.replace(';', ',') }).append("\r\n") }
            append("\r\n").append(bulletinTotals()).append("\r\n")
        }
        writeTo(target, app.getString(R.string.bm_export_csv)) { it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)) }
    }

    /** [text] cut with an ellipsis to fit [width] points in [paint]. */
    private fun fit(text: String, paint: android.graphics.Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var n = text.length
        while (n > 1 && paint.measureText(text, 0, n) + paint.measureText("…") > width) n--
        return text.take(n).trimEnd() + "…"
    }

    /** A4 landscape table, 28 items per page, totals on the last page. */
    fun exportBulletinPdf(target: Uri, title: String) {
        val v = view ?: return
        val app = getApplication<Application>()
        val (head, rows) = bulletinRows()
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, com.appdwg.core.Display.locale).format(java.util.Date())
        writeTo(target, app.getString(R.string.bm_export_pdf)) { out ->
            val doc = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 7.5f; isAntiAlias = true }
            val bold = android.graphics.Paint(paint).apply { isFakeBoldText = true }
            val right = android.graphics.Paint(paint).apply { textAlign = android.graphics.Paint.Align.RIGHT }
            val rightBold = android.graphics.Paint(bold).apply { textAlign = android.graphics.Paint.Align.RIGHT }
            // Column right edges for numbers; description and unit left aligned.
            val x = floatArrayOf(20f, 40f, 300f, 380f, 440f, 500f, 560f, 600f, 660f, 730f, 822f)
            val chunks = rows.chunked(28).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { pi, chunk ->
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pi + 1).create())
                val c = page.canvas
                c.drawText(app.getString(R.string.bm_title), 20f, 32f, android.graphics.Paint(bold).apply { textSize = 13f })
                c.drawText(app.getString(R.string.bm_pdf_head, title, v.bulletinNumber, v.bulletinPeriod, date), 20f, 48f, paint)
                var y = 72f
                head.forEachIndexed { k, h -> if (k <= 2) c.drawText(h, x[k], y, bold) else c.drawText(h, x[k], y, rightBold) }
                c.drawLine(20f, y + 4f, 822f, y + 4f, paint)
                y += 16f
                for (row in chunk) {
                    row.forEachIndexed { k, cell ->
                        if (k <= 2) c.drawText(if (k == 1) fit(cell, paint, x[2] - x[1] - 8f) else cell, x[k], y, paint) else c.drawText(cell, x[k], y, right)
                    }
                    y += 15f
                }
                if (pi == chunks.lastIndex) { c.drawLine(20f, y - 8f, 822f, y - 8f, paint); c.drawText(bulletinTotals(), 20f, y + 6f, bold) }
                c.drawText(app.getString(R.string.asbuilt_page, pi + 1, chunks.size), 760f, 580f, paint)
                doc.finishPage(page)
            }
            doc.writeTo(out)
            doc.close()
        }
    }

    // ---- Stockpile / volume to an elevation ----

    var volume by mutableStateOf<com.appdwg.core.VolumeResult?>(null)
        private set
    var volumeMessage by mutableStateOf<String?>(null)
        private set
    private var volumeJob: Job? = null

    fun computeVolume(surface: Surface, polygon: List<com.appdwg.core.Point2>?, base: com.appdwg.core.VolumeBase) {
        volumeJob?.cancel()
        volumeMessage = null
        val app = getApplication<Application>()
        volumeJob = viewModelScope.launch {
            val r = tracked(app.getString(R.string.vol_running)) {
                withContext(Dispatchers.Default) { com.appdwg.core.Volumes.of(surface, polygon, base) { !isActive } }
            }
            volume = r
            if (r == null) volumeMessage = app.getString(R.string.vol_none)
        }
    }

    fun clearVolume() { volume = null; volumeMessage = null }

    // ---- As-built check ----

    /** Last as-built check, also drawn on the plan as coloured rings. */
    var asBuilt by mutableStateOf<com.appdwg.core.AsBuiltReport?>(null)
        private set

    fun checkAsBuilt(points: List<CogoPoint>, surface: Surface, alignment: com.appdwg.core.Alignment?, tolerance: Double) {
        asBuilt = com.appdwg.core.AsBuiltReport.check(points, surface, alignment, tolerance)
    }

    fun clearAsBuilt() { asBuilt = null }

    private fun asBuiltStatus(s: com.appdwg.core.AsBuiltStatus) = getApplication<Application>().getString(when (s) {
        com.appdwg.core.AsBuiltStatus.OK -> R.string.asbuilt_status_ok
        com.appdwg.core.AsBuiltStatus.ABOVE -> R.string.asbuilt_status_above
        com.appdwg.core.AsBuiltStatus.BELOW -> R.string.asbuilt_status_below
        com.appdwg.core.AsBuiltStatus.OUTSIDE -> R.string.asbuilt_status_outside
    })

    /** Cells of one report row: point, description, E, N, surveyed, design, difference, station, offset, status. */
    private fun asBuiltCells(r: com.appdwg.core.AsBuiltReport, row: com.appdwg.core.AsBuiltRow, u: com.appdwg.core.ReportUnits): List<String> {
        fun n(v: Double?) = v?.takeIf { !it.isNaN() }?.let { u.l(it) } ?: ""
        val p = row.point
        return listOf(p.number, p.description, n(p.x), n(p.y), n(p.z), n(row.design), n(row.delta),
            row.stationOffset?.let { u.station(it.station) } ?: "", row.stationOffset?.let { u.offset(it.offset) } ?: "",
            asBuiltStatus(r.status(row)))
    }

    private fun asBuiltSummary(r: com.appdwg.core.AsBuiltReport, u: com.appdwg.core.ReportUnits): Pair<String, String> {
        val app = getApplication<Application>()
        fun l(v: Double?) = v?.let { u.lu(it) } ?: "—"
        fun qty(id: Int, n: Int) = app.resources.getQuantityString(id, n, n)
        return app.getString(
            R.string.asbuilt_summary,
            qty(R.plurals.asbuilt_checked, r.checked),
            StationFormat.decimal(r.passRate * 100, 1),
            qty(R.plurals.asbuilt_above, r.above),
            qty(R.plurals.asbuilt_below, r.below),
            qty(R.plurals.asbuilt_outside, r.outside),
        ) to
            app.getString(R.string.asbuilt_stats, l(r.mean), l(r.rms), l(r.stdDev), l(r.min), l(r.max))
    }

    /** Column titles of the as-built table, with the length unit where it applies. */
    private fun asBuiltHead(u: com.appdwg.core.ReportUnits): List<String> {
        val app = getApplication<Application>()
        fun w(id: Int) = app.getString(id) + " (" + u.len + ")"
        return listOf(app.getString(R.string.asbuilt_col_point), app.getString(R.string.asbuilt_col_desc), "E (${u.len})", "N (${u.len})",
            w(R.string.asbuilt_col_surveyed), w(R.string.asbuilt_col_design), w(R.string.asbuilt_col_delta), app.getString(R.string.asbuilt_col_station),
            app.getString(R.string.asbuilt_col_offset), app.getString(R.string.asbuilt_col_status))
    }

    fun exportAsBuiltCsv(target: Uri, title: String) {
        val r = asBuilt ?: return
        val app = getApplication<Application>()
        val u = com.appdwg.core.ReportUnits(metric = true)
        val head = asBuiltHead(u)
        val (summary, stats) = asBuiltSummary(r, u)
        val text = buildString {
            append(app.getString(R.string.asbuilt_report_title)).append(" — ").append(title).append("\r\n")
            append(summary).append("\r\n").append(stats).append("\r\n\r\n")
            append(head.joinToString(";")).append("\r\n")
            for (row in r.rows) append(asBuiltCells(r, row, u).joinToString(";") { it.replace(';', ',') }).append("\r\n")
        }
        writeTo(target, app.getString(R.string.asbuilt_export_csv)) { it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)) }
    }

    /** A4 portrait: heading and summary on every page, 40 rows per page, the status in colour. */
    fun exportAsBuiltPdf(target: Uri, title: String) {
        val r = asBuilt ?: return
        val app = getApplication<Application>()
        val u = com.appdwg.core.ReportUnits()
        val (summary, stats) = asBuiltSummary(r, u)
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, com.appdwg.core.Display.locale).format(java.util.Date())
        writeTo(target, app.getString(R.string.asbuilt_export_pdf)) { out ->
            val doc = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 7.5f; isAntiAlias = true }
            val bold = android.graphics.Paint(paint).apply { isFakeBoldText = true }
            val head = asBuiltHead(u)
            val colX = floatArrayOf(28f, 70f, 140f, 205f, 275f, 325f, 375f, 425f, 480f, 525f)
            val chunks = r.rows.chunked(40).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { pi, chunk ->
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pi + 1).create())
                val c = page.canvas
                c.drawText(app.getString(R.string.asbuilt_report_title), 28f, 40f, android.graphics.Paint(bold).apply { textSize = 13f })
                c.drawText(app.getString(R.string.asbuilt_report_head, title, r.surface, r.alignment ?: "—", u.lu(r.tolerance), date), 28f, 56f, paint)
                c.drawText(summary, 28f, 70f, bold)
                c.drawText(stats, 28f, 82f, paint)
                c.drawText(app.getString(R.string.asbuilt_sign), 28f, 94f, paint)
                var y = 116f
                head.forEachIndexed { k, h -> c.drawText(h, colX[k], y, bold) }
                c.drawLine(28f, y + 4f, 567f, y + 4f, paint)
                y += 16f
                for (row in chunk) {
                    val cells = asBuiltCells(r, row, u)
                    cells.forEachIndexed { k, v ->
                        val pp = if (k == cells.lastIndex) android.graphics.Paint(bold).apply {
                            color = when (r.status(row)) {
                                com.appdwg.core.AsBuiltStatus.OK -> 0xFF2E7D32.toInt()
                                com.appdwg.core.AsBuiltStatus.ABOVE -> 0xFF1565C0.toInt()
                                com.appdwg.core.AsBuiltStatus.BELOW -> 0xFFC62828.toInt()
                                com.appdwg.core.AsBuiltStatus.OUTSIDE -> 0xFF757575.toInt()
                            }
                        } else paint
                        // Long descriptions are cut so they do not run into the next column.
                        c.drawText(if (k == 1 && v.length > 14) v.take(13) + "…" else v, colX[k], y, pp)
                    }
                    y += 17f
                }
                c.drawText(app.getString(R.string.asbuilt_page, pi + 1, chunks.size), 500f, 820f, paint)
                doc.finishPage(page)
            }
            doc.writeTo(out)
            doc.close()
        }
    }

    // ---- 3D models ----

    /** Models added to the project, each with its placement. */
    var models by mutableStateOf<List<ModelEntry>>(emptyList())
        private set
    var modelMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Reads a model from [uris]: the model file plus, optionally, files it refers to (an OBJ's .mtl, a
     * glTF's .bin), chosen together. Placed by its georeference, or at [at] on the terrain.
     */
    fun addModel(uris: List<Uri>, at: Point2?) {
        val named = uris.map { it to displayName(it) }
        val main = named.firstOrNull { it.second.substringAfterLast('.').lowercase() in MODEL_EXTENSIONS }
            ?: run { modelMessage = tr("Escolha um arquivo OBJ, STL, PLY, glTF/GLB ou IFC."); return }
        uris.forEach { u -> runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        modelMessage = null
        viewModelScope.launch {
            try {
                val placementFor: (Model3D) -> ModelPlacement = { m -> defaultPlacement(m, at) }
                val entry = tracked(tr("Lendo modelo {0}", main.second)) { t ->
                    withContext(Dispatchers.Default) { loadModel(main.first, main.second, named, t)?.let { m -> ModelEntry(main.second, uris, m, placementFor(m)) } }
                } ?: return@launch
                models = models + entry
                refreshJob?.cancel(); refreshNow()
                val m = entry.model
                modelMessage = tr("{0}: {1} elemento(s), {2} triângulos", main.second, m.meshes.size, m.triangleCount) +
                    (if (m.georef != null) tr(", posicionado pela georreferência do IFC") else "") + "." +
                    (if (m.warnings.isNotEmpty()) tr(" Avisos: ") + m.warnings.take(5).joinToString("; ") else "")
            } catch (e: OutOfMemoryError) {
                modelMessage = tr("Modelo grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                modelMessage = tr("Não foi possível ler {0}: {1}", main.second, errorText(e))
            }
        }
    }

    private fun loadModel(uri: Uri, name: String, named: List<Pair<Uri, String>>, task: LoadTask): Model3D? {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error(tr("arquivo indisponível"))
        task.step(tr("Montando a geometria"))
        val model = ModelReader.read(bytes, name) { other ->
            // Companion files are matched by name among the ones chosen with the model.
            named.firstOrNull { it.second.equals(other.substringAfterLast('/').substringAfterLast('\\'), ignoreCase = true) }
                ?.let { (u, _) -> resolver.openInputStream(u)?.use { it.readBytes() } }
        }
        require(model.meshes.isNotEmpty()) { tr("nenhuma geometria encontrada") + if (model.warnings.isNotEmpty()) " (" + model.warnings.joinToString("; ") + ")" else "" }
        require(model.triangleCount <= MAX_MODEL_TRIANGLES) { tr("modelo com {0} triângulos; o limite neste aparelho é {1}. Simplifique o modelo.", model.triangleCount, MAX_MODEL_TRIANGLES) }
        return model
    }

    private fun defaultPlacement(m: Model3D, at: Point2?): ModelPlacement {
        m.georef?.let { g -> return ModelPlacement(g.east, g.north, g.height, g.rotationDeg, g.scale, m.unitScale) }
        val project = (state as? ProjectState.Loaded)?.project
        val p = at ?: project?.bounds?.center ?: Point2(0.0, 0.0)
        val z = project?.surfaces?.firstNotNullOfOrNull { it.elevationAt(p) } ?: 0.0
        return ModelPlacement(p.x, p.y, z, 0.0, 1.0, m.unitScale)
    }

    fun placeModel(index: Int, placement: ModelPlacement) {
        models = models.mapIndexed { i, m -> if (i == index) m.copy(placement = placement) else m }
        refreshProject()
    }

    fun removeModel(index: Int) {
        models = models.filterIndexed { i, _ -> i != index }
        refreshProject()
    }

    /** Elevation of the first surface at [p], for placing a model on the ground. */
    fun groundAt(p: Point2): Double? = (state as? ProjectState.Loaded)?.project?.surfaces?.firstNotNullOfOrNull { it.elevationAt(p) }

    // ---- Platform design ----

    var platform by mutableStateOf<PlatformResult?>(null)
        private set
    var platformVolumes by mutableStateOf<SurfaceDifference?>(null)
        private set
    var platformMessage by mutableStateOf<String?>(null)
        private set

    /** Designs the platform of the view settings against their terrain and lays it over the project. */
    fun designPlatform() {
        viewModelScope.launch { designPlatformNow() }
    }

    private suspend fun designPlatformNow() {
        val v = view ?: return
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val terrain = project.surfaces.firstOrNull { it.name == v.platformTerrain }
            ?: project.surfaces.firstOrNull { !it.name.startsWith(PLATFORM_LABEL) }
        if (terrain == null) { platformMessage = tr("Abra ou adicione uma superfície de terreno."); return }
        if (v.platformOutline.size < 3) { platformMessage = tr("Desenhe a plataforma com 3 vértices ou mais."); return }
        val pp = v.platformParams
        val outline = v.platformOutline
        val vertexZ = if (pp.useVertexElevations) outline.map { p -> userPoints.firstOrNull { it.x == p.x && it.y == p.y }?.z ?: Double.NaN } else null
        if (vertexZ != null && vertexZ.any { it.isNaN() }) { platformMessage = tr("Há vértices sem cota de ponto; desmarque \"usar a cota de cada ponto\"."); return }
        val base = pp.elevation ?: outline.mapNotNull { terrain.elevationAt(it) }.average().takeIf { !it.isNaN() }
            ?: run { platformMessage = tr("A plataforma está fora do terreno."); return }
        platformMessage = null
        try {
            val result = tracked(tr("Projetando plataforma")) { t ->
                withContext(Dispatchers.Default) {
                    t.step(tr("Taludes e bermas"))
                    val d = PlatformDesign(
                        outline, base, pp.gradePercent, pp.gradeAzimuth, vertexZ,
                        cut = SlopeRule(pp.cutRatio, pp.cutBenchHeight, pp.cutBenchWidth, pp.cutBenchFall, pp.cutHeights),
                        fill = SlopeRule(pp.fillRatio, pp.fillBenchHeight, pp.fillBenchWidth, pp.fillBenchFall, pp.fillHeights),
                    )
                    val r = Platform.design(d, terrain, PLATFORM_LABEL)
                    t.step(tr("Volumes"))
                    r to SurfaceCompare.difference(terrain, r.surface)
                }
            }
            platform = result.first
            platformVolumes = result.second
            v.platformTerrain = terrain.name
            v.platformBuilt = true
            addedFiles = addedFiles.filter { it.label != PLATFORM_LABEL } +
                AddedFile(PLATFORM_LABEL, LandXmlProject(null, null, listOf(result.first.surface), emptyList(), emptyList(), emptyList()), null)
            refreshJob?.cancel()
            refreshNow()
            result.second?.let { d ->
                difference = d
                differenceImage = withContext(Dispatchers.Default) { DifferenceImage.of(d) }
                differenceVisible = true
                v.compareBase = terrain.name; v.compareOther = PLATFORM_LABEL
            }
            platformMessage = if (result.first.unresolved > 0)
                tr("{0} perfil(is) de talude não encontraram o terreno (saem da superfície ou passam de 300 m).", result.first.unresolved) else null
        } catch (e: OutOfMemoryError) {
            platformMessage = tr("Memória insuficiente para projetar.")
        } catch (e: Exception) {
            platformMessage = tr("Não foi possível projetar: {0}", errorText(e))
        }
    }

    /** Shapes the app suggests for the drawn outline, with their earthwork. */
    var platformOptions by mutableStateOf<List<com.appdwg.core.PlatformOption>>(emptyList())
        private set
    /** Outline the options were worked out for; they no longer apply once it changes. */
    var platformOptionsOutline: List<Point2> = emptyList()
        private set

    /**
     * Suggests platform shapes for the drawn outline with the slopes in [pp]: flat or following
     * the ground, balanced or with the least earthwork, the fall capped at [maxGrade] percent.
     */
    fun suggestPlatforms(pp: PlatformParams, maxGrade: Double, swell: Double) {
        val v = view ?: return
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val terrain = project.surfaces.firstOrNull { it.name == v.platformTerrain }
            ?: project.surfaces.firstOrNull { !it.name.startsWith(PLATFORM_LABEL) }
        if (terrain == null) { platformMessage = tr("Abra ou adicione uma superfície de terreno."); return }
        if (v.platformOutline.size < 3) { platformMessage = tr("Desenhe a plataforma com 3 vértices ou mais."); return }
        val outline = v.platformOutline
        viewModelScope.launch {
            try {
                platformOptionsOutline = outline
                platformOptions = tracked(tr("Estudando formas da plataforma")) {
                    withContext(Dispatchers.Default) {
                        com.appdwg.core.PlatformOptions.suggest(outline, terrain,
                            SlopeRule(pp.cutRatio, pp.cutBenchHeight, pp.cutBenchWidth, pp.cutBenchFall, pp.cutHeights),
                            SlopeRule(pp.fillRatio, pp.fillBenchHeight, pp.fillBenchWidth, pp.fillBenchFall, pp.fillHeights),
                            maxGrade, swell) { !isActive }
                    }
                }
                platformMessage = if (platformOptions.isEmpty()) tr("A plataforma está fora do terreno.") else null
            } catch (e: Exception) {
                platformMessage = tr("Não foi possível projetar: {0}", errorText(e))
            }
        }
    }

    fun removePlatform() {
        platform = null
        platformVolumes = null
        view?.platformBuilt = false
        if (difference?.otherName == PLATFORM_LABEL) clearDifference()
        addedFiles = addedFiles.filter { it.label != PLATFORM_LABEL }
        refreshProject()
    }

    /** Edge, daylight and bench lines of the platform for DXF, in 3D. */
    fun platformLines(): List<ExportLine> {
        val r = platform ?: return emptyList()
        fun line(layer: String, pts: List<Point3>, aci: Int) = ExportLine(layer, pts.map { Point2(it.x, it.y) }, false, aci, zs = pts.map { it.z })
        return listOf(line("_PLAT_BORDA", r.edge, 1), line("_PLAT_OFFSET", r.daylight, 3)) + r.benches.map { line("_PLAT_BERMA", it, 4) }
    }

    // ---- Export ----

    var exportMessage by mutableStateOf<String?>(null)
        private set

    /** The shown project with the COGO points, as exports see it. */
    private fun exportProject(): LandXmlProject? =
        (state as? ProjectState.Loaded)?.project?.copy(points = userPoints)

    private fun hiddenLayers(): Set<String> = view?.let { it.offLayers.toSet() + it.frozenLayers } ?: emptySet()

    /** Measurement and cross section lines, for DXF and KML. */
    fun extraLines(sectionLine: Pair<Point2, Point2>?): List<ExportLine> = buildList {
        val m = view?.measurePoints.orEmpty()
        if (m.size >= 2) add(ExportLine("_MEDICAO", m, m.size >= 3, 4))
        sectionLine?.let { add(ExportLine("_SECAO", listOf(it.first, it.second), false, 2)) }
    }

    /** Writes [bytes] in pieces, showing the amount sent on [task]. */
    private fun writeWithProgress(target: Uri, bytes: ByteArray, task: LoadTask, cloud: Boolean) {
        (getApplication<Application>().contentResolver.openOutputStream(target, "wt") ?: error(tr("destino indisponível"))).use { out ->
            var done = 0
            while (done < bytes.size) {
                val n = minOf(1 shl 16, bytes.size - done)
                out.write(bytes, done, n)
                done += n
                fun mb(b: Int) = StationFormat.decimal(b / 1048576.0, 1)
                task.step((if (cloud) tr("Enviando · ") else tr("Gravando · ")) + mb(done) + " de " + mb(bytes.size) + " MB", done.toFloat() / bytes.size)
            }
        }
    }

    /** Removes the empty file a failed export left behind (the picker creates it before anything is written). */
    private suspend fun dropEmpty(target: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val cr = getApplication<Application>().contentResolver
            val size = cr.query(target, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            if (size == 0L) android.provider.DocumentsContract.deleteDocument(cr, target)
        }
    }

    private fun writeTo(target: Uri, what: String, block: (java.io.OutputStream) -> Unit) {
        viewModelScope.launch {
            exportMessage = try {
                val cloud = CloudDocs.isDrive(target.toString())
                tracked(if (cloud) tr("Exportando {0} para o Google Drive", what) else tr("Exportando {0}", what)) { t ->
                    withContext(Dispatchers.IO) {
                        val raw = getApplication<Application>().contentResolver.openOutputStream(target, "wt") ?: error(tr("destino indisponível"))
                        // Bytes written so far, shown while the file is made and sent.
                        val counting = object : java.io.FilterOutputStream(raw) {
                            var done = 0L
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                out.write(b, off, len); done += len
                                t.step((if (cloud) tr("Enviando · ") else tr("Gravando · ")) + StationFormat.decimal(done / 1048576.0, 1) + " MB")
                            }
                        }
                        counting.buffered(1 shl 16).use { block(it) }
                    }
                }
                if (cloud) tr("{0} exportado para o Google Drive ({1}); o app Drive termina o envio em segundo plano.", what, displayName(target))
                else tr("{0} exportado para {1}.", what, displayName(target))
            } catch (e: OutOfMemoryError) {
                dropEmpty(target)
                tr("Memória insuficiente para exportar {0}.", what)
            } catch (e: Exception) {
                dropEmpty(target)
                tr("Não foi possível exportar {0}: {1}", what, errorText(e))
            }
        }
    }

    /** [contourInterval] adds contour lines of the shown surfaces on layer _CURVAS. */
    fun exportDxf(target: Uri, includeSurfaces: Boolean, extra: List<ExportLine>, contourInterval: Double? = null, includeModels: Boolean = false) {
        val p = exportProject() ?: return
        val hidden = hiddenLayers()
        writeTo(target, "DXF") { out ->
            val contours = contourInterval?.let { iv ->
                p.surfaces.filter { it.name !in hidden }.flatMap { s ->
                    Contours.lines(Contours.segments(s, iv, maxSegments = 20_000_000) ?: emptyList())
                }.map { c -> ExportLine("_CURVAS", List(c.xs.size) { Point2(c.xs[it], c.ys[it]) }, false, 34, c.elevation) }
            }.orEmpty()
            out.writer(Charsets.UTF_8).let { w -> DxfWriter.write(p, w, hidden, extra + contours, includeSurfaces, includeModels); w.flush() }
        }
    }

    fun exportLandXml(target: Uri) {
        val p = exportProject() ?: return
        val hidden = hiddenLayers()
        writeTo(target, "LandXML") { out ->
            out.writer(Charsets.UTF_8).let { w ->
                val sources = HashMap<String, SurfaceSource>()
                platform?.let { r -> sources[PLATFORM_LABEL] = SurfaceSource(listOf(r.edge, r.daylight) + r.benches, r.daylight.map { Point2(it.x, it.y) }) }
                road?.let { r ->
                    // Outline: the left daylight down the road, back up the right one.
                    val outline = r.sections.map { sec -> sec.pointAt(sec.top.first().offset) } + r.sections.reversed().map { sec -> sec.pointAt(sec.top.last().offset) }
                    sources[ROAD_LABEL] = SurfaceSource(r.features.values.flatten(), outline)
                }
                LandXmlWriter.write(p.surfaces.filter { it.name !in hidden }, p.points, p.cgPoints, w, p.coordinateSystem?.epsgCode, sources,
                    p.alignments, road?.let { mapOf(it.alignment.name to it.sections) }.orEmpty())
                w.flush()
            }
        }
    }

    /** Civil 3D pipe network of the placed drainage. The sweep runs on the export thread. */
    fun exportPipeNetwork(target: Uri) {
        val devices = drainage
        val catalog = drainageCatalog
        val names = drainageNames
        val project = exportProject() ?: return
        writeTo(target, tr("rede de drenagem")) { out ->
            val hidden = hiddenLayers()
            val surfaces = project.surfaces.filter { it.name !in hidden && it.triangleCount > 0 }
            val terrain: (Double, Double) -> Double? = { x, y -> surfaces.firstNotNullOfOrNull { it.elevationAt(Point2(x, y)) } }
            val alignmentOf: (String) -> Alignment? = { name -> project.alignments.firstOrNull { it.name == name } }
            val graph = com.appdwg.core.drainage.DrainageNetwork.analyze(
                devices,
                { placed -> catalog.device(placed.code)?.let { com.appdwg.core.drainage.resolveSection(it, placed.params) } },
                { false },
                { placed -> catalog.device(placed.code)?.let(com.appdwg.core.drainage.DrainageNetwork::roughnessOf) ?: 0.015 },
                terrain,
                alignmentOf,
            )
            val circular = devices.associate { placed ->
                val type = catalog.device(placed.code)?.section?.type.orEmpty()
                placed.id to (type == "circular_barrels" || type.startsWith("circular"))
            }
            val bends = devices.associate { placed ->
                val section = catalog.device(placed.code)?.let { com.appdwg.core.drainage.resolveSection(it, placed.params) }
                placed.id to com.appdwg.core.drainage.PipeNetworkWriter.vertices(placed, section, terrain, alignmentOf)
            }
            val scene = buildDrainScene(devices, catalog, names, project)
            out.writer(Charsets.UTF_8).use { writer ->
                com.appdwg.core.drainage.PipeNetworkWriter.write(
                    "Drenagem", graph, { circular[it] == true }, writer, scene.runs, scene.runProfiles, bends,
                )
            }
        }
    }

    /** Quantity table of the placed drainage, semicolon separated, with a BOM for Excel. */
    fun exportDrainCsv(target: Uri) = exportDrainReport(target, pdf = false)

    /** Same quantities as [exportDrainCsv], laid out as an A4 table. */
    fun exportDrainPdf(target: Uri) = exportDrainReport(target, pdf = true)

    /** IFC4 property exchange: each device is a pipe segment with a drainage property set. */
    fun exportDrainIfc(target: Uri) {
        val devices = drainage
        val catalog = drainageCatalog
        val names = drainageNames
        val phases = drainagePhases
        val project = exportProject() ?: return
        writeTo(target, tr("IFC de drenagem")) { out ->
            val scene = buildDrainScene(devices, catalog, names, project)
            val graph = analyzeDrainage(devices, catalog, project)
            val codeOf = devices.associate { it.id to it.code }
            val phaseOf = phases.associate { it.deviceId to it.phase }
            val ifc = scene.takeoff.map { row ->
                val placed = devices.find { it.id == row.id }
                val def = catalog.device(row.code)
                val links = graph.links.filter { it.upstreamId == row.id || it.downstreamId == row.id }
                val connected = links.joinToString(",") { link ->
                    val other = if (link.upstreamId == row.id) link.downstreamId else link.upstreamId
                    codeOf[other] ?: other
                }
                val (x, y) = when (placed) {
                    is com.appdwg.core.drainage.PlacedDevice.Linear ->
                        (placed.xs.firstOrNull() ?: 0.0) to (placed.ys.firstOrNull() ?: 0.0)
                    is com.appdwg.core.drainage.PlacedDevice.Point -> placed.x to placed.y
                    else -> 0.0 to 0.0
                }
                val rings = if (def == null) emptyList() else com.appdwg.core.drainage.DeviceBody.previewRings(def).ifEmpty {
                    com.appdwg.core.drainage.resolveSection(def, placed?.params.orEmpty())?.rings.orEmpty()
                }
                val path = when (placed) {
                    is com.appdwg.core.drainage.PlacedDevice.Linear -> {
                        val count = placed.xs.size
                        val z0 = row.invertUp.takeIf { it.isFinite() } ?: 0.0
                        val z1 = row.invertDown.takeIf { it.isFinite() } ?: z0
                        placed.xs.indices.map { i ->
                            val t = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
                            com.appdwg.core.drainage.IfcPathPoint(placed.xs[i], placed.ys[i], z0 + (z1 - z0) * t)
                        }
                    }
                    is com.appdwg.core.drainage.PlacedDevice.Point -> listOf(
                        com.appdwg.core.drainage.IfcPathPoint(placed.x, placed.y, row.invertUp.takeIf { it.isFinite() } ?: 0.0),
                    )
                    else -> emptyList()
                }
                com.appdwg.core.drainage.IfcDevice(
                    row.id, row.code, def?.name ?: row.code, def?.family ?: "DNIT IPR-736",
                    row.length, row.invertUp, row.invertDown, row.gradeSlope.takeIf { it.isFinite() } ?: 0.0, phaseOf[row.id] ?: 0,
                    x, y, if (row.invertUp.isNaN()) 0.0 else row.invertUp, connected,
                    section = rings, path = path,
                )
            }
            out.writer(Charsets.UTF_8).use { com.appdwg.core.drainage.IfcDrainage.write("Drenagem", ifc, it) }
        }
    }

    private fun exportDrainReport(target: Uri, pdf: Boolean) {
        val devices = drainage
        val catalog = drainageCatalog
        val names = drainageNames
        val phases = drainagePhases
        val project = exportProject() ?: return
        val what = tr(if (pdf) "PDF de quantidades" else "CSV de quantidades")
        writeTo(target, what) { out ->
            val scene = buildDrainScene(devices, catalog, names, project)
            if (pdf) writeDrainPdf(scene, phases, out)
            else out.write(("\uFEFF" + drainCsv(scene, phases)).toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildDrainScene(
        devices: List<com.appdwg.core.drainage.PlacedDevice>,
        catalog: com.appdwg.core.drainage.DeviceCatalog,
        names: Map<String, String>,
        project: LandXmlProject,
    ): com.appdwg.core.drainage.DrainageBuilt {
        val hidden = hiddenLayers()
        val surfaces = project.surfaces.filter { it.name !in hidden && it.triangleCount > 0 }
        val terrain: (Double, Double) -> Double? = { x, y -> surfaces.firstNotNullOfOrNull { it.elevationAt(com.appdwg.core.Point2(x, y)) } }
        val alignmentOf: (String) -> com.appdwg.core.Alignment? = { name -> project.alignments.firstOrNull { it.name == name } }
        return com.appdwg.core.drainage.DrainageScene.build(devices, { catalog.device(it) }, terrain, alignmentOf, names)
    }

    private fun analyzeDrainage(
        devices: List<com.appdwg.core.drainage.PlacedDevice>,
        catalog: com.appdwg.core.drainage.DeviceCatalog,
        project: LandXmlProject,
    ): com.appdwg.core.drainage.DrainageGraph {
        val hidden = hiddenLayers()
        val surfaces = project.surfaces.filter { it.name !in hidden && it.triangleCount > 0 }
        val terrain: (Double, Double) -> Double? = { x, y -> surfaces.firstNotNullOfOrNull { it.elevationAt(com.appdwg.core.Point2(x, y)) } }
        val alignmentOf: (String) -> com.appdwg.core.Alignment? = { name -> project.alignments.firstOrNull { it.name == name } }
        return com.appdwg.core.drainage.DrainageNetwork.analyze(
            devices,
            { placed -> catalog.device(placed.code)?.let { com.appdwg.core.drainage.resolveSection(it, placed.params) } },
            { false },
            { placed -> catalog.device(placed.code)?.let(com.appdwg.core.drainage.DrainageNetwork::roughnessOf) ?: 0.015 },
            terrain,
            alignmentOf,
            cellsOf = { placed ->
                com.appdwg.core.drainage.DrainageNetwork.parallelCells(catalog.device(placed.code), placed.params)
            },
        )
    }

    private fun drainCsv(
        scene: com.appdwg.core.drainage.DrainageBuilt,
        phases: List<com.appdwg.core.drainage.DevicePhase>,
    ): String {
        val phaseOf = phases.associate { it.deviceId to it.phase }
        fun n(v: Double) = if (v.isNaN()) "" else StationFormat.decimal(v, 3)
        fun yn(v: Boolean) = if (v) tr("sim") else tr("não")
        return buildString {
            append(tr("Código;Eixo;Comprimento (m);Montante (m);Jusante (m);Declividade (%);Escavação (m³);Reaterro (m³);Berço (m³);Bota-fora (m³);Aterro (m³);Concreto (m³);Formas (m²);Escoramento;Profundo;Fase"))
            append("\r\n")
            for (row in scene.takeoff) {
                val run = scene.runs.find { row.id in it.deviceIds }
                val slope = if (row.gradeSlope.isNaN()) "" else StationFormat.decimal(row.gradeSlope * 100.0, 3)
                append(listOf(
                    row.code, run?.name.orEmpty(), n(row.length), n(row.invertUp), n(row.invertDown), slope,
                    n(if (row.trench > 0.0) row.trench else row.excavation), n(row.backfill), n(row.bedding),
                    n(row.spoil), n(row.fill), n(row.concrete), if (row.formworkKnown) n(row.formwork) else "",
                    yn(row.shoring), yn(row.tooDeep), (phaseOf[row.id] ?: 0).toString(),
                ).joinToString(";"))
                append("\r\n")
            }
        }
    }

    private fun writeDrainPdf(
        scene: com.appdwg.core.drainage.DrainageBuilt,
        phases: List<com.appdwg.core.drainage.DevicePhase>,
        out: java.io.OutputStream,
    ) {
        val phaseOf = phases.associate { it.deviceId to it.phase }
        fun n(v: Double) = if (v.isNaN()) "—" else StationFormat.decimal(v, 2)
        val doc = android.graphics.pdf.PdfDocument()
        val paint = android.graphics.Paint().apply { textSize = 8f; isAntiAlias = true; color = 0xFF1A1A1A.toInt() }
        val bold = android.graphics.Paint(paint).apply { isFakeBoldText = true; textSize = 9f }
        val title = android.graphics.Paint(bold).apply { textSize = 14f }
        val rows = scene.takeoff
        val pages = rows.chunked(24).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, pageIndex + 1).create())
            val canvas = page.canvas
            canvas.drawColor(0xFFF7F4EE.toInt())
            canvas.drawText(tr("Quantidades de drenagem"), 28f, 36f, title)
            canvas.drawText(tr("Página {0} de {1}", pageIndex + 1, pages.size), 680f, 36f, paint)
            var y = 58f
            val head = listOf(
                tr("Código"), tr("Eixo"), tr("L (m)"), tr("Montante"), tr("Jusante"), tr("i %"),
                tr("Vala m³"), tr("Reaterro"), tr("Berço"), tr("Concreto"), tr("Fase"),
            )
            val x = floatArrayOf(28f, 110f, 230f, 280f, 350f, 420f, 470f, 530f, 600f, 660f, 740f)
            head.forEachIndexed { i, h -> canvas.drawText(h, x[i], y, bold) }
            canvas.drawLine(28f, y + 4f, 814f, y + 4f, paint)
            y += 18f
            for (row in chunk) {
                val run = scene.runs.find { row.id in it.deviceIds }
                val cells = listOf(
                    row.code, run?.name.orEmpty(), n(row.length), n(row.invertUp), n(row.invertDown),
                    if (row.gradeSlope.isNaN()) "—" else n(row.gradeSlope * 100.0),
                    n(if (row.trench > 0.0) row.trench else row.excavation), n(row.backfill), n(row.bedding),
                    n(row.concrete), (phaseOf[row.id] ?: 0).toString(),
                )
                cells.forEachIndexed { i, cell -> canvas.drawText(cell.take(18), x[i], y, paint) }
                y += 16f
            }
            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }

    /** Points and lines for GIS files: the drawing's, the axes and [extra] (measurement, section…). */
    private fun gisContent(extra: List<ExportLine>): Pair<List<CogoPoint>, List<com.appdwg.core.Polyline>>? {
        val p = exportProject() ?: return null
        val hidden = hiddenLayers()
        val lines = extra.map { l -> com.appdwg.core.Polyline(l.layer, DoubleArray(l.points.size) { l.points[it].x }, DoubleArray(l.points.size) { l.points[it].y }, l.closed) } +
            p.alignments.map { a -> com.appdwg.core.Polyline(a.name, DoubleArray(a.vertices.size) { a.vertices[it].x }, DoubleArray(a.vertices.size) { a.vertices[it].y }, false) } +
            p.polylines.filter { it.layer !in hidden }
        val points = (if (POINTS_LAYER in hidden) emptyList() else p.points) +
            p.cgPoints.map { c -> CogoPoint(c.name, c.code ?: "", c.position.x, c.position.y, c.position.z) }
        return points to lines
    }

    /** GeoJSON in longitude/latitude (RFC 7946), from the project's UTM [zone]. */
    fun exportGeoJson(target: Uri, extra: List<ExportLine>) {
        val (points, lines) = gisContent(extra) ?: return
        val crs = crs()
        writeTo(target, "GeoJSON") { out ->
            out.writer(Charsets.UTF_8).let { w ->
                com.appdwg.core.Gis.writeGeoJson(points, lines, { x, y -> crs.toWgs84(x, y).let { (lat, lon) -> lon to lat } }, w); w.flush()
            }
        }
    }

    /** Shapefiles in a ZIP, in the project's coordinates (SIRGAS 2000 / UTM .prj when the zone is known). */
    fun exportShapefile(target: Uri, extra: List<ExportLine>) {
        val (points, lines) = gisContent(extra) ?: return
        // .prj of the project's system; none for a site grid, which has no standard definition.
        val prj = crs().takeIf { !it.local }?.prj()
        writeTo(target, "Shapefile") { out -> com.appdwg.core.Gis.writeShapefileZip(points, lines, prj, out) }
    }

    fun exportKml(target: Uri, extra: List<ExportLine>) {
        val crs = crs()
        val p = exportProject() ?: return
        val name = (state as? ProjectState.Loaded)?.fileName ?: "Gromatic"
        // Axes, drawing lines and the file's own points go too, not only the _POINTS layer.
        val lines = extra + p.alignments.map { a -> ExportLine(a.name, a.vertices, false, 1) } +
            p.polylines.map { l -> ExportLine(l.layer, List(l.xs.size) { Point2(l.xs[it], l.ys[it]) }, l.closed, 7) }
        val points = p.points + p.cgPoints.map { c -> CogoPoint(c.name, c.code ?: "", c.position.x, c.position.y, c.position.z) }
        writeTo(target, "KML") { out -> out.writer(Charsets.UTF_8).let { w -> KmlWriter.write(points, lines, crs, name, w); w.flush() } }
    }

    /** Volumes of the comparison, the matrix and the stages, as CSV (semicolon, decimal comma). */
    fun exportVolumes(target: Uri) {
        val d = difference
        val m = matrix
        val st = stageProgress
        val names = crossSelected
        fun n(v: Double) = StationFormat.decimal(v, 3)
        val text = buildString {
            if (d != null) {
                append(tr("Comparação;A;B;Corte (m3);Aterro (m3);Saldo (m3);Área corte (m2);Área aterro (m2);Malha (m)") + "\r\n")
                append("B - A;${d.baseName};${d.otherName};${n(d.cutVolume)};${n(d.fillVolume)};${n(d.netVolume)};${n(d.cutArea)};${n(d.fillArea)};${n(d.cellSize)}\r\n\r\n")
            }
            if (m.isNotEmpty()) {
                append(tr("Matriz (linha - coluna);Linha;Coluna;Corte (m3);Aterro (m3);Saldo (m3)") + "\r\n")
                for (r in names) for (c in names) if (r != c) matrixCell(r, c)?.let { x ->
                    append(";$r;$c;${n(x.cutVolume)};${n(x.fillVolume)};${n(x.netVolume)}\r\n")
                }
                append("\r\n")
            }
            if (st != null) {
                append(tr("Etapas;De;Para;Corte (m3);Aterro (m3);Movimentado (m3)") + "\r\n")
                st.periods.forEachIndexed { k, p -> append(tr("Período {0}", k + 1) + ";${p.from};${p.to};${n(p.cut)};${n(p.fill)};${n(p.cut + p.fill)}\r\n") }
                append(tr("Falta até o projeto") + ";;;${n(st.remainingCut)};${n(st.remainingFill)};${n(st.remainingCut + st.remainingFill)}\r\n")
                append(tr("Total terreno → projeto") + ";;;${n(st.totalCut)};${n(st.totalFill)};${n(st.totalCut + st.totalFill)}\r\n")
                append(tr("Concluído (%)") + ";;;;;${StationFormat.decimal(st.done * 100, 1)}\r\n")
            }
        }
        // BOM so Excel reads the accents.
        writeTo(target, tr("CSV de volumes")) { it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)) }
    }

    val hasVolumes get() = difference != null || matrix.isNotEmpty() || stageProgress != null

    /** Saves a picture of the screen as PNG or as a one-page PDF. */
    fun exportImage(target: Uri, bitmap: Bitmap, pdf: Boolean) {
        writeTo(target, if (pdf) "PDF" else "PNG") { out ->
            if (!pdf) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                // A4 landscape at 72 dpi, the picture scaled to fit.
                val doc = android.graphics.pdf.PdfDocument()
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(842, 595, 1).create())
                val scale = minOf(842f / bitmap.width, 595f / bitmap.height)
                page.canvas.translate((842 - bitmap.width * scale) / 2, (595 - bitmap.height * scale) / 2)
                page.canvas.scale(scale, scale)
                page.canvas.drawBitmap(bitmap, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                doc.writeTo(out)
                doc.close()
            }
            out.flush()
        }
    }

    // ---- Project file (.gromatic) ----

    /** User settings of the open drawing. */
    var view by mutableStateOf<ViewState?>(null)
        private set
    /** Where the project was last saved or opened from, and its content then (for "unsaved changes"). */
    var projectUri by mutableStateOf<Uri?>(null)
        private set
    /** Created time of an open .gmt, kept when the file is saved again. */
    private var gmtCreated: String? = null
    private var savedJson: String? = null
    var projectMessage by mutableStateOf<String?>(null)
        private set

    fun dismissProjectMessage() {
        projectMessage = null
    }

    fun report(message: String) {
        projectMessage = message
    }

    private fun currentDoc(): ProjectDoc? {
        val main = sourceUri ?: return null
        val v = view ?: return null
        val generated = addedFiles.any { it.uri == null && it.label.startsWith("$POINTS_LAYER ") }
        return ProjectDoc(
            main = main.toString(),
            mainName = displayName(main),
            files = addedFiles.mapNotNull { f -> f.uri?.let { f.label to it.toString() } },
            hiddenFiles = hiddenFiles.toList(),
            states = states,
            activeState = activeState ?: -1,
            pointsSurface = generated,
            drape = drapeUris.map { it.toString() },
            drapeVisible = drapeVisible,
            points = userPoints,
            view = ProjectDoc.viewToJson(v),
            compareShown = difference != null,
            cross = crossSelected,
            unloaded = unloaded.toList(),
            detached = detached.toList(),
            pathEdits = pathEdits,
            userLines = userLines,
            hiddenLines = hiddenOriginals.toList(),
            models = models.map { m -> m.uris.map { it.toString() } to m.placement },
            notes = notes,
            drainage = drainage,
            drainageBasins = drainageBasins,
            drainagePhases = drainagePhases,
            drainageNames = drainageNames,
            drainageCatalogJson = drainageUserLibrary,
            edited = addedFiles.filter { it.uri == null && it.label.endsWith(EDITED_SUFFIX) }
                .flatMap { f -> f.project.surfaces }.map { s -> synchronized(encodedSurfaces) { encodedSurfaces.getOrPut(s) { SurfaceCodec.encode(s) } } },
        )
    }

    /** Encoded edited surfaces, so checking for unsaved changes does not encode them again. */
    private val encodedSurfaces = java.util.IdentityHashMap<Surface, org.json.JSONObject>()

    /** True when something changed since the drawing was opened or the project saved. */
    /** Closes the open project and goes back to the start screen; the next one opened starts clean. */
    fun closeProject() {
        if (state !is ProjectState.Loaded) return
        state = ProjectState.Empty
        view = null
        sourceUri = null
        projectUri = null
        gmtCreated = null
        savedJson = null
        base = null
        baseTerrain = emptyList()
        rasters = emptyMap()
        tiledRasters.values.forEach { it.close() }
        tiledRasters = emptyMap()
        drape = null
        drapeUris = emptyList()
        encodedSurfaces.clear()
        drainage = emptyList()
        drainageBasins = emptyList()
        drainagePhases = emptyList()
        drainageNames = emptyMap()
        drainageUndo.clear()
        canUndoDrainage = false
        clearDrainageLibrary()
    }

    fun isDirty(): Boolean {
        val doc = currentDoc() ?: return false
        return doc.toJson().toString() != savedJson
    }

    /** Writes the project file to [target]. A `.gmt` name stores a single-file container; `.gromatic` stays JSON. */
    fun saveProject(target: Uri, then: () -> Unit = {}) {
        val doc = currentDoc() ?: return
        val gmt = GmtIo.isGmt(displayName(target), null, target.lastPathSegment)
        viewModelScope.launch {
            projectMessage = try {
                val json = doc.toJson()
                val cloud = CloudDocs.isDrive(target.toString())
                val skipped = if (gmt) saveGmt(target, doc, json, cloud) else {
                    val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
                    tracked(if (cloud) tr("Enviando ao Google Drive") else tr("Salvando projeto")) { t ->
                        withContext(Dispatchers.IO) { writeWithProgress(target, bytes, t, cloud) }
                    }
                    0
                }
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                savedJson = json.toString()
                projectUri = target
                CloudDocs.noteUsed(getApplication(), target)
                remember(target)
                then()
                val where = if (cloud) tr("Projeto salvo no Google Drive ({0}); o app Drive termina o envio em segundo plano.", displayName(target))
                    else tr("Projeto salvo em {0}.", displayName(target))
                if (skipped > 0) where + " " + tr("{0} arquivo(s) não entrou(aram) no .gmt (não foi possível lê-los).", skipped) else where
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gmt) dropEmpty(target)
                tr("Não foi possível salvar o projeto: {0}", gmtMessage(e))
            }
        }
    }

    /** Streams the open project into a .gmt. Returns how many referenced files could not be copied. */
    private suspend fun saveGmt(target: Uri, doc: ProjectDoc, json: org.json.JSONObject, cloud: Boolean): Int {
        val pieces = gmtPieces(doc)
        return tracked(if (cloud) tr("Enviando ao Google Drive") else tr("Salvando projeto")) { t ->
            withContext(Dispatchers.IO) {
                val raw = getApplication<Application>().contentResolver.openOutputStream(target, "wt") ?: error(tr("destino indisponível"))
                raw.use { out ->
                    val result = GmtIo.write(out, json.toString(2), pieces, BuildConfig.VERSION_NAME, gmtCreated) { bytes ->
                        t.step((if (cloud) tr("Enviando · ") else tr("Gravando · ")) + StationFormat.decimal(bytes / 1048576.0, 1) + " MB")
                    }
                    gmtCreated = result.created
                    result.skipped
                }
            }
        }
    }

    /** Files the project points at, plus note photos kept in the app. Unreadable ones are omitted. */
    private fun gmtPieces(doc: ProjectDoc): List<GmtPiece> {
        val pieces = ArrayList<GmtPiece>()
        val seen = HashSet<String>()
        fun add(role: String, source: String, name: String, size: Long, open: () -> java.io.InputStream?) {
            if (source.isBlank() || !seen.add(role + "\u0000" + source)) return
            val probe = open() ?: return
            probe.close()
            pieces += GmtPiece(role, source, name, size) { open() ?: error(tr("arquivo indisponível")) }
        }
        fun addUri(role: String, uri: String) {
            if (!uri.contains("://")) return
            val u = uri.toUri()
            add(role, uri, displayName(u), fileSize(u).coerceAtLeast(-1)) { input(u) }
        }
        addUri("main", doc.main)
        doc.files.forEach { (_, u) -> addUri("file", u) }
        doc.drape.forEach { addUri("drape", it) }
        doc.models.forEach { (uris, _) -> uris.forEach { addUri("model", it) } }
        doc.states.forEach { s ->
            if (s.file.isNotEmpty()) addUri("state", s.file)
            s.image.forEach { addUri("image", it) }
        }
        doc.view.optJSONObject("sheets")?.optString("logo")?.takeIf { it.isNotEmpty() }?.let { addUri("logo", it) }
        doc.notes.forEach { n ->
            val photo = n.photo ?: return@forEach
            val f = java.io.File(notesDir, photo)
            if (f.isFile) add("note", photo, f.name, f.length()) { f.inputStream() }
        }
        return pieces
    }

    private suspend fun openGmt(uri: Uri) {
        val name = displayName(uri)
        val task = LoadTask(tr("Abrindo {0}", name)) { openJob?.cancel() }
        state = ProjectState.Loading(name, task)
        try {
            val opened = withContext(Dispatchers.IO) {
                val cache = java.io.File(getApplication<Application>().cacheDir, "gmt-src").apply { mkdirs() }
                val zip = GmtIo.materialize(getApplication(), uri, java.io.File(cache, Integer.toHexString(uri.toString().hashCode()) + ".gmt")) {
                    task.step(tr("Lendo arquivo"), it.toFloat())
                }
                val dir = java.io.File(getApplication<Application>().filesDir, "gmt/" + Integer.toHexString(uri.toString().hashCode()))
                GmtIo.read(zip, dir, notesDir)
            }
            gmtCreated = opened.manifest.created
            val doc = ProjectDoc.fromJson(org.json.JSONObject(opened.projectJson))
            openProjectDoc(uri, doc, doc.main)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state = ProjectState.Failed(name, tr("Não foi possível ler o projeto: {0}", gmtMessage(e)))
        }
    }

    private fun gmtMessage(e: Exception): String = when (e) {
        is GmtException -> when (e.code) {
            "newer" -> tr("Este projeto foi salvo por uma versão mais nova do Gromatic ({0}). Atualize o app para abri-lo.", e.detail)
            "corrupt", "truncated" -> tr("O arquivo .gmt está corrompido ou incompleto.")
            "unknown" -> tr("Este arquivo não é um projeto .gmt do Gromatic.")
            "path" -> tr("O arquivo .gmt contém um caminho inválido e foi recusado.")
            "too-large" -> tr("O arquivo .gmt é grande demais para abrir neste aparelho.")
            else -> tr("Não foi possível ler o projeto .gmt ({0}).", e.code)
        }
        else -> errorText(e)
    }

    // ---- Recent files ----

    private val recentPrefs = app.getSharedPreferences("recent", Context.MODE_PRIVATE)

    /** Files opened lately (name, URI), newest first. */
    var recent by mutableStateOf(loadRecent())
        private set

    private fun loadRecent(): List<Pair<String, String>> = runCatching {
        val a = org.json.JSONArray(recentPrefs.getString("list", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("uri") } }
    }.getOrDefault(emptyList())

    private fun remember(uri: Uri) {
        val list = (listOf(displayName(uri) to uri.toString()) + recent.filter { it.second != uri.toString() }).take(12)
        recent = list
        recentPrefs.edit { putString("list", org.json.JSONArray(list.map { (n, u) -> JSONObject().put("name", n).put("uri", u) }).toString()) }
    }

    fun forgetRecent(uri: String) {
        recent = recent.filter { it.second != uri }
        recentPrefs.edit { putString("list", org.json.JSONArray(recent.map { (n, u) -> JSONObject().put("name", n).put("uri", u) }).toString()) }
    }

    /** Opens a drawing or, when [uri] is a project file, the drawing and everything saved with it. */
    fun open(uri: Uri, claimedOctetStream: Boolean = false) {
        // Read and write when the provider allows (a project on Drive is saved back in place), else read.
        val cr = getApplication<Application>().contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        CloudDocs.noteUsed(getApplication(), uri)
        projectMessage = null
        openJob?.cancel()
        val before = state
        openJob = viewModelScope.launch {
            val projectOpen = before is ProjectState.Loaded
            fun tooBig(bytes: Long) {
                val text = getApplication<Application>().getString(R.string.pro_too_big,
                    StationFormat.decimal(bytes / 1048576.0, 1), (FreeLimits.MAX_FILE_BYTES / 1048576).toString())
                if (projectOpen) projectMessage = text else state = ProjectState.Failed(displayName(uri), text)
                proRequested = true
            }
            // The free version opens small files only. A cloud provider reports the size before the copy.
            if (PickedFile.needsStreamedCopy(uri.scheme, uri.authority)) {
                val known = reportedSize(uri)
                if (!ProAccess.allows(ProFeature.OPEN_LARGE_FILE, pro.isPro) && known != null && known > FreeLimits.MAX_FILE_BYTES) {
                    tooBig(known)
                    return@launch
                }
                try {
                    if (projectOpen) ensureCached(uri) { openJob?.cancel() }
                    else {
                        val name = displayName(uri)
                        val task = LoadTask(tr("Abrindo {0}", name)) { openJob?.cancel() }
                        state = ProjectState.Loading(name, task)
                        download(uri, task)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!projectOpen) state = before
                    projectMessage = tr("Download cancelado.")
                    return@launch
                } catch (e: Exception) {
                    val name = displayName(uri)
                    if (projectOpen) projectMessage = tr("Não foi possível baixar o arquivo: {0}", errorText(e))
                    else state = ProjectState.Failed(name, if (CloudDocs.isDrive(uri.toString()))
                        tr("Não foi possível baixar do Google Drive: {0}", errorText(e))
                    else tr("Não foi possível baixar o arquivo: {0}", errorText(e)))
                    return@launch
                }
            }
            val size = withContext(Dispatchers.IO) { fileSize(uri) }
            if (!ProAccess.allows(ProFeature.OPEN_LARGE_FILE, pro.isPro) && size > FreeLimits.MAX_FILE_BYTES) {
                tooBig(size)
                return@launch
            }
            // A content octet-stream from Open with is a .gmt only when the zip holds manifest.json.
            // Anything else keeps the project that is already open.
            if (claimedOctetStream) {
                val gmt = withContext(Dispatchers.IO) { gmtZip(uri) }
                if (!gmt) {
                    val message = tr("Este arquivo não é um projeto .gmt do Gromatic.")
                    if (projectOpen) projectMessage = message else state = ProjectState.Failed(displayName(uri), message)
                    return@launch
                }
                openGmt(uri)
                if (state is ProjectState.Loaded) remember(uri)
                return@launch
            }
            val image = withContext(Dispatchers.IO) { PickedFile.isImage(displayName(uri), mimeOf(uri), headBytes(uri)) }
            if (PickedFile.route(projectOpen, image) == PickRoute.ATTACH_IMAGE) {
                attachImage(uri)
                return@launch
            }
            if (GmtIo.isGmt(displayName(uri), getApplication<Application>().contentResolver.getType(uri), uri.lastPathSegment)) {
                openGmt(uri)
                if (state is ProjectState.Loaded) remember(uri)
                return@launch
            }
            gmtCreated = null
            val head = withContext(Dispatchers.IO) {
                runCatching {
                    input(uri)?.use { s ->
                        val b = ByteArray(256); val n = s.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
                    }
                }.getOrNull().orEmpty()
            }
            if (ProjectDoc.looksLikeProject(head)) { openProject(uri); if (state is ProjectState.Loaded) remember(uri) } else if (openNow(uri)) {
                remember(uri)
                projectUri = null
                savedJson = currentDoc()?.toJson()?.toString()
            }
        }
    }

    /** The cached copy, a file URI, or the provider stream: a zip with a root manifest.json. */
    private fun gmtZip(uri: Uri): Boolean {
        cached(uri)?.let { return GmtArchive.isProjectZip(it) }
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return GmtArchive.isProjectZip(java.io.File(path))
        }
        return input(uri)?.use { GmtArchive.isProjectZip(it) } == true
    }

    private suspend fun openProject(uri: Uri) {
        val name = displayName(uri)
        val doc = try {
            withContext(Dispatchers.IO) {
                val text = input(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error(tr("arquivo indisponível"))
                ProjectDoc.fromJson(JSONObject(text))
            }
        } catch (e: Exception) {
            state = ProjectState.Failed(name, tr("Não foi possível ler o projeto: {0}", errorText(e)))
            return
        }
        openProjectDoc(uri, doc, doc.main)
    }

    /** Project whose drawing could not be opened, waiting for the user to point to it ([relocate]). */
    var relocating by mutableStateOf<Pair<Uri, ProjectDoc>?>(null)
        private set

    /** Opens the project waiting in [relocating] with [drawing] in place of the drawing it saved. */
    fun relocate(drawing: Uri) {
        val (projectFile, doc) = relocating ?: return
        relocating = null
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(drawing, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch {
            openProjectDoc(projectFile, doc, drawing.toString())
            if (state is ProjectState.Loaded) { remember(projectFile); savedJson = null }
        }
    }

    private suspend fun openProjectDoc(uri: Uri, doc: ProjectDoc, main: String) {
        val name = displayName(uri)
        relocating = null
        if (!openNow(main.toUri(), allowEmpty = true)) {
            val s = state
            if (s is ProjectState.Failed) {
                relocating = uri to doc
                val drawing = doc.mainName?.let { "o desenho \"$it\"" } ?: tr("um desenho")
                state = s.copy(message = tr("O projeto {0} usa {1}, mas o app não consegue abri-lo ", name, drawing) +
                    tr("(o arquivo mudou de lugar ou o app foi reinstalado).\n\nToque em \"Localizar desenho\" e escolha o arquivo; depois salve o projeto."))
            }
            return
        }
        val problems = ArrayList<String>()
        unloaded = doc.unloaded.toSet()
        detached = doc.detached.toSet()
        pathEdits = doc.pathEdits
        hiddenFiles = doc.hiddenFiles.toSet()
        states = doc.states
        activeState = doc.activeState.takeIf { it in doc.states.indices }
        hideMain = activeState?.let { doc.states[it].file.isNotEmpty() } ?: false
        timelineShown = states.size > 1
        for ((label, u) in doc.files) {
            val before = addedFiles.size
            addFileNow(u.toUri(), label)
            if (addedFiles.size == before) problems += label
        }
        userPoints = doc.points
        for (e in doc.edited) {
            try {
                val s = withContext(Dispatchers.Default) { SurfaceCodec.decode(e) }
                synchronized(encodedSurfaces) { encodedSurfaces[s] = e }
                addedFiles = addedFiles.filter { it.label != s.name } + AddedFile(s.name, LandXmlProject(null, null, listOf(s), emptyList(), emptyList(), emptyList()), null)
            } catch (ex: Exception) {
                problems += e.optString("name")
            }
        }
        for ((uris, placement) in doc.models) {
            val list = uris.map(Uri::parse)
            val named = list.map { it to displayName(it) }
            val main = named.firstOrNull { it.second.substringAfterLast('.').lowercase() in MODEL_EXTENSIONS } ?: continue
            try {
                val m = tracked(tr("Lendo modelo {0}", main.second)) { t -> withContext(Dispatchers.Default) { loadModel(main.first, main.second, named, t) } } ?: continue
                models = models + ModelEntry(main.second, list, m, placement)
            } catch (e: Exception) {
                problems += main.second
            }
        }
        notes = doc.notes
        userLines = doc.userLines
        hiddenOriginals = doc.hiddenLines.toSet()
        drainage = doc.drainage
        drainageBasins = doc.drainageBasins
        drainagePhases = doc.drainagePhases
        drainageNames = doc.drainageNames
        drainageUndo.clear()
        canUndoDrainage = false
        applySavedDrainageLibrary(doc.drainageCatalogJson)
        if (doc.pointsSurface) surfaceFromPointsNow()
        refreshJob?.cancel()
        refreshNow()
        view?.let { ProjectDoc.applyView(doc.view, it) }
        crossSelected = doc.cross
        if (doc.drape.isNotEmpty()) {
            val project = (state as? ProjectState.Loaded)?.project
            val uris = doc.drape.map(Uri::parse)
            val named = uris.map { it to displayName(it) }
            val world = named.firstOrNull { it.second.substringAfterLast('.', "").lowercase() in WORLD_FILE_EXTENSIONS }
            val picture = named.firstOrNull { it !== world }
            if (project != null && picture != null) drapeNow(project, picture.first, picture.second, world, uris)
            drapeVisible = doc.drapeVisible
        }
        if (view?.platformBuilt == true) designPlatformNow()
        if (view?.roadBuilt == true) designRoadNow()
        val v = view
        if (doc.compareShown && v != null && v.compareOther != PLATFORM_LABEL) {
            val project = (state as? ProjectState.Loaded)?.project
            val a = project?.surfaces?.firstOrNull { it.name == v.compareBase }
            val b = project?.surfaces?.firstOrNull { it.name == v.compareOther }
            if (a != null && b != null) compareNow(a, b)
        }
        projectUri = uri
        savedJson = currentDoc()?.toJson()?.toString()
        projectMessage = if (problems.isEmpty()) tr("Projeto {0} aberto.", name) else
            tr("Projeto {0} aberto, mas não foi possível abrir: {1}.", name, problems.joinToString(", "))
    }

    private suspend fun openNow(uri: Uri, allowEmpty: Boolean = false): Boolean {
        val name = displayName(uri)
        val task = LoadTask(tr("Abrindo {0}", name))
        state = ProjectState.Loading(name, task)
        run {
            state = try {
                val (project, terrain) = withContext(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    // A large file opened before comes back from the device's cache, already processed.
                    val key = openCache.keyOf(uri, fileSize(uri)) { input(uri) }
                    key?.let { k -> openCache.load(k) }?.let { cached ->
                        android.util.Log.i("Gromatic", "abrir $name: cache ${(System.nanoTime() - t0) / 1_000_000} ms")
                        return@withContext cached
                    }
                    val p = read(uri, task)
                    val t1 = System.nanoTime()
                    // Builds each surface's lookup index now, so the first tap on the plan is instant.
                    if (p.surfaces.isNotEmpty()) task.step(tr("Preparando consulta de cotas"))
                    p.surfaces.forEach { s -> s.bounds?.let { s.elevationAt(it.center) } }
                    val t2 = System.nanoTime()
                    if (p.surfaces.isNotEmpty()) task.step(tr("Gerando imagem do terreno"))
                    val images = p.surfaces.mapNotNull { TerrainImage.of(it) }
                    android.util.Log.i("Gromatic", "abrir $name: leitura ${(t1 - t0) / 1_000_000} ms, índice ${(t2 - t1) / 1_000_000} ms, " +
                        "imagem ${(System.nanoTime() - t2) / 1_000_000} ms")
                    // Saved in the background: the drawing shows without waiting for it.
                    key?.let { k -> viewModelScope.launch(Dispatchers.IO) { openCache.save(k, p, images) } }
                    p to images
                }
                if (!allowEmpty && project.surfaces.isEmpty() && project.alignments.isEmpty() && project.cgPoints.isEmpty() &&
                    project.polylines.isEmpty()
                ) {
                    ProjectState.Failed(name, tr("Nenhuma superfície, alinhamento ou ponto encontrado. O arquivo é um LandXML, DXF ou cache de pontos do Civil 3D (.aecc.pnt)?"))
                } else {
                    rasters = emptyMap()
                    tiledRasters.values.forEach { it.close() }
                    tiledRasters = emptyMap()
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
                    addedFiles = emptyList()
                    hiddenFiles = emptySet()
                    states = emptyList(); activeState = null; hideMain = false; statePeriods = emptyList(); stateTotal = null; timelineShown = false
                    userPoints = emptyList()
                    pointsMessage = null
                    compareMessage = null
                    clearDifference()
                    crossSelected = emptyList()
                    matrix = emptyMap()
                    stageProgress = null
                    drapeUris = emptyList()
                    models = emptyList()
                    modelMessage = null
                    notes = emptyList()
                    userLines = emptyList()
                    hiddenOriginals = emptySet()
                    drainage = emptyList()
                    drainageBasins = emptyList()
                    drainagePhases = emptyList()
                    drainageNames = emptyMap()
                    drainageUndo.clear()
                    canUndoDrainage = false
                    clearDrainageLibrary()
                    keptOriginals = project.polylines.indices.toList()
                    undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
                    platform = null
                    asBuilt = null
                    volume = null
                    analysisMode = 0; slopeImage = null; flowGrid = null; flowChannels = emptyList(); flowArrows = emptyList(); slopeClasses = emptyList()
                    basin = null; outlets = emptyList(); basinFlow = null; basinMessage = null
                    road = null
                    roadMessage = null
                    platformVolumes = null
                    platformMessage = null
                    routeSuggestions = emptyList(); gradeSuggestions = emptyList()
                    platformOptions = emptyList(); platformOptionsOutline = emptyList()
                    view = ViewState(project)
                    restoreRasters(project)
                    restoreXrefs(project)
                    ProjectState.Loaded(name, project, terrain)
                }
            } catch (e: OutOfMemoryError) {
                ProjectState.Failed(name, tr("Arquivo grande demais para a memória deste aparelho."))
            } catch (e: Exception) {
                ProjectState.Failed(name, tr("Não foi possível ler o arquivo: {0}", errorText(e)))
            }
        }
        return state is ProjectState.Loaded
    }

    /**
     * Drawing from GIS features. Longitude/latitude (by the .prj, or by the range of the values) go
     * to UTM: the open project's zone, else the zone of the data. Projected values are kept.
     */
    private fun gisProject(layer: String, epsg: String?, geographic: Boolean?, read: (com.appdwg.core.GeoToPlan) -> List<com.appdwg.core.GisFeature>): LandXmlProject {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val raw = read { x, y -> xs += x; ys += y; Point2(x, y) }
        val geo = geographic ?: com.appdwg.core.Gis.looksGeographic(xs, ys)
        val source = if (geo) com.appdwg.core.CrsCatalog.byEpsg("4326") else com.appdwg.core.CrsCatalog.byEpsg(epsg) ?: com.appdwg.core.CrsCatalog.byId(epsg)
        // Into the open project's system; a file opened on its own keeps its system (or the UTM zone of its longitudes).
        val target = if (state is ProjectState.Loaded) crs()
            else if (geo) com.appdwg.core.CrsCatalog.utmFor(xs.average(), ys.average() < 0) else source
        if (source == null || target == null || source.id == target.id || target.local) return com.appdwg.core.Gis.toProject(raw, layer, epsg)
        val features = read { x, y ->
            val (lat, lon) = if (source.isGeographic) y to x else source.toWgs84(x, y)
            target.fromWgs84(lat, lon)
        }
        return com.appdwg.core.Gis.toProject(features, layer, target.epsg)
    }

    private fun read(uri: Uri, task: LoadTask? = null): LandXmlProject {
        val resolver = getApplication<Application>().contentResolver
        val size = fileSize(uri)
        val raw = input(uri) ?: error(tr("arquivo indisponível"))
        val input = if (task == null) raw else CountingInputStream(raw, size) { task.step(tr("Lendo arquivo"), it) }
        return input.buffered().use { stream ->
            // Sniffs the content rather than trusting the file name, which e-mail apps often drop.
            stream.mark(HEAD_SIZE)
            val head = ByteArray(HEAD_SIZE)
            val n = stream.readNBytesCompat(head)
            stream.reset()
            val text = String(head, 0, n, Charsets.ISO_8859_1)
            val fileName = displayName(uri)
            val layer = fileName.substringBeforeLast('.')
            when {
                // Shapefile: a ZIP holding .shp (+ .dbf, .prj), or a bare .shp (geometry only).
                text.startsWith("PK\u0003\u0004") && fileName.endsWith(".zip", true) -> {
                    val parts = com.appdwg.core.Gis.unzipShapefiles(stream.readBytes()).ifEmpty { error(tr("o ZIP não tem arquivo .shp")) }
                    // Each shapefile on its own layer (its name), all in one drawing.
                    val drawings = parts.map { sp ->
                        gisProject(sp.name, com.appdwg.core.Gis.prjEpsg(sp.prj), if (sp.prj == null) null else com.appdwg.core.Gis.prjIsGeographic(sp.prj)) { toPlan ->
                            com.appdwg.core.Gis.readShapefile(sp.shp, sp.dbf, toPlan)
                        }
                    }
                    return@use drawings.first().copy(cgPoints = drawings.flatMap { it.cgPoints }, polylines = drawings.flatMap { it.polylines },
                        coordinateSystem = drawings.firstNotNullOfOrNull { it.coordinateSystem })
                }
                n >= 4 && head[0] == 0.toByte() && head[1] == 0.toByte() && head[2] == 0x27.toByte() && head[3] == 0x0A.toByte() -> {
                    val shp = stream.readBytes()
                    return@use gisProject(layer, null, null) { toPlan -> com.appdwg.core.Gis.readShapefile(shp, null, toPlan) }
                }
                text.trimStart().startsWith("{") && (fileName.endsWith("json", true) || "\"type\"" in text) -> {
                    val json = stream.readBytes().toString(Charsets.UTF_8)
                    return@use gisProject(layer, null, null) { toPlan -> com.appdwg.core.Gis.readGeoJson(json, toPlan) }
                }
                text.startsWith("AC10") -> error(tr("arquivo DWG. No AutoCAD/Civil 3D, salve como DXF (Salvar como > DXF) ou exporte para LandXML"))
                text.startsWith("AutoCAD Binary DXF") -> error(tr("DXF binário. Salve o DXF no formato ASCII"))
                // Pictures are laid over a drawing, not opened on their own.
                text.startsWith("II*") || text.startsWith("II+") || text.startsWith("MM\u0000*") || text.startsWith("MM\u0000+") || text.startsWith("\u0089PNG") || (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) ->
                    error(tr("é uma imagem. Abra primeiro um LandXML ou DXF e depois adicione a imagem pela ferramenta Imagem (ou Referências, no DXF)."))
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
        val fd = openLocal(uri) ?: error(tr("arquivo indisponível"))
        return fd.use {
            FileInputStream(it.fileDescriptor).channel.use { channel ->
                val input = object : RandomInput {
                    override val size = channel.size()
                    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
                        val bb = ByteBuffer.wrap(buffer, offset, length)
                        var pos = position
                        while (bb.hasRemaining()) {
                            val n = channel.read(bb, pos)
                            if (n < 0) error(tr("fim inesperado do arquivo"))
                            pos += n
                        }
                    }
                }
                val reader = TiffReader(
                    input,
                    jpeg = { CoilPictures.raster(getApplication(), it, 1) },
                    jpegReduced = { bytes, reduce -> CoilPictures.raster(getApplication(), bytes, reduce) },
                )
                val layout = reader.blocks()
                val raster = reader.read(RasterBudget.maxSide(layout.width, layout.height)) { task.step(tr("Decodificando TIFF"), it) }
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

    private fun fileSize(uri: Uri): Long = cached(uri)?.length() ?:
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
