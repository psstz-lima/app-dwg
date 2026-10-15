package com.appdwg.app

import com.appdwg.core.tr

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What Pro adds, shown on the Gromatic Pro screen. */
private val proFeatures get() = listOf(
    tr("Vistas 3D, Seção e Perfil"),
    tr("Estaca, afastamento e cotas ao tocar na planta"),
    tr("Camadas, superfícies e curvas de nível"),
    tr("Pontos, linhas, anotações e medições"),
    tr("Estrada, plataforma, volumes, as-built e boletim de medição"),
    tr("GPS/RTK, calibração e sistemas de coordenadas"),
    tr("Exportar (DXF, KML, CSV, PDF…) e salvar projetos"),
)

/** The Gromatic Pro screen: what it adds, the plans with Play's prices, subscribe and restore. */
@Composable
fun ProDialog(pro: ProStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    LaunchedEffect(Unit) { pro.loadPlans() }
    AlertDialog(
        modifier = Modifier.safeBars(),
        onDismissRequest = onDismiss,
        title = { Text("Gromatic Pro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        pro.isPro && pro.simulation == ProStore.Simulation.PRO -> tr("Pro simulado (versão de desenvolvimento).")
                        pro.isPro -> tr("Assinatura ativa: todas as ferramentas estão liberadas.")
                        else -> tr("Você está na versão grátis: abrir e ver a planta (2D), arquivos de até {0} MB, ", FreeLimits.MAX_FILE_BYTES / 1048576) +
                            tr("zoom e escala, unidades, idioma e tema.")
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text(tr("O Pro libera:"), style = MaterialTheme.typography.titleSmall)
                for (f in proFeatures) Text("•  $f")
                HorizontalDivider()
                if (!pro.isPro) {
                    if (pro.plans.isEmpty() && pro.message == null) Text(tr("Buscando os preços no Google Play…"), style = MaterialTheme.typography.bodySmall)
                    for (plan in pro.plans) Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (plan.productId == ProStore.YEARLY) tr("Anual") else tr("Mensal"), fontWeight = FontWeight.Bold)
                                Text(plan.price + " " + periodText(plan.period))
                                plan.trialDays?.let { Text(tr("{0} dias grátis para testar", it), style = MaterialTheme.typography.bodySmall) }
                            }
                            GButton(tr("Assinar"), { activity?.let { pro.buy(it, plan) } }, primary = true)
                        }
                    }
                }
                pro.message?.let { Text(it, color = LocalG.current.accent) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GButton(if (pro.busy) tr("Verificando…") else tr("Restaurar compra"), { if (!pro.busy) pro.refresh(userAsked = true) }, outlined = true)
                    GButton(tr("Gerenciar assinatura"), {
                        val url = "https://play.google.com/store/account/subscriptions?package=" + context.packageName
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    }, outlined = true)
                }
                Text(tr("Cobrada pela sua conta do Google Play, com renovação automática; cancele quando quiser no Google Play. ") +
                    tr("Sem anúncios. Sem internet, o Pro continua liberado por até {0} dias depois da última verificação.", ProStore.OFFLINE_DAYS),
                    style = MaterialTheme.typography.bodySmall)
                if (pro.debuggable) {
                    HorizontalDivider()
                    Text(tr("Teste (só na versão de desenvolvimento)"), fontWeight = FontWeight.Bold)
                    for ((s, label) in listOf(ProStore.Simulation.REAL to tr("Real (Google Play)"), ProStore.Simulation.FREE to tr("Simular Grátis"), ProStore.Simulation.PRO to tr("Simular Pro"))) {
                        Row(
                            Modifier.fillMaxWidth().selectable(pro.simulation == s, role = Role.RadioButton) { pro.simulate(s) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(pro.simulation == s, onClick = null)
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = { GButton(tr("Fechar"), onDismiss) },
    )
}

/** "por mês", "por ano" from an ISO 8601 billing period. */
private fun periodText(p: String) = when (p) {
    "P1M" -> tr("por mês")
    "P1Y" -> tr("por ano")
    "P1W" -> tr("por semana")
    "P3M" -> "a cada 3 meses"
    "P6M" -> "a cada 6 meses"
    else -> p
}
