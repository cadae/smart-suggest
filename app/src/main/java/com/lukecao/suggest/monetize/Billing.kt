package com.lukecao.suggest.monetize

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.lukecao.suggest.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "SuggestBilling"

/**
 * The one-off purchase that removes the ad bar, and the only thing this app sells.
 *
 * Owned by [com.lukecao.suggest.ui.MainActivity] and connected only while it is on
 * screen. Nothing in `work/`, `widget/`, `rank/` or `sense/` can reach this class, which
 * is the point: the refresh worker runs every 15 minutes in a process with no UI, and a
 * billing connection there would be a service bind and a network round-trip for an answer
 * nobody is waiting for. The widget reads [Prefs.adsRemoved] if it ever needs to, and
 * today it does not need to at all.
 *
 * A non-consumable one-time product. It is bought once, it is never consumed, and Play
 * keeps saying you own it forever — including on a new phone, which is what makes
 * "permanently" a promise the store can keep rather than one this app has to.
 */
class Billing(context: Context) : PurchasesUpdatedListener {

    private val app = context.applicationContext

    /**
     * Seeded from the cache rather than from `false`, so the first frame after launch is
     * right for somebody who has paid. Starting at `false` and correcting a second later
     * when Play answers would flash the ad bar at exactly the person who bought its
     * removal.
     */
    private val _state = MutableStateFlow(State(adsRemoved = Prefs.adsRemoved(app)))
    val state: StateFlow<State> = _state.asStateFlow()

    private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(this)
        // Required — the builder throws without it. Pending purchases are real for
        // one-time products: cash and bank-transfer payment methods in several countries
        // complete hours later, and the alternative to handling them is a purchase that
        // silently never arrives.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        // Play's service dies with Play Store updates and low-memory kills. Without this
        // the client stays dead until something calls startConnection again, and the
        // symptom is a Remove ads button that does nothing until the app is restarted.
        .enableAutoServiceReconnection()
        .build()

    data class State(
        /** The cached-then-confirmed entitlement. The ad bar reads only this. */
        val adsRemoved: Boolean,
        /**
         * Localised and currency-formatted by Play — `"$2.99"`, `"￥300"`. Never
         * assembled here: the price is set per country in the Console and a hardcoded
         * one would be wrong in most of them.
         */
        val price: String? = null,
        /** A payment Play has taken but not yet cleared. Grants nothing yet. */
        val pending: Boolean = false,
        /** The purchase sheet is open, or a purchase is being confirmed. */
        val buying: Boolean = false,
        /**
         * Why buying is impossible, when it is. Expected on a device with no Play
         * Store, and expected for the whole of local development until the product is
         * live in the Console — so it is a state the UI has to render, not an error.
         */
        val unavailable: String? = null,
    )

    /**
     * Connect, then ask Play two questions: what does this cost here, and has this person
     * already paid.
     *
     * Safe to call repeatedly. Called from `onStart` rather than `onCreate` so that
     * coming back from the purchase sheet re-checks.
     */
    fun start() {
        if (client.isReady) {
            queryPrice()
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.update { it.copy(unavailable = null) }
                    queryPrice()
                    refresh()
                } else {
                    // Not logged as an error. BILLING_UNAVAILABLE is the normal answer on
                    // a device without the Play Store, and it is also the answer for the
                    // whole of local development.
                    Log.i(TAG, "billing unavailable: ${result.responseCode} ${result.debugMessage}")
                    _state.update { it.copy(unavailable = describe(result)) }
                }
            }

            override fun onBillingServiceDisconnected() {
                // Deliberately does not touch adsRemoved. See [reconcile].
                Log.i(TAG, "billing service disconnected")
            }
        })
    }

    fun stop() {
        if (client.isReady) client.endConnection()
    }

    /**
     * Re-ask Play what is owned.
     *
     * This is the only thing that can *grant* the entitlement, and the only thing that can
     * take it away.
     */
    fun refresh() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases -> reconcile(result, purchases) }
    }

    fun buy(activity: Activity) {
        val product = details
        if (product == null) {
            // Either the price never arrived or the product is not live yet. Retrying the
            // fetch is more useful than a dialog explaining that we do not know the price.
            queryPrice()
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build(),
                ),
            )
            .build()
        _state.update { it.copy(buying = true) }
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow refused: ${result.responseCode} ${result.debugMessage}")
            _state.update { it.copy(buying = false, unavailable = describe(result)) }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                reconcile(result, purchases ?: emptyList())

            BillingClient.BillingResponseCode.USER_CANCELED ->
                _state.update { it.copy(buying = false) }

            // Play says owned, we did not think so — a reinstall, a restored device, or a
            // purchase that completed while the app was not running. Play is right.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _state.update { it.copy(buying = false) }
                refresh()
            }

            else -> {
                Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                _state.update { it.copy(buying = false) }
            }
        }
    }

    /**
     * Turn Play's answer into the entitlement, and **only when Play actually answered**.
     *
     * The whole design is in the first line. A non-OK response means the question was not
     * answered — no network, service disconnected, Play Store mid-update — and an
     * unanswered question must leave the cached `true` alone. Treating failure as "does
     * not own it" is the bug that shows ads to a paying customer on a plane, and it is the
     * easiest bug in billing to write, because the not-owned path and the do-not-know path
     * both arrive as an empty list.
     *
     * An OK response *is* allowed to revoke, and has to be: a refund or a chargeback comes
     * back as an OK query with the purchase gone, and there is no other signal for it.
     */
    private fun reconcile(result: BillingResult, purchases: List<Purchase>) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "purchase query not answered (${result.responseCode}), keeping cache")
            return
        }
        val ours = purchases.filter { REMOVE_ADS in it.products }
        val owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (owned != Prefs.adsRemoved(app)) Prefs.putAdsRemoved(app, owned)
        _state.update { it.copy(adsRemoved = owned, pending = pending, buying = false) }

        // Three days, or Play refunds it automatically and the customer is left having
        // paid, been refunded, and still seeing ads. Acknowledging is not optional and
        // there is no later opportunity to notice it was skipped.
        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { acknowledge(it) }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Left unacknowledged on purpose rather than retried in a loop. The next
                // start() re-queries and tries again, which is sooner than the three-day
                // window and cheaper than a retry schedule.
                Log.w(TAG, "acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun queryPrice() {
        if (!client.isReady) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(REMOVE_ADS)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "no product details: ${result.responseCode} ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            val product = found.productDetailsList.firstOrNull { it.productId == REMOVE_ADS }
            if (product == null) {
                // The commonest cause by a mile: the product exists in the Console but the
                // app has never been through a Play track, so Play has nothing to price.
                Log.i(TAG, "$REMOVE_ADS not offered here — is it active on a live track?")
                _state.update { it.copy(unavailable = NOT_OFFERED) }
                return@queryProductDetailsAsync
            }
            details = product
            _state.update {
                it.copy(
                    price = product.oneTimePurchaseOfferDetails?.formattedPrice,
                    unavailable = null,
                )
            }
        }
    }

    private fun describe(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play billing is not available on this device."
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
            "Could not reach Google Play. Check the connection and try again."
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "This version of Google Play cannot make in-app purchases."
        else -> "Google Play returned an error (${result.responseCode})."
    }

    companion object {
        /**
         * Must match the product ID created in the Play Console, under Monetise with Play
         * > Products > In-app products. There is no way to check that from here — a
         * mismatch reads exactly like a product that is not live yet.
         */
        const val REMOVE_ADS = "remove_ads"

        private const val NOT_OFFERED =
            "Not available yet. The purchase becomes available once the app is on a Play track."
    }
}
