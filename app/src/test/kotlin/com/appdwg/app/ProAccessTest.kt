package com.appdwg.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every capability that is closed in the free version, and the purchase results that open them.
 * Pro must unlock the whole list.
 */
class ProAccessTest {
    @Test
    fun listsEveryGatedFeatureAndUnlocksEachWhenPro() {
        val listed = ProFeature.entries.joinToString("\n") { "${it.id} — ${it.label}" }
        assertEquals(gated.joinToString("\n") { "${it.first} — ${it.second}" }, listed)
        assertEquals(gated.map { it.first }, ProFeature.entries.map { it.id })
        for (feature in ProFeature.entries) {
            assertFalse(feature.id, ProAccess.allows(feature, isPro = false))
            assertTrue(feature.id, ProAccess.allows(feature, isPro = true))
        }
        assertFalse(ProAccess.allowsAll(false))
        assertTrue(ProAccess.allowsAll(true))
    }

    @Test
    fun everyFeatureIsNamedByAGate() {
        val root = root()
        val main = File(root, "app/src/main/kotlin/com/appdwg/app/MainActivity.kt").readText()
        val model = File(root, "app/src/main/kotlin/com/appdwg/app/ProjectViewModel.kt").readText()
        val gates = main + "\n" + model
        for (feature in ProFeature.entries) {
            assertTrue(feature.name, gates.contains("ProFeature.${feature.name}"))
        }
        assertFalse(main.contains("!isPro"))
        assertFalse(main.contains("!vm.pro.isPro"))
        assertFalse(main.contains("!viewModel.pro.isPro"))
        assertFalse(model.contains("!pro.isPro"))
        assertEquals(Regex("""RailTool\(""").findAll(main).count(), Regex("""feature = ProFeature\.""").findAll(main).count())
    }

    @Test
    fun tapShowsLocationInfoWhenProIsActive() {
        // Phone, panel open: the card sits on the plan.
        assertTrue(ProAccess.tapInfoCardOnPlan(isPro = true, wide = false, panelOpen = true, fullScreen = false))
        assertFalse(ProAccess.tapInfoInSidePanel(isPro = true, wide = false, panelOpen = true, fullScreen = false))
        // Tablet, side panel open: the card is in the panel.
        assertFalse(ProAccess.tapInfoCardOnPlan(isPro = true, wide = true, panelOpen = true, fullScreen = false))
        assertTrue(ProAccess.tapInfoInSidePanel(isPro = true, wide = true, panelOpen = true, fullScreen = false))
        // Tablet, side panel folded: the card still appears on the plan.
        assertTrue(ProAccess.tapInfoCardOnPlan(isPro = true, wide = true, panelOpen = false, fullScreen = false))
        assertFalse(ProAccess.tapInfoInSidePanel(isPro = true, wide = true, panelOpen = false, fullScreen = false))
        // Free: neither place shows the location card.
        assertFalse(ProAccess.tapInfoCardOnPlan(isPro = false, wide = false, panelOpen = true, fullScreen = false))
        assertFalse(ProAccess.tapInfoCardOnPlan(isPro = false, wide = true, panelOpen = false, fullScreen = false))
        assertFalse(ProAccess.tapInfoInSidePanel(isPro = false, wide = true, panelOpen = true, fullScreen = false))
    }

    @Test
    fun licenseTesterPurchaseUnlocksMonthlyAndYearlyBeforeAcknowledgement() {
        val monthly = ProEntitlement.read("""{"productId":"gromatic_pro_mensal","purchaseState":0,"acknowledged":false}""")
        assertTrue(ProEntitlement.active(listOf(monthly)))
        assertTrue(ProEntitlement.needsAcknowledge(monthly))
        val yearly = ProEntitlement.read("""{"productIds":["gromatic_pro_anual"],"purchaseState":0,"acknowledged":false,"suspended":false}""")
        assertTrue(ProEntitlement.active(listOf(yearly)))
        assertTrue(ProEntitlement.needsAcknowledge(yearly))
        val both = ProEntitlement.read("""{"productIds":["gromatic_pro_mensal","gromatic_pro_anual"],"purchaseState":1}""")
        assertTrue(ProEntitlement.active(listOf(both)))
        assertFalse(ProEntitlement.needsAcknowledge(both))
    }

    @Test
    fun pendingSuspendedAndOtherProductsStayClosed() {
        val pending = ProEntitlement.read("""{"productId":"gromatic_pro_mensal","purchaseState":4,"acknowledged":false}""")
        assertFalse(ProEntitlement.active(listOf(pending)))
        assertFalse(ProEntitlement.needsAcknowledge(pending))
        val suspended = ProEntitlement.read("""{"productId":"gromatic_pro_anual","purchaseState":0,"suspended":true}""")
        assertFalse(ProEntitlement.active(listOf(suspended)))
        val other = ProEntitlement.read("""{"productId":"something_else","purchaseState":0}""")
        assertFalse(ProEntitlement.active(listOf(other)))
        assertFalse(ProEntitlement.active(emptyList()))
        val missingState = ProEntitlement.read("""{"productId":"gromatic_pro_mensal"}""")
        assertTrue(ProEntitlement.active(listOf(missingState)))
        assertEquals(1, missingState.state)
        assertTrue(missingState.acknowledged)
    }

    @Test
    fun olderEmptyQueryCannotClearANewerPurchase() {
        val session = EntitlementSession()
        val started = session.nextQuery()
        assertTrue(session.acceptQuery(started))
        session.notePurchase()
        assertFalse(session.acceptQuery(started))
        val after = session.nextQuery()
        assertTrue(session.acceptQuery(after))
        assertFalse(session.acceptQuery(started))
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }

    private val gated = listOf(
        "measure" to "Medir área e comprimentos",
        "points" to "Pontos",
        "lines" to "Linhas",
        "layers" to "Camadas",
        "contours" to "Curvas de nível",
        "image" to "Imagem sobre o terreno",
        "compare" to "Comparar",
        "platform" to "Plataforma",
        "road" to "Estrada",
        "bulletin" to "Boletim de medição",
        "surface_edit" to "Editar superfície",
        "analysis" to "Análise e declividade",
        "advice" to "Sugestões",
        "basin" to "Bacias",
        "volume" to "Volume",
        "as_built" to "As-built",
        "models" to "Modelos",
        "notes" to "Anotar",
        "gps" to "GPS",
        "calibrate" to "Calibrar",
        "calc" to "Cálculos",
        "map" to "Mapa",
        "download" to "Baixar dados",
        "goto" to "Ir para",
        "export" to "Exportar",
        "refs" to "Referências",
        "warnings" to "Avisos",
        "tap_info" to "Estaca, afastamento e cotas no toque",
        "note_open" to "Abrir anotação",
        "point_info" to "Ficha do ponto",
        "view_3d" to "Vista 3D",
        "view_section" to "Seção",
        "view_profile" to "Perfil",
        "save" to "Salvar",
        "save_as" to "Salvar como",
        "new_project" to "Novo projeto",
        "crs" to "Sistema de coordenadas",
        "open_large_file" to "Abrir arquivo maior que 5 MB",
        "files" to "Arquivos do projeto",
        "surfaces" to "Superfícies e estilos",
        "point_labels" to "Rótulos dos pontos",
        "sheets" to "Pranchas de seções",
        "cross_section" to "Comparar seções",
        "timeline" to "Linha do tempo da obra",
    )
}
