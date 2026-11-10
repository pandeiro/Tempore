package com.github.pandeiro.tempore.slsk.core.session

import android.content.Context
import com.github.pandeiro.tempore.slsk.core.domain.SoulseekGateway
import com.github.pandeiro.tempore.slsk.core.gateway.FakeSlskdGateway
import com.github.pandeiro.tempore.slsk.core.gateway.HostBridge
import com.github.pandeiro.tempore.slsk.core.gateway.LibraryGateway
import com.github.pandeiro.tempore.slsk.core.gateway.NavidromeClient
import com.github.pandeiro.tempore.slsk.core.gateway.SlskdGatewayImpl
import com.github.pandeiro.tempore.slsk.core.secure.CredentialStore
import com.github.pandeiro.tempore.slsk.core.secure.KeystoreCredentialStore
import com.github.pandeiro.tempore.slsk.core.secure.SlskdConfig
import com.github.pandeiro.tempore.slsk.core.secure.StoreError

/**
 * The single slsk process-scoped session object (matches upstream's no-DI style).
 * `install` is called once from `App.onCreate` (via the app-glue seam); screens come and
 * go, this stays. Never touches upstream app classes except for the [HostBridge] instance
 * passed in — polling glue lives in `:slsk:ui`, not here.
 */
object SlskSession {

    /** Set once at install: mirrors the host app's debuggable flag for HTTP logging. */
    @Volatile
    private var debuggableHost: Boolean = false
    @Volatile
    private var installed: Boolean = false

    @Volatile
    private var credentialStore: CredentialStore? = null

    @Volatile
    private var hostBridge: HostBridge? = null

    @Volatile
    private var gatewayInstance: SoulseekGateway = FakeSlskdGateway()

    @Volatile
    private var currentConfigValue: SlskdConfig? = null

    @Volatile
    private var storeErrorDescription: String? = null

    /**
     * Call from `App.onCreate()` after the existing server config is loaded. Re-entrant:
     * installing again simply re-reads stored credentials and rebuilds the gateway.
     */
    @Synchronized
    fun install(context: Context, hostBridge: HostBridge) {
        debuggableHost = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        this.hostBridge = hostBridge
        libraryGatewayValue = hostBridge.navidrome()?.let { config ->
            NavidromeClient(baseUrl = config.serverUrl, config = config)
        }
        val store: CredentialStore
        try {
            store = KeystoreCredentialStore.open(context.applicationContext)
        } catch (e: StoreError) {
            // Cannot secure credentials at all: demo gateway + surfaced error, never a crash.
            credentialStore = null
            storeErrorDescription = e.message
            installed = true
            return
        }
        credentialStore = store
        storeErrorDescription = null
        val config = try {
            store.load()
        } catch (e: StoreError) {
            // Surface it; the store itself stays usable so saveConfig can overwrite the blob.
            storeErrorDescription = e.message
            null
        } catch (t: Throwable) {
            // PR review #4: never let an unexpected crypto/parsing failure crash startup —
            // fall back to the demo gateway and surface the failure to the settings screen.
            storeErrorDescription = t.message ?: "secure storage failure"
            null
        }
        currentConfigValue = config
        gatewayInstance = if (storeErrorDescription == null) {
            config?.let { buildGateway(it) } ?: FakeSlskdGateway()
        } else {
            FakeSlskdGateway()
        }
        installed = true
    }

    /** Governing gateway; real when configured, a demo fake otherwise. */
    val gateway: SoulseekGateway
        get() {
            check(installed) { "SlskSession.install(context, hostBridge) was not called" }
            return gatewayInstance
        }

    @Volatile
    private var libraryGatewayValue: LibraryGateway? = null

    /**
     * The LibraryGateway for the handoff flow; null when Tempus isn't signed into a
     * Subsonic server. Built once at install from the HostBridge credentials.
     */
    val libraryGateway: LibraryGateway?
        get() = libraryGatewayValue

    /** Host bridge for the album deep-link hand-off; null before install(). */
    val host: HostBridge?
        get() = hostBridge

    /**
     * True once a real slskd configuration exists and the real gateway is in use. False
     * also when secure storage broke on this device (see [lastStoreError]).
     */
    val isConfigured: Boolean
        get() = currentConfigValue != null

    /** The persisted slskd credentials, or null when unset/unreadable. */
    val currentConfig: SlskdConfig?
        get() = currentConfigValue

    /**
     * Non-null message when the secure store failed at install time (broken Keystore);
     * callers show a setup card instead of silently guessing why nothing works.
     */
    fun lastStoreError(): String? = storeErrorDescription

    /**
     * UI settings screen: persists the config via the encrypted store, then swaps the
     * active gateway atomically. Throws [StoreError] when writing failed — surfaced by the
     * settings screen, never swallowed.
     */
    @Synchronized
    fun saveConfig(config: SlskdConfig) {
        val store = requireNotNull(credentialStore) {
            "secure storage unavailable on this device: " + (storeErrorDescription ?: "not installed")
        }
        store.save(config)
        currentConfigValue = config
        gatewayInstance = buildGateway(config)
    }

    /** Drops stored credentials and falls back to the demo fake gateway. */
    @Synchronized
    fun clearConfig() {
        runCatching { credentialStore?.clear() }
        currentConfigValue = null
        gatewayInstance = FakeSlskdGateway()
    }

    private fun buildGateway(config: SlskdConfig): SlskdGatewayImpl =
        SlskdGatewayImpl.Factory(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            debug = debuggableHost,
            pinnedCertSha256 = config.pinnedCertSha256,
        ).build()

    /**
     * Throws-away gateway used by the settings "Test connection" probe (PR review #3):
     * builds a gateway for the *typed* configuration without persisting anything, so a
     * test never writes credentials and never re-points the live session.
     */
    @Synchronized
    fun probeGateway(config: SlskdConfig): SoulseekGateway = buildGateway(config)
}
