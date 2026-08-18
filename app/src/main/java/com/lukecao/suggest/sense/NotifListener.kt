package com.lukecao.suggest.sense

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.data.Store
import com.lukecao.suggest.work.Scheduler
import java.util.concurrent.Executors

/**
 * Records which apps have an outstanding notification, because that is the strongest
 * single predictor that you are about to open one.
 *
 * Two things are kept, and they answer different questions. Every post goes into
 * `notif`, which is how the ranker learns *whether you act on* a given app's
 * notifications — without that, the noisiest app on the phone would win permanently.
 * The `pending` set tracks what is still on screen right now, which is what actually
 * earns the boost.
 *
 * Only the posting package and the post time are ever read. Titles, text, people and
 * images are all available to a listener and none of them are touched.
 *
 * The OS permission and the app's own toggle are separate on purpose: granting
 * notification access is a heavyweight, scary-sounding step, so the toggle can turn
 * the signal off without making you go and revoke it. When it is off the service asks
 * to be unbound, so nothing is delivered at all rather than delivered and discarded.
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        if (!enabled()) {
            requestUnbind()
            return
        }
        // The listener only hears about changes while it is bound, so whatever was
        // posted or dismissed while it was dead has to be reconciled from scratch.
        val active = try {
            activeNotifications?.filter { interesting(it) }?.map {
                Triple(it.key, it.packageName, it.postTime)
            }
        } catch (_: Throwable) {
            null
        } ?: return
        write { it.replacePending(active) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!enabled() || !interesting(n)) return
        val key = n.key
        val pkg = n.packageName
        val ts = n.postTime
        write {
            it.addNotif(ts, pkg)
            it.addPending(key, pkg, ts)
            // Inside the write so the widget re-ranks against the row that was just
            // committed rather than racing it.
            Scheduler.refreshFor(applicationContext, Scheduler.REASON_NOTIFICATION)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        // No `enabled()` check: if the toggle was switched off between the post and
        // the swipe, the row still needs clearing or it boosts that app for a day.
        write {
            // Only when there was something to clear. Every notification on the phone is
            // announced here, including the ongoing furniture `interesting` refused to
            // record, and a removal that changed nothing cannot change the ranking
            // either — clearing a media notification was asking for a re-rank that could
            // only reproduce the grid already on screen.
            if (!it.removePending(key)) return@write
            // Clearing one we did record takes a boost of up to 3x away, so it moves the
            // ranking as much as the arrival did.
            Scheduler.refreshFor(applicationContext, Scheduler.REASON_NOTIFICATION)
        }
    }

    private fun enabled(): Boolean = Prefs.settings(applicationContext).signals.notifications

    /**
     * Is this something that just happened, or is it furniture?
     *
     * A media session, a running download, a VPN, a "USB debugging connected" — these
     * sit in the shade for hours and never mean "open me". Left in, the boost would
     * pin whatever is playing music to the widget all day, which is exactly what was
     * observed on the device: the music app and System UI both ended up in `pending`.
     *
     * `FLAG_ONGOING_EVENT` alone does not catch them any more. Media notifications
     * stopped being ongoing once the platform took over managing them from the
     * `MediaSession`, so they have to be recognised by the session token they carry
     * and by their category. Checking for the presence of a key is not reading the
     * notification: titles, text and people are still never touched.
     */
    private fun interesting(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Not dismissible means not something you deal with and move on from.
        if (n.flags and Notification.FLAG_NO_CLEAR != 0) return false
        if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (n.category in FURNITURE) return false
        // Only apps we could actually offer. System UI posts plenty — "USB debugging
        // connected", screenshots, battery — and none of it can ever become a
        // suggestion, so there is no reason to have recorded it.
        return launchable(sbn.packageName)
    }

    private fun launchable(pkg: String): Boolean = try {
        packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Throwable) {
        false
    }

    /** Callbacks arrive on the main thread and these are disk writes. */
    private fun write(block: (Store) -> Unit) {
        // Resolved here rather than inside the task: it touches no disk — the helper
        // opens the file on first query — and it keeps the service off the executor's
        // hands for the lookup.
        val store = Store.of(applicationContext)
        IO.execute {
            try {
                block(store)
            } catch (_: Throwable) {
                // A dropped breadcrumb is not worth crashing a system-bound service.
            }
        }
    }

    companion object {
        private val IO = Executors.newSingleThreadExecutor()

        /** Categories that describe a state rather than an event: transport controls,
         *  a background service, a progress bar. */
        private val FURNITURE = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
        )

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** Called after the toggle goes back on, since we asked to be unbound when it
         *  went off and the system will not rebind us on its own. */
        fun rebind(context: Context) {
            if (!hasAccess(context)) return
            try {
                requestRebind(ComponentName(context, NotifListener::class.java))
            } catch (_: Throwable) {
                // Pre-rebind platforms simply keep the binding; nothing to do.
            }
        }
    }
}
