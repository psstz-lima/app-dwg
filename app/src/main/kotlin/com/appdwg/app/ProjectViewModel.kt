package com.appdwg.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class ProjectViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<ProjectState>(ProjectState.Empty)
        private set

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
                if (project.surfaces.isEmpty() && project.alignments.isEmpty() && project.cgPoints.isEmpty()) {
                    ProjectState.Failed(name, "Nenhuma superfície, alinhamento ou ponto encontrado. O arquivo é um LandXML?")
                } else {
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
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            LandXmlReader(parser).read()
        }
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
