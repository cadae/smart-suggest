package com.lukecao.suggest.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
// The Intent-taking overload is widget-specific; androidx.glance.action's one takes
// a ComponentName and cannot carry our extras.
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lukecao.suggest.R
import com.lukecao.suggest.data.AppCatalog
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.SuggestionCache
import com.lukecao.suggest.data.WidgetSettings
import com.lukecao.suggest.ui.MainActivity
import kotlin.math.sqrt

/**
 * One grid of suggestions, at a size fixed when you pick it out of the widget picker.
 *
 * The grid used to be a setting, which was the wrong shape for the question twice over:
 * a widget occupying four cells could be told to draw seven columns, and the host had no
 * idea the thing it had placed as 4x2 now wanted to be 4x5. Every launcher already has a
 * perfectly good interface for choosing how big something is — the picker — so the size
 * is a property of the provider now, and [Widgets] declares one subclass per size. What
 * you drop on the screen is what you get.
 *
 * @param cols columns of icons; also the divisor that sets the icon size.
 * @param rows rows of icons. `cols * rows` is how many suggestions this size shows.
 */
abstract class SuggestWidget(
    val cols: Int,
    val rows: Int,
) : GlanceAppWidget() {

    /**
     * The receiver declaring this size in the manifest.
     *
     * Glance resolves a widget's placed instances by looking up the receiver whose
     * `glanceAppWidget` is of this exact class, so the pairing is one-to-one and already
     * implied — but nothing can ask it the other way round, and [Widgets.maxSlots] needs
     * to know which sizes are actually on a home screen.
     */
    abstract val receiver: Class<out GlanceAppWidgetReceiver>

    val slots: Int get() = cols * rows

    /** Exact so the cells can be sized from the real measured width and height. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = Prefs.settings(context)
        val snapshot = SuggestionCache.getOrCompute(context)
        val shown = snapshot.items.take(slots)

        val options = hostOptions(context, id)
        val scale = hostScale(options)
        // Rasterised here, off the main thread, and memory-cached by AppCatalog.
        val px = iconPx(context, cols, slots, hostWidthDp(options), scale)
        val icons = shown.associate { it.pkg to AppCatalog.icon(context, it.pkg, px) }

        provideContent { Body(settings, shown, icons, scale) }
    }

    @Composable
    private fun Body(
        settings: WidgetSettings,
        items: List<SuggestionCache.Item>,
        icons: Map<String, Icon?>,
        scale: Float,
    ) {
        val size = LocalSize.current
        // Everything here is in the host's pre-scale space, [LocalSize] included, so
        // our own sizes have to be grossed up to survive the shrink on the way out.
        // Divide, and what the constants say is what lands on the screen.
        fun up(dp: Float) = dp / scale

        val pad = up(PAD_DP)
        val cellW = (size.width.value - 2 * pad) / cols
        val cellH = (size.height.value - 2 * pad) / rows
        // Already in the host's pre-scale space, because [cellW] is: no up() here, and
        // that is the whole point. The icon size used to be a number the user nudged
        // until the widget sat level with the dock, which meant re-nudging it after
        // every change to the launcher's grid. A fraction of the cell we were handed
        // tracks that on its own — the launcher sizes its own icons the same way.
        val iconDp = minOf(
            cellW * ICON_CELL_FRACTION,
            cellW - up(6f),
            cellH - up(LABEL_BLOCK_DP),
        ).coerceAtLeast(up(20f))

        // Transparent has to be stated, not implied. Omitting the modifier does not
        // clear a background, it declines to set one — so the root keeps whatever it
        // already had, which is why switching the background off left the last colour
        // on screen instead of going clear.
        var root = GlanceModifier
            .fillMaxSize()
            .background(
                ColorProvider(
                    if (settings.backgroundEnabled) {
                        Color(settings.backgroundArgb)
                    } else {
                        Color.Transparent
                    },
                ),
            )
        // Rounding a transparent box would only clip our own icons at the corners.
        if (settings.backgroundEnabled) root = root.cornerRadius(24.dp)

        Column(modifier = root.padding(pad.dp)) {
            if (items.isEmpty()) {
                EmptyState(scale)
            } else {
                for (r in 0 until rows) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (c in 0 until cols) {
                            val i = r * cols + c
                            if (i < items.size) {
                                Cell(
                                    item = items[i],
                                    slot = i,
                                    icon = icons[items[i].pkg],
                                    iconDp = iconDp,
                                    scale = scale,
                                    modifier = GlanceModifier.defaultWeight(),
                                )
                            } else {
                                Spacer(GlanceModifier.defaultWeight())
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState(scale: Float) {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(
                    actionStartActivity(Intent(context, MainActivity::class.java)),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // A resource rather than a literal: this is the only user-facing
                // sentence the widget ever shows, so it is the one string that has to
                // be translatable.
                text = context.getString(R.string.widget_empty),
                style = TextStyle(
                    color = LABEL,
                    fontSize = (11f / scale).sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    @Composable
    private fun Cell(
        item: SuggestionCache.Item,
        slot: Int,
        icon: Icon?,
        iconDp: Float,
        scale: Float,
        modifier: GlanceModifier,
    ) {
        val context = LocalContext.current
        val intent = Intent(context, LaunchTrampolineActivity::class.java)
            // Distinct data per slot, otherwise all the PendingIntents collapse
            // into one and every icon opens the same app.
            .setData(Uri.fromParts("suggest", item.pkg, null))
            .putExtra(LaunchTrampolineActivity.EXTRA_PKG, item.pkg)
            .putExtra(LaunchTrampolineActivity.EXTRA_SLOT, slot)

        Column(
            modifier = modifier.clickable(actionStartActivity(intent)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = item.label,
                    modifier = GlanceModifier.size(iconDp.dp),
                )
            } else {
                Spacer(GlanceModifier.size(iconDp.dp))
            }
            Spacer(GlanceModifier.size((GAP_DP / scale).dp))
            Text(
                text = item.label,
                maxLines = 1,
                style = TextStyle(
                    color = LABEL,
                    // Grossed up like everything else, or the host's shrink would
                    // leave the labels a barely legible 7sp.
                    fontSize = (LABEL_SP / scale).sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    private companion object {
        /** All four of these are real, on-screen dp. See `up()` in [Body]. */
        const val PAD_DP = 8f
        const val GAP_DP = 3f
        const val LABEL_SP = 9f

        /** Vertical room a cell needs for its label, below the icon. */
        const val LABEL_BLOCK_DP = 17f

        /**
         * How much of its cell's width an icon fills.
         *
         * This is the launcher's own proportion rather than a taste: measured against
         * One UI Home at its stock 5x8 grid, a dock icon draws into a 173px box and the
         * cell it sits in is 248px, which is 0.70. Because it is a fraction of whatever
         * cell we are handed rather than a dp figure, it follows the user's grid setting
         * for free — a tighter grid means narrower cells means smaller icons, exactly as
         * it does for the launcher's own.
         *
         * Verified end to end on the device: a 4-column placement reports 496dp before
         * One UI's 0.8 shrink, giving cells of (496 - 20) / 4 = 119dp, of which 0.70 is
         * 83dp, which arrives on screen at 83 x 0.8 = 66dp — the measured dock size.
         */
        const val ICON_CELL_FRACTION = 0.70f

        /**
         * The cell width assumed when the host has not told us how big we are, in the
         * host's own dp. Only reached for a preview or the moment before the first
         * resize callback, and only affects how sharp the raster is.
         */
        const val NOMINAL_CELL_DP = 100f

        /**
         * A raw-ARGB budget for the whole grid, kept well above the ~1 MB RemoteViews
         * transaction limit because [AppCatalog.icon] sends PNG and that comes in
         * around a tenth of the raw size. It only bites at the largest sizes: 4x2
         * rasters at full size, 4x5 trades a little sharpness for fitting.
         */
        const val BITMAP_BUDGET_BYTES = 2_500_000

        /** One UI Home's shrink factor for third-party widgets. */
        const val KEY_RESIZE_RATIO = "hsResizeRatio"

        val LABEL = ColorProvider(Color(0xFFFFFFFF))

        /** Everything the host has said about this instance's size, or null for one it
         *  has never sized — a preview, or the instant before the first callback. */
        fun hostOptions(context: Context, id: GlanceId): Bundle? =
            try {
                val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
                AppWidgetManager.getInstance(context)?.getAppWidgetOptions(widgetId)
            } catch (_: Throwable) {
                null
            }

        /**
         * How much the host will shrink our layout before it reaches the screen.
         *
         * One UI Home gives third-party widgets a canvas 1.25x wider than the cells
         * they actually occupy and then scales the rendering back down, saying so in
         * the options bundle: a 4-column placement of ours is reported as 496dp where
         * Samsung's own 4-column widget is reported as 396dp, and 396/496 is the 0.8
         * it puts in [KEY_RESIZE_RATIO]. Ignore it and every dp we ask for arrives a
         * fifth smaller — a 66dp icon landing at 53dp, visibly under the dock.
         *
         * Launchers that do not do this have no such key, so this reads 1.0 and the
         * arithmetic everywhere else becomes a no-op.
         */
        @Suppress("DEPRECATION") // Bundle.get, because the host is free to have
        // stored this as either a Float or a Double and a typed read of the wrong
        // one silently yields the default.
        fun hostScale(options: Bundle?): Float =
            ((options?.get(KEY_RESIZE_RATIO) as? Number)?.toFloat() ?: 1f)
                // Trust it only within a sane band — a stray value would blow the
                // layout up rather than merely misjudge it.
                .coerceIn(0.5f, 1f)

        /**
         * The widest the host says this instance may be, in its own pre-scale dp.
         *
         * The largest of everything on offer, because this only decides what size to
         * raster at: downsampling a bitmap costs nothing visible, and under-rastering
         * shows as blur.
         */
        fun hostWidthDp(options: Bundle?): Float? {
            if (options == null) return null
            val best = maxOf(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            )
            return if (best > 0) best.toFloat() else null
        }

        /**
         * Raster at roughly the size the icon will be laid out at, so the only
         * resampling is the host's own shrink — the same one every other pixel in the
         * widget goes through.
         *
         * "Roughly", because the exact cell is only known inside composition and
         * bitmaps must not be built there. This repeats the arithmetic from [Body]
         * against the upper bound of the host's reported width, so it errs large.
         */
        fun iconPx(
            context: Context,
            cols: Int,
            slots: Int,
            widthDp: Float?,
            scale: Float,
        ): Int {
            val width = widthDp ?: (cols * NOMINAL_CELL_DP + 2 * PAD_DP / scale)
            val cellW = (width - 2 * PAD_DP / scale) / cols
            val wanted = (cellW * ICON_CELL_FRACTION *
                context.resources.displayMetrics.density).toInt()
            val budget = sqrt(BITMAP_BUDGET_BYTES / slots.coerceAtLeast(1) / 4.0).toInt()
            return wanted.coerceAtMost(budget).coerceAtLeast(48)
        }
    }
}
