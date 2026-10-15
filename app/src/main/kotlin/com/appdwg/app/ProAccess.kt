package com.appdwg.app

import com.android.billingclient.api.Purchase

/**
 * Every capability that stays closed until Gromatic Pro is active.
 * Free keeps the 2D plan, files up to [FreeLimits.MAX_FILE_BYTES], zoom, units, language and theme.
 * Pro opens the whole list: [ProAccess.allows] is true for every entry when Pro is active.
 */
enum class ProFeature(val id: String, val label: String) {
    MEASURE("measure", "Medir área e comprimentos"),
    POINTS("points", "Pontos"),
    LINES("lines", "Linhas"),
    LAYERS("layers", "Camadas"),
    CONTOURS("contours", "Curvas de nível"),
    IMAGE("image", "Imagem sobre o terreno"),
    COMPARE("compare", "Comparar"),
    PLATFORM("platform", "Plataforma"),
    ROAD("road", "Estrada"),
    BULLETIN("bulletin", "Boletim de medição"),
    SURFACE_EDIT("surface_edit", "Editar superfície"),
    ANALYSIS("analysis", "Análise e declividade"),
    ADVICE("advice", "Sugestões"),
    BASIN("basin", "Bacias"),
    VOLUME("volume", "Volume"),
    AS_BUILT("as_built", "As-built"),
    MODELS("models", "Modelos"),
    NOTES("notes", "Anotar"),
    GPS("gps", "GPS"),
    CALIBRATE("calibrate", "Calibrar"),
    CALC("calc", "Cálculos"),
    MAP("map", "Mapa"),
    DOWNLOAD("download", "Baixar dados"),
    GOTO("goto", "Ir para"),
    EXPORT("export", "Exportar"),
    REFS("refs", "Referências"),
    WARNINGS("warnings", "Avisos"),
    TAP_INFO("tap_info", "Estaca, afastamento e cotas no toque"),
    NOTE_OPEN("note_open", "Abrir anotação"),
    POINT_INFO("point_info", "Ficha do ponto"),
    VIEW_3D("view_3d", "Vista 3D"),
    VIEW_SECTION("view_section", "Seção"),
    VIEW_PROFILE("view_profile", "Perfil"),
    SAVE("save", "Salvar"),
    SAVE_AS("save_as", "Salvar como"),
    NEW_PROJECT("new_project", "Novo projeto"),
    CRS("crs", "Sistema de coordenadas"),
    OPEN_LARGE_FILE("open_large_file", "Abrir arquivo maior que 5 MB"),
    FILES("files", "Arquivos do projeto"),
    SURFACES("surfaces", "Superfícies e estilos"),
    POINT_LABELS("point_labels", "Rótulos dos pontos"),
    SHEETS("sheets", "Pranchas de seções"),
    CROSS_SECTION("cross_section", "Comparar seções"),
    TIMELINE("timeline", "Linha do tempo da obra"),
}

/** Pro opens every [ProFeature]. There is no capability that stays locked once Pro is active. */
object ProAccess {
    fun allows(feature: ProFeature, isPro: Boolean): Boolean = isPro

    fun allowsAll(isPro: Boolean): Boolean = ProFeature.entries.all { allows(it, isPro) }

    /**
     * The station / offset / elevation card sits on the plan (phone, or a tablet whose side panel
     * is folded). A folded tablet panel still shows the card: the tap has to reveal the location.
     */
    fun tapInfoCardOnPlan(isPro: Boolean, wide: Boolean, panelOpen: Boolean, fullScreen: Boolean): Boolean {
        if (!allows(ProFeature.TAP_INFO, isPro)) return false
        if (wide && !fullScreen && !panelOpen) return true
        if (wide && !fullScreen) return false
        return panelOpen
    }

    /** The same card in the tablet side panel, when that panel is open. */
    fun tapInfoInSidePanel(isPro: Boolean, wide: Boolean, panelOpen: Boolean, fullScreen: Boolean): Boolean =
        allows(ProFeature.TAP_INFO, isPro) && wide && panelOpen && !fullScreen
}

/** One subscription purchase reduced to the fields that decide Pro. */
data class SubscriptionPurchase(
    val productIds: List<String>,
    val state: Int,
    val suspended: Boolean,
    val acknowledged: Boolean,
)

/**
 * Which Play purchases unlock Gromatic Pro.
 * A license-tester purchase is a normal purchased subscription (Play JSON `purchaseState` 0) and
 * counts before it is acknowledged. Pending (`purchaseState` 4) and suspended subscriptions do not.
 */
object ProEntitlement {
    fun from(purchase: Purchase): SubscriptionPurchase = read(purchase.originalJson)

    fun active(purchases: List<SubscriptionPurchase>): Boolean = purchases.any { p ->
        ours(p) && p.state == Purchase.PurchaseState.PURCHASED && !p.suspended
    }

    /** Play refunds a purchase that stays unacknowledged for three days. */
    fun needsAcknowledge(p: SubscriptionPurchase): Boolean =
        ours(p) && p.state == Purchase.PurchaseState.PURCHASED && !p.acknowledged

    private fun ours(p: SubscriptionPurchase): Boolean =
        p.productIds.any { it == ProStore.MONTHLY || it == ProStore.YEARLY }

    /**
     * Play's purchase JSON, read the same way as Billing 9's [Purchase]:
     * `productIds` when that key exists, otherwise `productId`;
     * `purchaseState` 4 is pending and every other value (including 0 and a missing field) is purchased;
     * `suspended` defaults to false; `acknowledged` defaults to true.
     */
    internal fun read(json: String): SubscriptionPurchase {
        val ids = stringArray(json, "productIds")
            ?: stringValue(json, "productId")?.let { listOf(it) }
            ?: emptyList()
        val raw = intValue(json, "purchaseState") ?: 1
        return SubscriptionPurchase(
            productIds = ids,
            state = if (raw == 4) Purchase.PurchaseState.PENDING else Purchase.PurchaseState.PURCHASED,
            suspended = boolValue(json, "suspended") ?: false,
            acknowledged = boolValue(json, "acknowledged") ?: true,
        )
    }

    private fun stringArray(json: String, key: String): List<String>? {
        val match = Regex(""""$key"\s*:\s*\[(.*?)]""").find(json) ?: return null
        return Regex(""""([^"\\]*)"""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private fun stringValue(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"\\]*)"""").find(json)?.groupValues?.get(1)

    private fun intValue(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toInt()

    private fun boolValue(json: String, key: String): Boolean? =
        Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1)?.toBoolean()
}

/**
 * Query generations. A purchase, or a newer query, invalidates a query that is still in flight
 * so an empty result from before the purchase cannot clear the license.
 */
class EntitlementSession {
    private val lock = Any()
    var generation: Int = 0
        private set

    fun nextQuery(): Int = synchronized(lock) { ++generation }

    fun notePurchase() = synchronized(lock) { generation++ }

    fun acceptQuery(gen: Int): Boolean = synchronized(lock) { gen == generation }
}
