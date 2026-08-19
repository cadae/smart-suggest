package com.lukecao.suggest.monetize

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SuggestAds"

/**
 * Consent, then initialisation, then nothing — the ad bar itself is [AdBar].
 *
 * **Deliberately not called from [com.lukecao.suggest.App].** The Application object is
 * constructed on every entry into the process, and most entries into this process have no
 * UI attached: the 15-minute WorkManager refresh, the boot receiver, the package-change
 * receiver, the screen and power triggers, and the launch trampoline. Initialising the ads
 * SDK there would mean disk reads, a network handshake and a wakelock's worth of work,
 * ninety-six times a day, for a banner nobody is looking at. It is called from
 * [com.lukecao.suggest.ui.MainActivity] and nowhere else.
 *
 * That separation is also what keeps the widget honest. Play policy forbids ads in a
 * widget or a notification, and the widget path never links any of this.
 */
object Ads {

    /**
     * Process-wide, and the reason this object is stateful at all.
     *
     * `MobileAds.initialize` is documented as safe to call twice, and the consent flow is
     * not: `loadAndShowConsentFormIfRequired` twice in a row can put two forms on screen.
     * Every rotation recreates the activity, so "once per activity" is not once.
     */
    private val started = AtomicBoolean(false)

    /**
     * Snapshot state, not the `@Volatile Boolean` this used to be, and the difference is a
     * shipping bug rather than a style preference.
     *
     * [AdBar] reads this *during composition*. A plain field is invisible to Compose, so
     * nothing invalidates when it flips — the composable only re-read it because an
     * unrelated key recomposed the lambda it sits in, and because `AdBar` happened not to be
     * skipped. Both of those are accidents, and one of them stopped being true: after the
     * Compose 1.11 upgrade the SDK initialised, `ready` went true, and the banner never
     * requested at all. What that leaves on screen is an `AdView` at exactly the right size
     * with nothing in it, and *silence* in the log, because the load is never issued and so
     * neither `onAdLoaded` nor `onAdFailedToLoad` ever fires.
     *
     * Snapshot state makes the dependency real. See README, *The banner stopped requesting
     * when Compose got better at skipping*.
     */
    private val readyState = mutableStateOf(false)

    /**
     * True once the SDK is initialised and an ad may be requested. Read by [AdBar] to
     * decide whether to build an `AdView` yet; a load issued before this is queued by the
     * SDK rather than dropped, but a view built before it has nothing to show and a
     * measured height of zero.
     */
    fun ready(): Boolean = readyState.value

    /**
     * Gather consent if it is needed, then initialise.
     *
     * Order matters and is not a style choice. Requesting ads before consent has been
     * resolved is a policy violation in the EEA and the UK, and the failure mode is
     * invisible from Australia: the form never appears in testing, so nothing looks wrong.
     * [ConsentInformation.canRequestAds] is the gate, and it answers true immediately for
     * everyone outside a consent region — so this costs a callback, not a dialog, for most
     * users.
     *
     * Failures fall through to initialising anyway when `canRequestAds` allows it. A
     * consent lookup that could not reach the network is not consent denied, and treating
     * it as denied would mean a first run on a bad connection never shows an ad again.
     */
    fun start(activity: Activity, onReady: () -> Unit = {}) {
        if (readyState.value) {
            onReady()
            return
        }
        if (!started.compareAndSet(false, true)) return

        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consent.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Loads and shows a form only where one is required and not yet answered.
                // Outside a consent region this calls straight back with no UI.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) {
                        Log.i(TAG, "consent form: ${error.errorCode} ${error.message}")
                    }
                    initialize(activity, consent, onReady)
                }
            },
            { error ->
                Log.i(TAG, "consent lookup failed: ${error.errorCode} ${error.message}")
                initialize(activity, consent, onReady)
            },
        )
    }

    /**
     * Whether a "Privacy options" entry has to be offered.
     *
     * Required in consent regions once consent has been given — the user has to be able to
     * change their mind, and Google checks for it. False everywhere else, which is why the
     * settings row is conditional rather than always shown.
     */
    fun privacyOptionsRequired(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity)
            .privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.i(TAG, "privacy form: ${error.errorCode} ${error.message}")
        }
    }

    private fun initialize(
        activity: Activity,
        consent: ConsentInformation,
        onReady: () -> Unit,
    ) {
        if (!consent.canRequestAds()) {
            Log.i(TAG, "consent withheld — not initialising")
            // Left as started-but-not-ready on purpose. Retrying on the next launch is
            // right; retrying on the next recomposition is a form loop.
            return
        }
        // On a background thread as well as behind the manifest's
        // OPTIMIZE_INITIALIZATION flag. initialize() reads several files and can block for
        // hundreds of milliseconds on a cold start, and this activity is the launcher
        // entry — the main thread here is the thing between a tap and a window.
        Thread {
            Log.i(TAG, "initialising the ads SDK")
            MobileAds.initialize(activity.applicationContext) { status ->
                // Logged for the same reason AdBar logs both outcomes: without it, a banner
                // that never appears is ambiguous between "the SDK never finished starting"
                // and "the request failed", and those have nothing in common. The adapter
                // statuses are in the message because a NOT_READY adapter is the SDK's own
                // account of why it is not serving.
                Log.i(
                    TAG,
                    "ads SDK ready: " + status.adapterStatusMap.entries.joinToString {
                        "${it.key}=${it.value.initializationState}"
                    },
                )
                // Both on the main thread: the state write is what recomposes AdBar, and
                // doing it here keeps the write and the callback in one order rather than
                // two racing ones.
                activity.runOnUiThread {
                    readyState.value = true
                    onReady()
                }
            }
        }.apply { name = "ads-init" }.start()
    }
}
