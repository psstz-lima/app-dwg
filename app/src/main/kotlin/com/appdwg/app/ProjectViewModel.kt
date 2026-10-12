package com.appdwg.app

import android.app.Application
import android.content.Context
import android.content.Intent
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
import com.appdwg.core.DxfReader
import com.appdwg.core.LandXmlProject
import com.appdwg.core.LandXmlReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

sealed interface ProjectState {
    data object Empty : ProjectState
    data class Loading(val fileName: String) : ProjectState
    data class Loaded(val fileName: String, val project: LandXmlProject, val terrain: List<TerrainImage>) : ProjectState
    data class Failed(val fileName: String, val message: String) : ProjectState
}

private const val HEAD_SIZE = 64

/** Longest side of a loaded raster image; larger pictures are downsampled to save memory. */
private const val MAX_RASTER = 4096

class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<ProjectState>(ProjectState.Empty)
        private set

    /** Raster files loaded for DXF images, by the path the drawing stores. */
    var rasters by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set

    private val prefs = app.getSharedPreferences("rasters", Context.MODE_PRIVATE)

    /** Loads the picture for the drawing's image [file] and remembers the choice for next time. */
    fun loadRaster(file: String, uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(file, uri.toString()).apply()
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeRaster(uri) }
            if (bitmap != null) rasters = rasters + (file to bitmap)
        }
    }

    /** Reloads pictures chosen earlier for the images of [project]. */
    private fun restoreRasters(project: LandXmlProject) {
        for (image in project.images) {
            val uri = prefs.getString(image.file, null)?.let(Uri::parse) ?: continue
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { decodeRaster(uri) }.getOrNull() }
                if (bitmap != null) rasters = rasters + (image.file to bitmap)
            }
        }
    }

    /** Decodes a picture, halving its size until the longer side is at most [MAX_RASTER] pixels. */
    private fun decodeRaster(uri: Uri): ImageBitmap? {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_RASTER) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    }

    fun open(uri: Uri) {
        val name = displayName(uri)
        state = ProjectState.Loading(name)
        viewModelScope.launch {
            state = try {
                val (project, terrain) = withContext(Dispatchers.IO) {
                    val p = read(uri)
                    // Builds each surface's lookup index now, so the first tap on the plan is instant.
                    p.surfaces.forEach { s -> s.bounds?.let { s.elevationAt(it.center) } }
                    p to p.surfaces.mapNotNull { TerrainImage.of(it) }
                }
                if (project.surfaces.isEmpty() && project.alignments.isEmpty() && project.cgPoints.isEmpty() &&
                    project.polylines.isEmpty()
                ) {
                    ProjectState.Failed(name, "Nenhuma superfície, alinhamento ou ponto encontrado. O arquivo é um LandXML ou DXF?")
                } else {
                    rasters = emptyMap()
                    restoreRasters(project)
                    ProjectState.Loaded(name, project, terrain)
                }
            } catch (e: OutOfMemoryError) {
                ProjectState.Failed(name, "Arquivo grande demais para a memória deste aparelho.")
            } catch (e: Exception) {
                ProjectState.Failed(name, "Não foi possível ler o arquivo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun read(uri: Uri): LandXmlProject {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: error("arquivo indisponível")
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
                DxfReader.looksLikeDxf(text) -> return@use DxfReader(stream.reader(Charsets.UTF_8).buffered(1 shl 16)).read()
            }
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            LandXmlReader(parser).read()
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
