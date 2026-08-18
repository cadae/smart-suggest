package com.lukecao.suggest.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lukecao.suggest.rank.RankConfig
import com.lukecao.suggest.rank.Session

/**
 * One observation of what was going on around the phone, dropped once per refresh.
 *
 * Everything here is retroactively matched to past app sessions by timestamp, which
 * is the whole reason it is sampled on a schedule rather than read on demand: at rank
 * time we can find out where you are now, but only a stored trail can say where you
 * were when you used an app three days ago.
 */
data class CtxSample(
    val ts: Long,
    /** Null when location is unavailable or its signal is switched off. */
    val lat: Double?,
    val lon: Double?,
    val accuracyMeters: Float,
    /** An opaque hash of the connected WiFi network — an exact place identity where
     *  the coordinates are only a 250 m guess. Null when not on WiFi, and also null
     *  when we could not tell; [FLAG_WIFI_KNOWN] is what separates the two. Hashed
     *  because equality is all the ranker needs, and a network name is a home address. */
    val ssid: String?,
    val flags: Int,
) {
    val hasLocation: Boolean get() = lat != null && lon != null

    /** Distinguishes "no headphones, not charging" from "we did not look". */
    val hasDeviceState: Boolean get() = flags and FLAG_STATE_KNOWN != 0
    val headphones: Boolean get() = flags and FLAG_HEADPHONES != 0
    val charging: Boolean get() = flags and FLAG_CHARGING != 0

    /**
     * Whether [ssid] is an answer rather than a gap.
     *
     * Being on no network at all is one of the strongest place signals there is — it is
     * what leaving the house looks like — but a null ssid on its own cannot carry it,
     * because the platform also hands back nothing when it declines to name a network we
     * are demonstrably connected to. Without this bit the ranker had to skip both cases,
     * which meant the wifi lift was 1.0 for every app forever.
     */
    val hasWifiState: Boolean get() = flags and FLAG_WIFI_KNOWN != 0

    companion object {
        const val FLAG_HEADPHONES = 1
        const val FLAG_CHARGING = 2
        const val FLAG_STATE_KNOWN = 4
        const val FLAG_WIFI_KNOWN = 8

        fun deviceFlags(headphones: Boolean, charging: Boolean): Int =
            FLAG_STATE_KNOWN or
                (if (headphones) FLAG_HEADPHONES else 0) or
                (if (charging) FLAG_CHARGING else 0)
    }
}

/**
 * The ranker's own memory: everything UsageStats cannot tell us.
 *
 * Six small tables — the app-session history, a context trail, widget taps, impression
 * counts, notification posts, and which notifications are still outstanding. Raw SQLite
 * rather than Room on purpose: the schema is narrow and this keeps an annotation
 * processor out of a build that has no Gradle wrapper.
 *
 * Nothing here ever leaves the device; the app declares no network permission at all.
 *
 * One instance per process — see [of]. Not constructible any other way, because more
 * than one helper over the same file means more than one connection, and two
 * connections can genuinely fail against each other where one cannot.
 */
class Store private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    data class Tap(val ts: Long, val pkg: String, val slot: Int)

    /** Decayed impression and tap counts for one app, from the daily buckets. */
    data class Impressions(val shown: Int, val slotSum: Int)

    data class Notif(val ts: Long, val pkg: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SESS_TABLE)
        db.execSQL(
            "CREATE TABLE ctx(" +
                "ts INTEGER PRIMARY KEY, lat REAL, lon REAL, acc REAL, " +
                "ssid TEXT, flags INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE tap(ts INTEGER PRIMARY KEY, pkg TEXT NOT NULL, " +
                "slot INTEGER NOT NULL DEFAULT -1)"
        )
        db.execSQL("CREATE INDEX idx_tap_pkg ON tap(pkg)")
        // Aggregated per day rather than one row per impression: eight slots every
        // fifteen minutes is 768 rows a day, and the decay only needs day resolution.
        db.execSQL(
            "CREATE TABLE impr(day INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                "n INTEGER NOT NULL, slotsum INTEGER NOT NULL, PRIMARY KEY(day, pkg))"
        )
        db.execSQL("CREATE TABLE notif(ts INTEGER NOT NULL, pkg TEXT NOT NULL, PRIMARY KEY(ts, pkg))")
        db.execSQL("CREATE INDEX idx_notif_pkg ON notif(pkg)")
        // Keyed by the platform's own notification key so a removal can find its post
        // again. Not a subset of `notif`: this one shrinks.
        db.execSQL(
            "CREATE TABLE pending(key TEXT PRIMARY KEY, pkg TEXT NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    /**
     * v1 held only `loc` and a slot-less `tap`. The trail is worth carrying forward —
     * it takes weeks to rebuild — and the taps even more so, since they are the user's
     * own feedback rather than something we can re-derive.
     *
     * v3 adds `sess`. Nothing to migrate: the first sync backfills it from whatever the
     * platform still holds, which is about a week.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE ctx(" +
                    "ts INTEGER PRIMARY KEY, lat REAL, lon REAL, acc REAL, " +
                    "ssid TEXT, flags INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("INSERT INTO ctx(ts, lat, lon, acc) SELECT ts, lat, lon, acc FROM loc")
            db.execSQL("DROP TABLE loc")
            db.execSQL("ALTER TABLE tap ADD COLUMN slot INTEGER NOT NULL DEFAULT -1")
            db.execSQL(
                "CREATE TABLE impr(day INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                    "n INTEGER NOT NULL, slotsum INTEGER NOT NULL, PRIMARY KEY(day, pkg))"
            )
            db.execSQL(
                "CREATE TABLE notif(ts INTEGER NOT NULL, pkg TEXT NOT NULL, PRIMARY KEY(ts, pkg))"
            )
            db.execSQL("CREATE INDEX idx_notif_pkg ON notif(pkg)")
            db.execSQL(
                "CREATE TABLE pending(key TEXT PRIMARY KEY, pkg TEXT NOT NULL, ts INTEGER NOT NULL)"
            )
        }
        if (oldVersion < 3) db.execSQL(SESS_TABLE)
    }

    // -------------------------------------------------------------- session history

    /**
     * Adds one sync's worth of foreground sessions, replacing any already held with the
     * same start.
     *
     * Replace rather than ignore, because a session read while the app was still in the
     * foreground was recorded with a duration truncated at the read, and the next sync
     * knows how long it really lasted. Two reads of one session always agree on its
     * start, which is why that is the key: the start is an
     * [ACTIVITY_RESUMED][android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED]
     * timestamp, fixed once it has happened.
     */
    fun addSessions(sessions: List<Session>) {
        if (sessions.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues(3)
            for (s in sessions) {
                v.put("ts", s.start)
                v.put("pkg", s.pkg)
                v.put("dur", s.durationMs)
                db.insertWithOnConflict("sess", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Ascending by start. The ranker reads this as a timeline. */
    fun sessionsBetween(from: Long, to: Long): List<Session> {
        val out = ArrayList<Session>()
        readableDatabase.query(
            "sess", arrayOf("ts", "pkg", "dur"),
            "ts BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()),
            null, null, "ts ASC",
        ).use { c ->
            while (c.moveToNext()) out += Session(c.getString(1), c.getLong(0), c.getLong(2))
        }
        return out
    }

    /** Start of the newest session held, or 0 when there are none. */
    fun newestSessionTs(): Long = newestTs(readableDatabase, "sess")

    // ------------------------------------------------------------------ context trail

    fun addSample(s: CtxSample) {
        val v = ContentValues(6).apply {
            put("ts", s.ts)
            if (s.lat != null) put("lat", s.lat) else putNull("lat")
            if (s.lon != null) put("lon", s.lon) else putNull("lon")
            put("acc", s.accuracyMeters)
            if (s.ssid != null) put("ssid", s.ssid) else putNull("ssid")
            put("flags", s.flags)
        }
        writableDatabase.insertWithOnConflict("ctx", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Ascending by timestamp — [com.lukecao.suggest.rank.Ranker] binary-searches this. */
    fun samplesBetween(from: Long, to: Long): List<CtxSample> {
        val out = ArrayList<CtxSample>()
        readableDatabase.query(
            "ctx", arrayOf("ts", "lat", "lon", "acc", "ssid", "flags"),
            "ts BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()),
            null, null, "ts ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out += CtxSample(
                    ts = c.getLong(0),
                    lat = if (c.isNull(1)) null else c.getDouble(1),
                    lon = if (c.isNull(2)) null else c.getDouble(2),
                    accuracyMeters = c.getFloat(3),
                    ssid = if (c.isNull(4)) null else c.getString(4),
                    flags = c.getInt(5),
                )
            }
        }
        return out
    }

    // -------------------------------------------------------------------------- taps

    fun addTap(ts: Long, pkg: String, slot: Int) {
        val v = ContentValues(3).apply {
            put("ts", ts)
            put("pkg", pkg)
            put("slot", slot)
        }
        writableDatabase.insertWithOnConflict("tap", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Bounded at both ends so a ranking of a past moment cannot see later taps. */
    fun tapsBetween(from: Long, to: Long): List<Tap> {
        val out = ArrayList<Tap>()
        readableDatabase.query(
            "tap", arrayOf("ts", "pkg", "slot"),
            "ts BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()),
            null, null, "ts ASC",
        ).use { c ->
            while (c.moveToNext()) out += Tap(c.getLong(0), c.getString(1), c.getInt(2))
        }
        return out
    }

    // ------------------------------------------------------------------- impressions

    /** One refresh's worth of slots, as package to zero-based position. */
    fun addImpressions(now: Long, slots: Map<String, Int>) {
        if (slots.isEmpty()) return
        val day = now / RankConfig.DAY_MS
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((pkg, slot) in slots) {
                db.execSQL(
                    "INSERT INTO impr(day, pkg, n, slotsum) VALUES(?, ?, 1, ?) " +
                        "ON CONFLICT(day, pkg) DO UPDATE SET n = n + 1, slotsum = slotsum + ?",
                    arrayOf<Any>(day, pkg, slot, slot),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Bounded at both ends, but only to the day: these are daily buckets, so a ranking
     * replayed at noon still sees that whole day's impressions. It costs the replay a
     * little of its honesty in exchange for a table a hundredth of the size, and it is
     * confined to the weakest term in the model.
     */
    fun impressionsBetween(from: Long, to: Long): Map<String, Impressions> {
        val out = HashMap<String, Impressions>()
        readableDatabase.rawQuery(
            "SELECT pkg, SUM(n), SUM(slotsum) FROM impr WHERE day BETWEEN ? AND ? GROUP BY pkg",
            arrayOf((from / RankConfig.DAY_MS).toString(), (to / RankConfig.DAY_MS).toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Impressions(c.getInt(1), c.getInt(2))
        }
        return out
    }

    // ----------------------------------------------------------------- notifications

    fun addNotif(ts: Long, pkg: String) {
        val v = ContentValues(2).apply {
            put("ts", ts)
            put("pkg", pkg)
        }
        writableDatabase.insertWithOnConflict("notif", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun notifsBetween(from: Long, to: Long): List<Notif> {
        val out = ArrayList<Notif>()
        readableDatabase.query(
            "notif", arrayOf("ts", "pkg"),
            "ts BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()),
            null, null, "ts ASC",
        ).use { c ->
            while (c.moveToNext()) out += Notif(c.getLong(0), c.getString(1))
        }
        return out
    }

    fun addPending(key: String, pkg: String, ts: Long) {
        val v = ContentValues(3).apply {
            put("key", key)
            put("pkg", pkg)
            put("ts", ts)
        }
        writableDatabase.insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * @return whether a row was actually there. The listener hears about the removal of
     *   every notification on the phone, including the ongoing furniture it declined to
     *   record in the first place, and only the ones it had recorded can move the
     *   ranking. Worth a re-rank; the rest are not.
     */
    fun removePending(key: String): Boolean =
        writableDatabase.delete("pending", "key = ?", arrayOf(key)) > 0

    /**
     * Rewrites the whole set from what the platform currently reports. The listener
     * only hears about changes while it is bound, so anything posted or dismissed
     * while it was dead would otherwise be wrong forever.
     */
    fun replacePending(entries: List<Triple<String, String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pending", null, null)
            for ((key, pkg, ts) in entries) {
                db.insertWithOnConflict(
                    "pending", null,
                    ContentValues(3).apply {
                        put("key", key)
                        put("pkg", pkg)
                        put("ts", ts)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Package to the newest outstanding notification's post time. */
    fun pendingLatest(): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.rawQuery(
            "SELECT pkg, MAX(ts) FROM pending GROUP BY pkg", null,
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    // ----------------------------------------------------------------------- pruning

    /** Drops rows the ranker can no longer reach, so the db stays a few hundred KB. */
    fun prune(now: Long) {
        val db = writableDatabase
        // Deleting is measured from the newest row we hold rather than from the clock,
        // when the two disagree. An RTC that comes up in 2038 — a flat battery, a bad
        // NITZ update — puts `now` three weeks past everything in the table, and every
        // row here is one the ranker cannot re-derive: the trail takes weeks to rebuild
        // and the taps are the user's own feedback. Costs at most the fifteen minutes
        // between the newest sample and the real present.
        //
        // Only covers a clock that is *transiently* wrong. Once it has been wrong long
        // enough to have written samples of its own, those are the newest rows and
        // nothing here can tell them from real ones.
        val newest = maxOf(newestTs(db, "ctx"), newestTs(db, "tap"), newestTs(db, "sess"))
        val reference = if (newest > 0L) minOf(now, newest) else now
        val cutoff = reference - RankConfig.LOOKBACK_DAYS * RankConfig.DAY_MS
        db.run {
            delete("ctx", "ts < ?", arrayOf(cutoff.toString()))
            // Re-derivable only for as long as the platform still holds the events,
            // which is about a week of the three we keep. The rest is gone if dropped.
            delete("sess", "ts < ?", arrayOf(cutoff.toString()))
            delete("tap", "ts < ?", arrayOf(cutoff.toString()))
            delete("notif", "ts < ?", arrayOf(cutoff.toString()))
            delete("impr", "day < ?", arrayOf((cutoff / RankConfig.DAY_MS).toString()))
            // A pending row this old means we missed its removal; it is not still on
            // screen, and left alone it would boost that app forever.
            delete("pending", "ts < ?", arrayOf((reference - PENDING_MAX_AGE_MS).toString()))
        }
    }

    /** Cheap: `ts` leads the primary key on every table it is asked about. */
    private fun newestTs(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT MAX(ts) FROM $table", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }

    companion object {
        private const val NAME = "suggest.db"
        private const val VERSION = 3
        private const val PENDING_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * Shared by [onCreate] and [onUpgrade], because they are the same table and
         * writing it twice is how the two quietly drift apart.
         *
         * No index beyond the key. Every read is a range over `ts`, which the primary
         * key already serves because `ts` leads it, and `pkg` is only ever selected —
         * never searched for.
         */
        private const val SESS_TABLE =
            "CREATE TABLE sess(ts INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                "dur INTEGER NOT NULL, PRIMARY KEY(ts, pkg))"

        @Volatile
        private var instance: Store? = null

        /**
         * The one [Store] there is, built on first use and kept for the life of the
         * process.
         *
         * Never closed, deliberately. It used to be `Closeable` and every caller opened
         * its own inside a `use` block, which is six or seven full open-and-close cycles
         * per refresh — each one re-reading the header, re-checking the schema version
         * and re-running WAL recovery — and, worse, meant several live connections to one
         * file whenever a refresh and a notification landed together. SQLite handles that
         * by making one of them wait and then fail on a busy timeout; a single connection
         * cannot contend with itself, and `SQLiteOpenHelper` already serialises access
         * across threads.
         *
         * The application context is what is held, so this is not a leak — it is the same
         * lifetime the database file has from the app's point of view.
         */
        fun of(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context).also { instance = it }
            }
    }
}
