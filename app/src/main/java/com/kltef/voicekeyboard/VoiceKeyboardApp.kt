package com.kltef.voicekeyboard

import android.app.Application
import com.kltef.voicekeyboard.util.CrashLog

/**
 * Installs a global uncaught-exception handler that records the crash so it can be shared
 * from the Setup screen, then delegates to the platform handler (so the system dialog and
 * normal crash flow still happen).
 */
class VoiceKeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { CrashLog.write(this, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
