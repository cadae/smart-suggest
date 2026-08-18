package com.lukecao.suggest

import android.app.Application
import com.lukecao.suggest.data.Prefs
import com.lukecao.suggest.work.Scheduler
import com.lukecao.suggest.work.ScreenTriggers

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.onProcessStart(this)
        // Registered per process rather than from any one component, because that is
        // exactly the lifetime a runtime receiver has. Whether it lasts a second or a
        // day is not up to us: see [ScreenTriggers].
        if (Prefs.settings(this).eventTriggers) ScreenTriggers.register(this)
    }
}
