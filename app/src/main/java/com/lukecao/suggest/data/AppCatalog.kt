package com.lukecao.suggest.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.os.Process
import android.util.LruCache
import java.io.ByteArrayOutputStream

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    /** First install, not last update: an app updated yesterday is not a new app.
     *  0 when the package manager would not say. */
    val installedAt: Long,
)

/**
 * The set of apps we are allowed to suggest, with their labels and icons.
 *
 * UsageStats reports package names for plenty of things that have no launcher
 * entry — system services, IMEs, background components. Intersecting with
 * [LauncherApps] is what keeps those out of the widget.
 */
object AppCatalog {

    private const val CATALOG_TTL_MS = 5 * 60 * 1000L

    private var catalog: Map<String, AppEntry>? = null
    private var catalogAt = 0L
    private val icons = LruCache<String, Icon>(48)

    @Synchronized
    fun launchable(context: Context): Map<String, AppEntry> {
        val now = System.currentTimeMillis()
        catalog?.let { if (now - catalogAt < CATALOG_TTL_MS) return it }

        val la = context.getSystemService(LauncherApps::class.java)
            ?: return emptyMap<String, AppEntry>().also { catalog = it; catalogAt = now }

        val installed = installTimes(context)
        val out = HashMap<String, AppEntry>(256)
        // Own profile only. Cross-profile launching needs the launcher role, which
        // a plain widget app cannot hold.
        for (info in la.getActivityList(null, Process.myUserHandle())) {
            val pkg = info.applicationInfo.packageName
            // First launcher activity wins; apps with several entries (e.g. dual
            // messengers) collapse to one slot.
            if (!out.containsKey(pkg)) {
                out[pkg] = AppEntry(
                    pkg = pkg,
                    label = info.label?.toString() ?: pkg,
                    component = info.componentName,
                    installedAt = installed[pkg] ?: 0L,
                )
            }
        }
        catalog = out
        catalogAt = now
        return out
    }

    /**
     * When each package first arrived, for the novelty term.
     *
     * One bulk query rather than a `getPackageInfo` per app: this runs on every catalog
     * rebuild, and two hundred separate IPCs to learn two hundred longs is not a
     * reasonable way to ask.
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun installTimes(context: Context): Map<String, Long> = try {
        // Lint is right that this no longer returns every installed app, and that is
        // fine: package visibility narrows it to what the <queries> MAIN/LAUNCHER
        // filter matches, which is the same launchable set the catalog itself holds. The
        // alternative is QUERY_ALL_PACKAGES, a sensitive permission needing a written
        // justification to Play, in exchange for install dates of apps we never rank.
        context.packageManager.getInstalledPackages(0)
            .associate { it.packageName to it.firstInstallTime }
    } catch (_: Throwable) {
        // Novelty is a nicety; losing it must not cost us the catalog.
        emptyMap()
    }

    @Synchronized
    fun invalidate() {
        catalog = null
        icons.evictAll()
    }

    /**
     * The app's launcher icon, rasterised at [sizePx] and PNG-compressed.
     *
     * Compressed rather than handed over as a raw [Bitmap] because every icon
     * crosses into the launcher inside a single RemoteViews transaction, and that
     * has a hard limit near 1 MB. A 189px icon is 143 KB as ARGB_8888 and roughly
     * a tenth of that as PNG — the difference between rastering at the size the
     * icon is actually drawn and rastering small and letting it go soft.
     *
     * What comes back from [LauncherApps] is the app's raw drawable, not the
     * launcher-ready icon, so [render] has to do the shaping and shadowing that a
     * launcher's icon factory would.
     */
    fun icon(context: Context, pkg: String, sizePx: Int): Icon? {
        val key = "$pkg@$sizePx"
        icons.get(key)?.let { return it }

        val entry = launchable(context)[pkg] ?: return null
        val la = context.getSystemService(LauncherApps::class.java) ?: return null
        val drawable: Drawable = try {
            la.getActivityList(pkg, Process.myUserHandle())
                .firstOrNull { it.componentName == entry.component }
                ?.getIcon(0)
                ?: context.packageManager.getApplicationIcon(pkg)
        } catch (t: Throwable) {
            return null
        }

        val bmp = render(drawable, sizePx)

        val sink = ByteArrayOutputStream(sizePx * sizePx / 4)
        val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, sink)
        bmp.recycle()
        if (!ok) return null

        val bytes = sink.toByteArray()
        val icon = Icon.createWithData(bytes, 0, bytes.size)
        icons.put(key, icon)
        return icon
    }

    /**
     * Draws [drawable] into a square the way a launcher's icon factory would:
     * shaped by [shape], then lifted off the wallpaper by a two-part drop shadow.
     *
     * Without the shadow the icons read as flat stickers next to the home screen's
     * own, which have one. It is two passes because one is not convincing: a faint
     * ambient ring all the way round for contact with the surface, and a stronger
     * key shadow dropped below it for the light direction. Both follow the icon's
     * real silhouette — taken from its alpha channel rather than from the mask — so
     * a circular icon casts a circular shadow.
     *
     * The face shrinks to make room for its own blur, which is also what the
     * launcher does: a dock icon reports a 179px bitmap drawn into 173px of bounds.
     */
    private fun render(drawable: Drawable, sizePx: Int): Bitmap {
        val blur = sizePx * SHADOW_BLUR
        val drop = sizePx * SHADOW_DROP
        val inset = (blur + drop).toInt()
        val side = sizePx - 2 * inset

        val shaped = shape(drawable)
        if (blur < 1f || side < 1) {
            // Too small for a blur radius Skia will accept. Shape it and move on.
            return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
                shaped.setBounds(0, 0, sizePx, sizePx)
                shaped.draw(Canvas(it))
            }
        }

        val face = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        shaped.setBounds(0, 0, side, side)
        shaped.draw(Canvas(face))

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // extractAlpha applies the blur and reports where the grown bitmap has to
        // sit for its centre to line up with the face's.
        val at = IntArray(2)
        val silhouette = face.extractAlpha(
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
            at,
        )
        val x = (inset + at[0]).toFloat()
        val y = (inset + at[1]).toFloat()
        val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        tint.color = AMBIENT_SHADOW
        canvas.drawBitmap(silhouette, x, y, tint)
        tint.color = KEY_SHADOW
        canvas.drawBitmap(silhouette, x, y + drop, tint)
        silhouette.recycle()

        canvas.drawBitmap(face, inset.toFloat(), inset.toFloat(), null)
        face.recycle()
        return out
    }

    /**
     * Puts a legacy icon into the system's icon shape.
     *
     * Apps that never shipped an adaptive icon hand back a plain square bitmap, and
     * dropped among masked icons it reads as a postage stamp — which is exactly what
     * Foxcloud 2.0 and friends look like untreated. Wrapping it as the background and
     * foreground layers of an [AdaptiveIconDrawable] borrows the platform's own mask,
     * whatever shape the device happens to use, instead of guessing at a corner
     * radius that would be wrong on the next phone.
     *
     * The artwork fills the mask rather than sitting inside it — see [LEGACY_INSET].
     * The white layer behind only shows through where the icon is transparent.
     */
    private fun shape(drawable: Drawable): Drawable =
        if (drawable is AdaptiveIconDrawable) {
            drawable
        } else {
            AdaptiveIconDrawable(
                ColorDrawable(LEGACY_BACKDROP),
                InsetDrawable(drawable, LEGACY_INSET),
            )
        }

    /** Blur radius and drop distance, both as a fraction of the icon's size. */
    private const val SHADOW_BLUR = 1f / 48f
    private const val SHADOW_DROP = 1f / 48f
    private const val AMBIENT_SHADOW = 0x24000000
    private const val KEY_SHADOW = 0x3D000000

    /** The tile a legacy icon sits on, matching a launcher's own wrapping. */
    private const val LEGACY_BACKDROP = Color.WHITE

    /**
     * Makes a legacy icon fill its box exactly, so the mask rounds off the artwork's
     * own corners instead of framing it.
     *
     * `AdaptiveIconDrawable` hands each layer bounds of 1.5x the icon and then clips to
     * the mask, so the central 1/1.5 of a layer is what lands edge to edge — an inset
     * of (1.5 - 1) / 2 / 1.5 on each side. Insetting further than this is what put a
     * visible white frame around Foxcloud 2.0: correct for a small logo on transparent,
     * wrong for the full-bleed square that most legacy icons actually are, and not what
     * One UI does to them.
     */
    private const val LEGACY_INSET = 1f / 6f

    /** The current home app, so we never suggest the launcher from inside itself. */
    fun homePackage(context: Context): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /**
     * Asks the package manager about one package rather than about all of them.
     *
     * This runs on the main thread of
     * [LaunchTrampolineActivity][com.lukecao.suggest.widget.LaunchTrampolineActivity],
     * between the user's finger landing and the app appearing, so it is the one place in
     * the app where tens of milliseconds are visible as lag. It used to look the component
     * up in [launchable], which enumerates every launcher activity on the phone and is
     * built for ranking a hundred and fifty apps, not for opening one. The launch intent
     * already carries the component, so [LauncherApps] — still the right way to start
     * another app's activity — can be handed it directly.
     */
    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val component = intent.component
        val la = context.getSystemService(LauncherApps::class.java)
        if (component != null && la != null) {
            try {
                la.startMainActivity(component, Process.myUserHandle(), null, null)
                return true
            } catch (_: Throwable) {
                // Fall through to the generic path.
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
