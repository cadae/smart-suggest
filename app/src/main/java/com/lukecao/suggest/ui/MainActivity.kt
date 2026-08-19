package com.lukecao.suggest.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lukecao.suggest.BuildConfig
import com.lukecao.suggest.Permissions
import com.lukecao.suggest.data.AppCatalog
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.SignalSettings
import com.lukecao.suggest.data.WidgetSettings
import com.lukecao.suggest.eval.Replay
import com.lukecao.suggest.monetize.AdBar
import com.lukecao.suggest.monetize.Ads
import com.lukecao.suggest.monetize.Billing
import com.lukecao.suggest.rank.Ranker
import com.lukecao.suggest.rank.Scored
import com.lukecao.suggest.sense.NotifListener
import com.lukecao.suggest.widget.Widgets
import com.lukecao.suggest.work.Scheduler
import com.lukecao.suggest.work.ScreenTriggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Setup, widget settings, and a live view of the ranker's output with the
 * per-signal breakdown, so you can check the scores actually track how you use
 * the phone.
 */
class MainActivity : ComponentActivity() {

    private val refreshKey = mutableIntStateOf(0)

    /**
     * Owned here, not by [com.lukecao.suggest.App], and connected only while this activity
     * is started. See [Billing] — the short version is that the other ways into this
     * process have no user in front of them.
     */
    private lateinit var billing: Billing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billing = Billing(this)
        // Consent first, then the SDK, and neither on the main thread. Started here rather
        // than lazily from the ad bar so that the round-trip overlaps with reading usage
        // stats instead of following it.
        //
        // Skipped outright for somebody who has paid. They are owed the absence of the ad
        // bar, but what they actually bought is the absence of everything behind it — a
        // WebView, a handshake with the ad server, and an advertising ID leaving the phone.
        // Hiding the bar while still initialising the SDK would keep all three. Read from
        // the cache rather than from Play so it holds offline; if the purchase is later
        // refunded, ads return on the next launch rather than mid-session, which is the
        // right way round.
        if (!Prefs.adsRemoved(this)) {
            Ads.start(this) { refreshKey.intValue++ }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val pay by billing.state.collectAsStateWithLifecycle()
                    Column(modifier = Modifier.fillMaxSize()) {
                        Screen(
                            refreshKey = refreshKey.intValue,
                            pay = pay,
                            onBuy = { billing.buy(this@MainActivity) },
                            modifier = Modifier.weight(1f),
                        ) { refreshKey.intValue++ }
                        if (!pay.adsRemoved) {
                            // navigationBarsPadding, because targetSdk 35 draws this
                            // window behind the system bars. Without it the bar sits under
                            // the navigation bar, which looks broken and — worse — puts a
                            // paid click target under the gesture area.
                            AdBar(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }
        maybeReplay()
    }

    override fun onStart() {
        super.onStart()
        billing.start()
    }

    override fun onDestroy() {
        billing.stop()
        super.onDestroy()
    }

    /**
     * Runs the evaluation from the command line, for when the phone is on the other end
     * of an adb cable and nobody is there to press the button:
     *
     *     adb shell am start -n com.lukecao.suggest/.ui.MainActivity --ez replay true
     *
     * The report goes to logcat under [Replay.TAG] either way, so `adb logcat -s
     * SuggestEval:I` is where to read it.
     *
     * Debug builds only. This activity is exported — it is the launcher entry — so
     * anything on the phone can send it this extra, and while a replay only reads the
     * app's own data and writes to its own log, an unshipped diagnostic has no business
     * being reachable in a release.
     *
     * Gated on [BuildConfig.DEBUG] rather than on `FLAG_DEBUGGABLE` specifically so that
     * it is a *compile-time* constant: both read the same truth, but only the constant
     * lets R8 prove the branch dead and drop [Replay] from the release APK entirely.
     * Verified by looking for the class in the release dex, not assumed.
     *
     * The extra is removed once acted on, because a standard-launch-mode activity keeps
     * the intent that started it and would otherwise replay on every rotation.
     *
     * Deliberately not scoped to the activity's lifecycle: this takes the better part of
     * a minute, longer than it takes to lock the phone and put it in a pocket, and a run
     * cancelled at the halfway mark reports nothing at all.
     */
    private fun maybeReplay() {
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra(EXTRA_REPLAY, false) != true) return
        intent.removeExtra(EXTRA_REPLAY)
        val app = applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            Replay.run(app, System.currentTimeMillis())
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission state, placed widgets and rankings all change while we are in
        // the background.
        refreshKey.intValue++
    }

    private companion object {
        const val EXTRA_REPLAY = "replay"
    }
}

@Composable
private fun Screen(
    refreshKey: Int,
    pay: Billing.State,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    bump: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ranked by remember { mutableStateOf<List<Scored>>(emptyList()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var computeMs by remember { mutableLongStateOf(0L) }
    var settings by remember { mutableStateOf(Prefs.settings(context)) }
    // Held here rather than inside EvalCard: a LazyColumn drops the composition of an
    // item scrolled out of view, and with it both the report and the coroutine producing
    // it. A minute-long job cannot live inside something that transient.
    var replayReport by remember { mutableStateOf<String?>(null) }
    var replaying by remember { mutableStateOf(false) }

    // Read on every recomposition rather than remembered: onResume bumps the key
    // after a trip to Settings, and a cached answer would still say "off".
    val hasUsage = Permissions.hasUsageAccess(context)
    val hasLoc = Permissions.hasLocation(context)
    val hasBgLoc = Permissions.hasBackgroundLocation(context)
    val hasCalendar = Permissions.hasCalendar(context)
    val hasNotifAccess = NotifListener.hasAccess(context)

    val locationRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { bump() }

    val calendarRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { bump() }

    // Keyed on the hidden set too: hiding an app has to drop it out of the list
    // below, or there is no feedback that the tap did anything.
    LaunchedEffect(refreshKey, settings.hidden, settings.hideSystemApps) {
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.Default) {
            Ranker.rank(context, System.currentTimeMillis(), limit = 20)
        }
        computeMs = System.currentTimeMillis() - started
        ranked = result
        // The picker below lists every launchable app, and hidden apps are gone from
        // the ranking by definition, so both need the catalog rather than the ranking.
        labels = withContext(Dispatchers.Default) {
            AppCatalog.launchable(context).mapValues { it.value.label }
        }
    }

    fun apply(next: WidgetSettings) {
        // Computed before `settings` is reassigned. This used to be read afterwards for
        // the trigger comparison, which made it always false — the runtime receiver
        // stayed registered after the switch went off, and only the manifest half of
        // the event triggers actually stopped.
        val triggersChanged = next.eventTriggers != settings.eventTriggers
        val rerank = next.hidden != settings.hidden ||
            next.hideSystemApps != settings.hideSystemApps ||
            next.signals != settings.signals
        // We asked the system to unbind us when this went off, and it will not come
        // back on its own.
        if (next.signals.notifications && !settings.signals.notifications) {
            NotifListener.rebind(context)
        }
        settings = next
        Prefs.putSettings(context, next)

        // The background colour is already satisfiable from the cache, so push it
        // straight to every placed widget for instant feedback.
        scope.launch { Widgets.updateAll(context) }
        if (triggersChanged) {
            // Enables or disables the manifest receiver as a component, so the system
            // stops delivering rather than waking us to ignore.
            Scheduler.reschedule(context)
            if (next.eventTriggers) {
                ScreenTriggers.register(context)
            } else {
                ScreenTriggers.unregister(context)
            }
        }
        // A different filter or signal set changes which apps belong in the snapshot.
        if (rerank) Scheduler.refreshNow(context)
    }

    // The status-bar height is added to the top padding rather than applied as a
    // statusBarsPadding modifier, because the two look different once the list scrolls.
    // A modifier insets the whole list, so the clock sits above an empty strip and
    // content is clipped at its lower edge; contentPadding insets only the content, so
    // the first item starts below the clock and later items pass under it. The second is
    // what every other edge-to-edge app does, and it is the reason the padding is here at
    // all rather than on the Column in onCreate.
    //
    // Needed since targetSdk 35, which made this window draw behind the system bars —
    // the sibling AdBar got its navigationBarsPadding at the time and this did not, so
    // the title has been sitting under the clock since. Not a targetSdk 36 change; 36
    // only removes the opt-out this app never used.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp + statusBar,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Smart Suggest", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            PermissionCard(
                title = "Usage access",
                granted = hasUsage,
                detail = "Required. Supplies the launch times and dwell durations everything else is built on.",
                buttonLabel = "Open usage access settings",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
            )
        }

        if (settings.signals.place && hasLoc) {
            item {
                PermissionCard(
                    title = "Location: allow all the time",
                    granted = hasBgLoc,
                    detail = "The refresh runs while nothing of ours is on screen. " +
                        "A while-in-use grant is refused then, so without this the place trail stays nearly empty. " +
                        "Set Location to \"Allow all the time\" in app permissions.",
                    buttonLabel = "Open app permissions",
                    onClick = { openAppSettings(context) },
                )
            }
        }

        item {
            Button(onClick = {
                Scheduler.refreshNow(context)
                bump()
            }) { Text("Refresh now") }
        }

        item {
            SignalsCard(
                settings = settings,
                hasLocation = hasLoc,
                hasCalendar = hasCalendar,
                hasNotifAccess = hasNotifAccess,
                onGrantLocation = {
                    locationRequest.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
                onGrantCalendar = { calendarRequest.launch(Manifest.permission.READ_CALENDAR) },
                onGrantNotifications = { openNotificationAccess(context) },
                onChange = { apply(it) },
            )
        }

        item { SizesCard(refreshKey) }
        item { BackgroundCard(settings) { apply(it) } }
        item { RefreshCard(settings) { apply(it) } }
        item { HiddenAppsCard(settings, labels) { apply(it) } }
        item { RemoveAdsCard(pay, onBuy) }

        if (BuildConfig.DEBUG) {
            item {
                EvalCard(replayReport, replaying) {
                    replaying = true
                    replayReport = null
                    scope.launch {
                        try {
                            replayReport = withContext(Dispatchers.Default) {
                                Replay.run(context, System.currentTimeMillis())
                            }
                        } finally {
                            replaying = false
                        }
                    }
                }
            }
        }

        item {
            val last = Prefs.lastRefresh(context)
            // LocalLocale rather than Locale.getDefault(): the latter is not observable
            // state, so a locale changed while this screen is open formats the timestamp
            // with the old one until something else happens to recompose. Compose's own
            // lint calls this an error as of 1.11, which is how it was found.
            val stamp = if (last == 0L) "never" else
                SimpleDateFormat("HH:mm:ss", LocalLocale.current.platformLocale)
                    .format(Date(last))
            Text(
                "Last background refresh: $stamp · ranked ${ranked.size} apps in ${computeMs}ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text("Ranking right now", style = MaterialTheme.typography.titleMedium)
            Text(
                "Each row is score · launches · dwell, then the usage prior and every " +
                    "signal that multiplied it, biggest mover first. Below 1.00 means " +
                    "that signal argued against the app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        itemsIndexed(ranked) { index, s ->
            ScoreRow(index + 1, s) {
                apply(settings.copy(hidden = settings.hidden + s.pkg))
            }
        }

        if (ranked.isEmpty() && hasUsage) {
            item {
                Text(
                    "No usage events yet. The OS starts recording once usage access is on — " +
                        "give it an hour of normal phone use.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The three signals that cost you something, with each one's permission state.
 *
 * This card used to hold eight switches. The other five — continuity and sequence,
 * weekly rhythm, tap and impression feedback, the exploration slot, headphones and
 * charging — are always on now and are not offered, because they cost nothing beyond
 * the usage access the app cannot work without, and a switch you have no basis to
 * choose between is not a setting, it is a question you have been left to answer.
 *
 * A toggle and its permission are separate. Switching one on does not ask for anything
 * on its own: the row says what is missing and offers the grant, so nothing is ever
 * requested as a side effect of a switch you flipped to see what it did.
 */
@Composable
private fun SignalsCard(
    settings: WidgetSettings,
    hasLocation: Boolean,
    hasCalendar: Boolean,
    hasNotifAccess: Boolean,
    onGrantLocation: () -> Unit,
    onGrantCalendar: () -> Unit,
    onGrantNotifications: () -> Unit,
    onChange: (WidgetSettings) -> Unit,
) {
    val sig = settings.signals
    fun set(next: SignalSettings) = onChange(settings.copy(signals = next))

    // The place row is only offered when the build actually asks for location. A switch
    // whose permission cannot be granted is not a setting, it is a broken control.
    val offered = listOf(sig.notifications, sig.calendar) +
        if (Permissions.LOCATION_DECLARED) listOf(sig.place) else emptyList()
    val on = offered.count { it }

    SettingsCard(
        title = "Ranking signals",
        subtitle = "$on of ${offered.size} on · each one needs a permission",
    ) {
        Text(
            "Everything that is free to measure is always on: total usage, time of day, " +
                "what you were just doing, which app follows which, weekly rhythm, " +
                "overdue apps, headphones and charging, and learning from what you tap " +
                "and ignore. The ones below are the ones that cost something — a " +
                "permission — so they are the ones worth deciding about. Switching one " +
                "off makes its term neutral rather than zero, so the ranking gets simpler " +
                "instead of worse.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(6.dp))
        HorizontalDivider()

        if (Permissions.LOCATION_DECLARED) {
            SignalRow(
                title = "Place",
                detail = "Where you are, the WiFi network as an exact place identity, and " +
                    "whether you are on the move. The only signal here that spends battery: " +
                    "it reads the OS location cache first and only asks for a fix when that " +
                    "has gone stale, and never from GPS. Coordinates are kept on the phone " +
                    "only, the network name is stored as a hash rather than a name, and " +
                    "movement is worked out from the distance between two readings rather " +
                    "than by asking for activity recognition.",
                checked = sig.place,
                blocked = sig.place && !hasLocation,
                blockedNote = "Needs location. Until it is granted this term is skipped.",
                onGrant = onGrantLocation,
                grantLabel = "Grant location",
            ) { set(sig.copy(place = it)) }
        }

        SignalRow(
            title = "Unopened notifications",
            detail = "The single strongest predictor that you are about to open an app — " +
                "weighted by how often you actually act on that app's notifications, " +
                "or the noisiest app on the phone would own the grid. Only which app " +
                "posted and when is ever read; not the title, the text or the sender.",
            checked = sig.notifications,
            blocked = sig.notifications && !hasNotifAccess,
            blockedNote = "Needs notification access, which is granted in Settings " +
                "rather than by a prompt. While this switch is off the service asks to " +
                "be unbound, so nothing is delivered to us at all.",
            onGrant = onGrantNotifications,
            grantLabel = "Open notification access",
        ) { set(sig.copy(notifications = it)) }

        SignalRow(
            title = "Meetings",
            detail = "Prefers the apps you use during meetings when one is imminent, and " +
                "opens the named app outright for an event that specifies one or " +
                "carries a recognisable meeting link. Read-only; nothing from an event " +
                "is stored.",
            checked = sig.calendar,
            blocked = sig.calendar && !hasCalendar,
            blockedNote = "Needs calendar access.",
            onGrant = onGrantCalendar,
            grantLabel = "Grant calendar",
        ) { set(sig.copy(calendar = it)) }
    }
}

@Composable
private fun SignalRow(
    title: String,
    detail: String,
    checked: Boolean,
    blocked: Boolean = false,
    blockedNote: String = "",
    onGrant: (() -> Unit)? = null,
    grantLabel: String = "Grant",
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.fillMaxWidth(0.02f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (blocked) {
            Spacer(Modifier.height(4.dp))
            Text(
                blockedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onGrant != null) {
                Button(onClick = onGrant) { Text(grantLabel) }
            }
        }
    }
}

/**
 * The sizes on offer, and how many of each are on a home screen.
 *
 * This replaces a columns-and-rows setting, which was the wrong shape for the question:
 * the host reserves cells for a widget when you place it and is never told the layout
 * changed afterwards, so a 4x2 asked to draw five rows simply drew them squashed. Each
 * size is its own provider now, and the picker — which every launcher already has — is
 * where you choose.
 *
 * @param key bumped on resume, so the placed counts are re-read after a trip to the
 *   home screen rather than being skipped as an unchanged parameter.
 */
@Composable
private fun SizesCard(key: Int) {
    val context = LocalContext.current
    // Nullable, and the two other callers in the app already treat it that way: the
    // platform hands back nothing on a device with no app widget service. Also asked
    // once rather than once per row — it is a binder call, and it used to be made
    // six times per recomposition of this card.
    val awm: AppWidgetManager? = AppWidgetManager.getInstance(context)
    val canPin = remember(key) { awm?.isRequestPinAppWidgetSupported == true }
    val sizes = remember { Widgets.all() }
    val placed = remember(key) { sizes.associate { it.slots to Widgets.placedCount(context, it) } }
    val total = placed.values.sum()

    SettingsCard(
        title = "Widget sizes",
        subtitle = if (total == 0) "None on the home screen yet" else "$total placed",
    ) {
        Text(
            "Each size is a separate widget and shows as many apps as it has cells. " +
                "They are not resizable on purpose: the size is what decides how many " +
                "suggestions there are, so it is fixed when you place one. Add as many " +
                "as you like — they all draw from the same ranking.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        sizes.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${w.cols} × ${w.rows} — ${w.slots} apps",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val n = placed[w.slots] ?: 0
                    Text(
                        if (n == 0) "Not placed" else if (n == 1) "1 on the home screen"
                        else "$n on the home screen",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (canPin) {
                    OutlinedButton(onClick = {
                        awm?.requestPinAppWidget(
                            ComponentName(context, w.receiver),
                            null,
                            null,
                        )
                    }) { Text("Add") }
                }
            }
        }

        if (!canPin) {
            Spacer(Modifier.height(6.dp))
            Text(
                "This launcher does not let an app place a widget for you. Long-press " +
                    "the home screen, open the widget picker and look for Smart Suggest.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HiddenAppsCard(
    settings: WidgetSettings,
    labels: Map<String, String>,
    onChange: (WidgetSettings) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    SettingsCard(
        title = "Hidden apps",
        subtitle = if (settings.hidden.isEmpty()) "None hidden by hand"
        else "${settings.hidden.size} hidden by hand",
        trailing = {
            Switch(
                checked = settings.hideSystemApps,
                onCheckedChange = { onChange(settings.copy(hideSystemApps = it)) },
            )
        },
    ) {
        Text(
            "The switch hides Settings, Device care, permission and installer " +
                "screens — things that rack up sessions without ever being worth a " +
                "shortcut.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            "Hide your dock apps by hand — the home screen's dock is private to One UI " +
                "Home, so no public API reports what is in it and it cannot be detected " +
                "automatically. The picker lists everything installed, not just what " +
                "currently ranks.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { picking = true }) { Text("Choose apps to hide") }

        if (settings.hidden.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            settings.hidden.sortedBy { labels[it] ?: it }.forEach { pkg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = labels[pkg] ?: pkg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                    Spacer(Modifier.fillMaxWidth(0.05f))
                    OutlinedButton(onClick = {
                        onChange(settings.copy(hidden = settings.hidden - pkg))
                    }) { Text("Unhide") }
                }
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            labels = labels,
            hidden = settings.hidden,
            onToggle = { pkg, hide ->
                onChange(
                    settings.copy(
                        hidden = if (hide) settings.hidden + pkg else settings.hidden - pkg,
                    ),
                )
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Every launchable app, searchable, with a tick for the ones being hidden.
 *
 * The ranking list at the bottom of the screen has a Hide button on each row, which
 * covers the common case — you hide what you can see turning up. It cannot cover the
 * uncommon one: an app you want out of the suggestions before it ever ranks well enough
 * to appear in the top twenty, which is exactly the app you would most want to
 * pre-empt.
 *
 * Each tick commits immediately rather than on a Done button. There is nothing to
 * cancel — the same tick reverses it — and a dialog that discards your choices if you
 * dismiss it is a worse surprise than one that does not.
 */
@Composable
private fun AppPickerDialog(
    labels: Map<String, String>,
    hidden: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // Our own entry is filtered out: hiding it would do nothing, since this app is
    // never a suggestion candidate in the first place.
    // The same non-observable-locale problem as the timestamp above, which lint does not
    // flag here because the call sits inside a `remember` lambda rather than directly in
    // the composable. It is a real one either way: a locale is what decides how these
    // sort, so it belongs in the key, or the list keeps yesterday's collation.
    val locale = LocalLocale.current.platformLocale
    val all = remember(labels, locale) {
        labels.entries
            .filter { it.key != context.packageName }
            .map { it.key to it.value }
            .sortedBy { it.second.lowercase(locale) }
    }
    // Matched on the package name as well as the label, because that is how you find
    // the second of two apps with the same name.
    val shown = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter {
            it.second.contains(q, ignoreCase = true) || it.first.contains(q, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Hide apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search ${all.size} apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text("Nothing matches that.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.first }) { (pkg, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(pkg, pkg !in hidden) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = pkg in hidden,
                                    onCheckedChange = { onToggle(pkg, it) },
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun BackgroundCard(settings: WidgetSettings, onChange: (WidgetSettings) -> Unit) {
    val alpha = (settings.backgroundArgb ushr 24) and 0xFF

    SettingsCard(
        title = "Background",
        subtitle = if (settings.backgroundEnabled) "$alpha/255 opacity" else "Off — fully transparent",
        trailing = {
            Switch(
                checked = settings.backgroundEnabled,
                onCheckedChange = { onChange(settings.copy(backgroundEnabled = it)) },
            )
        },
    ) {
        if (!settings.backgroundEnabled) return@SettingsCard

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetSettings.BACKGROUND_COLOURS.forEach { rgb ->
                val selected = (settings.backgroundArgb and 0xFFFFFF) == rgb
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF000000.toInt() or rgb))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable {
                            val keepAlpha = settings.backgroundArgb and 0xFF000000.toInt()
                            onChange(settings.copy(backgroundArgb = keepAlpha or rgb))
                        },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Opacity", style = MaterialTheme.typography.labelMedium)

        // Local state during the drag: committing on every pixel would fire a
        // widget update per frame.
        var dragging by remember(settings.backgroundArgb) { mutableFloatStateOf(alpha.toFloat()) }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val rgb = settings.backgroundArgb and 0xFFFFFF
                onChange(settings.copy(backgroundArgb = (dragging.toInt() shl 24) or rgb))
            },
            valueRange = 0f..255f,
        )
    }
}

@Composable
private fun RefreshCard(settings: WidgetSettings, onChange: (WidgetSettings) -> Unit) {
    val context = LocalContext.current
    val lastTrigger = remember(settings) { Prefs.lastTrigger(context) }

    SettingsCard(
        title = "Refresh",
        subtitle = "Every ${WidgetSettings.REFRESH_MINUTES} minutes" +
            if (settings.eventTriggers) ", and on events" else "",
    ) {
        Text(
            "Fifteen minutes is the shortest period the OS will schedule background " +
                "work at, so it is the interval, and there is no setting for it: " +
                "everything longer is a worse widget, and there was no honest way to go " +
                "shorter. The OS may still delay a run when the phone is idle, so treat " +
                "it as \"no staler than\" rather than a promise.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Refresh on events", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Re-rank when a signal changes rather than waiting out the " +
                        "interval: a notification arrives, headphones or the charger " +
                        "go in or out, you unlock the phone, an app is installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = settings.eventTriggers,
                onCheckedChange = { onChange(settings.copy(eventTriggers = it)) },
            )
        }

        if (settings.eventTriggers) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Which events the OS actually delivers is up to the OS and varies by " +
                    "phone. Unlock and headphone changes are the least certain: they " +
                    "need something of ours already running, which notification " +
                    "access provides. Anything that never arrives just leaves the " +
                    "timer underneath.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (lastTrigger != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last refresh: $lastTrigger",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Runs [Replay] and shows what it said.
 *
 * Debug builds only — not because it is risky, since it only reads the app's own tables
 * and writes nothing but a log — but because a page of Hit@4 percentages is a note to
 * whoever is tuning the model, not a setting.
 *
 * Logcat is the real venue. The report's lines are 85 characters wide, and what is on
 * screen here is 9sp with a sideways scroll: enough to check a number on the spot,
 * not enough to read the whole thing comfortably.
 */
@Composable
private fun EvalCard(report: String?, running: Boolean, onRun: () -> Unit) {
    SettingsCard(
        title = "Measure the ranking",
        subtitle = "Scores the ranker against what you actually opened",
    ) {
        Text(
            "Replays every app open in the stored history: ranks the moment just before " +
                "it from what was known then, and records where the app you went on to " +
                "open had landed. Takes about a minute, and needs a few days of history " +
                "before the numbers mean anything. Also logged under \"${Replay.TAG}\".",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onRun, enabled = !running) {
            Text(if (running) "Running…" else "Run replay")
        }
        if (report != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                report.trimEnd(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * The one thing this app sells, and the privacy-options entry the ad SDK requires.
 *
 * Reads only [Billing.State], so every branch here is a state Play actually reported.
 * Nothing is inferred from "the button did not work".
 */
@Composable
private fun RemoveAdsCard(pay: Billing.State, onBuy: () -> Unit) {
    val context = LocalContext.current
    // The activity, but only when a privacy-options row is actually owed — carried as a
    // nullable value rather than a boolean beside it, so the thing that needs an activity
    // and the thing that decides whether to show it cannot disagree.
    //
    // Owed only once consent has been given in a consent region, and null everywhere else,
    // so this is a conditional row rather than a permanent one. Recomputed on each
    // composition because answering the consent form changes the answer.
    val privacyHost = (context as? Activity)?.takeIf { Ads.privacyOptionsRequired(it) }

    SettingsCard(
        title = "Ads",
        subtitle = when {
            pay.adsRemoved -> "Removed — thank you"
            pay.pending -> "Payment pending with Google"
            else -> "One bar at the bottom of this screen"
        },
    ) {
        if (pay.adsRemoved) {
            Text(
                "The bar is gone for good, on this phone and on any phone signed in to " +
                    "the same Google account. Google Play remembers the purchase, not " +
                    "this app, so reinstalling does not lose it and there is nothing to " +
                    "restore.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "The widget itself never shows an ad — only this settings screen does, " +
                    "and only while it is open. Nothing about your usage, your apps or " +
                    "where you have been is sent to the advertiser: the ranking is " +
                    "computed on the phone and stays there.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Removing them is a single payment, once, forever. Not a subscription.",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))

            if (pay.pending) {
                Text(
                    "Google is still clearing the payment. The bar disappears by itself " +
                        "once that completes — there is nothing else to do, and paying " +
                        "again would charge twice.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(
                    onClick = onBuy,
                    // Disabled without a price rather than showing a button that opens
                    // nothing: no price means Play has not offered the product here.
                    enabled = pay.price != null && !pay.buying,
                ) {
                    Text(
                        when {
                            pay.buying -> "Opening Google Play…"
                            pay.price != null -> "Remove ads · ${pay.price}"
                            else -> "Remove ads"
                        },
                    )
                }
                if (pay.price == null && pay.unavailable != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(pay.unavailable, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (privacyHost != null) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "You chose what advertisers here may use. That choice can be changed at " +
                    "any time.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { Ads.showPrivacyOptions(privacyHost) }) {
                Text("Ad privacy options")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.fillMaxWidth(0.06f))
                trailing()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ScoreRow(position: Int, s: Scored, onHide: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.78f)) {
            Text("$position. ${s.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "%.2f · %d launches · %.0f min".format(
                    s.score, s.launches, s.dwellMinutes,
                ),
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                // Reads as the arithmetic that produced the score, so a surprising
                // position can be traced to the term responsible.
                text = "usage %.2f".format(s.prior) +
                    s.factors.take(MAX_FACTORS_SHOWN)
                        .joinToString("") { " · %s %.2fx".format(it.name, it.value) },
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.fillMaxWidth(0.04f))
        TextButton(onClick = onHide) { Text("Hide") }
    }
}

/** Enough to explain a position; the tail is all near-neutral by construction. */
private const val MAX_FACTORS_SHOWN = 6

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

/**
 * Notification access has no runtime prompt; it is a trip to Settings either way. The
 * detail screen lands on our own entry, which beats leaving you to find it in a list of
 * every app on the phone — but not every vendor implements it, hence the fallback.
 */
private fun openNotificationAccess(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, NotifListener::class.java).flattenToString(),
        )
    try {
        context.startActivity(direct)
    } catch (_: Throwable) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    detail: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (granted) "$title — on" else "$title — off",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClick) { Text(buttonLabel) }
            }
        }
    }
}
