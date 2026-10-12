package com.appdwg.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What a file pick in the references dialog is for. */
private sealed interface Pick {
    data class Image(val file: String) : Pick
    data class Xref(val name: String, val path: String) : Pick
}

/**
 * External references manager, like AutoCAD's XREF palette: images and xref drawings with their
 * saved path (editable, saved into a DXF copy), the file used on this device, and load / unload /
 * detach actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReferencesDialog(vm: ProjectViewModel, onDismiss: () -> Unit) {
    var pick by remember { mutableStateOf<Pick?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) when (val p = pick) {
            is Pick.Image -> vm.loadRaster(p.file, uri)
            is Pick.Xref -> vm.loadXref(p.name, p.path, uri)
            null -> {}
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        if (uri != null) vm.saveDxfCopy(uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            TextButton(enabled = vm.pathEdits.isNotEmpty(), onClick = { saver.launch("referencias-editadas.dxf") }) {
                Text("Salvar cópia do DXF")
            }
        },
        title = { Text("Referências externas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "O DXF guarda só o caminho de cada referência. Escolha o arquivo no aparelho para vê-la. " +
                        "Imagens: TIFF/GeoTIFF, JPG, PNG, WebP. Desenhos (XREF): DXF. Edite o caminho salvo e use " +
                        "\"Salvar cópia do DXF\" para gravar os novos caminhos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                (vm.rasterError ?: vm.referenceMessage)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                vm.allImages.forEach { img ->
                    val key = img.file
                    val status = when {
                        key in vm.detached -> "desanexada"
                        key in vm.unloaded -> "descarregada"
                        key in vm.rasters -> "carregada"
                        else -> "arquivo não escolhido"
                    }
                    ReferenceCard(
                        kind = "Imagem",
                        title = img.fileName,
                        details = "${img.widthPx} × ${img.heightPx} px · camada ${img.layer} · $status",
                        path = vm.pathEdits[key] ?: key,
                        onPath = { vm.editPath(key, it) },
                        detached = key in vm.detached,
                        unloaded = key in vm.unloaded,
                        onChoose = { pick = Pick.Image(key); picker.launch(arrayOf("*/*")) },
                        onUnload = { vm.setUnloaded(key, it) },
                        onDetach = { vm.setDetached(key, it) },
                    )
                }
                vm.allXrefs.forEach { x ->
                    val status = when {
                        x.name in vm.detached -> "desanexado"
                        x.name in vm.unloaded -> "descarregado"
                        x.name in vm.loadedXrefs -> "carregado"
                        else -> "arquivo não escolhido"
                    }
                    ReferenceCard(
                        kind = if (x.overlay) "Desenho (sobreposição)" else "Desenho (anexo)",
                        title = x.name,
                        details = "${x.fileName} · ${x.placements.size} inserção(ões) · $status",
                        path = vm.pathEdits[x.path] ?: x.path,
                        onPath = { vm.editPath(x.path, it) },
                        detached = x.name in vm.detached,
                        unloaded = x.name in vm.unloaded,
                        onChoose = { pick = Pick.Xref(x.name, x.path); picker.launch(arrayOf("*/*")) },
                        onUnload = { vm.setUnloaded(x.name, it) },
                        onDetach = { vm.setDetached(x.name, it) },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceCard(
    kind: String,
    title: String,
    details: String,
    path: String,
    onPath: (String) -> Unit,
    detached: Boolean,
    unloaded: Boolean,
    onChoose: () -> Unit,
    onUnload: (Boolean) -> Unit,
    onDetach: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(details, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = path,
                onValueChange = onPath,
                label = { Text("Caminho salvo no DXF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detached) {
                    OutlinedButton(onClick = { onDetach(false) }) { Text("Anexar de novo") }
                } else {
                    OutlinedButton(onClick = onChoose) { Text("Escolher arquivo") }
                    OutlinedButton(onClick = { onUnload(!unloaded) }) { Text(if (unloaded) "Recarregar" else "Descarregar") }
                    OutlinedButton(onClick = { onDetach(true) }) { Text("Desanexar") }
                }
            }
        }
    }
}
