package com.appdwg.app

import com.appdwg.core.tr

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
import com.appdwg.core.Placement
import com.appdwg.core.Surface
import com.appdwg.core.CogoPoint
import com.appdwg.core.POINTS_LAYER
import com.appdwg.core.PointFile
import com.appdwg.core.DxfWriter
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

/** File laid over the open project; [uri] is null for a surface the app generated. */
data class AddedFile(val label: String, val project: LandXmlProject, val uri: Uri?)

/** Suffix of a surface made by editing another. */
const val EDITED_SUFFIX = " (editada)"

/** Placement that leaves a drawing where it is. */
private val IDENTITY = Placement(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, "0")

/** Picture laid over the surfaces ("imagem sobre a superfície"), placed at [bounds] in plan. */
/** Picture over the surfaces; a very large one also comes in [tiles], drawn on top of [image] (its overview). */
class Drape(val name: String, val bounds: Bounds, val image: ImageBitmap, val placement: String, val tiles: TiledPicture? = null)

/** Decoded picture, with its plan extent when the file carries one and its size in the file. */
private class Picture(val image: ImageBitmap, val extent: Bounds?, val sourceWidth: Int, val sourceHeight: Int)

/** Extensions of world files, which place a JPG, PNG or TIFF picture in plan. */
private val WORLD_FILE_EXTENSIONS = setOf("jgw", "jpgw", "jpegw", "pgw", "pngw", "tfw", "tifw", "tiffw", "wld", "bpw", "gfw")

/** Longest side of a loaded raster image; larger pictures are downsampled to save memory. */
private const val MAX_RASTER = 4096
/** TIFFs with a side longer than this are shown in tiles from a pyramid instead of whole. */
private const val TILED_FROM = 8192

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
    private val cloudCopies = java.util.concurrent.ConcurrentHashMap<Uri, java.io.File>()

    /** The file's content: its downloaded copy when there is one. */
    private fun input(uri: Uri): java.io.InputStream? =
        cloudCopies[uri]?.takeIf { it.exists() }?.inputStream() ?: getApplication<Application>().contentResolver.openInputStream(uri)

    /** Size the provider reports without downloading the file, or null. */
    private fun reportedSize(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /**
     * Downloads a Google Drive file to the app's cache, showing the bytes received on [task].
     * Throws CancellationException when the user cancels.
     */
    private suspend fun download(uri: Uri, task: LoadTask) = withContext(Dispatchers.IO) {
        val total = reportedSize(uri)?.takeIf { it > 0 }
        val dir = java.io.File(getApplication<Application>().cacheDir, "cloud").apply { mkdirs() }
        val file = java.io.File(dir, Integer.toHexString(uri.toString().hashCode()))
        task.step(tr("Aguardando o Google Drive…"))
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
                            task.step(tr("Baixando do Google Drive · ") + mb(done) + (total?.let { " de " + mb(it) } ?: "") + " MB",
                                total?.let { (done.toFloat() / it).coerceAtMost(1f) })
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        cloudCopies[uri] = file
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
    fun loadDrape(uris: List<Uri>) {
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val named = uris.map { it to displayName(it) }
        val world = named.firstOrNull { it.second.substringAfterLast('.', "").lowercase() in WORLD_FILE_EXTENSIONS }
        val picture = named.firstOrNull { it !== world }
        drapeMessage = null
        if (picture == null) {
            drapeMessage = tr("Escolha também a imagem (TIFF, JPG ou PNG), não só o arquivo de georreferência.")
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
                // Very large TIFFs are shown in tiles, never decoded whole.
                tiledDrape(project, uri, name, world, uris)?.let { return }
                val t0 = System.nanoTime()
                val pic = tracked(tr("Imagem {0}", name)) { t -> withContext(Dispatchers.IO) { decodePicture(uri, t) } }
                    ?: error(tr("formato de imagem não reconhecido"))
                android.util.Log.i("Gromatic", "imagem $name: ${(System.nanoTime() - t0) / 1_000_000} ms, ${pic.sourceWidth}x${pic.sourceHeight}")
                val surfaces = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                val area = surfaces ?: project.bounds ?: error(tr("projeto sem área"))
                val worldText = world?.let { (w, _) ->
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(w)?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
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
            } catch (e: OutOfMemoryError) {
                drapeMessage = tr("Imagem grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                drapeMessage = tr("Não foi possível usar a imagem: {0}", e.message ?: e.javaClass.simpleName)
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
        run {
            try {
                val drawing = tracked(tr("Adicionando {0}", name)) { t ->
                    if (CloudDocs.isDrive(uri.toString())) download(uri, t)
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
                compareMessage = tr("Não foi possível ler {0}: {1}", name, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun removeFile(label: String) {
        if (addedFiles.none { it.label == label }) return
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
                pointsMessage = tr("Não foi possível ler {0}: {1}", name, e.message ?: e.javaClass.simpleName)
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
                tr("Não foi possível exportar: {0}", e.message ?: e.javaClass.simpleName)
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
                val surface = withContext(Dispatchers.Default) {
                    // Named like its entry, so it shows as just the label.
                    AeccPointCache.triangulate(
                        label,
                        DoubleArray(pts.size) { pts[it].x }, DoubleArray(pts.size) { pts[it].y }, DoubleArray(pts.size) { pts[it].z },
                    )
                }
                addedFiles = addedFiles.filter { it.label != label } +
                    AddedFile(label, LandXmlProject(null, null, listOf(surface), emptyList(), emptyList(), emptyList()), null)
                refreshJob?.cancel()
                refreshNow()
                pointsMessage = tr("Superfície \"{0}\" gerada com {1} pontos ({2} triângulos).", label, pts.size, surface.triangleCount)
            } catch (e: Exception) {
                pointsMessage = tr("Não foi possível gerar a superfície: {0}", e.message ?: e.javaClass.simpleName)
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
                prefs.edit().putString("xref:$path", uri.toString()).apply()
                refreshProject()
            } catch (e: OutOfMemoryError) {
                referenceMessage = tr("Desenho grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                referenceMessage = tr("Não foi possível ler {0}: {1}", displayName(uri), e.message ?: e.javaClass.simpleName)
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
                tr("Não foi possível salvar: {0}", e.message ?: e.javaClass.simpleName)
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
                p to baseTerrain + images
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
                if ((state as? ProjectState.Loaded)?.project?.images?.firstOrNull { it.file == file }?.let { tiledRaster(it, uri) } == true) { prefs.edit().putString(file, uri.toString()).apply(); return@launch }
                val bitmap = tracked(tr("Imagem {0}", displayName(uri))) { t -> withContext(Dispatchers.IO) { decodeRaster(uri, t) } }
                if (bitmap != null) {
                    tiledRasters[file]?.close()
                    tiledRasters = tiledRasters - file
                    rasters = rasters + (file to bitmap)
                    prefs.edit().putString(file, uri.toString()).apply()
                } else {
                    rasterError = tr("Formato de imagem não reconhecido.")
                }
            } catch (e: OutOfMemoryError) {
                rasterError = tr("Imagem grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                rasterError = tr("Não foi possível ler a imagem: {0}", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Reloads pictures chosen earlier for the images of [project]. */
    private fun restoreRasters(project: LandXmlProject) {
        for (image in project.images) {
            val uri = prefs.getString(image.file, null)?.let(Uri::parse) ?: continue
            viewModelScope.launch {
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
            val fd = cloudCopies[uri]?.takeIf { it.exists() }?.let { android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: app.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val tp = runCatching {
                TiledPicture.open(name, fd, java.io.File(app.cacheDir, "pyramid"), key, Bounds(0.0, 0.0, img.widthPx.toDouble(), img.heightPx.toDouble()))
            }.getOrNull()
            when {
                tp == null -> { fd.close(); null }
                maxOf(tp.width, tp.height) <= TILED_FROM -> { tp.close(); null }
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
                error(tr("ECW é um formato fechado (Hexagon) sem leitor livre para Android. Converta para TIFF ou JPG no QGIS (Exportar > Salvar como)"))
            displayName(uri).endsWith(".jp2", ignoreCase = true) ->
                error(tr("JPEG 2000 não é suportado. Converta para TIFF ou JPG"))
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_RASTER) sample *= 2
        task.step(tr("Decodificando imagem"))
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return Picture(bitmap.asImageBitmap(), null, bounds.outWidth, bounds.outHeight)
    }

    /**
     * Lays a TIFF larger than [TILED_FROM] pixels over the surfaces as a [TiledPicture]; null when the
     * picture is not such a TIFF. Its pyramid is built in the background the first time.
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
            val fd = cloudCopies[uri]?.takeIf { it.exists() }?.let { android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: app.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val probe = runCatching { TiledPicture.open(name, fd, java.io.File(app.cacheDir, "pyramid"), key, null) }.getOrNull()
            if (probe == null || maxOf(probe.width, probe.height) <= TILED_FROM) { probe?.close() ?: fd.close(); return@withContext null }
            val worldText = world?.let { (w, _) -> app.contentResolver.openInputStream(w)?.use { it.readBytes().toString(Charsets.ISO_8859_1) } }
            val placed = worldText?.let { WorldFile.extent(it, probe.width, probe.height) }
            val tiles = if (placed != null) { probe.close(); TiledPicture.open(name, app.contentResolver.openFileDescriptor(uri, "r")!!, java.io.File(app.cacheDir, "pyramid"), key, placed) } else probe
            Triple(tiles, if (placed != null) tr("posicionada pelo arquivo {0}", world?.second) else tr("posicionada pelo GeoTIFF"), tiles?.ready == true)
        } ?: return null
        val (tiles, placement, ready) = opened
        tiles ?: return null
        val area = project.surfaces.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: project.bounds
        drape?.tiles?.close()
        val overview = if (ready) withContext(Dispatchers.IO) { tiles.overview() } else null
        drape = Drape(name, tiles.bounds, (overview ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImageBitmap(), placement, tiles)
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
    fun newProject(lat: Double, lon: Double) {
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
            view?.let { v -> v.crs = crs; v.area = Bounds(c.x - 1000, c.y - 1000, c.x + 1000, c.y + 1000) }
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
                                notes += tr("OpenStreetMap não baixado: {0}", e.message ?: e.javaClass.simpleName); return@withContext
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
                areaMessage = tr("Sem conexão com o servidor: {0}. Confira a internet e tente de novo.", e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                areaMessage = tr("Memória insuficiente: use uma área menor ou uma tolerância maior.")
            } catch (e: Exception) {
                areaMessage = tr("Não foi possível baixar: {0}", e.message ?: e.javaClass.simpleName)
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
                scenesMessage = tr("Não foi possível buscar: {0}", e.message ?: e.javaClass.simpleName); emptyList()
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
        val reader = com.appdwg.core.TiffReader(input)
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
        val c = (java.net.URL(com.appdwg.core.Sentinel2.SEARCH).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException(tr("resposta {0} do servidor", c.responseCode))
            return com.appdwg.core.Sentinel2.scenes(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, south, west, north, east)
        } finally { c.disconnect() }
    }

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
        val reader = com.appdwg.core.TiffReader(input)
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
            val c = (java.net.URL(com.appdwg.core.Overpass.ENDPOINT).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 120_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                c.outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                when (c.responseCode) {
                    200 -> return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    429, 502, 503, 504 -> if (attempt < 3) { Thread.sleep(wait); wait *= 2 } else throw java.io.IOException(tr("servidor do OpenStreetMap ocupado; tente mais tarde"))
                    else -> throw java.io.IOException(tr("resposta {0} do servidor", c.responseCode))
                }
            } finally { c.disconnect() }
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
    val geo = Geodesy(app).also { g -> g.gridUri?.let { u -> viewModelScope.launch { readGeoidGrid(Uri.parse(u), quiet = true) } } }

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
            if (!quiet) geo.gridMessage = app.getString(R.string.calib_grid_error, e.message ?: e.javaClass.simpleName); false
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
        crsPrefs.edit().putString("custom", org.json.JSONArray(customCrs.map { CrsJson.toJson(it) }).toString()).apply()
    }

    fun deleteCustomCrs(c: com.appdwg.core.Crs) {
        customCrs = customCrs.filter { it.id != c.id }
        crsPrefs.edit().putString("custom", org.json.JSONArray(customCrs.map { CrsJson.toJson(it) }).toString()).apply()
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
        crsPrefs.edit().putString("recent", recentCrs.joinToString(",")).apply()
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
                    fun get(url: String) = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("User-Agent", "Gromatic")
                        if (responseCode != 200) error("HTTP $responseCode")
                        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
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
                app.getString(R.string.crs_online_failed, e.message ?: e.javaClass.simpleName)
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

    /** Alignment the road settings describe, with the file's profile if any; null when not defined yet. */
    fun roadAxis(rp: RoadParams, project: LandXmlProject): Alignment? {
        if (rp.alignmentIndex >= 0) return project.alignments.filter { !it.name.endsWith(ROAD_SUFFIX) }.getOrNull(rp.alignmentIndex)
        if (rp.pis.size < 2) return null
        return AlignmentBuilder.fromPis(tr("Eixo"), rp.pis.mapIndexed { i, p ->
            if (i == 0 || i == rp.pis.size - 1) Pi(p) else Pi(p, rp.radius, rp.spiral)
        })
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
        val profile = when {
            rp.pvis.size >= 2 -> Profile(tr("Greide"), AlignmentBuilder.curvesFromK(rp.pvis.sortedBy { it.station }, rp.kValue).mapIndexed { i, p ->
                // A curve length typed by the user wins over K.
                rp.pvis.sortedBy { it.station }[i].takeIf { it.curveLength > 0 } ?: p
            })
            axis0.profile != null -> axis0.profile
            else -> { roadMessage = tr("Defina o greide: ao menos 2 PVIs (estaca e cota)."); return }
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
            v.roadParams = rp.copy(terrain = terrain.name)
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
            roadMessage = tr("Não foi possível projetar: {0}", e.message ?: e.javaClass.simpleName)
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
            surfaceEditMessage = app.getString(R.string.se_done, name, r.vertexCount, r.triangleCount)
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
        app.getSharedPreferences("sheets", android.content.Context.MODE_PRIVATE).edit()
            .putString("company", p.company).putString("responsible", p.responsible).putString("registration", p.registration).apply()
        val project = (state as? ProjectState.Loaded)?.project ?: return
        val axis = project.alignments.firstOrNull { it.name == p.alignment } ?: project.alignments.firstOrNull() ?: return
        val hidden = hiddenLayers()
        val surfaces = project.surfaces.filter { it.name !in hidden }
        val roadSections = road?.takeIf { it.alignment.name == axis.name }?.sections
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, com.appdwg.core.Display.locale).format(java.util.Date())
        writeTo(target, app.getString(R.string.sh_export)) { out ->
            val doc = android.graphics.pdf.PdfDocument()
            val built = SectionSheets.render(doc, p, axis, surfaces, roadSections, title, date)
            doc.writeTo(out); doc.close()
            if (built.sections == 0) error(app.getString(R.string.sh_none))
        }
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
        return app.getString(R.string.asbuilt_summary, r.checked, StationFormat.decimal(r.passRate * 100, 1), r.above, r.below, r.outside) to
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
                modelMessage = tr("Não foi possível ler {0}: {1}", main.second, e.message ?: e.javaClass.simpleName)
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
            platformMessage = tr("Não foi possível projetar: {0}", e.message ?: e.javaClass.simpleName)
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
                tr("Memória insuficiente para exportar {0}.", what)
            } catch (e: Exception) {
                tr("Não foi possível exportar {0}: {1}", what, e.message ?: e.javaClass.simpleName)
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
    private var savedJson: String? = null
    var projectMessage by mutableStateOf<String?>(null)
        private set

    fun dismissProjectMessage() {
        projectMessage = null
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
            edited = addedFiles.filter { it.uri == null && it.label.endsWith(EDITED_SUFFIX) }
                .flatMap { f -> f.project.surfaces }.map { s -> synchronized(encodedSurfaces) { encodedSurfaces.getOrPut(s) { SurfaceCodec.encode(s) } } },
        )
    }

    /** Encoded edited surfaces, so checking for unsaved changes does not encode them again. */
    private val encodedSurfaces = java.util.IdentityHashMap<Surface, org.json.JSONObject>()

    /** True when something changed since the drawing was opened or the project saved. */
    fun isDirty(): Boolean {
        val doc = currentDoc() ?: return false
        return doc.toJson().toString() != savedJson
    }

    /** Writes the project file to [target]. */
    fun saveProject(target: Uri, then: () -> Unit = {}) {
        val doc = currentDoc() ?: return
        viewModelScope.launch {
            projectMessage = try {
                val json = doc.toJson()
                val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
                val cloud = CloudDocs.isDrive(target.toString())
                tracked(if (cloud) tr("Enviando ao Google Drive") else tr("Salvando projeto")) { t ->
                    withContext(Dispatchers.IO) { writeWithProgress(target, bytes, t, cloud) }
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
                if (CloudDocs.isDrive(target.toString())) tr("Projeto salvo no Google Drive ({0}); o app Drive termina o envio em segundo plano.", displayName(target))
                else tr("Projeto salvo em {0}.", displayName(target))
            } catch (e: Exception) {
                tr("Não foi possível salvar o projeto: {0}", e.message ?: e.javaClass.simpleName)
            }
        }
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
        recentPrefs.edit().putString("list", org.json.JSONArray(list.map { (n, u) -> JSONObject().put("name", n).put("uri", u) }).toString()).apply()
    }

    fun forgetRecent(uri: String) {
        recent = recent.filter { it.second != uri }
        recentPrefs.edit().putString("list", org.json.JSONArray(recent.map { (n, u) -> JSONObject().put("name", n).put("uri", u) }).toString()).apply()
    }

    /** Opens a drawing or, when [uri] is a project file, the drawing and everything saved with it. */
    fun open(uri: Uri) {
        // Read and write when the provider allows (a project on Drive is saved back in place), else read.
        val cr = getApplication<Application>().contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        CloudDocs.noteUsed(getApplication(), uri)
        projectMessage = null
        openJob?.cancel()
        val before = state
        openJob = viewModelScope.launch {
            // The free version opens small files only (the cloud reports the size before downloading).
            if (CloudDocs.isDrive(uri.toString())) {
                val known = reportedSize(uri)
                if (!pro.isPro && known != null && known > FreeLimits.MAX_FILE_BYTES) {
                    state = ProjectState.Failed(displayName(uri), getApplication<Application>().getString(R.string.pro_too_big,
                        StationFormat.decimal(known / 1048576.0, 1), (FreeLimits.MAX_FILE_BYTES / 1048576).toString()))
                    proRequested = true
                    return@launch
                }
                val name = displayName(uri)
                val task = LoadTask(tr("Abrindo {0}", name)) { openJob?.cancel() }
                state = ProjectState.Loading(name, task)
                try {
                    download(uri, task)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    state = before
                    projectMessage = tr("Download cancelado.")
                    return@launch
                } catch (e: Exception) {
                    state = ProjectState.Failed(name, tr("Não foi possível baixar do Google Drive: {0}", e.message ?: e.javaClass.simpleName))
                    return@launch
                }
            }
            val size = withContext(Dispatchers.IO) { fileSize(uri) }
            if (!pro.isPro && size > FreeLimits.MAX_FILE_BYTES) {
                state = ProjectState.Failed(displayName(uri), getApplication<Application>().getString(R.string.pro_too_big,
                    StationFormat.decimal(size / 1048576.0, 1), (FreeLimits.MAX_FILE_BYTES / 1048576).toString()))
                proRequested = true
                return@launch
            }
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

    private suspend fun openProject(uri: Uri) {
        val name = displayName(uri)
        val doc = try {
            withContext(Dispatchers.IO) {
                val text = input(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error(tr("arquivo indisponível"))
                ProjectDoc.fromJson(JSONObject(text))
            }
        } catch (e: Exception) {
            state = ProjectState.Failed(name, tr("Não foi possível ler o projeto: {0}", e.message ?: e.javaClass.simpleName))
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
        if (!openNow(Uri.parse(main), allowEmpty = true)) {
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
        for ((label, u) in doc.files) {
            val before = addedFiles.size
            addFileNow(Uri.parse(u), label)
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
                    keptOriginals = project.polylines.indices.toList()
                    undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
                    platform = null
                    asBuilt = null
                    volume = null
                    analysisMode = 0; slopeImage = null; flowGrid = null; flowChannels = emptyList(); flowArrows = emptyList(); slopeClasses = emptyList()
                    road = null
                    roadMessage = null
                    platformVolumes = null
                    platformMessage = null
                    view = ViewState(project)
                    restoreRasters(project)
                    restoreXrefs(project)
                    ProjectState.Loaded(name, project, terrain)
                }
            } catch (e: OutOfMemoryError) {
                ProjectState.Failed(name, tr("Arquivo grande demais para a memória deste aparelho."))
            } catch (e: Exception) {
                ProjectState.Failed(name, tr("Não foi possível ler o arquivo: {0}", e.message ?: e.javaClass.simpleName))
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
        val resolver = getApplication<Application>().contentResolver
        val fd = resolver.openFileDescriptor(uri, "r") ?: error(tr("arquivo indisponível"))
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
                val raster = TiffReader(input, jpeg = { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                        val px = IntArray(b.width * b.height)
                        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                        Raster(b.width, b.height, px).also { b.recycle() }
                    }
                }).read(MAX_RASTER) { task.step(tr("Decodificando TIFF"), it) }
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

    private fun fileSize(uri: Uri): Long = cloudCopies[uri]?.takeIf { it.exists() }?.length() ?:
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
