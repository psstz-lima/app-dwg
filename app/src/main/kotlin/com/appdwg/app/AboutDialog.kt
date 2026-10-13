package com.appdwg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Version, what the app does with data, map credits and open source licences. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sobre o App DWG") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Versão $version")
                Text("Visualização e ferramentas de campo para dados do Civil 3D (LandXML, DXF, pontos COGO), sem precisar de CAD.")

                Text("Privacidade", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Os arquivos, pontos, anotações e fotos ficam só no aparelho; o app não tem conta, anúncios nem envia dados a servidores próprios. " +
                        "A localização é usada só enquanto o GPS está ligado, para mostrar a posição na planta. " +
                        "A internet é usada para baixar mapas de fundo e, se configurado, receber correções NTRIP do caster informado por você.",
                )

                Text("Mapas de fundo", style = MaterialTheme.typography.titleSmall)
                Text("Satélite: Esri, Maxar, Earthstar Geographics e comunidade de usuários GIS.")
                Text("Ruas e Topográfico: Esri, HERE, Garmin, USGS, NGA, EPA, NPS, © colaboradores do OpenStreetMap.")
                Text("OpenStreetMap: © colaboradores do OpenStreetMap, dados sob a licença ODbL (openstreetmap.org/copyright).")
                Text("Os mapas são de terceiros e seguem os termos de uso de cada serviço.", style = MaterialTheme.typography.bodySmall)

                Text("Software de código aberto", style = MaterialTheme.typography.titleSmall)
                for ((name, licence) in listOf(
                    "Kotlin e kotlinx.coroutines (JetBrains)" to "Apache 2.0",
                    "Jetpack Compose, Material 3, AndroidX Activity, Lifecycle e Core (Google)" to "Apache 2.0",
                    "kXML2 (incluído no Android)" to "MIT",
                )) Text("$name — licença $licence")
                Text("Texto da licença Apache 2.0: apache.org/licenses/LICENSE-2.0", style = MaterialTheme.typography.bodySmall)

                Text("Sistemas de coordenadas: SIRGAS 2000 / UTM (GRS80). Conversões pelas séries de Krüger.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
