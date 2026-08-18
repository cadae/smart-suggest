package com.lukecao.suggest.rank

import android.content.Context
import com.lukecao.suggest.data.Store

/**
 * The app's own copy of its usage history, and the only thing the ranker reads.
 *
 * [android.app.usage.UsageStatsManager.queryEvents] keeps roughly a week of raw events
 * and then discards them, which quietly capped the whole model: [RankConfig.LOOKBACK_DAYS]
 * asks for three weeks and got one, so the day-of-week term was working from about a
 * single observation per weekday and every earlier day of the user's history was thrown
 * away the moment the platform let go of it. Nothing else in the app could have noticed —
 * the ranker was handed a shorter list than it asked for and had no way to tell that
 * from a quiet fortnight.
 *
 * So: read the platform's window on every rank, upsert it into `sess`, and rank from the
 * table. The window read is the same call the ranker was already making, so this costs
 * one bounded insert per refresh and nothing else. What it buys is history that outlives
 * the platform's retention, which is also the precondition for measuring any of this —
 * a chronological evaluation needs more past than the thing being evaluated uses.
 *
 * Not a backup. Anything the platform forgets while the app is not running is gone: a
 * phone left off for a fortnight comes back to a hole, and the table will honestly show
 * one rather than interpolating over it.
 */
object SessionLog {

    /**
     * Sessions between [from] and [to], newest history folded in first.
     *
     * @param sync false to read the table as it stands, without consulting the platform.
     *   That is what a replay of a past moment wants: syncing there would both write
     *   during a read-only pass and pull in events from after the moment being replayed.
     */
    fun history(context: Context, from: Long, to: Long, sync: Boolean = true): List<Session> {
        val store = Store.of(context)
        if (sync) sync(context, store, from, to)
        return store.sessionsBetween(from, to)
    }

    /**
     * Folds the platform's current window into the table.
     *
     * Only rows from [REWRITE_MS] before the newest one already held are written, which
     * is both an optimisation and the thing that keeps the table from double-counting.
     * The rows are keyed on a session's start, and a start is stable — except at the
     * *beginning* of the read window, where [UsageReader]'s merge of back-to-back
     * sessions can only see the members that fall inside it, so the same underlying
     * session can come back with a later start than it was first stored under. That
     * boundary sits a week or more in the past. This one sits hours in the past. They
     * cannot meet, so no row is ever inserted twice under two different starts.
     *
     * [REWRITE_MS] is measured from the newest stored row rather than from the clock on
     * purpose: a phone that spent five hours in doze has a stale watermark, not a wider
     * window to redo, and anchoring on the clock would silently stop rewriting the very
     * sessions that were open when it went to sleep.
     */
    private fun sync(context: Context, store: Store, from: Long, to: Long) {
        val fresh = UsageReader.sessions(context, from, to)
        if (fresh.isEmpty()) return
        val newest = store.newestSessionTs()
        val floor = if (newest <= 0L) {
            // First run, or the table was just dropped: take the lot.
            Long.MIN_VALUE
        } else {
            // minOf, for the same reason Store.prune uses it: an RTC that has come up in
            // 2038 would otherwise put the watermark past every real session and store
            // nothing at all until the clock caught up with itself.
            minOf(to, newest) - REWRITE_MS
        }
        store.addSessions(fresh.filter { it.start >= floor })
    }

    /**
     * How far back a sync rewrites what it already holds.
     *
     * A session that was still in the foreground when the last sync read it was stored
     * with its duration truncated at that read, and only a later read knows how long it
     * actually lasted. Any such session started within [RankConfig.MAX_SESSION_MS] of
     * that sync — beyond which the duration has hit its clamp and there is nothing left
     * to correct — so that plus an hour of slack covers every row whose value can still
     * change.
     */
    private val REWRITE_MS = RankConfig.MAX_SESSION_MS + 60 * 60 * 1000L
}
