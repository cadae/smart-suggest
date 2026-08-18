package com.lukecao.suggest.rank

/**
 * Every knob the ranker has. See the README for what each group does to the output.
 *
 * The scoring shape is `prior^ALPHA x product of lifts x instant x feedback`, so
 * these fall into four families: what sizes the prior, how much a context signal is
 * allowed to move it, the signals that depend only on right now, and the feedback
 * terms. Almost everything is expressed as a multiplier around 1.0, so a missing
 * signal is a no-op rather than a bias.
 */
object RankConfig {

    // ---------------------------------------------------------------- window & prior

    /**
     * How far back the ranker looks, and how long [Store][com.lukecao.suggest.data.Store]
     * keeps anything.
     *
     * This used to be an upper bound rather than a promise: UsageStats keeps roughly a
     * week of raw events and then discards them, so asking for three weeks got one and
     * nothing in the app could tell that from a quiet fortnight. Since
     * [SessionLog][com.lukecao.suggest.rank.SessionLog] began keeping the sessions
     * itself it is a promise — after three weeks of the app running, anyway.
     */
    const val LOOKBACK_DAYS = 21

    /** A session's contribution to the prior halves every this many days. This is
     *  the slow, forgetting decay; short-term movement comes from [CONTINUITY_BOOST]. */
    const val RECENCY_HALF_LIFE_DAYS = 3.0

    /**
     * Compresses the prior, which is what lets context win.
     *
     * Raw usage mass spans about 6x from the heaviest app to the eighth heaviest on a
     * normal phone, and no bounded context multiplier can overcome that. At 0.6 the
     * 6x becomes 2.9x, which a genuinely context-locked app can beat. Set to 1.0 to
     * get the old volume-dominated behaviour back.
     */
    const val VOLUME_EXPONENT = 0.6

    /** Sessions longer than this are clamped; an app forgotten in the foreground
     *  overnight should not outweigh a week of real use. */
    const val MAX_SESSION_MS = 4 * 60 * 60 * 1000L

    /** Consecutive sessions of one app closer together than this are merged, so
     *  activity-to-activity hops inside an app count as a single launch. */
    const val SESSION_MERGE_GAP_MS = 30 * 1000L

    // ---------------------------------------------------------------- context lifts

    /**
     * A per-signal lift is `P(context | app) / P(context)`: how much more of this
     * app's history sits in the current context than the average app's does. 1.0
     * means the app tells us nothing about the context, and vice versa.
     *
     * Clamped because the ratio is unbounded — an app used exactly once, at the
     * office, would otherwise earn an enormous WiFi lift.
     */
    const val LIFT_MAX = 4.0

    /**
     * Shrinkage toward 1.0, in sessions. An app with this many sessions keeps half of
     * its measured lift; with three times this many, three quarters. Without it every
     * barely-used app looks perfectly context-locked, because two sessions are always
     * "always".
     */
    const val LIFT_SHRINK_SESSIONS = 8.0

    /**
     * Per-signal exponents on the lift. Below 1.0 damps a signal, above 1.0 sharpens
     * it. This is the honest place to express "how much do I trust this", rather than
     * pretending the lift itself needs tuning.
     */
    const val W_TIME_OF_DAY = 1.0
    const val W_DAY_OF_WEEK = 0.7
    const val W_PLACE = 1.0

    /**
     * Damped below [W_PLACE] because it is a coarsening of the same measurement rather
     * than an independent one.
     *
     * At one of your usual places it says little that [W_PLACE] and [W_WIFI] have not
     * already said, and leaving it at full weight would triple-count "at home". The
     * information it adds on its own is the case those two cannot express at all, which
     * is being somewhere new — so it is worth having, but not worth a full vote.
     */
    const val W_ANCHOR = 0.8
    const val W_WIFI = 1.0
    const val W_HEADPHONES = 0.8
    const val W_CHARGING = 0.5
    const val W_MOTION = 0.8
    const val W_CALENDAR = 1.0

    /**
     * The most exponent the four location terms may spend between them, however many of
     * them fire.
     *
     * They are four measurements of one fact. [W_PLACE], [W_ANCHOR] and [W_WIFI] all
     * answer "are you at home", and [W_MOTION] answers "are you sitting still", which at
     * home is the same question again. Their weights sum to 3.6, and multiplying four
     * near-independent-looking terms is what that arithmetic assumes — so a mild reading
     * repeated four times compounds into a strong one. Measured on the real trail: at
     * home the four came to 0.69, 0.74, 0.69 and 0.90, each individually a shrug, whose
     * product is 0.31. With a mature trail, where shrinkage no longer damps them, Maps
     * fell to 46th of the 54 apps scored — beaten by the four-fold restatement of "you
     * are at home and Maps is not a home app" rather than by anything else's merit.
     *
     * A shared budget fixes it without giving up the individual terms, each of which does
     * say something the others cannot somewhere. 1.4 rather than 1.0 because the family
     * genuinely does hold slightly more than one signal's worth of information — being at
     * a usual place and being on a known network are not quite the same claim — and
     * rather than 3.6 because they are nowhere near four. Replayed against the trail this
     * puts Maps 1st of 54 out on the road and 9th at home, where before it was 3rd and
     * 46th.
     *
     * Applied only to what is actually spent: a term with no contrast to measure returns
     * 1.0 and takes no budget with it, so being away from home — where the fine place
     * term has nothing to say — leaves the other three at close to full strength.
     */
    const val W_LOCATION_BUDGET = 1.4

    /** Width of the time-of-day match, in minutes. 60 means a session two hours off
     *  contributes almost nothing to the time term. */
    const val TOD_SIGMA_MINUTES = 60.0

    /** How much a same-day-type session counts when it is not the same weekday.
     *  Tuesday and Thursday are more alike than Tuesday and Sunday. */
    const val DAY_TYPE_PARTIAL = 0.4

    /** Distance over which the place match decays. 250 m keeps "at home" and "at the
     *  office" distinct without being GPS-jitter sensitive. */
    const val PLACE_RADIUS_METERS = 250.0

    /**
     * A fix whose own error bar is wider than this is not allowed to define where you
     * are now, or to contribute a speed.
     *
     * Two radii, because a fix good to less than the distance we are trying to resolve
     * can still say something useful, and one good to several times it cannot. Measured
     * on the device: a network fix came back claiming 800 m accuracy while the phone sat
     * on a desk, and because nothing looked at the accuracy it became "here" — 929 m
     * from home. Every app's place lift inverted, and the same pair of fixes implied
     * 11 m/s of travel. Rejecting the fix outright is the only honest reading: it does
     * not say we are somewhere else, it says it does not know where we are.
     */
    const val MAX_USABLE_ACCURACY_METERS = 2 * PLACE_RADIUS_METERS

    /**
     * Share of placeable trail time a spot has to hold before it counts as one of your
     * usual places.
     *
     * Measured against time rather than breadcrumb count on purpose: the sampling
     * interval is a user setting, so counting samples would quietly rate the places
     * visited since the interval was shortened as more familiar than the ones before it.
     *
     * On a full three-week trail this comes to a working day's worth of time in one
     * 250 m circle. Home clears it many times over and a workplace comfortably, while
     * the weekly supermarket does not — which is the intent, since "somewhere I am not
     * usually" is the thing being detected and the shops belong on that side of it.
     */
    const val ANCHOR_DWELL_SHARE = 0.05

    /**
     * And an absolute floor, because a share alone is meaningless on a short trail: on
     * day one a single hour-long stop is a large fraction of everything we have ever
     * seen, and would enrol a car park as a usual place.
     *
     * One hour, not the day-or-so the share works out to, because the two thresholds are
     * for opposite ends of the trail's life and the floor governs the short end. Measured
     * against a five-hour trail containing one real trip out: home held 3.75 h in a single
     * cluster while the trip scattered across eight, none holding more than a quarter of an
     * hour, so anywhere in the range 1-3 h separates them. Set it to the day-or-so figure
     * instead and nothing qualifies for the first week — the signal would sit there
     * contributing a silent 1.0, which is the exact failure it was written to fix.
     */
    const val ANCHOR_MIN_DWELL_MS = 60 * 60 * 1000L

    /**
     * How much time one breadcrumb may speak for when totting up dwell.
     *
     * The gap to the previous breadcrumb is the only estimate of "how long you were
     * there" available, and it is wrong across a gap in sampling — the phone drops no
     * breadcrumbs overnight, so the first one of the morning would otherwise credit
     * wherever you are with eight hours. Capping undercounts home, which is harmless:
     * home is never the place in danger of missing the bar.
     */
    const val ANCHOR_DWELL_CAP_MS = 30 * 60 * 1000L

    /**
     * A session is attributed to the context sample nearest in time, but only if that
     * sample is within this window.
     *
     * It has to exceed half the sampling interval or most sessions get no context at
     * all: at the 15-minute default the nearest sample can be 7.5 minutes away.
     */
    const val CTX_MATCH_WINDOW_MS = 20 * 60 * 1000L

    /** A context sample older than this is not trusted to represent "now". */
    const val HERE_MAX_AGE_MS = 30 * 60 * 1000L

    /** Speed between two breadcrumbs above which we call it travelling, in m/s. 2.0
     *  is a brisk walk; it separates "out and about" from "sitting down". */
    const val MOVING_SPEED_MPS = 2.0

    /** Two breadcrumbs further apart in time than this cannot give a useful speed. */
    const val MOTION_MAX_GAP_MS = 30 * 60 * 1000L

    // ------------------------------------------------------------- instant signals

    /**
     * Short-term continuity: you mostly reopen apps you were just in. Measured on
     * real event logs, 63% of opens are of an app used in the previous hour, drawn
     * from a set averaging a sixth of the catalog — a 4x lift, which is what this
     * peak boost is scaled to.
     *
     * The slow [RECENCY_HALF_LIFE_DAYS] decay cannot express this: across one hour it
     * moves a score by 1%.
     */
    const val CONTINUITY_BOOST = 2.5
    const val CONTINUITY_HALF_LIFE_MS = 25 * 60 * 1000L

    /**
     * How long after closing an app it stays suppressed — you are on your way out of it,
     * not looking to go back in. Set to 0 to disable.
     *
     * A multiplier rather than an exclusion, which is what it used to be. As a hard
     * filter this made 49 of 338 replayed moments unrankable — 14.5% of them, and 6 of
     * the 10 from-home bounce-backs — so the widget was structurally forbidden from
     * making the easiest prediction available to it, and the replay could only score
     * those moments as impossible rather than as wrong. A penalty lets them be ranked
     * badly instead of not at all, which is both more honest and measurable.
     */
    const val COOLDOWN_MS = 90 * 1000L

    /**
     * The multiplier at the instant an app is closed, easing back to 1.0 across
     * [COOLDOWN_MS].
     *
     * Deliberately not tuned to maximise Hit@1. [CONTINUITY_BOOST] peaks at 2.5x over
     * these same seconds, so 0.1 nets a 4x demotion at the moment of closing: enough to
     * move a just-closed app out of the first slot and into the second half of the grid,
     * not enough to hide it. Accuracy alone would argue for less, because the app you
     * just closed is genuinely likely to be the next one opened — but a slot spent on it
     * is a wasted slot even when the prediction is right, since the recents button
     * already gets you there faster. That is the one thing Hit@1 cannot see, which is
     * why this is set by hand and not fitted.
     */
    const val COOLDOWN_FLOOR = 0.1

    /**
     * Sequence: how much more likely this app is to follow the one you just used than
     * to be opened at all. Measured at roughly 2x the top-1 accuracy of a frequency
     * baseline, which is why it gets a full-weight exponent.
     */
    const val W_SEQUENCE = 1.0

    /** Transitions out of the previous app needed before the bigram is half trusted.
     *  Also sets how much one observed hop is worth: seeing it once where the base rate
     *  expected a fifth of a hop is a fivefold lift, not a certainty. */
    const val SEQUENCE_SHRINK_COUNT = 5.0

    /** Two app uses further apart than this are not one after the other, they are two
     *  separate sittings, and pairing them would teach the bigram nothing. */
    const val SEQUENCE_MAX_GAP_MS = 10 * 60 * 1000L

    /**
     * Due-ness. An app with a rhythm — a daily standup, a weekly shop — becomes
     * *more* likely as its usual gap elapses. It is the only term here that rises
     * with time, which is precisely why it adds movement the others cannot.
     */
    const val DUE_BOOST = 1.2

    /** Width of the due window, in natural logs of the gap. 0.6 means "between about
     *  half and twice the usual gap" counts as due. */
    const val DUE_SIGMA_LOG = 0.6

    /** Below this many launches there is no rhythm to speak of, only noise. */
    const val DUE_MIN_LAUNCHES = 4

    /** A newly installed app has no history to rank on, so it would never surface
     *  without a hand up. Decays to nothing across [NOVELTY_DAYS]. */
    const val NOVELTY_BOOST = 1.5
    const val NOVELTY_DAYS = 7.0

    /** Launches at which the novelty boost is half gone. Deliberately tiny: the point
     *  is to cover an app with no history, and a few opens are history. It matters more
     *  than it looks — a phone set up last week reports most of its catalog as newly
     *  installed, so without this the boost applies to nearly everything. */
    const val NOVELTY_TRUST_LAUNCHES = 2.0

    /** The prior given to an app installed inside [NOVELTY_DAYS] that has never been
     *  opened, equivalent to one bare launch. Far too small to rank on its own — its
     *  only job is to make a fresh install visible to the exploration slot. */
    const val NEW_APP_PRIOR = 1.0

    /** An unopened notification is the strongest single predictor of an imminent
     *  open, decayed because a two-hour-old one has been seen and ignored. */
    const val NOTIF_BOOST = 3.0
    const val NOTIF_HALF_LIFE_MS = 20 * 60 * 1000L

    /** Notifications from an app needed before its act-on rate is trusted, and the
     *  rate assumed until then. Without this, one notification from a spammy app that
     *  you happened to open would mark it as always worth surfacing. */
    const val NOTIF_SHRINK_COUNT = 5.0
    const val NOTIF_PRIOR_RATE = 0.35

    /** How long after a notification an open still counts as acting on it. */
    const val NOTIF_ACT_WINDOW_MS = 10 * 60 * 1000L

    /** How close a calendar event has to be to count as imminent. Asymmetric on
     *  purpose: you open the meeting app before it starts, and during it. */
    const val CAL_BEFORE_MS = 15 * 60 * 1000L
    const val CAL_AFTER_MS = 5 * 60 * 1000L

    /** An event that names its app — via `CUSTOM_APP_PACKAGE` or a recognisable
     *  meeting URL — is not a statistical hint, it is an answer. */
    const val CAL_NAMED_APP_BOOST = 4.0

    // ------------------------------------------------------------------- feedback

    /** Peak multiplier from taps on the widget. Choosing an app out of the eight on
     *  offer is a much stronger statement of intent than opening it incidentally. */
    const val TAP_BOOST = 2.0

    /** Tap weight saturates at this much accumulated, decayed tap evidence, so one
     *  much-loved app cannot run away with the whole grid. */
    const val TAP_SATURATION = 2.0

    /** A tap on a later slot counts for more: you had to look past everything above
     *  it. Scales linearly from 1.0 at the first slot to 1 + this at the last. */
    const val SLOT_BIAS = 0.5

    /**
     * Impressions as negative evidence. Shown twenty times and never tapped is a
     * clear statement, and it is the only term that actively pushes a stale
     * suggestion out. The exponent is below 1.0 because tap-through on a widget is
     * noisy: you also open apps from the dock and the drawer.
     */
    const val IMPRESSION_EXPONENT = 0.5

    /**
     * Smoothing on the tap-through rate, in pseudo-taps at the measured average rate.
     *
     * Expressed in taps rather than impressions on purpose. Tap-through on a widget runs
     * around 1%, so twenty impressions without a tap is not evidence of anything — you
     * would only have expected a fifth of a tap. One pseudo-tap converts to a hundred
     * pseudo-impressions at that rate and to ten at a rate of 10%, which is the scale
     * the question is actually asked on.
     */
    const val CTR_PRIOR_TAPS = 1.0

    /**
     * Total impressions needed across all apps before tap-through is used at all.
     *
     * The rate an app is compared against is the average across everything the widget
     * has shown, not a guessed constant: a fixed prior of a few percent would demote
     * every app that has ever appeared, since almost nothing beats a made-up rate.
     */
    const val CTR_MIN_SHOWN = 200.0

    /** Small bonus for apps already on the widget, so the grid does not reshuffle on
     *  scoring noise and break your muscle memory. */
    const val STICKINESS_BOOST = 1.08

    // ------------------------------------------------------------------ exploration

    /**
     * How far below the cut the exploration slot draws from. Without it the feedback
     * terms can only ever learn about apps that already rank, which is a closed loop:
     * nothing new is shown, so nothing new is tapped, so nothing new ever ranks.
     */
    const val EXPLORE_POOL = 12

    /** Slots to assume when no widget is placed to size the ranking — the same count as
     *  the 4x2, which is what most people end up with. Each placed size asks for its own
     *  `cols * rows`; see [Widgets.maxSlots][com.lukecao.suggest.widget.Widgets.maxSlots]. */
    const val SLOTS = 8

    const val DAY_MS = 24 * 60 * 60 * 1000L
}
