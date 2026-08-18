package com.lukecao.suggest.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-ranks when something changes that the ranker actually weighs, instead of waiting
 * out the rest of the interval.
 *
 * Every action here maps onto a term in the model. Plugging in headphones flips the
 * headphones flag, which carries a weight of 0.8. Putting the phone on a charger flips
 * the charging flag. A timezone change silently redefines what "9am" means, so the
 * time-of-day lift is measuring something different from a moment ago.
 *
 * Which of these the platform will actually deliver to a manifest receiver is not
 * something the documentation settles, and it varies by OEM. They are declared in the
 * hope that they arrive and every one records why it refreshed, so the app can be asked
 * afterwards which ones are real on this phone. A trigger that never fires costs
 * nothing: the timer is still underneath it.
 */
class EventTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> Scheduler.REASON_POWER

            Intent.ACTION_USER_PRESENT -> Scheduler.REASON_UNLOCK

            Intent.ACTION_TIMEZONE_CHANGED -> Scheduler.REASON_TIMEZONE

            // Headphones are absent on purpose: they are watched as audio devices
            // rather than as a broadcast, by ScreenTriggers, because the only
            // broadcast for them describes the 3.5mm jack and nothing else.
            else -> return
        }

        // Coalesced like everything else, including unlocking: locking and unlocking
        // three times in a minute does not mean the answer changed three times.
        Scheduler.refreshFor(context, reason)
    }
}
