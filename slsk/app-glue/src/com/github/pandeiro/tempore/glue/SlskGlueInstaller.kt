package com.github.pandeiro.tempore.glue

import android.app.Application
import android.content.Context
import com.github.pandeiro.tempore.slsk.core.session.SlskSession

/**
 * Single seam (S7) wired by the orchestrator from App.onCreate(): installs the slsk
 * session with the host bridge so every :slsk:ui screen can reach the gateway,
 * library gateway and deep-link navigation.
 */
object SlskGlueInstaller {
    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext as Application
        val bridge = TemporeHostBridge()
        app.registerActivityLifecycleCallbacks(bridge)
        SlskSession.install(app, bridge)
    }
}
