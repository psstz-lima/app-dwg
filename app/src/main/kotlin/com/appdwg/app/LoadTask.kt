package com.appdwg.app

import com.appdwg.core.tr

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.FilterInputStream
import java.io.InputStream

/** Progress of a file being loaded: what is happening and, when known, how far along it is. */
class LoadTask(val label: String, /** Stops the task; null when it cannot be stopped. */ val onCancel: (() -> Unit)? = null) {
    var stage by mutableStateOf(tr("Calculando"))
    /** 0..1, or null while the step has no measurable progress. */
    var fraction by mutableStateOf<Float?>(null)
    val startedAt = SystemClock.elapsedRealtime()

    /** Sets the stage and its progress together, so the screen never shows one without the other. */
    fun step(name: String, fraction: Float? = null) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            stage = name
            this.fraction = fraction
        }
    }
}

/** Counts the bytes read from [inner] and reports the fraction of [total] in steps of 1%. */
class CountingInputStream(inner: InputStream, private val total: Long, private val onProgress: (Float) -> Unit) :
    FilterInputStream(inner) {
    private var count = 0L
    private var reported = -1

    private fun advance(n: Long) {
        if (n <= 0 || total <= 0) return
        count += n
        val percent = (count * 100 / total).toInt().coerceAtMost(100)
        if (percent != reported) {
            reported = percent
            onProgress(percent / 100f)
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { advance(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { advance(it) }
}

/** Stage, bar (or moving bar when the step has no measure), percentage and elapsed time. */
@Composable
fun LoadProgress(task: LoadTask, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(task) {
        while (true) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val seconds = (now - task.startedAt) / 1000
    Column(modifier.widthIn(max = 420.dp)) {
        Text(task.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row {
            Text(task.stage, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                (task.fraction?.let { "${(it * 100).toInt()}% · " } ?: "") + "${seconds}s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        val f = task.fraction
        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        task.onCancel?.let { cancel ->
            Spacer(Modifier.height(8.dp))
            GButton(tr("Cancelar"), cancel, outlined = true)
        }
    }
}

/**
 * The view model's running tasks inside a dialog. While a dialog shows them, the card over the
 * plan ([BackgroundTasks]) stays hidden, so the same progress never shows twice.
 */
@Composable
fun TaskProgress(vm: ProjectViewModel, modifier: Modifier = Modifier) {
    androidx.compose.runtime.DisposableEffect(vm) {
        vm.taskViewers++
        onDispose { vm.taskViewers-- }
    }
    Column(modifier) { vm.tasks.forEach { LoadProgress(it) } }
}

/** Cards for the files still loading in the background (images, xrefs). */
@Composable
fun BackgroundTasks(tasks: List<LoadTask>, modifier: Modifier = Modifier) {
    if (tasks.isEmpty()) return
    Column(modifier) {
        tasks.forEach { t ->
            Card(Modifier.padding(4.dp)) { LoadProgress(t, Modifier.padding(12.dp)) }
        }
    }
}
