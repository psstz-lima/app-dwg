package com.appdwg.app

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** What the free version allows; everything else needs Gromatic Pro. */
object FreeLimits {
    /** Largest file the free version opens. */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
}

/** A subscription plan as the Play Store sells it, prices formatted by Play. */
class ProPlan(val productId: String, val title: String, val price: String, val period: String, val trialDays: Int?, val details: ProductDetails, val offerToken: String)

/**
 * Gromatic Pro: subscriptions through Google Play Billing. The state is kept on the device and
 * trusted offline for [OFFLINE_DAYS] after the last check with Play; it is checked again whenever
 * the app opens with Play reachable. Debug builds can simulate Free or Pro.
 */
class ProStore(private val context: Context) {
    companion object {
        const val MONTHLY = "gromatic_pro_mensal"
        const val YEARLY = "gromatic_pro_anual"
        const val OFFLINE_DAYS = 7
        private const val DAY = 24L * 3600 * 1000
    }

    /** Debug builds only: force Free or Pro instead of asking Play. */
    enum class Simulation { REAL, FREE, PRO }

    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    var simulation by mutableStateOf(if (debuggable) Simulation.entries.getOrElse(prefs.getInt("sim", 0)) { Simulation.REAL } else Simulation.REAL)
        private set
    /** Subscription active at the last check with Play. */
    private var licensed by mutableStateOf(prefs.getBoolean("active", false))
    private var lastCheck by mutableStateOf(prefs.getLong("checked", 0L))
    var plans by mutableStateOf<List<ProPlan>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** True when the Pro features are open. */
    val isPro: Boolean
        get() = when (simulation) {
            Simulation.FREE -> false
            Simulation.PRO -> true
            Simulation.REAL -> licensed && System.currentTimeMillis() - lastCheck < OFFLINE_DAYS * DAY
        }

    /** Days left of offline trust, for the Pro screen. */
    val offlineDaysLeft: Int get() = (OFFLINE_DAYS - (System.currentTimeMillis() - lastCheck) / DAY).toInt().coerceAtLeast(0)

    fun simulate(s: Simulation) { if (debuggable) { simulation = s; prefs.edit().putInt("sim", s.ordinal).apply() } }

    private fun store(active: Boolean) {
        licensed = active; lastCheck = System.currentTimeMillis()
        prefs.edit().putBoolean("active", active).putLong("checked", lastCheck).apply()
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases.orEmpty(), fromQuery = false)
            else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) message = context.getString(R.string.pro_error, result.debugMessage) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()

    private fun connected(then: () -> Unit) {
        if (client.isReady) { then(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) then()
                else { message = context.getString(R.string.pro_unavailable, r.debugMessage.ifBlank { r.responseCode.toString() }); busy = false }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Checks the subscription with Play (at start and on "Restaurar compra"). */
    fun refresh(userAsked: Boolean = false) {
        if (userAsked) { busy = true; message = null }
        connected {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
                busy = false
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    handle(list, fromQuery = true)
                    if (userAsked) message = context.getString(if (isPro) R.string.pro_restored else R.string.pro_none_found)
                }
            }
        }
    }

    /** Plans and prices from Play, for the Pro screen. */
    fun loadPlans() {
        connected {
            val products = listOf(MONTHLY, YEARLY).map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { r, result ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) { message = context.getString(R.string.pro_unavailable, r.debugMessage); return@queryProductDetailsAsync }
                plans = result.productDetailsList.mapNotNull { d -> plan(d) }.sortedBy { if (it.productId == MONTHLY) 0 else 1 }
                if (plans.isEmpty()) message = context.getString(R.string.pro_no_plans)
            }
        }
    }

    private fun plan(d: ProductDetails): ProPlan? {
        val offers = d.subscriptionOfferDetails ?: return null
        // The offer with a free trial when there is one, else the base plan.
        val offer = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: offers.first()
        val phases = offer.pricingPhases.pricingPhaseList
        val paid = phases.last()
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod?.let(::days)
        return ProPlan(d.productId, d.name, paid.formattedPrice, paid.billingPeriod, trial, d, offer.offerToken)
    }

    /** ISO 8601 period ("P7D", "P1W", "P1M") in days. */
    private fun days(p: String): Int? = Regex("P(\\d+)([DWMY])").matchEntire(p)?.let { m ->
        val n = m.groupValues[1].toInt(); when (m.groupValues[2]) { "D" -> n; "W" -> 7 * n; "M" -> 30 * n; else -> 365 * n }
    }

    fun buy(activity: Activity, plan: ProPlan) {
        connected {
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build(),
            )).build()
            val r = client.launchBillingFlow(activity, params)
            if (r.responseCode != BillingClient.BillingResponseCode.OK) message = context.getString(R.string.pro_error, r.debugMessage)
        }
    }

    private fun handle(purchases: List<Purchase>, fromQuery: Boolean) {
        val ours = purchases.filter { p -> p.products.any { it == MONTHLY || it == YEARLY } }
        val active = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // A purchase not acknowledged in 3 days is refunded by Play.
        for (p in ours) if (p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        if (fromQuery || active) store(active)
        if (!fromQuery && active) message = context.getString(R.string.pro_thanks)
        if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }) message = context.getString(R.string.pro_pending)
    }
}
