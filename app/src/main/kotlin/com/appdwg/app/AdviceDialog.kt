package com.appdwg.app

import com.appdwg.core.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdwg.core.Advice

/** What the app suggests doing with the opened files, each with a button that starts it. */
@Composable
fun AdviceDialog(advice: List<Advice>, onDo: (Advice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(tr("Fechar")) } },
        title = { Text(tr("Sugestões para este projeto")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("O app leu o que os arquivos têm e sugere o que dá para fazer com eles."), style = MaterialTheme.typography.bodySmall)
                advice.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.bodyMedium)
                            Text(a.reason, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { onDo(a) }) { Text(tr("Fazer")) }
                    }
                }
            }
        },
    )
}
