package com.lukecao.suggest.rank

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/** One stretch of an app being in the foreground. */
data class Session(val pkg: String, val start: Long, val durationMs: Long)

/**
 * Turns the raw UsageStats event stream into foreground sessions with dwell time.
 *
 * This is deliberately built on [UsageStatsManager.queryEvents] rather than
 * `queryUsageStats`: the aggregated API only gives per-day totals, which throws
 * away the timestamps that the time-of-day and place terms need.
 */
object UsageReader {

    private const val TAG = "UsageReader"

    fun sessions(context: Context, from: Long, to: Long): List<Session> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()

        val events = try {
            usm.queryEvents(from, to)
        } catch (t: Throwable) {
            // Thrown when usage access has not been granted yet.
            Log.w(TAG, "queryEvents failed", t)
            return emptyList()
        } ?: return emptyList()

        val open = HashMap<String, Long>()
        val raw = ArrayList<Session>()
        val e = UsageEvents.Event()

        while (events.getNextEvent(e)) {
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    // Keep the earliest resume if several activities stack up.
                    open.putIfAbsent(pkg, e.timeStamp)

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    val start = open.remove(pkg) ?: continue
                    raw += Session(pkg, start, clamp(e.timeStamp - start))
                }
            }
        }

        // Whatever was still in the foreground when the window closed.
        for ((pkg, start) in open) raw += Session(pkg, start, clamp(to - start))

        return merge(raw)
    }

    private fun clamp(d: Long): Long = d.coerceIn(0L, RankConfig.MAX_SESSION_MS)

    /**
     * Collapses back-to-back sessions of the same package. Navigating between two
     * activities inside an app emits PAUSED/RESUMED and would otherwise be counted
     * as two separate launches, inflating apps with many screens.
     *
     * Grouping by package to do that scrambles the order, so the result is sorted
     * again on the way out: the sequence signal reads this list as a timeline, and
     * silently handing it a per-package one would have it learn nonsense.
     */
    private fun merge(raw: List<Session>): List<Session> {
        if (raw.size < 2) return raw.sortedBy { it.start }
        val byPkg = raw.groupBy { it.pkg }
        val out = ArrayList<Session>(raw.size)

        for ((pkg, list) in byPkg) {
            val sorted = list.sortedBy { it.start }
            var start = sorted[0].start
            var end = start + sorted[0].durationMs

            for (i in 1 until sorted.size) {
                val s = sorted[i]
                if (s.start - end <= RankConfig.SESSION_MERGE_GAP_MS) {
                    end = maxOf(end, s.start + s.durationMs)
                } else {
                    out += Session(pkg, start, clamp(end - start))
                    start = s.start
                    end = s.start + s.durationMs
                }
            }
            out += Session(pkg, start, clamp(end - start))
        }
        out.sortBy { it.start }
        return out
    }
}
