package com.lukecao.suggest.data

import android.content.Context
import com.lukecao.suggest.rank.RankConfig
import com.lukecao.suggest.rank.Ranker
import com.lukecao.suggest.widget.Widgets
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widget renders from here rather than ranking inline, so drawing stays cheap
 * and predictable. [RefreshWorker][com.lukecao.suggest.work.RefreshWorker] keeps
 * this warm; [getOrCompute] covers the cold cases (freshly placed widget, cache
 * older than the refresh interval).
 */
object SuggestionCache {

    /** As many as the largest widget size can show, so one snapshot serves every size
     *  placed on the home screen. */
    private const val CACHE_SIZE = Widgets.MAX_SLOTS

    data class Item(val pkg: String, val label: String)
    data class Snapshot(val items: List<Item>, val computedAt: Long)

    /**
     * @param creditImpressions whether the visible slots count as having been seen. False
     *   when this snapshot replaces one from seconds ago, which nobody had time to look
     *   at; see [RefreshWorker][com.lukecao.suggest.work.RefreshWorker]. The grid itself
     *   is written either way — the widget always shows the newest ranking.
     */
    fun compute(
        context: Context,
        now: Long = System.currentTimeMillis(),
        creditImpressions: Boolean = true,
    ): Snapshot {
        // What the user can actually see, which is the largest widget they have placed.
        // Everything below that is about the visible slots specifically: which apps got
        // an impression, which are sticky, and which one the exploration slot displaces.
        val slots = Widgets.maxSlots(context)
        // Ranked deeper than any widget shows: the tail fills a bigger size placed
        // between refreshes, and it is also the pool the exploration slot draws from.
        val depth = maxOf(CACHE_SIZE, slots + RankConfig.EXPLORE_POOL)
        val ranked = Ranker.rank(context, now, limit = depth, slots = slots)
            .map { Item(it.pkg, it.label) }
        val items = explore(context, ranked, slots)
        val snapshot = Snapshot(items, now)

        val json = JSONObject().apply {
            put("at", now)
            put(
                "items",
                JSONArray().apply {
                    snapshot.items.forEach {
                        put(JSONObject().put("p", it.pkg).put("l", it.label))
                    }
                },
            )
        }
        Prefs.putSnapshot(context, json.toString())

        val shown = snapshot.items.take(slots)
        // Feeds the stickiness term on the next run. Only the slots actually on the
        // widget count as "shown", so the cached tail does not get sticky. Written even
        // when the impression is not credited: this describes what the widget is showing,
        // not what the user has had a chance to ignore.
        Prefs.putLastShown(context, shown.map { it.pkg })
        if (creditImpressions) {
            try {
                Store.of(context).addImpressions(
                    now,
                    shown.withIndex().associate { it.value.pkg to it.index },
                )
            } catch (_: Throwable) {
                // A lost impression count is not worth failing a refresh over.
            }
        }
        return snapshot
    }

    /**
     * Hands the last visible slot to a candidate from below the cut, moving one place
     * along each refresh.
     *
     * Without this the feedback terms can only ever learn about apps that already rank,
     * which is a closed loop: nothing new is shown, so nothing new is tapped, so nothing
     * new ever ranks. It walks the pool on a counter rather than picking at random so
     * the widget stays a function of its inputs — the same moment twice gives the same
     * grid, which matters when you are trying to work out why something appeared.
     *
     * The demoted app swaps places with the promoted one rather than being dropped, so
     * a bigger grid still shows it and the ordering stays a permutation of the ranking.
     */
    private fun explore(context: Context, items: List<Item>, slots: Int): List<Item> {
        if (slots < 1 || items.size <= slots) return items
        val pool = minOf(items.size, slots + RankConfig.EXPLORE_POOL) - slots
        if (pool < 1) return items
        val pick = slots + Prefs.nextExploreCursor(context) % pool
        val last = slots - 1
        val out = ArrayList(items)
        out[last] = items[pick]
        out[pick] = items[last]
        return out
    }

    fun load(context: Context): Snapshot? {
        val raw = Prefs.snapshot(context) ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            val items = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                items += Item(it.getString("p"), it.getString("l"))
            }
            Snapshot(items, o.getLong("at"))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The cold path: a widget being drawn with no usable snapshot behind it.
     *
     * Tolerates twice the refresh interval rather than exactly one, because a snapshot is
     * written at the *end* of a run and read at the start of a draw. At exactly
     * [WidgetSettings.REFRESH_MS] anything that delayed the periodic work by a second —
     * Doze, which the recorded runs show deferring it by up to 85 minutes — makes every
     * draw in the gap rank from scratch on the main thread, which is the one thing this
     * cache exists to prevent. Half an hour of slack costs a grid that is at worst one
     * missed period stale; a widget redraw is not the place to notice that.
     *
     * Never credits impressions. A redraw is the launcher asking for the bitmap again —
     * rotating the screen, coming back to the home page, restoring after a low-memory
     * kill — and it can happen many times a minute. Counting each as "the user saw these
     * eight apps and opened none of them" is a demotion the user never earned, applied
     * fastest to whatever is currently ranked highest. Only
     * [RefreshWorker][com.lukecao.suggest.work.RefreshWorker] decides an impression
     * happened, because only it knows the grid is new.
     */
    fun getOrCompute(
        context: Context,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = WidgetSettings.REFRESH_MS * 2,
    ): Snapshot {
        val cached = load(context)
        if (cached != null && cached.items.isNotEmpty() && now - cached.computedAt <= maxAgeMs) {
            return cached
        }
        return compute(context, now, creditImpressions = false)
    }
}
