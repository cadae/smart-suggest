package com.lukecao.suggest.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.lukecao.suggest.rank.RankConfig
import com.lukecao.suggest.work.Scheduler

/**
 * The sizes on offer in the widget picker, and the only place that knows the full set.
 *
 * One class per size rather than one resizable widget, because Glance resolves a
 * widget's placed instances by its exact class: `updateAll` on a shared class would
 * only ever find the instances of whichever receiver declared that class, so every
 * size needs its own pair. The cost is this boilerplate; the benefit is that the host
 * knows how many cells each one wants and reserves them, which a resizable widget
 * guessing at its own row count never manages.
 *
 * 4x2 keeps the original class names. Its receiver is the one already sitting on a home
 * screen, and an `install -r` that renamed it would silently delete the widget.
 */
class SuggestWidget2x2 : SuggestWidget(2, 2) {
    override val receiver = SuggestWidgetReceiver2x2::class.java
}

class SuggestWidget2x3 : SuggestWidget(2, 3) {
    override val receiver = SuggestWidgetReceiver2x3::class.java
}

class SuggestWidget4x2 : SuggestWidget(4, 2) {
    override val receiver = SuggestWidgetReceiver::class.java
}

class SuggestWidget4x3 : SuggestWidget(4, 3) {
    override val receiver = SuggestWidgetReceiver4x3::class.java
}

class SuggestWidget4x5 : SuggestWidget(4, 5) {
    override val receiver = SuggestWidgetReceiver4x5::class.java
}

object Widgets {

    /** Smallest first, which is the order the picker lists them in. */
    fun all(): List<SuggestWidget> = listOf(
        SuggestWidget2x2(),
        SuggestWidget2x3(),
        SuggestWidget4x2(),
        SuggestWidget4x3(),
        SuggestWidget4x5(),
    )

    /**
     * The most suggestions any size can show, which is how deep the cache has to go.
     *
     * A plain constant rather than `all().maxOf { it.slots }` because
     * [SuggestionCache][com.lukecao.suggest.data.SuggestionCache] sizes an array with
     * it before any widget exists. Kept honest by [check] below.
     */
    const val MAX_SLOTS = 20

    init {
        check(all().maxOf { it.slots } == MAX_SLOTS) { "MAX_SLOTS is out of date" }
    }

    /** Pushes a freshly computed snapshot to every placed widget of every size. */
    suspend fun updateAll(context: Context) {
        for (w in all()) w.updateAll(context)
    }

    /**
     * How many suggestions are actually on a home screen right now — the largest placed
     * size, or the default if nothing is placed at all.
     *
     * The impression and stickiness terms both need to know what the user was shown, and
     * with several sizes available that is the union over the placed ones, which for
     * nested grids is just the biggest. Counting every size regardless would credit the
     * bottom of a 4x5 with impressions nobody ever saw, and that is negative evidence:
     * apps would be demoted for being ignored on a widget that does not exist.
     */
    fun maxSlots(context: Context): Int {
        var best = 0
        for (w in all()) if (placedCount(context, w) > 0) best = maxOf(best, w.slots)
        return if (best > 0) best else RankConfig.SLOTS
    }

    /** How many copies of one size are on a home screen. Shown in the app, so you can
     *  tell a widget that is missing from one that is merely empty. */
    fun placedCount(context: Context, w: SuggestWidget): Int =
        try {
            AppWidgetManager.getInstance(context)
                ?.getAppWidgetIds(ComponentName(context, w.receiver))
                ?.size ?: 0
        } catch (_: Throwable) {
            // A provider the host has never heard of. Not placed, then.
            0
        }
}

/**
 * Shared behaviour for all five receivers: make sure the schedule that keeps the
 * snapshot warm is actually running.
 *
 * Placing any widget is the moment this app first has a reason to do background work,
 * and [onUpdate] arriving is the cheapest possible reminder that it should still be.
 */
abstract class SuggestReceiver : GlanceAppWidgetReceiver() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensurePeriodicRefresh(context)
        Scheduler.refreshNow(context)
    }

    /** Fires on the updatePeriodMillis heartbeat as well as our own pushes. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Scheduler.ensurePeriodicRefresh(context)
    }
}

class SuggestWidgetReceiver2x2 : SuggestReceiver() {
    override val glanceAppWidget = SuggestWidget2x2()
}

class SuggestWidgetReceiver2x3 : SuggestReceiver() {
    override val glanceAppWidget = SuggestWidget2x3()
}

class SuggestWidgetReceiver4x3 : SuggestReceiver() {
    override val glanceAppWidget = SuggestWidget4x3()
}

class SuggestWidgetReceiver4x5 : SuggestReceiver() {
    override val glanceAppWidget = SuggestWidget4x5()
}
