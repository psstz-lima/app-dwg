package com.appdwg.app

import com.appdwg.core.tr

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
import androidx.compose.foundation.layout.size

/** Version, what the app does with data, map credits and open source licences. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppMark(Modifier.size(48.dp))
                Text(tr("Sobre o Gromatic"))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Versão {0}", version))
                Text(tr("© 2026 Paulo Lima. Todos os direitos reservados."), style = MaterialTheme.typography.bodySmall)
                Text(tr("Gromatic: Survey & Earthworks. Visualização e ferramentas de campo para dados do Civil 3D (LandXML, DXF, pontos COGO), sem precisar de CAD."))

                Text(tr("Privacidade"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Os arquivos, pontos, anotações e fotos ficam só no aparelho; o app não tem conta, anúncios nem envia dados a servidores próprios. ") +
                        tr("A assinatura Gromatic Pro é cobrada pelo Google Play; o app não vê dados de pagamento. ") +
                        tr("A localização é usada só enquanto o GPS está ligado, para mostrar a posição na planta. ") +
                        tr("A internet é usada para baixar mapas de fundo e, se configurado, receber correções NTRIP do caster informado por você."),
                )

                Text(tr("Mapas de fundo"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Satélite: Esri, Maxar, Earthstar Geographics e comunidade de usuários GIS."))
                Text(tr("Ruas e Topográfico: Esri, HERE, Garmin, USGS, NGA, EPA, NPS, © colaboradores do OpenStreetMap."))
                Text(tr("OpenStreetMap: © colaboradores do OpenStreetMap, dados sob a licença ODbL (openstreetmap.org/copyright)."))
                Text(tr("Os mapas são de terceiros e seguem os termos de uso de cada serviço."), style = MaterialTheme.typography.bodySmall)

                Text(tr("Componentes de terceiros"), style = MaterialTheme.typography.titleSmall)
                for ((name, licence) in listOf(
                    tr("Kotlin e kotlinx.coroutines (JetBrains)") to "Apache 2.0",
                    tr("Jetpack Compose, Material 3, AndroidX Activity, Lifecycle e Core (Google)") to "Apache 2.0",
                    tr("Google Play Billing Library (Google)") to tr("do Android SDK e do Google Play"),
                    tr("Delaunator, triangulação adaptada (Vladimir Agafonkin, Mapbox)") to "ISC",
                    tr("Fontes Barlow, IBM Plex Sans e Plex Mono, Manrope, DM Mono, Atkinson Hyperlegible e JetBrains Mono") to "SIL Open Font License 1.1",
                )) Text(tr("{0} — licença {1}", name, licence))
                Text(tr("Textos das licenças: apache.org/licenses/LICENSE-2.0 e openfontlicense.org. Os componentes de terceiros seguem as próprias licenças; o restante do app é proprietário."),
                    style = MaterialTheme.typography.bodySmall)

                Text(tr("Sistemas de coordenadas: catálogo por região (UTM, LTM, RTM, Lambert e geográficas), Transversa de Mercator pelas séries de Krüger; transformações de SAD69, SAD69(96) e Córrego Alegre para SIRGAS 2000 com os parâmetros do IBGE."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fechar")) } },
    )
}
