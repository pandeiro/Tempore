package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.core.domain.SoulseekGateway
import com.github.pandeiro.tempore.slsk.core.gateway.HostBridge
import com.github.pandeiro.tempore.slsk.core.gateway.LibraryGateway
import com.github.pandeiro.tempore.slsk.core.secure.SlskdConfig
import com.github.pandeiro.tempore.slsk.core.session.SlskSession

/**
 * The only place in :slsk:ui that touches the core session facade. Every screen and
 * the seams facade resolve dependencies through this so contract drift stays contained.
 */
object SlskSessionAccess {

    val gateway: SoulseekGateway get() = SlskSession.gateway

    val libraryGateway: LibraryGateway? get() = SlskSession.libraryGateway

    val host: HostBridge? get() = SlskSession.host

    val isConfigured: Boolean get() = SlskSession.isConfigured

    /**
     * Gateway or null: null means "install not called yet" or "not configured".
     * Both surface as an honest not-configured setup card in the UI.
     */
    fun gatewayOrNull(): SoulseekGateway? = try {
        if (SlskSession.isConfigured) SlskSession.gateway else null
    } catch (_: IllegalStateException) {
        null
    }

    fun currentConfig(): SlskdConfig? = SlskSession.currentConfig

    fun save(config: SlskdConfig) {
        SlskSession.saveConfig(config)
    }

    fun clear() {
        SlskSession.clearConfig()
    }

    fun lastStoreError(): String? = SlskSession.lastStoreError()
}
