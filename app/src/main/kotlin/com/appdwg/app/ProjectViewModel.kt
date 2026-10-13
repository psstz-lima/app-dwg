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
import com.appdwg.core.Placement
import com.appdwg.core.Surface
import com.appdwg.core.CogoPoint
import com.appdwg.core.POINTS_LAYER
import com.appdwg.core.PointFile
import com.appdwg.core.DxfWriter
import com.appdwg.core.Platform
import com.appdwg.core.PlatformDesign
import com.appdwg.core.PlatformResult
import com.appdwg.core.Point3
import com.appdwg.core.Polyline
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
import com.appdwg.core.StationFormat
import com.appdwg.core.SurfaceCompare
import com.appdwg.core.SurfaceDifference
import com.appdwg.core.StageProgress
import com.appdwg.core.XrefDrawing
import com.appdwg.core.Xrefs
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
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

/** File laid over the open project; [uri] is null for a surface the app generated. */
data class AddedFile(val label: String, val project: LandXmlProject, val uri: Uri?)

/** Placement that leaves a drawing where it is. */
private val IDENTITY = Placement(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, "0")

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

    /** Files of the picture over the surfaces, for saving the project. */
    var drapeUris: List<Uri> = emptyList()
        private set

    fun removeDrape() {
        drapeUris = emptyList()
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
        uris.forEach { u ->
            runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        viewModelScope.launch { drapeNow(project, uri, name, world, uris) }
    }

    private suspend fun drapeNow(project: LandXmlProject, uri: Uri, name: String, world: Pair<Uri, String>?, uris: List<Uri>) {
        run {
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
                drapeUris = uris
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

    // ---- Files added to the open project and surface comparison ----

    /** Other files laid over the open one (name shown, read project), in the order added. */
    var addedFiles by mutableStateOf<List<AddedFile>>(emptyList())
        private set
    var compareMessage by mutableStateOf<String?>(null)
        private set

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
                val drawing = tracked("Adicionando $name") { t -> withContext(Dispatchers.IO) { read(uri, t) } }
                if (drawing.surfaces.isEmpty() && drawing.alignments.isEmpty() && drawing.cgPoints.isEmpty() && drawing.polylines.isEmpty()) {
                    compareMessage = "$name não tem superfície, alinhamento, ponto nem linha."
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
                    drawing.surfaces.size.takeIf { it > 0 }?.let { "$it superfície(s)" },
                    drawing.alignments.size.takeIf { it > 0 }?.let { "$it alinhamento(s)" },
                    drawing.cgPoints.size.takeIf { it > 0 }?.let { "$it ponto(s)" },
                    drawing.polylines.size.takeIf { it > 0 }?.let { "${it} linha(s)" },
                ).joinToString(", ") + "."
            } catch (e: OutOfMemoryError) {
                compareMessage = "Arquivo grande demais para a memória deste aparelho."
            } catch (e: Exception) {
                compareMessage = "Não foi possível ler $name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun removeFile(label: String) {
        if (addedFiles.none { it.label == label }) return
        addedFiles = addedFiles.filter { it.label != label }
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
                matrix = tracked("Comparação cruzada") { t ->
                    withContext(Dispatchers.Default) {
                        val out = LinkedHashMap<Pair<String, String>, SurfaceDifference>()
                        val pairs = surfaces.size * (surfaces.size - 1) / 2
                        var k = 0
                        for (i in surfaces.indices) for (j in i + 1 until surfaces.size) {
                            t.step("Par ${++k} de $pairs", k.toFloat() / pairs)
                            SurfaceCompare.difference(surfaces[i], surfaces[j], maxCells = 500_000)
                                ?.let { out[surfaces[i].name to surfaces[j].name] = it }
                        }
                        out
                    }
                }
            } catch (e: OutOfMemoryError) {
                compareMessage = "Superfícies grandes demais para comparar neste aparelho."
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
                val p = tracked("Comparação por etapas") { withContext(Dispatchers.Default) { SurfaceCompare.stages(surfaces) } }
                stageProgress = p
                if (p == null) compareMessage = "Há etapas que não se sobrepõem."
            } catch (e: OutOfMemoryError) {
                compareMessage = "Superfícies grandes demais para comparar neste aparelho."
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
                val result = tracked("Comparando superfícies") { t ->
                    withContext(Dispatchers.Default) {
                        t.step("Calculando diferença e volumes")
                        SurfaceCompare.difference(a, b)?.let { it to DifferenceImage.of(it) }
                    }
                }
                if (result == null) {
                    compareMessage = "As duas superfícies não se sobrepõem."
                    clearDifference()
                } else {
                    difference = result.first
                    differenceImage = result.second
                    differenceVisible = true
                }
            } catch (e: OutOfMemoryError) {
                compareMessage = "Superfícies grandes demais para comparar neste aparelho."
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
                        ?: error("arquivo indisponível")
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
                    " ${skipped.size} linha(s) ignorada(s) por não estarem no formato número, descrição, E, N, cota: " +
                        skipped.take(8).joinToString(", ") { "linha ${it.first}" } + if (skipped.size > 8) "…" else ""
            } catch (e: Exception) {
                pointsMessage = "Não foi possível ler $name: ${e.message ?: e.javaClass.simpleName}"
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
                    } ?: error("destino indisponível")
                }
                "${points.size} ponto(s) exportado(s) para ${displayName(target)}."
            } catch (e: Exception) {
                "Não foi possível exportar: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Points of the last "generate points", for undoing it. */
    var lastGenerated by mutableStateOf<List<CogoPoint>>(emptyList())
        private set

    fun addGenerated(points: List<CogoPoint>) {
        userPoints = userPoints + points
        lastGenerated = points
        pointsMessage = "${points.size} ponto(s) gerado(s) na camada $POINTS_LAYER."
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
            pointsMessage = "São precisos 3 pontos com cota para gerar uma superfície."
            return
        }
        viewModelScope.launch { surfaceFromPointsNow() }
    }

    private suspend fun surfaceFromPointsNow() {
        val pts = userPoints.filter { !it.z.isNaN() }
        if (pts.size < 3) return
        run {
            try {
                val label = "$POINTS_LAYER superfície"
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
                pointsMessage = "Superfície \"$label\" gerada com ${pts.size} pontos (${surface.triangleCount} triângulos)."
            } catch (e: Exception) {
                pointsMessage = "Não foi possível gerar a superfície: ${e.message ?: e.javaClass.simpleName}"
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
            val (project, terrain) = tracked("Atualizando referências") { withContext(Dispatchers.Default) {
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
                    p = Xrefs.attach(p, XrefDrawing(label, label, listOf(IDENTITY), overlay = true), drawing)
                    // A surface named like its file (point caches) reads better as just the file name.
                    p = p.copy(
                        alignments = p.alignments + drawing.alignments,
                        surfaces = p.surfaces.map { s ->
                            if (s.name == "$label|$label") Surface(label, s.xs, s.ys, s.zs, s.triangles) else s
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
            ?: run { modelMessage = "Escolha um arquivo OBJ, STL, PLY, glTF/GLB ou IFC."; return }
        uris.forEach { u -> runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        modelMessage = null
        viewModelScope.launch {
            try {
                val placementFor: (Model3D) -> ModelPlacement = { m -> defaultPlacement(m, at) }
                val entry = tracked("Lendo modelo ${main.second}") { t ->
                    withContext(Dispatchers.Default) { loadModel(main.first, main.second, named, t)?.let { m -> ModelEntry(main.second, uris, m, placementFor(m)) } }
                } ?: return@launch
                models = models + entry
                refreshJob?.cancel(); refreshNow()
                val m = entry.model
                modelMessage = "${main.second}: ${m.meshes.size} elemento(s), ${m.triangleCount} triângulos" +
                    (if (m.georef != null) ", posicionado pela georreferência do IFC" else "") + "." +
                    (if (m.warnings.isNotEmpty()) " Avisos: " + m.warnings.take(5).joinToString("; ") else "")
            } catch (e: OutOfMemoryError) {
                modelMessage = "Modelo grande demais para a memória deste aparelho."
            } catch (e: Exception) {
                modelMessage = "Não foi possível ler ${main.second}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun loadModel(uri: Uri, name: String, named: List<Pair<Uri, String>>, task: LoadTask): Model3D? {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("arquivo indisponível")
        task.step("Montando a geometria")
        val model = ModelReader.read(bytes, name) { other ->
            // Companion files are matched by name among the ones chosen with the model.
            named.firstOrNull { it.second.equals(other.substringAfterLast('/').substringAfterLast('\\'), ignoreCase = true) }
                ?.let { (u, _) -> resolver.openInputStream(u)?.use { it.readBytes() } }
        }
        require(model.meshes.isNotEmpty()) { "nenhuma geometria encontrada" + if (model.warnings.isNotEmpty()) " (" + model.warnings.joinToString("; ") + ")" else "" }
        require(model.triangleCount <= MAX_MODEL_TRIANGLES) { "modelo com ${model.triangleCount} triângulos; o limite neste aparelho é $MAX_MODEL_TRIANGLES. Simplifique o modelo." }
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
        if (terrain == null) { platformMessage = "Abra ou adicione uma superfície de terreno."; return }
        if (v.platformOutline.size < 3) { platformMessage = "Desenhe a plataforma com 3 vértices ou mais."; return }
        val pp = v.platformParams
        val outline = v.platformOutline
        val vertexZ = if (pp.useVertexElevations) outline.map { p -> userPoints.firstOrNull { it.x == p.x && it.y == p.y }?.z ?: Double.NaN } else null
        if (vertexZ != null && vertexZ.any { it.isNaN() }) { platformMessage = "Há vértices sem cota de ponto; desmarque \"usar a cota de cada ponto\"."; return }
        val base = pp.elevation ?: outline.mapNotNull { terrain.elevationAt(it) }.average().takeIf { !it.isNaN() }
            ?: run { platformMessage = "A plataforma está fora do terreno."; return }
        platformMessage = null
        try {
            val result = tracked("Projetando plataforma") { t ->
                withContext(Dispatchers.Default) {
                    t.step("Taludes e bermas")
                    val d = PlatformDesign(
                        outline, base, pp.gradePercent, pp.gradeAzimuth, vertexZ,
                        cut = SlopeRule(pp.cutRatio, pp.cutBenchHeight, pp.cutBenchWidth, pp.cutBenchFall),
                        fill = SlopeRule(pp.fillRatio, pp.fillBenchHeight, pp.fillBenchWidth, pp.fillBenchFall),
                    )
                    val r = Platform.design(d, terrain, PLATFORM_LABEL)
                    t.step("Volumes")
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
                "${result.first.unresolved} perfil(is) de talude não encontraram o terreno (saem da superfície ou passam de 300 m)." else null
        } catch (e: OutOfMemoryError) {
            platformMessage = "Memória insuficiente para projetar."
        } catch (e: Exception) {
            platformMessage = "Não foi possível projetar: ${e.message ?: e.javaClass.simpleName}"
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

    private fun writeTo(target: Uri, what: String, block: (java.io.OutputStream) -> Unit) {
        viewModelScope.launch {
            exportMessage = try {
                tracked("Exportando $what") {
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.buffered(1 shl 16)?.use { block(it) }
                            ?: error("destino indisponível")
                    }
                }
                "$what exportado para ${displayName(target)}."
            } catch (e: OutOfMemoryError) {
                "Memória insuficiente para exportar $what."
            } catch (e: Exception) {
                "Não foi possível exportar $what: ${e.message ?: e.javaClass.simpleName}"
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
                val sources = platform?.let { r ->
                    mapOf(PLATFORM_LABEL to SurfaceSource(listOf(r.edge, r.daylight) + r.benches, r.daylight.map { Point2(it.x, it.y) }))
                }.orEmpty()
                LandXmlWriter.write(p.surfaces.filter { it.name !in hidden }, p.points, p.cgPoints, w, p.coordinateSystem?.epsgCode, sources)
                w.flush()
            }
        }
    }

    fun exportKml(target: Uri, zone: Int, south: Boolean, extra: List<ExportLine>) {
        val p = exportProject() ?: return
        val name = (state as? ProjectState.Loaded)?.fileName ?: "App DWG"
        writeTo(target, "KML") { out -> out.writer(Charsets.UTF_8).let { w -> KmlWriter.write(p.points, extra, zone, south, name, w); w.flush() } }
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
                append("Comparação;A;B;Corte (m3);Aterro (m3);Saldo (m3);Área corte (m2);Área aterro (m2);Malha (m)\r\n")
                append("B - A;${d.baseName};${d.otherName};${n(d.cutVolume)};${n(d.fillVolume)};${n(d.netVolume)};${n(d.cutArea)};${n(d.fillArea)};${n(d.cellSize)}\r\n\r\n")
            }
            if (m.isNotEmpty()) {
                append("Matriz (linha - coluna);Linha;Coluna;Corte (m3);Aterro (m3);Saldo (m3)\r\n")
                for (r in names) for (c in names) if (r != c) matrixCell(r, c)?.let { x ->
                    append(";$r;$c;${n(x.cutVolume)};${n(x.fillVolume)};${n(x.netVolume)}\r\n")
                }
                append("\r\n")
            }
            if (st != null) {
                append("Etapas;De;Para;Corte (m3);Aterro (m3);Movimentado (m3)\r\n")
                st.periods.forEachIndexed { k, p -> append("Período ${k + 1};${p.from};${p.to};${n(p.cut)};${n(p.fill)};${n(p.cut + p.fill)}\r\n") }
                append("Falta até o projeto;;;${n(st.remainingCut)};${n(st.remainingFill)};${n(st.remainingCut + st.remainingFill)}\r\n")
                append("Total terreno → projeto;;;${n(st.totalCut)};${n(st.totalFill)};${n(st.totalCut + st.totalFill)}\r\n")
                append("Concluído (%);;;;;${StationFormat.decimal(st.done * 100, 1)}\r\n")
            }
        }
        // BOM so Excel reads the accents.
        writeTo(target, "CSV de volumes") { it.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)) }
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

    // ---- Project file (.appdwg) ----

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
        val generated = addedFiles.any { it.uri == null && it.label == "$POINTS_LAYER superfície" }
        return ProjectDoc(
            main = main.toString(),
            files = addedFiles.mapNotNull { f -> f.uri?.let { f.label to it.toString() } },
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
        )
    }

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
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.use {
                        it.write(json.toString(2).toByteArray(Charsets.UTF_8))
                    } ?: error("destino indisponível")
                }
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                savedJson = json.toString()
                projectUri = target
                then()
                "Projeto salvo em ${displayName(target)}."
            } catch (e: Exception) {
                "Não foi possível salvar o projeto: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Opens a drawing or, when [uri] is a project file, the drawing and everything saved with it. */
    fun open(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        projectMessage = null
        viewModelScope.launch {
            val head = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { s ->
                        val b = ByteArray(256); val n = s.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
                    }
                }.getOrNull().orEmpty()
            }
            if (ProjectDoc.looksLikeProject(head)) openProject(uri) else if (openNow(uri)) {
                projectUri = null
                savedJson = currentDoc()?.toJson()?.toString()
            }
        }
    }

    private suspend fun openProject(uri: Uri) {
        val name = displayName(uri)
        val doc = try {
            withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("arquivo indisponível")
                ProjectDoc.fromJson(JSONObject(text))
            }
        } catch (e: Exception) {
            state = ProjectState.Failed(name, "Não foi possível ler o projeto: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (!openNow(Uri.parse(doc.main))) {
            val s = state
            if (s is ProjectState.Failed) state = s.copy(message = s.message + "\n\nO projeto $name guarda o desenho em ${doc.main}; se o arquivo mudou de lugar, abra-o de novo e salve o projeto.")
            return
        }
        val problems = ArrayList<String>()
        unloaded = doc.unloaded.toSet()
        detached = doc.detached.toSet()
        pathEdits = doc.pathEdits
        for ((label, u) in doc.files) {
            val before = addedFiles.size
            addFileNow(Uri.parse(u), label)
            if (addedFiles.size == before) problems += label
        }
        userPoints = doc.points
        for ((uris, placement) in doc.models) {
            val list = uris.map(Uri::parse)
            val named = list.map { it to displayName(it) }
            val main = named.firstOrNull { it.second.substringAfterLast('.').lowercase() in MODEL_EXTENSIONS } ?: continue
            try {
                val m = tracked("Lendo modelo ${main.second}") { t -> withContext(Dispatchers.Default) { loadModel(main.first, main.second, named, t) } } ?: continue
                models = models + ModelEntry(main.second, list, m, placement)
            } catch (e: Exception) {
                problems += main.second
            }
        }
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
        val v = view
        if (doc.compareShown && v != null && v.compareOther != PLATFORM_LABEL) {
            val project = (state as? ProjectState.Loaded)?.project
            val a = project?.surfaces?.firstOrNull { it.name == v.compareBase }
            val b = project?.surfaces?.firstOrNull { it.name == v.compareOther }
            if (a != null && b != null) compareNow(a, b)
        }
        projectUri = uri
        savedJson = currentDoc()?.toJson()?.toString()
        projectMessage = if (problems.isEmpty()) "Projeto $name aberto." else
            "Projeto $name aberto, mas não foi possível abrir: ${problems.joinToString(", ")}."
    }

    private suspend fun openNow(uri: Uri): Boolean {
        val name = displayName(uri)
        val task = LoadTask("Abrindo $name")
        state = ProjectState.Loading(name, task)
        run {
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
                    addedFiles = emptyList()
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
                    userLines = emptyList()
                    hiddenOriginals = emptySet()
                    keptOriginals = project.polylines.indices.toList()
                    undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
                    platform = null
                    platformVolumes = null
                    platformMessage = null
                    view = ViewState(project)
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
        return state is ProjectState.Loaded
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
