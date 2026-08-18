package com.lukecao.suggest.eval

import android.content.Context
import android.util.Log
import com.lukecao.suggest.data.AppCatalog
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.rank.RankConfig
import com.lukecao.suggest.rank.Ranker
import com.lukecao.suggest.rank.Scored
import com.lukecao.suggest.rank.SessionLog
import kotlin.math.max

/**
 * Asks the ranker, at every past moment the history contains, which app the user was
 * about to open — and scores its answers.
 *
 * Every constant in [RankConfig] was arrived at by argument plus replaying the trail by
 * hand. That was enough to catch outright errors, and not enough to answer the questions
 * that actually matter: is the place term earning the location permission it costs, is the
 * cooldown helping or just hiding the answer, would plain frecency do as well. Each of
 * those is one number, and none of them could be produced.
 *
 * The design rule here is that there is exactly one ranker. This calls the real
 * [Ranker.rank] with `historical = true` rather than reimplementing the scoring, because a
 * harness that scores differently from production measures the harness.
 *
 * What it cannot do honestly, it says so about rather than working around:
 *  - the calendar and the app catalog are read as they are *now*, so an event since
 *    deleted or an app since uninstalled is invisible at a moment where it was there;
 *  - impressions are day buckets, so a moment sees its own whole day of them;
 *  - notifications outstanding at a past moment are reconstructed, not recorded — see
 *    [Ranker.rank]'s `historical` parameter.
 * None of these can be fixed by measuring more carefully. They are the price of not
 * having logged the inputs at the time, which is the other half of what this file is for:
 * from now on, [SessionLog] does.
 */
object Replay {

    const val TAG = "SuggestEval"

    /**
     * How deep to rank. Far past the widget, because the point is *where* the answer
     * landed: an app at 30 and an app that was never a candidate are the same miss at
     * Hit@8 and very different problems.
     */
    private const val DEPTH = 400

    /**
     * History a moment needs behind it before it is worth predicting.
     *
     * The first day of a fresh log has almost no prior and no rhythm, and scoring it
     * measures the cold start rather than the model. Three days is where the
     * [recency half life][RankConfig.RECENCY_HALF_LIFE_DAYS] has had one full turn.
     */
    private val WARMUP_MS = 3 * RankConfig.DAY_MS

    /**
     * A ranking costs a full read of three weeks of history, so the number of moments is
     * capped and the excess is dropped by taking every Nth — evenly across the window
     * rather than the first N, which would only ever measure the oldest, thinnest part of
     * the log. The report says what the stride was.
     *
     * Raised from 400, which was throwing away three quarters of the data and costing the
     * report its ability to answer anything. Ablations here are paired orderings that
     * differ on a handful of moments, so at 338 evaluated the error bar on a difference
     * was around a point of Hit@4 — and every single-term result except removing context
     * altogether was inside it. Two of the first three findings evaporated on the second
     * run for exactly that reason. The cap costs about 45 seconds at 1,351 moments, which
     * is the right trade for halving the error bars on a diagnostic run by hand.
     */
    private const val MAX_MOMENTS = 1500

    /** Below this there is nothing to say and the numbers would be noise. */
    private const val MIN_MOMENTS = 20

    /** One moment worth predicting: what was opened, and when. */
    private class Moment(
        /** Just before the open, so the ranking cannot see the answer in its own input. */
        val at: Long,
        val label: String,
        /** Whether the launcher was the app before it — the closest thing the history has
         *  to "the home screen, and so the widget, was on screen". */
        val fromHome: Boolean,
        /** What was open before the launcher, when [fromHome]. A great many home-screen
         *  visits are a detour out of one app and straight back into it, and that is a
         *  different prediction problem from picking the next app. */
        val beforeHome: String?,
        /** End of the label app's own previous session, or 0. For attributing misses to
         *  [RankConfig.COOLDOWN_MS]. */
        val labelLastEnd: Long,
    )

    /** Running score for one way of ordering the candidates. */
    private class Tally(val name: String) {
        var moments = 0
        var reached = 0
        var hit1 = 0
        var hit4 = 0
        var hit8 = 0
        var mrr = 0.0

        /** @param position zero-based, or -1 when the app was not a candidate at all. */
        fun add(position: Int) {
            moments++
            if (position < 0) return
            reached++
            if (position < 1) hit1++
            if (position < 4) hit4++
            if (position < 8) hit8++
            mrr += 1.0 / (position + 1)
        }

        fun line(): String {
            if (moments == 0) return "%-16s no moments".format(name)
            val n = moments.toDouble()
            return "%-16s n %4d  Hit@1 %5.1f%%  Hit@4 %5.1f%%  Hit@8 %5.1f%%  MRR %.3f  reach %5.1f%%"
                .format(
                    name, moments,
                    100 * hit1 / n, 100 * hit4 / n, 100 * hit8 / n, mrr / n, 100 * reached / n,
                )
        }
    }

    /**
     * Runs the whole thing. Slow on purpose — a full ranking per moment — so call it off
     * the main thread and expect tens of seconds.
     *
     * @return the report, which is also written to logcat under [TAG] a line at a time,
     *   because a single log message this long is truncated.
     */
    fun run(context: Context, now: Long): String {
        val started = System.currentTimeMillis()
        val out = StringBuilder()
        fun say(line: String) {
            Log.i(TAG, line)
            out.append(line).append('\n')
        }

        val from = now - RankConfig.LOOKBACK_DAYS * RankConfig.DAY_MS
        val history = SessionLog.history(context, from, now)
        val catalog = AppCatalog.launchable(context)
        val settings = Prefs.settings(context)
        val home = AppCatalog.homePackage(context)
        val excluded = Ranker.excludedPackages(context, settings, home)

        if (history.isEmpty() || catalog.isEmpty()) {
            return "no history yet — the session log fills as the app refreshes\n"
        }

        // ------------------------------------------------------------------- moments
        val moments = ArrayList<Moment>()
        val lastEnd = HashMap<String, Long>()
        var offLimits = 0
        val warmupEnd = history.first().start + WARMUP_MS
        for (i in history.indices) {
            val s = history[i]
            val previousEnd = lastEnd[s.pkg] ?: 0L
            lastEnd[s.pkg] = max(previousEnd, s.start + s.durationMs)
            if (s.start < warmupEnd) continue
            if (!catalog.containsKey(s.pkg)) continue
            // Not a miss and not a question: the widget is not allowed to answer these.
            if (s.pkg in excluded) {
                offLimits++
                continue
            }
            // Coming back to the app you were already in is not a choice the widget
            // could have helped with.
            val prev = history.getOrNull(i - 1)
            if (prev != null && prev.pkg == s.pkg) continue
            val fromHome = home != null && prev?.pkg == home
            moments += Moment(
                at = s.start - 1,
                label = s.pkg,
                fromHome = fromHome,
                beforeHome = if (fromHome) history.getOrNull(i - 2)?.pkg else null,
                labelLastEnd = previousEnd,
            )
        }

        val stride = if (moments.size > MAX_MOMENTS) {
            (moments.size + MAX_MOMENTS - 1) / MAX_MOMENTS
        } else {
            1
        }
        val sampled = moments.filterIndexed { i, _ -> i % stride == 0 }

        say("=== replay ===")
        say(
            "history  %d sessions over %.1f days, %d apps in the catalog"
                .format(history.size, (now - history.first().start) / RankConfig.DAY_MS.toDouble(), catalog.size)
        )
        say(
            "moments  %d of %d evaluated (1 in %d), %d dropped by the stride, %d off-limits opens"
                .format(sampled.size, moments.size, stride, moments.size - sampled.size, offLimits)
        )
        // The three things package visibility can quietly break, now that
        // QUERY_ALL_PACKAGES is not declared. A launcher that resolves to null stops the
        // widget excluding the launcher itself, and installedAt is 0 whenever the package
        // manager declines to say, which switches novelty off without complaining.
        say(
            "visible  launcher %s, %d of %d apps have an install date"
                .format(home ?: "UNRESOLVED", catalog.values.count { it.installedAt > 0L }, catalog.size)
        )

        if (sampled.size < MIN_MOMENTS) {
            say("too few moments to say anything — let the log run for a few days")
            return out.toString()
        }

        // -------------------------------------------------------------------- replay
        val full = Tally("full model")
        val frecency = Tally("frecency only")
        val recent = Tally("most recent")
        // The same three again over the launcher-preceded subset. Reported separately
        // rather than instead, because the two populations answer different questions and
        // the smaller one is the one the widget lives in: an aggregate that includes every
        // hop between two apps is measuring recents and back, which the widget is not.
        val homeFull = Tally("full model")
        val homeFrecency = Tally("frecency only")
        val homeRecent = Tally("most recent")
        val ablations = Ranker.FACTOR_NAMES.associateWith { Tally(it) }
        val withoutPrior = Tally("(context only)")
        // The moments the cooldown used to make impossible, scored on their own. This is
        // the population [RankConfig.COOLDOWN_FLOOR] is set against: it says where a
        // just-closed app actually lands now, which turns "is 0.1 too harsh" into a
        // question with an answer. Expect it to be *worse* than the aggregate and to stay
        // that way — the floor is deliberately costing accuracy here, because a slot spent
        // on the app you closed twenty seconds ago is wasted even when it is right.
        val cooling = Tally("inside cooldown")
        var cooldownMisses = 0
        var otherMisses = 0
        var homeReturns = 0
        var homeReturnsMissed = 0
        val everShown = HashSet<String>()
        val unlisted = HashSet<String>()
        var previousTop: List<String>? = null
        var overlapSum = 0.0
        var overlapN = 0

        for ((i, m) in sampled.withIndex()) {
            if (i > 0 && i % 50 == 0) Log.i(TAG, "… $i/${sampled.size}")
            val ranked = Ranker.rank(
                context = context,
                now = m.at,
                limit = DEPTH,
                historical = true,
                factorFloor = 0.0,
            )
            if (ranked.isEmpty()) continue

            val position = ranked.indexOfFirst { it.pkg == m.label }
            full.add(position)
            val insideCooldown = m.labelLastEnd > 0 &&
                m.at - m.labelLastEnd < RankConfig.COOLDOWN_MS
            if (insideCooldown) cooling.add(position)
            if (position < 0) {
                // Now that the cooldown is a multiplier rather than an exclusion the first
                // of these should be zero, and it is kept as the check on that: anything
                // landing there means something *else* is dropping a just-closed app out
                // of the candidate set, which is worth knowing and would otherwise be
                // filed under "no history".
                if (insideCooldown) {
                    cooldownMisses++
                } else {
                    otherMisses++
                }
            }

            // Both baselines are drawn from the same candidate set, so the comparison is
            // about the ordering and nothing else.
            val byPrior = reorder(ranked, m.label) { it.prior }
            val byRecency = reorder(ranked, m.label) { it.lastUsed.toDouble() }
            frecency.add(byPrior)
            recent.add(byRecency)
            if (m.fromHome) {
                homeFull.add(position)
                homeFrecency.add(byPrior)
                homeRecent.add(byRecency)
                if (m.beforeHome == m.label) {
                    homeReturns++
                    if (position < 0) homeReturnsMissed++
                }
            }

            // Ablation by division, which is exact: the score is a product, so removing a
            // term is dividing it back out. No second scoring path to keep in step.
            for ((name, tally) in ablations) {
                tally.add(reorder(ranked, m.label) { s -> s.score / factor(s, name) })
            }
            withoutPrior.add(reorder(ranked, m.label) { s -> s.score / max(s.prior, MIN_DIVISOR) })

            for (s in ranked) for (f in s.factors) if (f.name !in ablations) unlisted += f.name

            val top = ranked.take(RankConfig.SLOTS).map { it.pkg }
            everShown += top
            previousTop?.let { before ->
                overlapSum += before.count { it in top } / RankConfig.SLOTS.toDouble()
                overlapN++
            }
            previousTop = top
        }

        // -------------------------------------------------------------------- report
        say("")
        say("every moment")
        say(full.line())
        say(frecency.line())
        say(recent.line())
        say("")
        say("only the ones opened from the home screen — where the widget actually is")
        say(homeFull.line())
        say(homeFrecency.line())
        say(homeRecent.line())
        say(
            "  of those, %d were a return to the app open before the home screen, %d of them unreachable"
                .format(homeReturns, homeReturnsMissed)
        )
        say("")
        say(
            "misses   %d unreachable: %d inside the %ds cooldown, %d no history"
                .format(
                    cooldownMisses + otherMisses,
                    cooldownMisses,
                    RankConfig.COOLDOWN_MS / 1000,
                    otherMisses,
                )
        )
        say("")
        say(
            "the %ds cooldown no longer excludes — where a just-closed app lands instead"
                .format(RankConfig.COOLDOWN_MS / 1000)
        )
        say(cooling.line())
        say(
            "grid     %d distinct apps ever in a top-%d, %.0f%% of it held between moments"
                .format(
                    everShown.size,
                    RankConfig.SLOTS,
                    if (overlapN > 0) 100 * overlapSum / overlapN else 0.0,
                )
        )

        say("")
        say("with each term removed, against full model Hit@4 ${pct(full.hit4, full.moments)}")
        // Biggest loser first: the term whose removal costs the most is the one earning
        // its keep. A term that improves the model by leaving is the interesting case and
        // sorts to the bottom, which is where it will be looked for.
        val ranking = (ablations.values + withoutPrior)
            .filter { it.moments > 0 }
            .sortedBy { it.hit4 }
        for (t in ranking) {
            say(
                "  %-16s Hit@4 %5.1f%% (%+5.1f)  MRR %.3f (%+.3f)".format(
                    t.name,
                    100.0 * t.hit4 / t.moments,
                    100.0 * t.hit4 / t.moments - 100.0 * full.hit4 / full.moments,
                    t.mrr / t.moments,
                    t.mrr / t.moments - full.mrr / full.moments,
                )
            )
        }
        if (unlisted.isNotEmpty()) {
            say("")
            say("not ablated, missing from Ranker.FACTOR_NAMES: ${unlisted.sorted()}")
        }
        say("")
        say("took ${(System.currentTimeMillis() - started) / 1000}s")
        return out.toString()
    }

    /**
     * Where [label] lands when the candidates are ordered by [key] instead of by score.
     *
     * Descending, and stable, so candidates the key does not distinguish keep the order
     * the ranker gave them — an ablation should move the apps the term touched and leave
     * the rest where they were.
     */
    private inline fun reorder(
        ranked: List<Scored>,
        label: String,
        crossinline key: (Scored) -> Double,
    ): Int = ranked
        .sortedByDescending { key(it) }
        .indexOfFirst { it.pkg == label }

    /** One factor's multiplier for one app, or 1.0 if it did not apply to it. */
    private fun factor(s: Scored, name: String): Double {
        val v = s.factors.firstOrNull { it.name == name }?.value ?: return 1.0
        return max(v, MIN_DIVISOR)
    }

    /** Guards the division. Every term is a positive multiplier, so this never fires;
     *  it is here so that a future one that can reach zero cannot produce an infinity
     *  that silently sorts to the top of an ablation. */
    private const val MIN_DIVISOR = 1e-12

    private fun pct(n: Int, of: Int): String =
        if (of == 0) "n/a" else "%.1f%%".format(100.0 * n / of)
}
