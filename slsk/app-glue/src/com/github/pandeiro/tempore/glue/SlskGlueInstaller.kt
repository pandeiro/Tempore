package com.github.pandeiro.tempore.glue

import android.app.Application
import android.content.Context
import android.util.Log
import com.github.pandeiro.tempore.slsk.core.session.SlskSession

/**
 * Single seam (S7) wired by the orchestrator from App.onCreate(): installs the slsk
 * session with the host bridge so every :slsk:ui screen can reach the gateway,
 * library gateway and deep-link navigation.
 */
object SlskGlueInstaller {
    private const val TAG = "TempCrash"

    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext as Application
        val bridge = TemporeHostBridge()
        app.registerActivityLifecycleCallbacks(bridge)
        SlskSession.install(app, bridge)
        // Debug aid: CAOC swallows uncaught exceptions silently; tee them to logcat
        // before delegating so automation can read the stack. Debug builds only.
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val upstream = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "UNCAUGHT on ${thread.name}", throwable)
                upstream?.uncaughtException(thread, throwable)
            }
        }
    }
}
