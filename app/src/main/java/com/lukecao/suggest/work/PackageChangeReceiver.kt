package com.lukecao.suggest.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukecao.suggest.data.AppCatalog

/**
 * Installing or uninstalling an app should be reflected immediately — a widget
 * still offering an app you just removed is the most obvious kind of stale.
 *
 * PACKAGE_ADDED and PACKAGE_FULLY_REMOVED are on the exemption list, so unlike
 * most implicit broadcasts they are still delivered to manifest receivers.
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED &&
            action != Intent.ACTION_PACKAGE_FULLY_REMOVED
        ) {
            return
        }

        // The catalog caches a label and an icon per app, and an update can change
        // either, so it is stale either way.
        AppCatalog.invalidate()

        // An update arrives as PACKAGE_ADDED with EXTRA_REPLACING set, and the set of
        // installed apps has not changed, so there is nothing new to rank. Worth
        // skipping rather than leaning on the two-minute gap: Play updates apps in
        // overnight batches, so this fires many times in a row for no new information.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        Scheduler.refreshNow(context, Scheduler.REASON_PACKAGES)
    }
}
