package com.lukecao.suggest.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lukecao.suggest.sense.ContextSampler

/**
 * The triggers that can only be registered at runtime, hosted by whatever of ours
 * happens to be alive.
 *
 * `ACTION_SCREEN_ON` is not deliverable to a manifest receiver at all — the platform
 * requires a live process to be listening; `ACTION_USER_PRESENT` is registered here as
 * well because whether a manifest receiver gets it is unreliable; and headphones are
 * watched through an [AudioDeviceCallback], which is a callback rather than a broadcast
 * and so has nowhere to be declared. Registering twice is harmless: the refresh is
 * coalesced, so a broadcast that arrives by both routes still causes one re-rank.
 *
 * The awkward part, stated plainly: a runtime receiver lives exactly as long as the
 * process, and this app has no component of its own that keeps one alive. What it has is
 * [NotifListener][com.lukecao.suggest.sense.NotifListener], which the system keeps bound
 * while notification access is granted, so these triggers are reliable when that is on
 * and best-effort when it is not — the process still exists for a while after any
 * refresh, and unlocking during that window is caught.
 *
 * Deliberately not solved by keeping the listener bound when its own signal is off:
 * holding notification access for something unrelated to notifications is not a trade
 * worth making, and a foreground service with a permanent notification is a far worse
 * deal than a slightly staler widget.
 */
object ScreenTriggers {

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var audioCallback: AudioDeviceCallback? = null

    /**
     * Whether headphones were connected the last time we were told: [UNKNOWN] until the
     * first callback. See [headphonesChanged] for why it has to be remembered.
     */
    @Volatile
    private var headphones = UNKNOWN

    private const val UNKNOWN = -1
    private const val ABSENT = 0
    private const val PRESENT = 1

    @Synchronized
    fun register(context: Context) {
        val app = context.applicationContext
        registerReceiver(app)
        registerAudio(app)
    }

    @Synchronized
    fun unregister(context: Context) {
        val app = context.applicationContext
        receiver?.let { r ->
            receiver = null
            try {
                app.unregisterReceiver(r)
            } catch (_: Throwable) {
                // Already gone with the process.
            }
        }
        audioCallback?.let { cb ->
            audioCallback = null
            // So a later register() discards its own opening replay again.
            headphones = UNKNOWN
            try {
                app.getSystemService(AudioManager::class.java)
                    ?.unregisterAudioDeviceCallback(cb)
            } catch (_: Throwable) {
            }
        }
    }

    private fun registerReceiver(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                val reason = when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> Scheduler.REASON_UNLOCK
                    // Screen-on without unlocking still puts the widget in front of
                    // you on a phone with no secure lock.
                    Intent.ACTION_SCREEN_ON -> Scheduler.REASON_UNLOCK
                    else -> return
                }
                Scheduler.refreshFor(ctx.applicationContext, reason)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            ContextCompat.registerReceiver(
                app,
                r,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = r
        } catch (_: Throwable) {
            // Nothing to fall back to, and nothing broken: the timer still runs.
        }
    }

    /**
     * Watches the audio output devices instead of `ACTION_HEADSET_PLUG`.
     *
     * That broadcast only ever describes the 3.5mm jack. On a phone without one — this
     * one, and most sold since about 2018 — it can never fire, so the trigger was dead
     * while the signal it exists to serve was live:
     * [ContextSampler.headphonesConnected] counts A2DP, SCO, BLE and hearing aids, so
     * connecting Bluetooth headphones moved the ranking inputs without anything asking
     * for a re-rank, and the new ordering appeared only when the next timer or unrelated
     * event came round.
     *
     * The callback fires for wired and wireless alike, and asking the sampler what the
     * answer is now means the trigger and the signal cannot drift apart: one definition
     * of headphones, used by both.
     */
    private fun registerAudio(app: Context) {
        if (audioCallback != null) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
                devicesChanged(app)

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
                devicesChanged(app)
        }
        try {
            am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            audioCallback = cb
        } catch (_: Throwable) {
            // Same as the receiver: the period is still underneath it.
        }
    }

    private fun devicesChanged(app: Context) {
        if (!headphonesChanged(app)) return
        Scheduler.refreshFor(app, Scheduler.REASON_HEADPHONES)
    }

    /**
     * Whether the headphones actually changed, as opposed to us having just started
     * listening or something else on the audio bus having moved.
     *
     * Registering an [AudioDeviceCallback] delivers the whole current device list to
     * `onAudioDevicesAdded` immediately, before anything has happened. That would be a
     * curiosity if this were registered once, but the process is built again for every
     * broadcast the app receives — many times an hour — and each of those rebuilds would
     * otherwise fire a re-rank labelled "headphones changed" for headphones that had been
     * sitting in the same state all day. The callback also fires for devices nobody
     * listens through, so speakers and HDMI coming and going have to fall out here too.
     * Both are the same test: compare the answer against the last answer.
     *
     * Discarding the first delivery costs a real connection that happens to land in the
     * same moment as a process start, which the period picks up.
     */
    @Synchronized
    private fun headphonesChanged(app: Context): Boolean {
        val state = if (ContextSampler.headphonesConnected(app)) PRESENT else ABSENT
        val previous = headphones
        headphones = state
        return previous != UNKNOWN && state != previous
    }
}
