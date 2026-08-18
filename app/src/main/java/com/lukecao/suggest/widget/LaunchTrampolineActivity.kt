package com.lukecao.suggest.widget

import android.app.Activity
import android.os.Bundle
import com.lukecao.suggest.data.AppCatalog
import com.lukecao.suggest.data.Store

/**
 * Invisible hop between tapping a slot and the app opening, so the tap can be
 * recorded as feedback for the ranker.
 *
 * Launch happens first and the write second — the write must never be in the
 * user's way.
 */
class LaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No overridePendingTransition call: it is deprecated as of API 34, and
        // Theme.Trampoline already suppresses the animation via
        // android:windowAnimationStyle=@null.

        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (pkg != null) {
            AppCatalog.launch(this, pkg)
            val now = System.currentTimeMillis()
            // Which slot it was tapped in, so the ranker can tell "chosen over
            // everything above it" from "chosen because it was first".
            val slot = intent?.getIntExtra(EXTRA_SLOT, -1) ?: -1
            // Deliberately no re-rank here. It was the right thing on paper — the app
            // you just opened drives continuity and the next bigram — but in the hand
            // it meant the grid you had just aimed at rearranged itself underneath you
            // while the app was still opening, so coming back out landed you somewhere
            // different. The tap is recorded either way and the next refresh picks it up.
            Thread {
                // A bare thread has no handler of its own, so anything thrown here goes
                // to the default one and takes the process down — and this one is holding
                // a database write, which is exactly the kind of thing that throws when
                // storage is full or the file has been swapped underneath us. Crashing on
                // the way into an app the user just tapped would be the most visible bug
                // in the app, in service of recording a single tap.
                try {
                    Store.of(applicationContext).addTap(now, pkg, slot)
                } catch (_: Throwable) {
                    // Lost feedback. The launch itself already happened.
                }
            }.start()
        }
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SLOT = "slot"
    }
}
