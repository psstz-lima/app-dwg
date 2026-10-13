package com.appdwg.app

import android.content.Intent
import android.net.Uri
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
private val proFeatures = listOf(
    "Vistas 3D, Seção e Perfil",
    "Estaca, afastamento e cotas ao tocar na planta",
    "Camadas, superfícies e curvas de nível",
    "Pontos, linhas, anotações e medições",
    "Estrada, plataforma, volumes, as-built e boletim de medição",
    "GPS/RTK, calibração e sistemas de coordenadas",
    "Exportar (DXF, KML, CSV, PDF…) e salvar projetos",
)

/** The Gromatic Pro screen: what it adds, the plans with Play's prices, subscribe and restore. */
@Composable
fun ProDialog(pro: ProStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    LaunchedEffect(Unit) { pro.loadPlans() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gromatic Pro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        pro.isPro && pro.simulation == ProStore.Simulation.PRO -> "Pro simulado (versão de desenvolvimento)."
                        pro.isPro -> "Assinatura ativa: todas as ferramentas estão liberadas."
                        else -> "Você está na versão grátis: abrir e ver a planta (2D), arquivos de até ${FreeLimits.MAX_FILE_BYTES / 1048576} MB, " +
                            "zoom e escala, unidades, idioma e tema."
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text("O Pro libera:", style = MaterialTheme.typography.titleSmall)
                for (f in proFeatures) Text("•  $f")
                HorizontalDivider()
                if (!pro.isPro) {
                    if (pro.plans.isEmpty() && pro.message == null) Text("Buscando os preços no Google Play…", style = MaterialTheme.typography.bodySmall)
                    for (plan in pro.plans) Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (plan.productId == ProStore.YEARLY) "Anual" else "Mensal", fontWeight = FontWeight.Bold)
                                Text(plan.price + " " + periodText(plan.period))
                                plan.trialDays?.let { Text("$it dias grátis para testar", style = MaterialTheme.typography.bodySmall) }
                            }
                            GButton("Assinar", { activity?.let { pro.buy(it, plan) } }, primary = true)
                        }
                    }
                }
                pro.message?.let { Text(it, color = LocalG.current.accent) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GButton(if (pro.busy) "Verificando…" else "Restaurar compra", { if (!pro.busy) pro.refresh(userAsked = true) }, outlined = true)
                    GButton("Gerenciar assinatura", {
                        val url = "https://play.google.com/store/account/subscriptions?package=" + context.packageName
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }, outlined = true)
                }
                Text("Cobrada pela sua conta do Google Play, com renovação automática; cancele quando quiser no Google Play. " +
                    "Sem anúncios. Sem internet, o Pro continua liberado por até ${ProStore.OFFLINE_DAYS} dias depois da última verificação.",
                    style = MaterialTheme.typography.bodySmall)
                if (pro.debuggable) {
                    HorizontalDivider()
                    Text("Teste (só na versão de desenvolvimento)", fontWeight = FontWeight.Bold)
                    for ((s, label) in listOf(ProStore.Simulation.REAL to "Real (Google Play)", ProStore.Simulation.FREE to "Simular Grátis", ProStore.Simulation.PRO to "Simular Pro")) {
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
        confirmButton = { GButton("Fechar", onDismiss) },
    )
}

/** "por mês", "por ano" from an ISO 8601 billing period. */
private fun periodText(p: String) = when (p) {
    "P1M" -> "por mês"
    "P1Y" -> "por ano"
    "P1W" -> "por semana"
    "P3M" -> "a cada 3 meses"
    "P6M" -> "a cada 6 meses"
    else -> p
}
