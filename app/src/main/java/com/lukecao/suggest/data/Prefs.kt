package com.lukecao.suggest.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val FILE = "suggest"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_LAST_SHOWN = "last_shown"
    private const val KEY_LAST_REFRESH = "last_refresh"
    private const val KEY_EXPLORE_CURSOR = "explore_cursor"
    private const val KEY_BG_ON = "bg_on"
    private const val KEY_BG_ARGB = "bg_argb"
    private const val KEY_EVENT_TRIGGERS = "event_triggers"
    private const val KEY_LAST_TRIGGER = "last_trigger"
    private const val KEY_HIDE_SYSTEM = "hide_system"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_SIG_PLACE = "sig_place"
    private const val KEY_SIG_NOTIF = "sig_notif"
    private const val KEY_SIG_CALENDAR = "sig_calendar"

    /**
     * Settings that no longer exist, cleared on the next write.
     *
     * Not required — an unread key costs nothing — but this file is the first thing
     * either of us reads when the widget misbehaves, and a stale `refresh_minutes=5`
     * sitting in it is a false lead.
     */
    private val RETIRED_KEYS = listOf(
        "cols", "rows", "icon_dp", "refresh_minutes",
        "sig_recent", "sig_rhythm", "sig_feedback", "sig_explore", "sig_device",
        "ads_removed", "banner_filled",
    )

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun putSnapshot(context: Context, json: String) =
        sp(context).edit().putString(KEY_SNAPSHOT, json).apply()

    fun snapshot(context: Context): String? = sp(context).getString(KEY_SNAPSHOT, null)

    fun putLastShown(context: Context, pkgs: List<String>) =
        sp(context).edit().putStringSet(KEY_LAST_SHOWN, pkgs.toSet()).apply()

    fun lastShown(context: Context): Set<String> =
        sp(context).getStringSet(KEY_LAST_SHOWN, emptySet()) ?: emptySet()

    fun putLastRefresh(context: Context, ts: Long) =
        sp(context).edit().putLong(KEY_LAST_REFRESH, ts).apply()

    fun lastRefresh(context: Context): Long = sp(context).getLong(KEY_LAST_REFRESH, 0L)

    /**
     * What caused the most recent refresh.
     *
     * Shown in the app, but the reason it is stored at all is that the platform will
     * not tell you which broadcasts it has decided to deliver to you. Several of the
     * event triggers are declared in the manifest in the hope that they arrive, and
     * this is the only way to find out whether they did.
     */
    fun putLastTrigger(context: Context, reason: String) =
        sp(context).edit().putString(KEY_LAST_TRIGGER, reason).apply()

    fun lastTrigger(context: Context): String? = sp(context).getString(KEY_LAST_TRIGGER, null)

    /**
     * Advances one step per refresh, and is what makes the exploration slot walk the
     * candidate pool instead of sitting on whichever app happens to rank ninth.
     *
     * A counter rather than a random pick so the same inputs give the same widget:
     * randomness here would mean the grid could change without anything else changing.
     *
     * Synchronized because it is a read, an add and a write, and a widget being drawn can
     * reach it at the same moment as a refresh — two callers both reading the same value
     * hand back the same index, which is the one thing a counter is here to avoid.
     */
    @Synchronized
    fun nextExploreCursor(context: Context): Int {
        val s = sp(context)
        // Wrapped well below Int.MAX_VALUE so the modulo in the ranker never sees a
        // negative index after enough years of refreshes.
        val next = (s.getInt(KEY_EXPLORE_CURSOR, 0) + 1) % 1_000_000
        s.edit().putInt(KEY_EXPLORE_CURSOR, next).apply()
        return next
    }

    fun settings(context: Context): WidgetSettings {
        val s = sp(context)
        val defaults = SignalSettings()
        return WidgetSettings(
            backgroundEnabled = s.getBoolean(KEY_BG_ON, true),
            backgroundArgb = s.getInt(KEY_BG_ARGB, WidgetSettings.DEFAULT_BACKGROUND),
            eventTriggers = s.getBoolean(KEY_EVENT_TRIGGERS, true),
            hideSystemApps = s.getBoolean(KEY_HIDE_SYSTEM, true),
            hidden = s.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet(),
            signals = SignalSettings(
                place = s.getBoolean(KEY_SIG_PLACE, defaults.place),
                notifications = s.getBoolean(KEY_SIG_NOTIF, defaults.notifications),
                calendar = s.getBoolean(KEY_SIG_CALENDAR, defaults.calendar),
            ),
        )
    }

    fun putSettings(context: Context, v: WidgetSettings) =
        sp(context).edit()
            .putBoolean(KEY_BG_ON, v.backgroundEnabled)
            .putInt(KEY_BG_ARGB, v.backgroundArgb)
            .putBoolean(KEY_EVENT_TRIGGERS, v.eventTriggers)
            .putBoolean(KEY_HIDE_SYSTEM, v.hideSystemApps)
            // Copied: SharedPreferences keeps the very set instance you hand it.
            .putStringSet(KEY_HIDDEN, HashSet(v.hidden))
            .putBoolean(KEY_SIG_PLACE, v.signals.place)
            .putBoolean(KEY_SIG_NOTIF, v.signals.notifications)
            .putBoolean(KEY_SIG_CALENDAR, v.signals.calendar)
            .also { e -> RETIRED_KEYS.forEach { e.remove(it) } }
            .apply()
}
