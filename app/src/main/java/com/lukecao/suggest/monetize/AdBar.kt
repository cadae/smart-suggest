package com.lukecao.suggest.monetize

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.lukecao.suggest.BuildConfig
import com.lukecao.suggest.data.Prefs

private const val TAG = "SuggestAds"

/**
 * The ad bar, anchored at the bottom of the main screen.
 *
 * Placed **outside** the settings list rather than as an item in it, for two reasons that
 * are both about `LazyColumn` rather than about ads. A list drops the composition of
 * anything scrolled out of view, so an `AdView` in an item would be built and destroyed
 * every time it passed the edge of the screen — each rebuild a fresh ad request, and a
 * request rate like that is what "invalid traffic" means. And an ad that scrolls is an ad
 * that can slide under a thumb as the list settles, which is the other half of the same
 * policy.
 */
@Composable
fun AdBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    var loaded by remember { mutableStateOf(false) }

    // Whether to hold the bar's height open before the first ad of this session arrives.
    // Starts true once a banner has filled on this install, which is what stops every
    // later launch from opening with a gap that closes a couple of seconds in. Cleared
    // again by a failed request, so a device that cannot fill still ends up with no band
    // rather than an empty one.
    var reserved by remember { mutableStateOf(Prefs.bannerEverFilled(context)) }

    // Anchored adaptive rather than the fixed 320x50 BANNER: the height comes back sized
    // to the device instead of scaled to it, which on a Fold's inner display is the
    // difference between a banner and a stretched postage stamp.
    val size = remember(widthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }

    val view = remember(size) {
        AdView(context).apply {
            adUnitId = BuildConfig.BANNER_UNIT_ID
            setAdSize(size)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    // Logged as well as the failure, so that silence in the log means
                    // "this composable never ran" rather than being ambiguous between
                    // that and "it worked". Without the pair, the only way to tell a
                    // working banner from an absent one is to look at the screen, and
                    // that is not always available — a phone can be locked, and the
                    // height below is zero until this fires. The size is in the message
                    // because it is the number that decides the layout.
                    Log.i(TAG, "banner loaded: ${size.width}x${size.height}dp")
                    loaded = true
                    // Latched on the first fill only, so the banner's own refresh timer
                    // does not write to disk every thirty seconds for the life of the
                    // screen.
                    if (!reserved) {
                        reserved = true
                        Prefs.putBannerEverFilled(context)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Not an error worth showing anybody. NO_FILL is routine, and so is
                    // every failure on a device with no connection.
                    Log.i(TAG, "banner failed: ${error.code} ${error.message}")
                    loaded = false
                    reserved = false
                }
            }
        }
    }

    // Requested once the SDK is up, and not before: an AdView loaded pre-initialisation
    // reports a zero height and does not recover without a second load.
    val ready = Ads.ready()
    LaunchedEffect(view, ready) {
        if (ready) view.loadAd(AdRequest.Builder().build())
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        // A banner refreshes itself on a timer, and the timer does not care that the
        // activity is in the background. Left unpaused it spends requests on ads nobody
        // can see, and those count as unviewed impressions against the unit's own stats.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view.pause()
                Lifecycle.Event.ON_RESUME -> view.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // AdView holds a WebView. Without this the process keeps it, and the leak is
            // the size of a browser rather than the size of a view.
            view.destroy()
        }
    }

    // Height is driven explicitly rather than left to wrap. An AdView measures to its
    // declared AdSize from the moment setAdSize is called, whether or not anything has
    // filled it — so "wrap content" reserves the full banner height on a device that will
    // never show one, and the result is a permanent empty band above the navigation bar.
    //
    // Zero until the first ad ever arrives, then held open from the first frame of every
    // launch after that. The two conditions are deliberately different because the two
    // situations are: on a device with no fill there is nothing to wait for and the band
    // should never exist, but on a device that fills routinely, collapsing to zero at
    // launch means about 2.5s of missing bar followed by the content jumping up by the
    // banner's height. Measured on the cover panel: first frame at +197ms, `banner loaded`
    // at +2.56s. That gap is also indistinguishable from the ads having been removed,
    // which is exactly how it gets reported.
    AndroidView(
        factory = { view },
        modifier = modifier
            .fillMaxWidth()
            .height(if (loaded || reserved) size.height.dp else 0.dp),
    )
}
