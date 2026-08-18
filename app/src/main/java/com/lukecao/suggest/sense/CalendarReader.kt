package com.lukecao.suggest.sense

import android.content.Context
import android.provider.CalendarContract
import com.lukecao.suggest.rank.RankConfig

/**
 * Reads the calendar as two different kinds of evidence.
 *
 * The weak, statistical kind is "were you in a meeting?", which becomes an ordinary
 * context signal: apps you use during meetings get boosted when a meeting is imminent
 * and quietly demoted when one is not. That works retroactively over the whole
 * lookback window, because the calendar remembers the past and our own trail does not
 * have to.
 *
 * The strong kind is an event that names its app outright, through the
 * `CUSTOM_APP_PACKAGE` column the platform provides for exactly this, or through a
 * recognisable meeting link. That is not a hint to weigh against usage history; it is
 * an answer, and it gets a boost to match.
 *
 * Read-only, and nothing is stored. Titles and descriptions are scanned for links in
 * memory and discarded.
 */
object CalendarReader {

    /** A merged set of "a meeting is happening around now" windows, ordered and
     *  non-overlapping so membership is a binary search. */
    class Windows(private val starts: LongArray, private val ends: LongArray) {

        fun contains(ts: Long): Boolean {
            if (starts.isEmpty()) return false
            // Largest window whose start is <= ts.
            var lo = 0
            var hi = starts.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= ts) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found >= 0 && ts <= ends[found]
        }

        val isEmpty: Boolean get() = starts.isEmpty()

        companion object {
            val EMPTY = Windows(LongArray(0), LongArray(0))
        }
    }

    data class Result(
        val windows: Windows,
        /** Packages named by an event that is happening around now. */
        val imminentApps: Set<String>,
    )

    /** One range's worth of instances, before overlapping windows are merged. */
    private class Scan(
        val starts: List<Long>,
        val ends: List<Long>,
        val imminent: Set<String>,
    )

    /**
     * How long the historical half of the read is trusted for. The live half is widened
     * to match, so there is no window of time that neither covers.
     */
    private const val HISTORY_TTL_MS = 60 * 60_000L

    private var cachedHistory: Scan? = null
    private var cachedFrom = 0L
    private var cachedAt = 0L

    /**
     * @param installed the launchable catalog, so a link is only resolved to an app
     *   that is actually on the phone — three vendors claim `meet.google.com`.
     *
     * Two queries rather than one, because the two halves of the answer go stale at
     * completely different rates. Which app you are about to open has to be right *now*;
     * whether you were in a meeting a fortnight ago was settled a fortnight ago. This used
     * to be a single query across the whole lookback window, re-issued on every refresh —
     * three hundred times a day on the recorded trail, each one crossing a Binder into
     * the calendar provider and walking every instance of every recurring event for three
     * weeks, to produce a set of windows that had not changed since the last time.
     *
     * So the old part is read once an hour and kept, and the recent part is read every
     * time. The recent part deliberately reaches back a full [HISTORY_TTL_MS] past where
     * the cache ends: the cached half stops at the boundary it was built with, which by
     * now is up to an hour old, and an hour-wide live query is still a twentieth of a
     * three-week one.
     */
    fun read(context: Context, from: Long, to: Long, installed: Set<String>, now: Long): Result {
        val history = history(context, from, installed, now)
        // Everything the cache is allowed to be missing, plus the future.
        val live = scan(
            context,
            now - HISTORY_TTL_MS - RankConfig.CAL_AFTER_MS,
            // Far enough ahead to catch the meeting you are about to join.
            to + RankConfig.CAL_BEFORE_MS,
            installed,
            now,
        )
        if (history == null && live == null) return Result(Windows.EMPTY, emptySet())

        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        history?.let { starts += it.starts; ends += it.ends }
        live?.let { starts += it.starts; ends += it.ends }
        // Windows can be merged from both halves — an event spanning the boundary is
        // returned by both queries and the union is the same either way. `imminentApps`
        // comes from the live half alone, because it is the answer to a question about
        // this moment and a cached one would be up to an hour out of date. Nothing is
        // lost: a meeting running right now is inside the live range by definition.
        return Result(merge(starts, ends), live?.imminent ?: emptySet())
    }

    /**
     * The settled half: everything from [from] up to shortly before now.
     *
     * Re-read when it expires, and also when [from] moves *backwards*, which only a clock
     * going backwards can do and which would otherwise leave the cache short at the old
     * end.
     */
    private fun history(context: Context, from: Long, installed: Set<String>, now: Long): Scan? =
        synchronized(this) {
            val cached = cachedHistory
            if (cached != null && now - cachedAt in 0 until HISTORY_TTL_MS && cachedFrom <= from) {
                return@synchronized cached
            }
            // Stops where an event could still be relevant to *now*, so the live query
            // owns that case outright.
            val boundary = now - RankConfig.CAL_AFTER_MS
            val scanned = scan(context, from, boundary, installed, now)
                ?: return@synchronized null
            cachedHistory = scanned
            cachedFrom = from
            cachedAt = now
            scanned
        }

    /** Null when there is no calendar signal to be had at all — see below. */
    private fun scan(
        context: Context,
        from: Long,
        to: Long,
        installed: Set<String>,
        now: Long,
    ): Scan? {
        if (to <= from) return Scan(emptyList(), emptyList(), emptySet())
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            ALL_DAY,
            SELF_ATTENDEE_STATUS,
            CUSTOM_APP_PACKAGE,
            EVENT_LOCATION,
            DESCRIPTION,
            TITLE,
        )

        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        val imminent = HashSet<String>()

        val cursor = try {
            CalendarContract.Instances.query(context.contentResolver, projection, from, to)
        } catch (_: Throwable) {
            // No permission, no calendar provider, or a provider that refused. All of
            // them mean the same thing here: no calendar signal.
            null
        } ?: return null

        cursor.use { c ->
            while (c.moveToNext()) {
                // An all-day event is a label on the date, not something you attend,
                // and it would put every session of that day inside a meeting window.
                if (c.getInt(2) != 0) continue
                if (c.getInt(3) == STATUS_DECLINED) continue

                val begin = c.getLong(0)
                val end = c.getLong(1).coerceAtLeast(begin)
                val open = begin - RankConfig.CAL_BEFORE_MS
                val close = end + RankConfig.CAL_AFTER_MS
                starts += open
                ends += close

                if (now in open..close) {
                    val named = c.getString(4)
                    if (!named.isNullOrEmpty() && named in installed) {
                        imminent += named
                    } else {
                        imminent += appsFromLinks(
                            listOfNotNull(c.getString(5), c.getString(6), c.getString(7)),
                            installed,
                        )
                    }
                }
            }
        }

        return Scan(starts, ends, imminent)
    }

    /** Overlapping meetings are common and a union is all the signal needs. */
    private fun merge(startList: List<Long>, endList: List<Long>): Windows {
        if (startList.isEmpty()) return Windows.EMPTY
        val order = startList.indices.sortedBy { startList[it] }
        val starts = ArrayList<Long>(startList.size)
        val ends = ArrayList<Long>(startList.size)
        for (i in order) {
            val s = startList[i]
            val e = endList[i]
            if (starts.isNotEmpty() && s <= ends[ends.size - 1]) {
                ends[ends.size - 1] = maxOf(ends[ends.size - 1], e)
            } else {
                starts += s
                ends += e
            }
        }
        return Windows(starts.toLongArray(), ends.toLongArray())
    }

    private fun appsFromLinks(fields: List<String>, installed: Set<String>): Set<String> {
        if (fields.isEmpty()) return emptySet()
        val haystack = fields.joinToString(" ").lowercase()
        val out = HashSet<String>()
        for ((needle, candidates) in MEETING_LINKS) {
            if (!haystack.contains(needle)) continue
            candidates.firstOrNull { it in installed }?.let { out += it }
        }
        return out
    }

    /*
     * Column names as literals, which needs a word of explanation.
     *
     * `CalendarContract.EventsColumns` is protected, and Kotlin cannot reach a Java
     * interface's constants through a class that merely implements it — so neither
     * `EventsColumns.ALL_DAY` nor `Events.ALL_DAY` compiles, even though the second is
     * legal Java. These are the wire names of the provider contract: every app that
     * queries the calendar passes exactly these strings, so they cannot change without
     * breaking all of them. `Instances.BEGIN` and `END` are declared on the class
     * itself and are used normally above.
     */
    private const val ALL_DAY = "allDay"
    private const val SELF_ATTENDEE_STATUS = "selfAttendeeStatus"
    private const val CUSTOM_APP_PACKAGE = "customAppPackage"
    private const val EVENT_LOCATION = "eventLocation"
    private const val DESCRIPTION = "description"
    private const val TITLE = "title"

    /** `AttendeesColumns.ATTENDEE_STATUS_DECLINED`, protected for the same reason. */
    private const val STATUS_DECLINED = 2

    /**
     * Only the links worth being confident about. Several map to more than one package
     * because the same service ships under different names — Google Meet is `tachyon`
     * on a phone that came with it and `meetings` where it was installed later.
     */
    private val MEETING_LINKS: List<Pair<String, List<String>>> = listOf(
        "teams.microsoft.com" to listOf("com.microsoft.teams"),
        "teams.live.com" to listOf("com.microsoft.teams"),
        "zoom.us" to listOf("us.zoom.videomeetings"),
        "meet.google.com" to listOf(
            "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings",
        ),
        "webex.com" to listOf("com.cisco.webex.meetings"),
        "gotomeet" to listOf("com.gotomeeting"),
        "slack.com" to listOf("com.Slack"),
        "discord.gg" to listOf("com.discord"),
    )
}
