package com.appdwg.app

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
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
 * trusted offline for [OFFLINE_DAYS] after the last check with Play. It is checked again when the
 * app starts, whenever it resumes, and immediately when a purchase comes back from Play (an empty
 * purchase update re-queries instead of being ignored). Debug builds can simulate Free or Pro.
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

    fun simulate(s: Simulation) { if (debuggable) { simulation = s; prefs.edit { putInt("sim", s.ordinal) } } }

    private fun store(active: Boolean) {
        licensed = active; lastCheck = System.currentTimeMillis()
        prefs.edit {
            putBoolean("active", active)
            putLong("checked", lastCheck)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = EntitlementSession()
    private val gate = Any()
    private val waiters = ArrayDeque<() -> Unit>()
    private var connecting = false

    /** Billing callbacks arrive off the main thread; Compose only sees state written on it. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            onMain {
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK ->
                        // Play sends null when the update has to be read with queryPurchases.
                        if (purchases.isNullOrEmpty()) refresh() else handle(purchases, fromQuery = false, gen = session.generation)
                    BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                    else -> message = context.getString(R.string.pro_error, result.debugMessage)
                }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()

    /**
     * One connection at a time. A second [BillingClient.startConnection] while the first is still
     * connecting is rejected by Play and would drop that caller's query.
     */
    private fun connected(then: () -> Unit) {
        val action = synchronized(gate) {
            if (client.isReady) 1
            else {
                waiters.add(then)
                if (connecting) 0 else { connecting = true; 2 }
            }
        }
        when (action) {
            1 -> then()
            2 -> client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                        val jobs = synchronized(gate) { connecting = false; waiters.toList().also { waiters.clear() } }
                        if (jobs.isEmpty()) refresh() else jobs.forEach { it() }
                    } else {
                        val already = r.debugMessage.contains("already in the process of connecting")
                        synchronized(gate) { connecting = false }
                        if (already) mainHandler.postDelayed({ drainOrRetry() }, 300) else failSetup(r)
                    }
                }
                override fun onBillingServiceDisconnected() { synchronized(gate) { connecting = false } }
            })
        }
    }

    private fun drainOrRetry() {
        if (client.isReady) {
            val jobs = synchronized(gate) { waiters.toList().also { waiters.clear() } }
            if (jobs.isEmpty()) refresh() else jobs.forEach { it() }
        } else {
            val pending = synchronized(gate) { waiters.toList().also { waiters.clear() } }
            pending.forEach { connected(it) }
        }
    }

    private fun failSetup(r: BillingResult) {
        synchronized(gate) { waiters.clear() }
        onMain {
            message = context.getString(R.string.pro_unavailable, r.debugMessage.ifBlank { r.responseCode.toString() })
            busy = false
        }
    }

    /**
     * Checks the subscription with Play: at start, on resume, right after a purchase update, and
     * on "Restaurar compra". A failed check leaves the cached license in place.
     */
    fun refresh(userAsked: Boolean = false) {
        val gen = session.nextQuery()
        if (userAsked) onMain { busy = true; message = null }
        connected {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
                onMain {
                    busy = false
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                        handle(list, fromQuery = true, gen = gen)
                        if (userAsked) message = context.getString(if (isPro) R.string.pro_restored else R.string.pro_none_found)
                    }
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
                onMain {
                    if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                        message = context.getString(R.string.pro_unavailable, r.debugMessage); return@onMain
                    }
                    plans = result.productDetailsList.mapNotNull { d -> plan(d) }.sortedBy { if (it.productId == MONTHLY) 0 else 1 }
                    if (plans.isEmpty()) message = context.getString(R.string.pro_no_plans)
                }
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
            onMain {
                val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build(),
                )).build()
                val r = client.launchBillingFlow(activity, params)
                if (r.responseCode != BillingClient.BillingResponseCode.OK) message = context.getString(R.string.pro_error, r.debugMessage)
            }
        }
    }

    private fun handle(purchases: List<Purchase>, fromQuery: Boolean, gen: Int) {
        val subs = purchases.map { ProEntitlement.from(it) }
        val active = ProEntitlement.active(subs)
        for (p in purchases) if (ProEntitlement.needsAcknowledge(ProEntitlement.from(p))) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        if (!fromQuery && active) {
            // Drop a query that started before this purchase and might still return empty.
            session.notePurchase()
            store(true)
            message = context.getString(R.string.pro_thanks)
        } else if (fromQuery && session.acceptQuery(gen)) {
            store(active)
        }
        if (subs.any { it.state == Purchase.PurchaseState.PENDING && it.productIds.any { id -> id == MONTHLY || id == YEARLY } }) {
            message = context.getString(R.string.pro_pending)
        }
    }
}
