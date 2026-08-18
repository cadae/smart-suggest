package com.lukecao.suggest.work

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.Store
import com.lukecao.suggest.data.SuggestionCache
import com.lukecao.suggest.data.WidgetSettings
import com.lukecao.suggest.sense.ContextSampler
import com.lukecao.suggest.widget.Widgets
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The heartbeat. Drops a context breadcrumb, re-ranks, and pushes the result to
 * every placed widget of every size.
 *
 * Every 15 minutes, the floor WorkManager allows. That floor is the whole point of
 * this app: One UI's own indexer does the equivalent every 15 *hours*, which is why
 * its widget looks frozen for a day at a time.
 *
 * Also where the *upper* limit lives — [WidgetSettings.MIN_RERANK_GAP_MS]. Six or seven
 * paths can ask for a refresh and only this one knows whether one has just happened, so
 * this is the honest place to decide, rather than each caller guessing from a timestamp
 * that no run has written yet.
 *
 * Every step is independent and none of them can stop the others — see [stage]. Most of
 * what happens here is a call into the platform, and the platform is entitled to refuse:
 * a location provider, a notification listener that has been unbound, a widget removed
 * from the home screen half a second ago.
 */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /**
     * One run at a time, process-wide, and only then the check on whether this run is
     * wanted at all.
     *
     * The order is the point. [WidgetSettings.MIN_RERANK_GAP_MS] is enforced by reading a
     * timestamp and then acting on it, and that is only sound if no other run can be
     * between the two. Nothing about the request side guarantees that:
     * `refresh-periodic` and `refresh-now` are separate names, so WorkManager considers
     * them unrelated work and will start both on its executor at once. `KEEP` and
     * `REPLACE` order requests *within* one name and cannot see across two.
     *
     * The trail says it happened, roughly once an hour: fifteen pairs of runs in a day
     * one to two hundred milliseconds apart, which is two workers overlapping rather
     * than one worker running twice. Both read the same stale timestamp, both concluded
     * they were the first, both ranked, and both credited a set of impressions for a
     * single grid the user saw once. Under a lock the second one waits, re-reads a
     * timestamp the first has now written, and skips.
     *
     * A mutex is enough because there is one process. WorkManager will use another if
     * you ask it to, via `work-multiprocess`; this app does not, and if it ever did the
     * limit would have to move into the database to stay honest.
     */
    private companion object {
        val gate = Mutex()

        // Shared with Scheduler on purpose: the two halves of one decision are easier to
        // read as one log stream.
        const val TAG = "SuggestScheduler"
    }

    override suspend fun doWork(): Result {
        val queuedAt = SystemClock.elapsedRealtime()
        return gate.withLock {
            val waited = SystemClock.elapsedRealtime() - queuedAt
            // Only interesting when it is not zero, which is nearly always.
            if (waited >= 50L) Log.i(TAG, "waited ${waited}ms for a run already in flight")
            rerank()
        }
    }

    /** Holds [gate]. Reads the clock itself, because it may have waited to get here. */
    private suspend fun rerank(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        val reason = inputData.getString(Scheduler.KEY_REASON) ?: Scheduler.REASON_TIMER
        // Negative if the clock has been moved back, which is not "too soon".
        val since = now - Prefs.lastRefresh(ctx)
        val tooSoon = since in 0 until WidgetSettings.MIN_RERANK_GAP_MS

        if (tooSoon && !inputData.getBoolean(Scheduler.KEY_FORCE, false)) {
            // Success, not retry: this run is not needed, so there is nothing to come
            // back for. The period is untouched and the next event is free to try again.
            Log.i(TAG, "skipped $reason, re-ranked ${since}ms ago")
            return Result.success()
        }

        // Recorded by the run that actually happens rather than by whoever asked for
        // it: requests get replaced, dropped and coalesced, so the caller writing this
        // itself could leave a reason behind for a run that never occurred.
        Prefs.putLastTrigger(ctx, reason)

        // A forced run can still land inside the window, and impressions are negative
        // evidence: crediting one for a grid computed seconds ago would mean the app
        // teaching itself that apps it just promoted are being ignored. Nobody was
        // looking at the home screen — the settings screen was open, or a store was
        // installing something.
        val credit = !tooSoon && screenOn(ctx)

        stage("context sample") { ContextSampler.sampleAndStore(ctx, now) }
        stage("prune") { Store.of(ctx).prune(now) }
        stage("rank") { SuggestionCache.compute(ctx, now, creditImpressions = credit) }
        // After the stages that can fail, and unconditionally: a run that got this far
        // has done whatever it was going to do, and if ranking is failing every time then
        // repeating it on every notification for the rest of the day helps nobody. The
        // floor is what stops that becoming a loop.
        Prefs.putLastRefresh(ctx, now)
        stage("widget update") { Widgets.updateAll(ctx) }

        return Result.success()
    }

    /**
     * Runs one step of a refresh, and lets the rest of them happen if it throws.
     *
     * These four are independent: a location fix that times out into an exception has no
     * bearing on whether the ranking can be recomputed, and a widget that has been
     * removed mid-update has none on whether the tap was recorded. Before this, any one
     * of them threw out of `doWork` — which WorkManager catches, so nothing crashed, but
     * the run stopped there and the three steps after it silently did not happen. The
     * failure mode that matters is the last one: a widget left showing yesterday's grid
     * because the *sampler* failed.
     *
     * Never returns [Result.failure]. There is no state to roll back and nothing a retry
     * would do differently, and the next period is fifteen minutes away regardless.
     *
     * [CancellationException] is rethrown rather than logged. It is how WorkManager stops
     * a worker — the deadline passed, the constraints changed, the work was replaced —
     * and swallowing it would mean carrying on with a coroutine the framework has already
     * given up on. This is also why the body is not a `runCatching`: that catches it.
     */
    private suspend fun stage(name: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "$name failed, carrying on", t)
        }
    }

    /**
     * Whether anyone could have been looking.
     *
     * An impression is the claim "these eight apps were offered and none was opened",
     * which is only evidence of anything if the screen was on. Half of these runs happen
     * in the user's pocket at three in the morning, and counting those was quietly
     * teaching the ranker that its own best guesses get ignored — worst of all for
     * whatever currently ranks highest, since that is what is on the grid to be ignored.
     *
     * Interactive is not the same as *the home screen is visible*; the launcher does not
     * tell anyone that. It is the part the platform will answer, and it is the half that
     * was wrong. Defaults to crediting if the service cannot be reached, because losing
     * the signal entirely is worse than the occasional over-count it replaces.
     */
    private fun screenOn(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }
}
