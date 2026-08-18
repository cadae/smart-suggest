package com.lukecao.suggest.data

/**
 * Everything the user can change about the widget.
 *
 * Read fresh on every widget draw and every refresh, so it is a plain immutable
 * snapshot rather than live state. Settings are global rather than per-widget
 * instance: simpler, and there is little reason to want two differently
 * configured copies of this on one home screen.
 *
 * Notably absent: the grid, the icon size and the refresh interval. All three used to
 * live here and all three were answers the phone could give better than the user
 * could. The grid is now the widget you picked off the picker
 * ([Widgets][com.lukecao.suggest.widget.Widgets]), the icon size is derived from the
 * cell the launcher gives us, and the interval is fixed at the shortest one
 * WorkManager will schedule.
 */
data class WidgetSettings(
    val backgroundEnabled: Boolean = true,
    val backgroundArgb: Int = DEFAULT_BACKGROUND,
    /**
     * Re-rank when something happens, not only when the clock says so.
     *
     * A timer is a poor fit for the signals that move fastest: plugging in headphones
     * or getting a notification changes the answer immediately, and waiting out the
     * rest of the interval shows a stale grid for no reason.
     */
    val eventTriggers: Boolean = true,
    /** Keeps Settings, Device care and friends out of the suggestions. */
    val hideSystemApps: Boolean = true,
    /** Packages the user has hidden by hand — typically whatever is in their dock. */
    val hidden: Set<String> = emptySet(),
    /** Which of the permission-gated ranking signals are switched on. See
     *  [SignalSettings]. */
    val signals: SignalSettings = SignalSettings(),
) {
    companion object {
        /** Black at 40%: enough to lift white labels off a bright wallpaper. */
        const val DEFAULT_BACKGROUND: Int = 0x66000000.toInt()

        /**
         * How often the widget re-ranks on the clock alone. Not configurable.
         *
         * It is the floor WorkManager will schedule periodic work at, and there is
         * nothing above it worth offering: a suggestion grid that is up to an hour
         * stale is not a suggestion grid. Going below it meant a chain of inexact
         * alarms, which cost battery for timing the OS was free to ignore anyway —
         * the event triggers cover the same ground honestly, by reacting to the thing
         * that changed instead of polling for it.
         */
        const val REFRESH_MINUTES = 15
        const val REFRESH_MS = REFRESH_MINUTES * 60_000L

        /**
         * Nothing re-ranks twice inside this window.
         *
         * Events arrive in bursts — a dozen notifications land together, headphones
         * report connected twice — and re-ranking on each would be wasted work for an
         * answer that barely differs. Long enough to collapse a burst, short enough
         * that it never feels like the widget ignored you.
         *
         * Two minutes rather than the 45 seconds this started at, because a day of
         * recorded runs showed what the real trigger volume is: 297 re-ranks in 26
         * hours, one every 5.3 minutes, and 20 to 23 an hour through the evening. A
         * 45-second floor permits 80 an hour, so it was never the thing deciding how
         * often this ran. Almost every gap it did catch was a notification burst, and
         * a third of all gaps fell in the 45-second-to-two-minute band it allowed
         * through — which is to say the burst it was written for was mostly landing
         * just outside it.
         *
         * Enforced by [RefreshWorker][com.lukecao.suggest.work.RefreshWorker] rather
         * than by whoever asked for the refresh, because the timestamp it compares
         * against is only written when a run finishes. A caller checking it during a
         * burst reads a stale value and lets the whole burst through, which is exactly
         * what used to happen. The trigger path still checks it first, but only to
         * avoid waking WorkManager for a request that is going to be dropped anyway.
         */
        const val MIN_RERANK_GAP_MS = 120_000L

        /**
         * How long a refresh that cannot be dropped waits before it runs.
         *
         * Some requests have to be honoured however recently we re-ranked: a settings
         * change, an app installed, a widget placed. They still arrive in bursts —
         * ticking six apps in the hidden-apps picker is six of them, a store updating
         * your library is one per app — so instead of dropping them they are collapsed,
         * by restarting this delay each time and letting the last request win.
         *
         * Short enough to be invisible next to the widget's own redraw, long enough to
         * cover an unhurried run of taps. It matters that the work sits *pending* for
         * this long rather than running immediately: a pending request is replaced for
         * free, where replacing a running one would cancel a re-rank halfway through.
         */
        const val RERANK_DEBOUNCE_MS = 2_000L

        /** Opaque bases. The alpha comes from the opacity slider, not from here. */
        val BACKGROUND_COLOURS = listOf(
            0x000000, // black
            0x1C1C1E, // charcoal
            0x3A3A3C, // grey
            0xFFFFFF, // white
            0x1F6FEB, // blue
            0x4A2B7A, // purple
        )
    }
}
