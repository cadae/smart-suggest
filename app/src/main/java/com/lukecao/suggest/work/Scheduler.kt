package com.lukecao.suggest.work

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.WidgetSettings
import java.util.concurrent.TimeUnit

/**
 * Decides when the widget re-ranks: on a fixed period, and on events.
 *
 * Two mechanisms, and they cover different things rather than the same thing twice.
 * WorkManager is the reliable floor and will not go below 15 minutes, which is fine
 * because it is also what keeps the context trail dense while the screen is off.
 * Events are the only way to react to something the moment it happens, and the
 * platform delivers them at its discretion. Lose the events and you are back to the
 * timer, which is a duller widget rather than a broken one.
 *
 * Both funnel into one uniquely-named one-shot, and every path that asks for a re-rank
 * comes through [refreshFor] or [refreshNow] — which differ only in whether the request
 * may be dropped. The rate limit itself lives in [RefreshWorker], because that is the
 * only place that knows a re-rank actually happened, and because the periodic work and
 * the one-shot are separate unique names that WorkManager is free to run at the same
 * moment — nothing decided here can order them against each other.
 *
 * There used to be a third: a chain of inexact alarms driving a 5- or 10-minute
 * interval. It went because it was paying battery for timing the OS was free to defer
 * anyway, and because the events it was standing in for are better answered directly —
 * a notification arriving is worth a re-rank, and the four minutes in which nothing
 * happened are not.
 */
object Scheduler {

    private const val TAG = "SuggestScheduler"

    private const val PERIODIC = "refresh-periodic"
    private const val ONE_SHOT = "refresh-now"

    /** Carries the trigger reason to the worker as input data. */
    const val KEY_REASON = "reason"

    /**
     * Set on a request the worker's rate limit must not drop.
     *
     * The distinction is whether the widget is stale in a way the user can see. A
     * notification arriving might move the ranking, and if it is ignored the next event
     * or the period covers it. A hidden app still sitting on the grid is a bug until the
     * next run, so those requests get through and are collapsed by a delay instead.
     */
    const val KEY_FORCE = "force"

    /** Reasons, kept short because they are shown in the app. */
    const val REASON_TIMER = "timer"
    const val REASON_NOTIFICATION = "a notification arrived"
    const val REASON_UNLOCK = "you unlocked the phone"
    const val REASON_HEADPHONES = "headphones changed"
    const val REASON_POWER = "charger changed"
    const val REASON_TIMEZONE = "the time zone changed"
    const val REASON_PACKAGES = "an app was installed or removed"
    const val REASON_SETTINGS = "you changed a setting"
    const val REASON_BOOT = "the phone restarted"
    const val REASON_UPDATE = "the app was updated"

    /** Everything that has to be re-established after a settings change or a reboot. */
    fun reschedule(context: Context) {
        ensurePeriodicRefresh(context)
        ensureEventReceiver(context)
    }

    /**
     * The same. Kept as its own name because a process start is not a lifecycle event —
     * the app is constructed again for every broadcast we receive, so this can run many
     * times an hour, and anything added here has to be idempotent and cheap.
     */
    fun onProcessStart(context: Context) {
        ensurePeriodicRefresh(context)
        ensureEventReceiver(context)
    }

    // ------------------------------------------------------------------ workmanager

    /**
     * [ExistingPeriodicWorkPolicy.UPDATE] rather than KEEP so a period changed in a
     * future version takes effect in place, without losing the work's history.
     */
    fun ensurePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            WidgetSettings.REFRESH_MINUTES.toLong(),
            TimeUnit.MINUTES,
        )
            // No network or charging constraints: everything this does is local,
            // so there is no reason to ever defer it.
            .setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    // --------------------------------------------------------------------- triggers

    /**
     * Turns the event receiver on and off at the component level rather than checking
     * the setting once the broadcast has already woken us. Off means the system stops
     * delivering, which is the difference between not reacting and not being told.
     */
    fun ensureEventReceiver(context: Context) {
        val on = Prefs.settings(context).eventTriggers
        val state = if (on) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context, EventTriggerReceiver::class.java)
        try {
            // Read before writing. This runs on every process start, and a component
            // state change is persisted by the package manager — not something to
            // rewrite dozens of times a day for no change.
            if (context.packageManager.getComponentEnabledSetting(component) == state) return
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not toggle event receiver", t)
        }
    }

    /**
     * Refresh because something happened, unless something already happened very
     * recently.
     *
     * The gap is what makes it safe to wire this to noisy broadcasts: twelve
     * notifications landing together produce one re-rank, not twelve. Checked here only
     * to avoid waking WorkManager at all — the timestamp it reads is written when a run
     * *finishes*, so during a burst every member of the burst still sees the old value
     * and gets through. [RefreshWorker] has the same check and is the one that counts.
     */
    fun refreshFor(context: Context, reason: String) {
        Log.i(TAG, "trigger: $reason")
        if (!Prefs.settings(context).eventTriggers) return
        val since = System.currentTimeMillis() - Prefs.lastRefresh(context)
        if (since in 0 until WidgetSettings.MIN_RERANK_GAP_MS) {
            Log.i(TAG, "trigger: $reason coalesced, ${since}ms since last")
            return
        }
        // KEEP, not REPLACE: the burst this is meant to survive is precisely the case
        // the check above cannot see, and replacing would mean each notification in it
        // cancelling the re-rank the one before it asked for. Fast enough arrivals could
        // starve the work entirely — never finishing, so never advancing the timestamp
        // that would have stopped them. Dropping a request instead is free: whatever the
        // run already in flight computes is a few hundred milliseconds newer than this
        // request, not older.
        enqueue(context, reason, force = false, ExistingWorkPolicy.KEEP, delayMs = 0L)
    }

    /**
     * Refresh for something that must be reflected: a settings change, the button in the
     * app, an app installed or removed, a widget placed, a reboot.
     *
     * Never dropped, and therefore delayed rather than immediate — see
     * [WidgetSettings.RERANK_DEBOUNCE_MS]. REPLACE while the request is still pending is
     * what collapses a run of them into one re-rank after the last one.
     */
    fun refreshNow(context: Context, reason: String = REASON_SETTINGS) {
        enqueue(
            context,
            reason,
            force = true,
            ExistingWorkPolicy.REPLACE,
            WidgetSettings.RERANK_DEBOUNCE_MS,
        )
    }

    private fun enqueue(
        context: Context,
        reason: String,
        force: Boolean,
        policy: ExistingWorkPolicy,
        delayMs: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(workDataOf(KEY_REASON to reason, KEY_FORCE to force))
            .apply { if (delayMs > 0L) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, policy, request)
    }
}
