package com.lukecao.suggest

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

object Permissions {

    /**
     * Whether this build asks for location at all. **False for the first release.**
     *
     * The manifest is what actually makes it false — the three `ACCESS_*_LOCATION` lines
     * are commented out there, so [hasLocation] would return false whatever this said.
     * It exists so the decision is one greppable constant rather than an absence: the
     * settings screen needs to know to stop offering a switch it can never satisfy, and a
     * compile-time `false` lets R8 strip the sampling and the four lift terms out of the
     * build rather than shipping them as unreachable code.
     *
     * Why it is off. Replayed over 13.8 days the whole location family came to about a
     * point of Hit@4 between its four terms — three moments out of 338, inside the error
     * bar, and `wifi` and `moving` measured at exactly zero. Against that,
     * `ACCESS_BACKGROUND_LOCATION` is the most heavily reviewed permission on Play: it
     * needs a prominent in-app disclosure, a written Console justification and usually a
     * manual review with a demo video, and it is the likeliest single cause of a
     * first submission stalling for weeks. Asking for while-in-use instead is not
     * available — the refresh runs with nothing of ours on screen, which is exactly when
     * that grant is refused, so it is background or nothing. Dropping it also removes the
     * location entry from the Data Safety form, which is one fewer thing to get wrong.
     *
     * This is not a verdict on the signal, which may well be worth more than the replay
     * can currently show; it is a verdict on that trade for a first submission by a new
     * developer account. Everything it gates is intact, documented and measured, so
     * turning it back on is this line and the manifest.
     */
    const val LOCATION_DECLARED = false

    /**
     * Usage access is an appop, not a runtime permission, so it needs the
     * two-step check: MODE_DEFAULT means "no explicit decision recorded", and
     * only then does the manifest permission decide.
     *
     * `unsafeCheckOpNoThrow` became deprecated when compileSdk moved to 36 — it is not
     * deprecated against android-35 — and it is kept anyway, deliberately. It is not
     * removed, and there is no public API that reads an appop's mode without it, so the
     * only alternative is to infer access from behaviour: call `queryUsageStats` and treat
     * an empty list as denied. That inference is wrong in a way that matters here, because
     * an empty list is ambiguous — a phone that granted access five minutes ago also
     * returns nothing — and this answer drives whether the UI shows "Required" against the
     * one permission the app cannot work without. A stale "not granted" on a freshly
     * granted phone is the worst possible first impression.
     *
     * Revisit when the platform offers a replacement rather than when the warning gets
     * annoying, and check the deprecation note at that point: the `unsafe` prefix already
     * warned that MODE_ALLOWED is not a security guarantee, which is fine for a UI hint
     * and would not be fine for a gate.
     */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val aom = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = aom.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * Checked against [LOCATION_DECLARED] as well as the grant, so that one false
     * constant is enough to switch the whole family off. Without it this would still
     * return false — an undeclared permission is never granted — but only by accident of
     * the manifest, and the callers would each have to know that.
     */
    fun hasLocation(context: Context): Boolean =
        LOCATION_DECLARED && (
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
                granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
            )

    /**
     * Without this the place term is mostly dead weight: the refresh worker runs
     * while nothing of ours is on screen, which is exactly when a foreground-only
     * grant is refused.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        LOCATION_DECLARED && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasCalendar(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CALENDAR)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
