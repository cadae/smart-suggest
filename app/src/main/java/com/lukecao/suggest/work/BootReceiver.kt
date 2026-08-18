package com.lukecao.suggest.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-establishes the schedule after a reboot or an app update, and re-ranks once.
 *
 * Separate from [EventTriggerReceiver] and never disabled, because the event triggers
 * are optional and this is not. WorkManager's period does survive both a reboot and an
 * update, so the schedule would come back on its own — but only whenever the period
 * next came due, and a home screen showing yesterday evening's suggestions is the first
 * thing you see after a restart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Both of these are protected broadcasts and the receiver is not exported, so
        // nothing else can reach it — but a receiver that ignores the action it was
        // handed will happily do work for any future intent-filter somebody adds, and
        // rescheduling is not free. Checked so the filter and the behaviour cannot
        // drift apart.
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.reschedule(context)
                Scheduler.refreshNow(context, Scheduler.REASON_BOOT)
            }
        }
    }
}
