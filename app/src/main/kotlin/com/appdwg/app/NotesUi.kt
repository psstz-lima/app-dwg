package com.appdwg.app

import com.appdwg.core.tr

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.appdwg.core.FieldNote
import com.appdwg.core.StationFormat
import java.io.File

/** Small copy of a note's photo for the screen. */
fun thumbnail(file: File, maxSide: Int = 800): ImageBitmap? {
    if (!file.exists()) return null
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, b)
    var s = 1
    while (maxOf(b.outWidth, b.outHeight) / s > maxSide) s *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

/** Creates or edits a field note: text, elevation and a photo from the camera or the gallery. */
@Composable
fun NoteDialog(vm: ProjectViewModel, initial: FieldNote, isNew: Boolean, onDismiss: () -> Unit, onSaveAndNew: (() -> Unit)? = null) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial.text) }
    var z by remember { mutableStateOf(if (initial.z.isNaN()) "" else StationFormat.lengthValue(initial.z)) }
    var photo by remember { mutableStateOf(initial.photo) }
    var pending by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo = pending?.name else pending?.delete()
        pending = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.importPhoto(uri)?.let { photo = it } }
    val thumb = remember(photo) { photo?.let { thumbnail(File(vm.notesDir, it)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OutlinedButton(onClick = {
                vm.saveNote(initial.copy(text = text.trim(), z = StationFormat.parseLength(z) ?: Double.NaN, photo = photo))
                onDismiss()
            }) { Text(tr("Salvar")) }
        },
        dismissButton = {
            Row {
                if (isNew && onSaveAndNew != null) OutlinedButton(onClick = {
                    vm.saveNote(initial.copy(text = text.trim(), z = StationFormat.parseLength(z) ?: Double.NaN, photo = photo))
                    onSaveAndNew()
                }) { Text(tr("Salvar e nova")) }
                if (!isNew) OutlinedButton(onClick = { vm.deleteNote(initial); onDismiss() }) { Text(tr("Apagar")) }
                OutlinedButton(onClick = onDismiss) { Text(tr("Cancelar")) }
            }
        },
        title = { Text(if (isNew) tr("Nova anotação") else tr("Anotação {0}", initial.id)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("E ${StationFormat.lengthValue(initial.x)} · N ${StationFormat.lengthValue(initial.y)} · ${initial.time}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, label = { Text(tr("Texto")) }, minLines = 2)
                OutlinedTextField(z, { z = it }, label = { Text(tr("Cota ({0})", StationFormat.lengthUnit)) }, singleLine = true, modifier = Modifier.width(180.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val f = vm.newPhotoFile()
                        pending = f
                        camera.launch(FileProvider.getUriForFile(context, context.packageName + ".notes", f))
                    }) { Text(tr("Tirar foto")) }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Text(tr("Da galeria")) }
                    if (photo != null) OutlinedButton(onClick = { photo = null }) { Text(tr("Remover foto")) }
                }
                thumb?.let { Image(it, contentDescription = "foto", modifier = Modifier.heightIn(max = 260.dp)) }
            }
        },
    )
}

/** Data of a tapped note with its photo. */
@Composable
fun NoteCard(vm: ProjectViewModel, note: FieldNote, onEdit: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val thumb = remember(note.photo) { note.photo?.let { thumbnail(File(vm.notesDir, it), 600) } }
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Anotação {0} · {1}", note.id, note.time), style = MaterialTheme.typography.titleSmall)
            if (note.text.isNotEmpty()) Text(note.text)
            Text("E ${StationFormat.lengthValue(note.x)} · N ${StationFormat.lengthValue(note.y)}" + (if (note.z.isNaN()) "" else " · cota ${StationFormat.lengthValue(note.z)}"),
                style = MaterialTheme.typography.bodySmall)
            thumb?.let { Image(it, contentDescription = "foto", modifier = Modifier.heightIn(max = 220.dp)) }
            Row {
                OutlinedButton(onClick = onEdit) { Text(tr("Editar")) }
                OutlinedButton(onClick = onClose) { Text(tr("Fechar")) }
            }
        }
    }
}
