package com.lukecao.suggest.sense

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.lukecao.suggest.Permissions
import com.lukecao.suggest.data.CtxSample
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.Store
import com.lukecao.suggest.rank.RankConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.function.Consumer
import kotlin.coroutines.resume

/**
 * Drops one breadcrumb per refresh describing the situation the phone is in, so the
 * ranker can later ask "what was going on when I used this app?".
 *
 * Cheap by construction. The location half reads the OS cache first and only spends a
 * fix when that has gone stale, and then only from the fused or network provider —
 * never GPS. Everything else is a synchronous read of state the system already holds.
 *
 * The location half is gated on both its toggle and its permission, and when it is off
 * it writes nothing rather than a neutral value: a row claiming "not on WiFi" when we
 * never looked would be matched by every past session and dilute the trail.
 */
object ContextSampler {

    /** A cached fix newer than this is good enough; no need to spend a fix. */
    private const val CACHE_FRESH_MS = 10 * 60 * 1000L

    private const val FIX_TIMEOUT_MS = 20_000L

    /** The network callback fires almost immediately for a network already connected,
     *  so this is a ceiling on a wait that should not happen rather than a budget. */
    private const val SSID_TIMEOUT_MS = 3_000L

    suspend fun sampleAndStore(context: Context, now: Long) {
        val signals = Prefs.settings(context).signals

        var lat: Double? = null
        var lon: Double? = null
        var acc = 0f
        var ssid: String? = null
        var wifiFlags = 0

        if (signals.place && Permissions.hasLocation(context)) {
            location(context, now)?.let {
                lat = it.latitude
                lon = it.longitude
                acc = it.accuracy
            }
            // Deliberately not gated on getting a fix: the network name is useful on
            // its own, and it is the more precise of the two.
            val connected = onWifiNow(context)
            ssid = if (connected == true) wifiIdentity(context) else null
            // Marked known only when the answer is unambiguous: either we are on nothing,
            // or we are on something we can name. Being on no network is worth recording —
            // it is the clearest evidence there is of being out of the house — but
            // "connected, and the platform would not say to what" must not be stored as
            // the same thing, or every redacted read at home would read as a trip out.
            if (connected == false || ssid != null) wifiFlags = CtxSample.FLAG_WIFI_KNOWN
        }

        // Unconditional: both are synchronous reads of state the system already holds,
        // neither needs a permission, and neither says anything about you that the
        // usage access this app cannot work without does not already say.
        val flags = wifiFlags or
            CtxSample.deviceFlags(headphonesConnected(context), charging(context))

        // Device state is always known, so the row is always worth writing; the guard
        // stays as a floor in case that stops being true.
        if (lat == null && ssid == null && flags == 0) return

        Store.of(context).addSample(CtxSample(now, lat, lon, acc, ssid, flags))
    }

    // ------------------------------------------------------------------- location

    private suspend fun location(context: Context, now: Long): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        val cached = freshestCached(lm)
        val loc = if (cached != null && now - cached.time <= CACHE_FRESH_MS) {
            cached
        } else {
            requestOneFix(context, lm) ?: cached
        } ?: return null

        // A fix that predates the sample window would misattribute the breadcrumb,
        // which is worse than having no breadcrumb at all.
        return if (now - loc.time > RankConfig.HERE_MAX_AGE_MS) null else loc
    }

    /**
     * Suppressed because the permission is genuinely not declared, and lint is right
     * about that — it is just drawing the wrong conclusion.
     *
     * `ACCESS_COARSE/FINE_LOCATION` are commented out of the manifest while the place
     * family is unshipped, so lint sees a location call with no permission behind it and
     * reports an error. Every route to here is behind
     * [Permissions.LOCATION_DECLARED][com.lukecao.suggest.Permissions.LOCATION_DECLARED],
     * a compile-time `const val false`, so R8 deletes this method from the release build
     * outright. Belt and braces, the call already catches [SecurityException] and returns
     * null, so it would degrade to "no breadcrumb" rather than crash even if it were
     * somehow reached.
     *
     * Deleting the code instead would be worse: the two edits that bring the place family
     * back are uncommenting three manifest lines and flipping that constant, and this is
     * one of the things they bring back.
     */
    @SuppressLint("MissingPermission")
    private fun freshestCached(lm: LocationManager): Location? {
        var best: Location? = null
        for (p in providers()) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Unshipped while the place family is off — see [freshestCached]. */
    @SuppressLint("MissingPermission")
    private suspend fun requestOneFix(context: Context, lm: LocationManager): Location? {
        val provider = providers().firstOrNull { enabled(lm, it) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                        Consumer<Location> { location ->
                            if (cont.isActive) cont.resume(location)
                        },
                    )
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun enabled(lm: LocationManager, provider: String) =
        try {
            lm.isProviderEnabled(provider)
        } catch (_: IllegalArgumentException) {
            false
        }

    /** Cheapest useful provider first. GPS is deliberately absent. */
    private fun providers() = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    // ----------------------------------------------------------------------- wifi

    /**
     * A stable, opaque identifier for the network we are on.
     *
     * This is the better place signal of the two: "the same WiFi as last Tuesday" is
     * exact, where a coordinate match is a 250 m gaussian over a fix that may be a
     * street away. It costs no battery and needs no permission beyond the location one
     * already asked for.
     *
     * Hashed rather than stored, because the ranker only ever compares two of these for
     * equality and a list of network names is a list of the places you go.
     */
    private suspend fun wifiIdentity(context: Context): String? {
        val name = ssidFromCallback(context) ?: ssidFromWifiManager(context) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether we are on WiFi at all, regardless of whether the name is obtainable.
     *
     * Asked separately from [wifiIdentity] because the two nulls mean opposite things.
     * A synchronous capability read cannot be redacted — the transport type is not
     * location-sensitive, only the network's identity is — so this is the one part of
     * the WiFi signal that is always answerable.
     *
     * Null rather than false when the read itself fails. Returning false there would
     * reintroduce the bug one layer down: a failed read would be stored as the fact
     * "was on no network", which is precisely what a trip out looks like.
     */
    private fun onWifiNow(context: Context): Boolean? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            // No active network at all is an answer, and it is a "no".
            val network = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The un-redacted SSID, via a momentarily registered network callback.
     *
     * The obvious call, `WifiManager.getConnectionInfo()`, is redacted to
     * `UNKNOWN_SSID` unless the caller is in the foreground — and we sample from a
     * WorkManager job, so it is always redacted for us. Measured on the device: every
     * breadcrumb came back with no network name at all.
     *
     * This path is gated on holding fine location instead of on being foreground, which
     * is exactly the case the replacement API was added for. The callback fires for an
     * already-connected network as soon as it is registered, so this is a short wait
     * rather than a subscription, and it is unregistered in every exit path.
     */
    private suspend fun ssidFromCallback(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        // Unregistered in a finally rather than on cancellation alone, so the success
        // path lets go of it too: a subscription we forgot to drop would keep waking us
        // for every network change on the phone.
        var registered: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(SSID_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    val callback = object : ConnectivityManager.NetworkCallback(
                        // Without this the transport info is redacted even with the
                        // permission: the flag is how a caller says it wants the
                        // location-sensitive fields and accepts having the app-op noted.
                        FLAG_INCLUDE_LOCATION_INFO,
                    ) {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities,
                        ) {
                            val info = caps.transportInfo as? WifiInfo ?: return
                            if (cont.isActive) cont.resume(clean(info.ssid))
                        }
                    }
                    registered = callback
                    try {
                        cm.registerNetworkCallback(request, callback)
                    } catch (_: Throwable) {
                        // Too many callbacks registered, or no permission.
                        registered = null
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            registered?.let { unregister(cm, it) }
        }
    }

    /** Works when something of ours is on screen, and costs nothing to try. */
    @Suppress("DEPRECATION")
    private fun ssidFromWifiManager(context: Context): String? {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            clean(wm.connectionInfo?.ssid)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun unregister(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback) {
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
            // Already unregistered. Nothing to undo.
        }
    }

    /** What the platform hands back when it will not tell us is not an error, just an
     *  absent signal. */
    private fun clean(raw: String?): String? {
        val name = raw?.trim('"') ?: return null
        if (name.isEmpty() || name == WifiManager.UNKNOWN_SSID || name == "0x") return null
        return name
    }

    // --------------------------------------------------------------- device state

    /**
     * The device state right now, in the same flag layout the trail stores.
     *
     * The ranker asks for this directly instead of reading the newest breadcrumb: both
     * of these are instantaneous and free to query, and a twenty-minute-old answer
     * about whether headphones are plugged in is worse than useless.
     */
    fun deviceStateNow(context: Context): Int =
        CtxSample.deviceFlags(headphonesConnected(context), charging(context))

    /**
     * Anything you would listen through. Headphones are a strong statement about what
     * you are about to do — podcasts, music, video — and it is free to read.
     *
     * Not private because
     * [ScreenTriggers][com.lukecao.suggest.work.ScreenTriggers] watches for this exact
     * answer changing. The trigger and the signal have to agree on what counts as
     * headphones, or one of them fires for a device the other ignores.
     */
    internal fun headphonesConnected(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val devices = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Throwable) {
            return false
        }
        return devices.any { it.type in LISTENING_DEVICES }
    }

    private val LISTENING_DEVICES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private fun charging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true
}
