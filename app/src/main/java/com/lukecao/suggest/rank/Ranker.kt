package com.lukecao.suggest.rank

import android.content.Context
import com.lukecao.suggest.Permissions
import com.lukecao.suggest.data.AppCatalog
import com.lukecao.suggest.data.CtxSample
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.Store
import com.lukecao.suggest.data.SystemApps
import com.lukecao.suggest.data.WidgetSettings
import com.lukecao.suggest.sense.CalendarReader
import com.lukecao.suggest.sense.ContextSampler
import com.lukecao.suggest.sense.NotifListener
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** One multiplier that moved an app's score, for the breakdown in the app. */
data class Factor(val name: String, val value: Double)

/** A ranked app, plus what produced its score. */
data class Scored(
    val pkg: String,
    val label: String,
    val score: Double,
    val launches: Int,
    val dwellMinutes: Double,
    val lastUsed: Long,
    /** Usage mass after [RankConfig.VOLUME_EXPONENT]: the score before any signal. */
    val prior: Double,
    /** Everything that multiplied [prior] into [score], biggest mover first. */
    val factors: List<Factor>,
)

/**
 * Scores every launchable app against the current moment.
 *
 *     score(p) = mass(p)^a  x  PI lift_k(p)^w_k  x  instant(p)  x  feedback(p)
 *
 * The first term is the app's usage volume, deliberately compressed by
 * [RankConfig.VOLUME_EXPONENT]. The rest are multipliers around 1.0.
 *
 * Each `lift_k` asks the naive-Bayes question `P(context | app) / P(context)`: how much
 * more of this app's history sits in the situation you are in now than the average
 * app's does. That framing matters. The previous version added bounded boosts to a
 * volume score, and since raw volume spans about 6x from the heaviest app to the
 * eighth, no bounded boost could ever reorder anything — the widget was a usage
 * league table wearing a context costume. A lift can be below 1.0, which is what lets
 * a heavily used app be pushed down for being wrong for right now.
 *
 * Every term degrades to exactly 1.0 when its signal is missing or switched off, so
 * this works on a fresh install with no trail, no taps and no permissions beyond usage
 * access, and each toggle removes a factor rather than breaking the model.
 */
object Ranker {

    fun rank(
        context: Context,
        now: Long,
        limit: Int = RankConfig.SLOTS,
        /** How many of the results will actually be shown, for the slot debiasing in
         *  [ctrFactor]. Not the same as [limit], which ranks deeper than the widget. */
        slots: Int = RankConfig.SLOTS,
        /**
         * Rank a moment that has already passed, for
         * [Replay][com.lukecao.suggest.eval.Replay].
         *
         * Everything read here is bounded by [now] either way — that is what makes a
         * chronological evaluation mean anything — but three inputs have no history to be
         * bounded against, because the app only ever stored their latest value: the live
         * device state, the set of notifications currently on screen, and which apps the
         * widget last showed. In this mode the first two are reconstructed from the trail
         * and the notification log, and the third is dropped. See [historicalPending].
         */
        historical: Boolean = false,
        /**
         * How far from neutral a multiplier has to be to be named in [Scored.factors].
         * The default keeps the breakdown in the app readable; an ablation needs every
         * factor, including the ones too small to explain anything, because it works by
         * dividing them back out of the product.
         */
        factorFloor: Double = FACTOR_SHOW,
    ): List<Scored> {
        val catalog = AppCatalog.launchable(context)
        if (catalog.isEmpty()) return emptyList()

        val settings = Prefs.settings(context)
        val sig = settings.signals
        // Both the toggle and the permission, checked here rather than trusted from the
        // switch: a permission can be revoked in Settings long after it was flipped on.
        val usePlace = sig.place && Permissions.hasLocation(context)
        val useCalendar = sig.calendar && Permissions.hasCalendar(context)
        val useNotif = sig.notifications && NotifListener.hasAccess(context)

        val from = now - RankConfig.LOOKBACK_DAYS * RankConfig.DAY_MS
        val sessions = SessionLog.history(context, from, now, sync = !historical)
        if (sessions.isEmpty()) return emptyList()

        val home = AppCatalog.homePackage(context)
        val excluded = buildSet {
            addAll(excludedPackages(context, settings, home))
            // An app installed after the moment being replayed could not have been
            // offered at it, and scoring it would flatter the result with a candidate
            // the widget never had.
            if (historical) {
                for ((pkg, entry) in catalog) if (entry.installedAt > now) add(pkg)
            }
        }

        val store = Store.of(context)
        val samples = store.samplesBetween(from, now)
        val taps = store.tapsBetween(from, now)
        val impressions = store.impressionsBetween(from, now)
        val notifs = if (useNotif) store.notifsBetween(from, now) else emptyList()
        val pending = when {
            !useNotif -> emptyMap()
            historical -> historicalPending(notifs, now)
            else -> store.pendingLatest()
        }

        // ------------------------------------------------------------------ where now
        val trail = Trail(samples, computeMotion = usePlace)
        // Each of these takes the newest sample that actually carries the signal, not
        // simply the newest sample: a fix can fail while the WiFi name is still known.
        val hereLoc = if (usePlace) samples.lastOrNull { fresh(now, it.ts) && usableLocation(it) } else null
        // The newest sample that knows, which is allowed to know that we are on nothing.
        val hereWifi = if (usePlace) samples.lastOrNull { fresh(now, it.ts) && it.hasWifiState } else null
        val hereMoving = if (usePlace) trail.movingNow(now) else null
        val anchors = if (usePlace) Anchors(samples) else null
        val hereAnchor = if (anchors != null && hereLoc != null) anchors.at(hereLoc) else null
        // Read live rather than from the trail: headphones and charging are
        // instantaneous, free to query, and a 20-minute-old answer is worthless. A replay
        // has no live to read and falls back to the trail, which is the same 20-minute-old
        // answer — worth less, but the alternative is measuring the term against today's
        // headphones for every moment in the past three weeks.
        val hereState = if (historical) {
            samples.lastOrNull { fresh(now, it.ts) && it.hasDeviceState }?.flags
        } else {
            ContextSampler.deviceStateNow(context)
        }
        val hereHeadphones = hereState?.let { it and CtxSample.FLAG_HEADPHONES != 0 }
        val hereCharging = hereState?.let { it and CtxSample.FLAG_CHARGING != 0 }

        val calendar = if (useCalendar) {
            CalendarReader.read(context, from, now, catalog.keys, now)
        } else {
            null
        }
        val nowInMeeting = calendar?.windows?.contains(now) ?: false

        val cal = Calendar.getInstance()
        val nowTod = todMinutes(now, cal)
        val nowWeekend = isWeekend(now, cal)
        val nowDow = dayOfWeek(now, cal)
        val halfLifeMs = RankConfig.RECENCY_HALF_LIFE_DAYS * RankConfig.DAY_MS

        // ------------------------------------------------------------ one pass, three jobs
        val acc = HashMap<String, Acc>(catalog.size)
        val global = Lifts()
        val notifPkgs = notifs.mapTo(HashSet()) { it.pkg }
        val notifStarts = HashMap<String, ArrayList<Long>>()
        val chain = ArrayList<Session>(sessions.size)

        for (s in sessions) {
            // Kept for every app with notifications, ranked or not, because the
            // act-on rate is measured over all of them.
            if (s.pkg in notifPkgs) notifStarts.getOrPut(s.pkg) { ArrayList() } += s.start

            // The launcher and our own trampoline sit between almost every pair of
            // apps and predict nothing. Stepping over them is what makes the bigram
            // mean "A, then B" instead of "A, then home screen".
            if (s.pkg != context.packageName && s.pkg != home) chain += s

            if (s.pkg in excluded || !catalog.containsKey(s.pkg)) continue

            val recency = halfLifeDecay((now - s.start).coerceAtLeast(0L).toDouble(), halfLifeMs)
            if (recency < MIN_RECENCY) continue

            // Sublinear in dwell: a bare launch counts 1.0, ten minutes ~3.4, an hour
            // ~5.1. Stops one long video session dominating a whole week.
            val mass = 1.0 + ln(1.0 + s.durationMs / 60_000.0)
            val w = recency * mass

            val a = acc.getOrPut(s.pkg) { Acc() }
            a.prior += w
            a.launches++
            a.dwellMs += s.durationMs
            if (a.lastStart > 0L) a.gaps += s.start - a.lastStart
            a.lastStart = s.start
            a.lastEnd = maxOf(a.lastEnd, s.start + s.durationMs)

            // Time of day costs nothing and needs no permission, so it has no toggle.
            a.measure(
                global, F_TOD, w,
                gaussian(
                    circularDelta(todMinutes(s.start, cal), nowTod).toDouble(),
                    RankConfig.TOD_SIGMA_MINUTES,
                ),
            )

            // Tuesday and Thursday are more alike than Tuesday and Sunday.
            a.measure(
                global, F_DAY, w,
                when {
                    dayOfWeek(s.start, cal) == nowDow -> 1.0
                    isWeekend(s.start, cal) == nowWeekend -> RankConfig.DAY_TYPE_PARTIAL
                    else -> 0.0
                },
            )

            val ctxAt = trail.nearest(s.start)
            if (ctxAt >= 0) {
                val c = samples[ctxAt]
                if (hereLoc != null && usableLocation(c)) {
                    val d = distanceMeters(c.lat!!, c.lon!!, hereLoc.lat!!, hereLoc.lon!!)
                    a.measure(global, F_PLACE, w, gaussian(d, placeSigma(c, hereLoc)))
                }
                if (anchors != null && hereAnchor != null && usableLocation(c)) {
                    // Both "yes" does not mean the same usual place, only that each was
                    // at one. That is the coarsening, and it is the point: it is what
                    // lets two different roadsides count as the same situation.
                    a.measure(global, F_ANCHOR, w, agree(anchors.at(c), hereAnchor))
                }
                if (hereWifi != null && c.hasWifiState) {
                    // Both null means both samples were on no network, which agree they
                    // do — so this needs no special case for the away-from-home reading.
                    a.measure(global, F_WIFI, w, if (c.ssid == hereWifi.ssid) 1.0 else 0.0)
                }
                if (c.hasDeviceState && hereHeadphones != null && hereCharging != null) {
                    a.measure(global, F_HEADPHONES, w, agree(c.headphones, hereHeadphones))
                    a.measure(global, F_CHARGING, w, agree(c.charging, hereCharging))
                }
                val wasMoving = trail.moving(ctxAt)
                if (hereMoving != null && wasMoving != null) {
                    a.measure(global, F_MOTION, w, agree(wasMoving, hereMoving))
                }
            }

            if (calendar != null) {
                // Works retroactively over the whole window, because the calendar
                // remembers where you were and our own trail does not have to.
                a.measure(global, F_MEETING, w, agree(calendar.windows.contains(s.start), nowInMeeting))
            }
        }

        // An app installed this week with no sessions yet cannot enter the loop above,
        // so it would be invisible even to the exploration slot. Seed it with a single
        // notional launch — enough to be a candidate, nowhere near enough to rank.
        //
        // Unless the whole phone looks new, which is what a factory reset or a
        // device-to-device restore looks like from here: every package reports a
        // `firstInstallTime` from the day the phone was set up, so this would seed a
        // hundred and fifty apps at once, all identical, and hand the exploration slot a
        // pool of noise for a week. "New app" is only a useful idea when it means one app
        // against a settled background — which is exactly the majority test below.
        val fresh = catalog.count { noveltyFactor(it.value.installedAt, now, 0) > 1.0 }
        if (fresh * 2 <= catalog.size) {
            for ((pkg, entry) in catalog) {
                if (pkg in excluded || acc.containsKey(pkg)) continue
                if (noveltyFactor(entry.installedAt, now, 0) <= 1.0) continue
                acc[pkg] = Acc().apply { prior = RankConfig.NEW_APP_PRIOR }
            }
        }

        // ---------------------------------------------------------------- what follows what
        val opens = HashMap<String, Int>()
        val outOf = HashMap<String, Int>()
        val follow = HashMap<String, HashMap<String, Int>>()
        for (i in chain.indices) {
            val cur = chain[i]
            opens[cur.pkg] = (opens[cur.pkg] ?: 0) + 1
            if (i == 0) continue
            val prev = chain[i - 1]
            if (prev.pkg == cur.pkg) continue
            if (cur.start - (prev.start + prev.durationMs) > RankConfig.SEQUENCE_MAX_GAP_MS) continue
            outOf[prev.pkg] = (outOf[prev.pkg] ?: 0) + 1
            val row = follow.getOrPut(prev.pkg) { HashMap() }
            row[cur.pkg] = (row[cur.pkg] ?: 0) + 1
        }
        val prevPkg = chain.lastOrNull()
            ?.takeIf { now - (it.start + it.durationMs) <= RankConfig.SEQUENCE_MAX_GAP_MS }
            ?.pkg

        // ------------------------------------------------------------------- feedback
        val tapEvidence = HashMap<String, Double>()
        val tapCount = HashMap<String, Int>()
        for (t in taps) {
            val decay = halfLifeDecay((now - t.ts).coerceAtLeast(0L).toDouble(), halfLifeMs)
            // Slot -1 is a tap recorded before the position was tracked.
            val bias = if (t.slot < 0) 1.0 else slotWeight(t.slot.toDouble(), slots)
            tapEvidence[t.pkg] = (tapEvidence[t.pkg] ?: 0.0) + decay * bias
            tapCount[t.pkg] = (tapCount[t.pkg] ?: 0) + 1
        }
        // The tap-through rate an app is judged against is the one the widget actually
        // gets, measured over the same window. A guessed constant would be far too high
        // — a widget earns a tap in a low single-digit percentage of impressions — and
        // would then demote every app that has ever been shown, uniformly, which is not
        // feedback, just a penalty for having ranked.
        val totalShown = impressions.values.sumOf { it.shown }
        val ctrBase = if (totalShown >= RankConfig.CTR_MIN_SHOWN && taps.isNotEmpty()) {
            taps.size.toDouble() / totalShown
        } else {
            // Not enough of our own output to measure against. Nothing is demoted.
            0.0
        }
        val actRates = actRates(notifs, notifStarts)
        // Empty in a replay: only the latest grid was ever stored, so there is no honest
        // answer to "what was on the widget three weeks ago". It is a 1.08 tiebreak, and
        // leaving it in would apply today's grid to every past moment — the one bias that
        // would make a replay look better than the app really was.
        val sticky = if (historical) emptySet() else Prefs.lastShown(context)

        // ---------------------------------------------------------------------- score
        val out = ArrayList<Scored>(acc.size)
        // Reused across candidates; [Lifts.measureAgainst] overwrites both in full.
        val ratio = DoubleArray(F_COUNT)
        val exponent = DoubleArray(F_COUNT)
        for ((pkg, a) in acc) {
            val prior = a.prior.pow(RankConfig.VOLUME_EXPONENT)
            var score = prior
            val factors = ArrayList<Factor>(8)
            fun mul(name: String, v: Double) {
                if (v <= 0.0) return
                score *= v
                // Applied either way; only listed as a reason if it actually moved the
                // score. A signal can be legitimately weak — most apps are used both
                // in and out of meetings — and eight near-neutral terms would fill the
                // breakdown and push the ones that mattered off the end of it.
                if (abs(ln(v)) >= factorFloor) factors += Factor(name, v)
            }

            a.lifts.measureAgainst(global, ratio, exponent)
            for (f in 0 until F_COUNT) mul(F_NAMES[f], ratio[f].pow(exponent[f]))

            mul(
                "just used",
                1.0 + (RankConfig.CONTINUITY_BOOST - 1.0) * halfLifeDecay(
                    (now - a.lastEnd).coerceAtLeast(0L).toDouble(),
                    RankConfig.CONTINUITY_HALF_LIFE_MS.toDouble(),
                ),
            )
            // Deliberately opposed to the term above, over the same input. Together they
            // read as one curve: about 0.25x at the moment of closing, back through 1.0
            // inside the minute, then the slow continuity rise for the next hour. Kept as
            // two factors rather than folded into one so the replay can divide this one
            // out on its own and say what the cooldown is actually worth.
            mul("cooling", cooldownFactor(now - a.lastEnd))
            if (prevPkg != null && pkg != prevPkg) {
                mul("follows", sequenceLift(pkg, prevPkg, follow, outOf, opens, chain.size))
            }
            mul("due", dueFactor(a, now))
            mul("new", noveltyFactor(catalog[pkg]?.installedAt ?: 0L, now, a.launches))
            if (useNotif) {
                pending[pkg]?.let { mul("notification", notifFactor(it, now, actRates[pkg])) }
            }
            if (calendar != null && pkg in calendar.imminentApps) {
                mul("meeting app", RankConfig.CAL_NAMED_APP_BOOST)
            }
            mul("tapped", tapFactor(tapEvidence[pkg]))
            mul("shown", ctrFactor(impressions[pkg], tapCount[pkg] ?: 0, slots, ctrBase))
            // Keeps the grid from reshuffling on scoring noise and breaking muscle
            // memory. Applied last so it is a tiebreak, not a signal.
            if (pkg in sticky) mul("sticky", RankConfig.STICKINESS_BOOST)

            out += Scored(
                pkg = pkg,
                label = catalog[pkg]?.label ?: pkg,
                score = score,
                launches = a.launches,
                dwellMinutes = a.dwellMs / 60_000.0,
                lastUsed = a.lastEnd,
                prior = prior,
                // By distance from neutral in log space, so a 0.4x demotion outranks
                // a 1.2x boost in the explanation.
                factors = factors.sortedByDescending { abs(ln(it.value)) },
            )
        }

        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /**
     * The apps a suggestion may never be.
     *
     * Extracted because [Replay][com.lukecao.suggest.eval.Replay] has to know it too: an
     * app open the widget was forbidden from predicting is not a miss, it is not a
     * question, and counting it as one would make the same hidden dock apps that were
     * removed on purpose look like the model's biggest failure.
     */
    internal fun excludedPackages(
        context: Context,
        settings: WidgetSettings,
        home: String?,
    ): Set<String> = buildSet {
        add(context.packageName)
        home?.let { add(it) }
        // Whatever the user hid by hand — usually the apps already sitting in their
        // dock, where a suggestion would just waste a slot.
        addAll(settings.hidden)
        if (settings.hideSystemApps) addAll(SystemApps.PACKAGES)
    }

    // -------------------------------------------------------------------------- lifts

    private const val F_TOD = 0
    private const val F_DAY = 1
    private const val F_PLACE = 2
    private const val F_ANCHOR = 3
    private const val F_WIFI = 4
    private const val F_HEADPHONES = 5
    private const val F_CHARGING = 6
    private const val F_MOTION = 7
    private const val F_MEETING = 8
    private const val F_COUNT = 9

    /** Shown in the app's breakdown, so they read as reasons rather than variables. */
    private val F_NAMES = arrayOf(
        "time of day", "day", "place", "usual place", "wifi",
        "headphones", "charging", "moving", "meeting",
    )

    /**
     * Every name [rank] can put in [Scored.factors], for the ablation in
     * [Replay][com.lukecao.suggest.eval.Replay].
     *
     * Declared here rather than up beside [rank] because an `object` initialises its
     * properties in declaration order, and this one reads [F_NAMES].
     *
     * The second half is hand-kept alongside the `mul` calls, and checked rather than
     * trusted: the replay reports any factor it meets that is not listed here, so a term
     * added without being added to this list turns into a line in the report instead of
     * quietly going unmeasured.
     */
    internal val FACTOR_NAMES: List<String> = F_NAMES.toList() + listOf(
        "just used", "cooling", "follows", "due", "new", "notification", "meeting app",
        "tapped", "shown", "sticky",
    )

    private val F_WEIGHTS = doubleArrayOf(
        RankConfig.W_TIME_OF_DAY,
        RankConfig.W_DAY_OF_WEEK,
        RankConfig.W_PLACE,
        RankConfig.W_ANCHOR,
        RankConfig.W_WIFI,
        RankConfig.W_HEADPHONES,
        RankConfig.W_CHARGING,
        RankConfig.W_MOTION,
        RankConfig.W_CALENDAR,
    )

    /**
     * How well each session matched the present, per signal.
     *
     * A signal is only recorded for sessions where it is *known*, which is why [known]
     * is tracked separately from the app's total weight: an app used mostly before the
     * WiFi trail existed should be judged on the sessions we can actually place, not
     * diluted by the ones we cannot.
     */
    private class Lifts {
        val match = DoubleArray(F_COUNT)
        val known = DoubleArray(F_COUNT)
        val n = IntArray(F_COUNT)

        fun add(f: Int, w: Double, m: Double) {
            match[f] += w * m
            known[f] += w
            n[f]++
        }

        /**
         * Every signal's `P(context | app) / P(context)` and the exponent it is to be
         * raised to, written into [ratio] and [exponent].
         *
         * Both at once because the exponents are not independent of each other: the
         * location family shares a budget, and that cannot be decided one term at a
         * time. Written into caller-owned arrays because this runs once per candidate
         * app on every refresh.
         *
         * The shrink is not optional. Two sessions are always "always", so without it
         * every barely-used app looks perfectly context-locked and the widget fills up
         * with things opened once, at the office.
         */
        fun measureAgainst(global: Lifts, ratio: DoubleArray, exponent: DoubleArray) {
            for (f in 0 until F_COUNT) {
                ratio[f] = 1.0
                exponent[f] = 0.0
                val nf = n[f]
                if (nf == 0 || known[f] <= 0.0 || global.known[f] <= 0.0) continue
                val pGlobal = global.match[f] / global.known[f]
                // No contrast: either nothing ever matches this context or everything
                // does, and in both cases the signal cannot separate two apps.
                if (pGlobal <= 0.0 || pGlobal >= 1.0) continue
                val raw = (match[f] / known[f] / pGlobal)
                    .coerceIn(1.0 / RankConfig.LIFT_MAX, RankConfig.LIFT_MAX)
                // A ratio of exactly 1.0 is not a measurement, so it must not spend any
                // of the family's budget on saying nothing.
                if (raw == 1.0) continue
                ratio[f] = raw
                exponent[f] = nf / (nf + RankConfig.LIFT_SHRINK_SESSIONS) * F_WEIGHTS[f]
            }

            // Four ways of asking where you are, held to one and a bit signals' worth of
            // influence between them. See [RankConfig.W_LOCATION_BUDGET] for the
            // measurement that made this necessary; the short version is that at home
            // all four fire together and their product buried an app that was merely
            // absent from the house.
            var spent = 0.0
            for (f in LOCATION_FAMILY) spent += exponent[f]
            if (spent > RankConfig.W_LOCATION_BUDGET) {
                val scale = RankConfig.W_LOCATION_BUDGET / spent
                for (f in LOCATION_FAMILY) exponent[f] *= scale
            }
        }
    }

    /** The terms that are all, in one way or another, "where are you". */
    private val LOCATION_FAMILY = intArrayOf(F_PLACE, F_ANCHOR, F_WIFI, F_MOTION)

    private class Acc {
        val lifts = Lifts()
        var prior = 0.0
        var launches = 0
        var dwellMs = 0L
        /** Last session's start, for the inter-arrival rhythm. */
        var lastStart = 0L
        /** Last session's end, for continuity and the cooldown. */
        var lastEnd = 0L
        val gaps = ArrayList<Long>()

        fun measure(global: Lifts, f: Int, w: Double, m: Double) {
            lifts.add(f, w, m)
            global.add(f, w, m)
        }
    }

    // ------------------------------------------------------------------------- place

    /**
     * Whether a fix is precise enough to be worth comparing at all.
     *
     * A fix good to 800 m cannot distinguish home from the shops 2 km away in any
     * meaningful sense, and letting it try is worse than dropping it: it does not report
     * uncertainty, it reports a confident position that happens to be wrong.
     */
    private fun usableLocation(c: CtxSample): Boolean =
        c.hasLocation && c.accuracyMeters <= RankConfig.MAX_USABLE_ACCURACY_METERS

    /**
     * The place radius widened by both fixes' own error, added in quadrature.
     *
     * Comparing two fixes each good to 100 m against a flat 250 m radius asserts a
     * precision neither of them has. Folding the error bars into the width is ordinary
     * error propagation, and it gives the behaviour you would want by hand: as a fix gets
     * vaguer its gaussian gets broader, so it stops discriminating between apps instead
     * of discriminating wrongly.
     */
    private fun placeSigma(a: CtxSample, b: CtxSample): Double {
        val ea = a.accuracyMeters.toDouble()
        val eb = b.accuracyMeters.toDouble()
        val r = RankConfig.PLACE_RADIUS_METERS
        return sqrt(r * r + ea * ea + eb * eb)
    }

    /**
     * The two or three places you actually spend your life, recovered from the trail, so
     * that "somewhere I am not usually" becomes a thing an app can be associated with.
     *
     * This exists because [F_PLACE] cannot represent being out, and fails silently while
     * doing so. That term asks how close a past session was to the 250 m spot you are
     * standing in right now; on a trip every moment is a different spot from every other
     * one, so almost nothing in history matches and `P(context)` collapses. Measured
     * 11 km from home it came to 0.0095, against 0.79 at home — and with the denominator
     * that small every app's ratio lands next to 1.0 whatever its history, on whichever
     * side of it the noise puts them (Maps, used for the whole of that trip and nowhere
     * else, scored 0.84 — a penalty). The term does not report that it cannot tell; it
     * reports that nothing is unusual, at the one moment it was most wanted.
     *
     * Coarsening the question to "were you at a usual place, or somewhere else?" gives it
     * something with real variance to measure — 0.21 on the same trail — and pools every
     * kilometre of road into a single bucket instead of making each 250 m of it its own
     * unrepeatable place. On that trip the road came to eight separate clusters, none of
     * which the fine term could ever match again, and all eight answer this one the same
     * way. Replaying the trail at 11 km from home moved Maps from third to second of the
     * 54 apps actually scored; replaying it back at home pushed it from eleventh to
     * twenty-second, which is the same signal reading correctly in the other direction —
     * but not by the same amount, because away from home this term and [F_MOTION] are the
     * only two of the four location terms with any contrast left, while at an anchor all
     * four fire together. See the README on that; it is a property of the family rather
     * than of this term.
     *
     * Deliberately blind to *which* usual place: home and the office both answer yes.
     * Separating those is [F_PLACE]'s job, and at a place you have history for it has the
     * contrast to actually do it.
     */
    private class Anchors(samples: List<CtxSample>) {

        /** Cluster centres, and the trail time attributed to each. Parallel arrays
         *  because this is built and thrown away on every refresh. */
        private val lat = ArrayList<Double>()
        private val lon = ArrayList<Double>()
        private val dwell = ArrayList<Double>()

        /** Indices into the above that cleared the bar. Usually one or two. */
        private val anchors = ArrayList<Int>()

        init {
            var previous = 0L
            var last = -1
            var total = 0.0

            for (s in samples) {
                val gap = if (previous == 0L) {
                    0.0
                } else {
                    (s.ts - previous).coerceIn(0L, RankConfig.ANCHOR_DWELL_CAP_MS).toDouble()
                }
                previous = s.ts
                // Time we could not place is left out of the denominator as well as the
                // numerator, so a share is "of the time we know where you were" rather
                // than being quietly diluted by every failed fix.
                if (!usableLocation(s)) continue
                total += gap

                // Consecutive breadcrumbs are nearly always the same place, so trying
                // last time's answer first makes the common case a single distance check
                // instead of a scan. It matters: a few long drives leave one cluster per
                // 250 m of road, so the list is not always short.
                var hit = if (last >= 0 && near(last, s)) last else -1
                if (hit < 0) hit = lat.indices.firstOrNull { near(it, s) } ?: -1
                if (hit < 0) {
                    lat += s.lat!!
                    lon += s.lon!!
                    dwell += 0.0
                    hit = lat.size - 1
                }
                dwell[hit] += gap
                last = hit
            }

            // The bar is a share of the trail *and* an absolute floor, because either on
            // its own admits a car park: a share alone is trivially cleared on a one-day
            // trail, and a floor alone would enrol anywhere you once spent a long
            // afternoon.
            val bar = maxOf(
                RankConfig.ANCHOR_MIN_DWELL_MS.toDouble(),
                total * RankConfig.ANCHOR_DWELL_SHARE,
            )
            for (i in dwell.indices) if (dwell[i] >= bar) anchors += i
        }

        /** Whether [c] sits at one of them. Only meaningful for a usable fix. */
        fun at(c: CtxSample): Boolean = anchors.any { near(it, c) }

        /**
         * Cluster centres are the first fix that opened the cluster and are never moved.
         *
         * A running centroid would be the obvious refinement and is actively wrong here:
         * along a road each new fix would drag the centre a little further down it, and
         * one cluster would walk the length of the journey absorbing the whole trip.
         */
        private fun near(i: Int, c: CtxSample): Boolean {
            val cLat = c.lat ?: return false
            // Cheap rejection on latitude alone before the trig, since most clusters are
            // nowhere near most samples.
            if (abs(cLat - lat[i]) > DEG_PER_RADIUS) return false
            return distanceMeters(cLat, c.lon!!, lat[i], lon[i]) <= RankConfig.PLACE_RADIUS_METERS
        }

        private companion object {
            /** [RankConfig.PLACE_RADIUS_METERS] as degrees of latitude, which is the one
             *  axis where the conversion does not depend on where you are. */
            const val DEG_PER_RADIUS = RankConfig.PLACE_RADIUS_METERS / 111_320.0
        }
    }

    // ------------------------------------------------------------------------- trail

    /**
     * The stored context trail, indexed for the two questions the ranker asks of it:
     * what was going on at a given moment, and was the phone moving at the time.
     */
    private class Trail(private val samples: List<CtxSample>, computeMotion: Boolean) {

        /** Whether the phone was moving on the way to each sample; null = unknown. */
        private val moving = arrayOfNulls<Boolean>(samples.size)

        init {
            if (computeMotion) {
                for (i in 1 until samples.size) {
                    val a = samples[i - 1]
                    val b = samples[i]
                    // A coarse fix is dropped rather than compared, exactly as in the
                    // place term: a pair of vague fixes can imply any speed at all.
                    if (!usableLocation(a) || !usableLocation(b)) continue
                    val dt = b.ts - a.ts
                    if (dt <= 0L || dt > RankConfig.MOTION_MAX_GAP_MS) continue
                    val d = distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
                    // Displacement inside the fixes' own error bars is jitter, not
                    // travel. Calling that "still" is the parsimonious reading, and
                    // it is what stops a phone on a desk looking like a bus ride.
                    // Added in quadrature rather than taking the larger: two fixes each
                    // good to 100 m can sit 140 m apart without either having moved.
                    val ea = a.accuracyMeters.toDouble()
                    val eb = b.accuracyMeters.toDouble()
                    val jitter = sqrt(ea * ea + eb * eb)
                    moving[i] = d >= jitter && d / (dt / 1000.0) >= RankConfig.MOVING_SPEED_MPS
                }
            }
        }

        /** Index of the sample closest to [ts], or -1 if none is close enough to
         *  describe it. */
        fun nearest(ts: Long): Int {
            if (samples.isEmpty()) return -1
            var lo = 0
            var hi = samples.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (samples[mid].ts < ts) lo = mid + 1 else hi = mid
            }
            var best = lo
            if (lo > 0 && abs(samples[lo - 1].ts - ts) < abs(samples[lo].ts - ts)) best = lo - 1
            return if (abs(samples[best].ts - ts) <= RankConfig.CTX_MATCH_WINDOW_MS) best else -1
        }

        fun moving(index: Int): Boolean? = moving[index]

        /** The freshest motion reading, or null if the trail has gone cold. */
        fun movingNow(now: Long): Boolean? {
            for (i in samples.indices.reversed()) {
                if (!fresh(now, samples[i].ts)) return null
                moving[i]?.let { return it }
            }
            return null
        }
    }

    // ----------------------------------------------------------------------- factors

    private fun sequenceLift(
        pkg: String,
        prev: String,
        follow: Map<String, Map<String, Int>>,
        outOf: Map<String, Int>,
        opens: Map<String, Int>,
        total: Int,
    ): Double {
        val n = outOf[prev] ?: return 1.0
        if (n <= 0 || total <= 0) return 1.0
        val base = (opens[pkg] ?: 0).toDouble() / total
        if (base <= 0.0) return 1.0
        val seen = (follow[prev]?.get(pkg) ?: 0).toDouble()
        // Measured against what a pair we have never seen would score, rather than
        // against the base rate directly, so "never followed it" comes out at exactly
        // 1.0 instead of a small penalty applied to almost every app at once. That
        // penalty was a common factor and so reordered nothing, but it read as evidence
        // in the breakdown when it was only a normalising constant.
        //
        // Absence is the right neutral here regardless: three weeks of events give a
        // handful of transitions out of any one app, which is far too few to conclude
        // anything from a pair not appearing.
        val lift = 1.0 + seen / (RankConfig.SEQUENCE_SHRINK_COUNT * base)
        // Shrunk by how many transitions out of the previous app we have actually seen,
        // so one hop observed once counts for less than one seen twenty times.
        val trust = n / (n + RankConfig.SEQUENCE_SHRINK_COUNT)
        return lift
            .coerceAtMost(RankConfig.LIFT_MAX)
            .pow(trust * RankConfig.W_SEQUENCE)
    }

    /**
     * The cooldown, as a multiplier easing from [RankConfig.COOLDOWN_FLOOR] back to 1.0
     * over [RankConfig.COOLDOWN_MS].
     *
     * Geometric rather than linear, because every other term here multiplies: an even
     * ramp in log space is what "half way out of the cooldown" has to mean when the thing
     * being ramped is a factor. A linear ramp would spend most of its range close to 1.0
     * and do almost nothing for the first half of the window.
     */
    private fun cooldownFactor(sinceEnd: Long): Double {
        val window = RankConfig.COOLDOWN_MS
        if (window <= 0L) return 1.0
        val since = sinceEnd.coerceAtLeast(0L)
        if (since >= window) return 1.0
        return RankConfig.COOLDOWN_FLOOR.pow(1.0 - since.toDouble() / window)
    }

    /**
     * How overdue this app is against its own rhythm.
     *
     * The only term in the whole ranker that *rises* with time, which is exactly why it
     * adds movement the others cannot: everything else can only decay between
     * refreshes, and a score that only decays never reorders.
     */
    private fun dueFactor(a: Acc, now: Long): Double {
        if (a.launches < RankConfig.DUE_MIN_LAUNCHES || a.gaps.size < 2) return 1.0
        // Median, not mean: a fortnight's absence would drag a mean gap far off the
        // rhythm it is meant to describe.
        val typical = median(a.gaps)
        if (typical <= 0L) return 1.0
        val elapsed = (now - a.lastStart).coerceAtLeast(1L)
        val m = gaussian(ln(elapsed.toDouble() / typical), RankConfig.DUE_SIGMA_LOG)
        return 1.0 + (RankConfig.DUE_BOOST - 1.0) * m
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    /**
     * A hand up for an app too new to have a prior — and only for as long as that is
     * actually true.
     *
     * It has to fade with usage, not just with time, because `firstInstallTime` says
     * "restored two days ago" for most of the catalog on a freshly set up phone. Without
     * the [launches] fade, the boost lands on almost everything at once: a near-uniform
     * multiplier that reorders nothing and drowns the real reasons in the breakdown.
     * A handful of opens is all it takes for the prior to speak for itself.
     */
    private fun noveltyFactor(installedAt: Long, now: Long, launches: Int): Double {
        if (installedAt <= 0L) return 1.0
        val days = (now - installedAt) / RankConfig.DAY_MS.toDouble()
        if (days < 0.0 || days > RankConfig.NOVELTY_DAYS) return 1.0
        val fresh = 1.0 - days / RankConfig.NOVELTY_DAYS
        val unknown = RankConfig.NOVELTY_TRUST_LAUNCHES /
            (launches + RankConfig.NOVELTY_TRUST_LAUNCHES)
        return 1.0 + (RankConfig.NOVELTY_BOOST - 1.0) * fresh * unknown
    }

    /**
     * An outstanding notification, worth less the longer it has sat there and worth
     * nothing from an app whose notifications you never act on. Without that second
     * half the noisiest app on the phone would own the widget permanently.
     */
    private fun notifFactor(pendingTs: Long, now: Long, rate: Double?): Double {
        val decay = halfLifeDecay(
            (now - pendingTs).coerceAtLeast(0L).toDouble(),
            RankConfig.NOTIF_HALF_LIFE_MS.toDouble(),
        )
        val trust = ((rate ?: RankConfig.NOTIF_PRIOR_RATE) / RankConfig.NOTIF_PRIOR_RATE)
            .coerceIn(0.0, 1.0)
        return 1.0 + (RankConfig.NOTIF_BOOST - 1.0) * decay * trust
    }

    /**
     * The notifications plausibly still on screen at [now], for a replay.
     *
     * `pending` shrinks — a dismissal deletes the row — so there is no record of what was
     * outstanding at a past moment, only of what was ever posted. This takes the newest
     * post per app inside [PENDING_REPLAY_MS] and calls it outstanding, which is wrong
     * exactly for the notifications that were dismissed quickly and right for the ones
     * that were not.
     *
     * The window is short because the term it feeds is short: at a
     * [twenty-minute half life][RankConfig.NOTIF_HALF_LIFE_MS] two hours leaves a
     * sixty-fourth of the boost, so the error stops mattering well before the
     * approximation stops holding.
     */
    private fun historicalPending(notifs: List<Store.Notif>, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        val floor = now - PENDING_REPLAY_MS
        // Ascending, so the last write for a package is its newest post.
        for (n in notifs) if (n.ts >= floor) out[n.pkg] = n.ts
        return out
    }

    /** Fraction of an app's notifications that were followed by opening it. */
    private fun actRates(
        notifs: List<Store.Notif>,
        starts: Map<String, List<Long>>,
    ): Map<String, Double> {
        if (notifs.isEmpty()) return emptyMap()
        val total = HashMap<String, Int>()
        val acted = HashMap<String, Int>()
        for (x in notifs) {
            total[x.pkg] = (total[x.pkg] ?: 0) + 1
            val s = starts[x.pkg] ?: continue
            if (openedWithin(s, x.ts, x.ts + RankConfig.NOTIF_ACT_WINDOW_MS)) {
                acted[x.pkg] = (acted[x.pkg] ?: 0) + 1
            }
        }
        return total.mapValues { (pkg, n) ->
            ((acted[pkg] ?: 0) + RankConfig.NOTIF_SHRINK_COUNT * RankConfig.NOTIF_PRIOR_RATE) /
                (n + RankConfig.NOTIF_SHRINK_COUNT)
        }
    }

    /** [starts] ascending. */
    private fun openedWithin(starts: List<Long>, from: Long, to: Long): Boolean {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < from) lo = mid + 1 else hi = mid
        }
        return lo < starts.size && starts[lo] <= to
    }

    private fun tapFactor(evidence: Double?): Double {
        if (evidence == null || evidence <= 0.0) return 1.0
        return 1.0 + (RankConfig.TAP_BOOST - 1.0) *
            (evidence / RankConfig.TAP_SATURATION).coerceAtMost(1.0)
    }

    /**
     * Shown and ignored, as negative evidence — the only term that actively pushes a
     * stale suggestion out rather than waiting for something else to outgrow it.
     *
     * Debiased by position, because an app that spends its life in the last slot is
     * tapped less through no fault of its own, and penalising it for that would pin it
     * there forever.
     *
     * Demotion only, capped at 1.0. An above-average tap-through rate is the same
     * evidence [tapFactor] has already rewarded, and letting it through twice squares
     * the boost: one app on the real data earned 2.00x from its taps and another 2.00x
     * from the rate those taps implied, arriving at 4x for six impressions and four
     * taps. Only the silence is this term's own to report.
     */
    private fun ctrFactor(im: Store.Impressions?, taps: Int, slots: Int, base: Double): Double {
        if (base <= 0.0 || im == null || im.shown <= 0) return 1.0
        // A pseudo-count in taps, converted to impressions at the measured rate: one
        // pseudo-tap is a hundred impressions at 1% and ten at 10%, so "shown a few
        // times, never tapped" stays close to neutral while a long silence does not.
        val pseudoShown = RankConfig.CTR_PRIOR_TAPS / base
        val ctr = (taps + RankConfig.CTR_PRIOR_TAPS) / (im.shown + pseudoShown)
        val expected = base / slotWeight(im.slotSum.toDouble() / im.shown, slots)
        // Floored like a lift: an app shown two thousand times without a tap has made
        // its point, and letting the raw ratio through would bury it permanently.
        return (ctr / expected)
            .coerceIn(1.0 / RankConfig.LIFT_MAX, 1.0)
            .pow(RankConfig.IMPRESSION_EXPONENT)
    }

    /** How much harder it is to earn a tap in [slot] than in the first one. */
    private fun slotWeight(slot: Double, slots: Int): Double {
        val last = (slots - 1).coerceAtLeast(1).toDouble()
        return 1.0 + RankConfig.SLOT_BIAS * (slot.coerceIn(0.0, last) / last)
    }

    private fun agree(a: Boolean, b: Boolean): Double = if (a == b) 1.0 else 0.0

    private fun fresh(now: Long, ts: Long): Boolean = now - ts <= RankConfig.HERE_MAX_AGE_MS

    /** Below this the session is 6+ half-lives old and cannot affect the order. */
    private const val MIN_RECENCY = 0.005

    /** How far from neutral a multiplier has to be, in natural logs, to be worth
     *  naming as a reason. 0.05 is about a 5% move either way. */
    private const val FACTOR_SHOW = 0.05

    /** How far back [historicalPending] will believe a notification is still up. */
    private const val PENDING_REPLAY_MS = 2 * 60 * 60 * 1000L
}
