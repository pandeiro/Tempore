package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.pandeiro.tempore.slsk.core.secure.SlskdConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Soulseek (slskd) settings (spec §2.1, §5.3 step 1): base URL, masked API key, optional
 * cert pin, autoscan toggle, test connection, activity entry, debug-only intent seeding.
 */
class SlskSettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var bind: SlskActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = SlskActivitySettingsBinding.inflate(layoutInflater)
        setContentView(bind.root)

        bind.slskSettingsBaseUrl.setText(SlskSessionAccess.currentConfig()?.baseUrl ?: "")
        bind.slskSettingsPinnedCert.setText(SlskSessionAccess.currentConfig()?.pinnedCertSha256 ?: "")
        // API key stays blank: hint explains "leave unchanged keeps the saved key" (masked, §6.1 no-logging rule)
        bind.slskSettingsApiKey.hint = if (SlskSessionAccess.currentConfig()?.apiKey != null) {
            getString(R.string.slsk_settings_api_key_hint)
        } else {
            getString(R.string.slsk_settings_api_key)
        }
        bind.slskSettingsApiKey.setText("")

        lifecycleScope.launch {
            bind.slskSettingsAutoscan.isChecked = SlskPrefs.autoscanEnabled(applicationContext)
        }
        bind.slskSettingsAutoscan.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { SlskPrefs.setAutoscan(this@SlskSettingsActivity, checked) }
        }

        // Debug-only intent seeding (never honored in release builds)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("slsk_debug_url")?.let {
                bind.slskSettingsBaseUrl.setText(normalizeBaseUrl(it))
            }
            intent.getStringExtra("slsk_debug_key")?.let { bind.slskSettingsApiKey.setText(it) }
        }

        bind.slskSettingsTest.setOnClickListener { testConnection() }
        bind.slskSettingsSave.setOnClickListener { save() }
        bind.slskSettingsOpenActivity.setOnClickListener { openActivity() }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    private fun buildConfig(): SlskdConfig? {
        val url = normalizeBaseUrl(bind.slskSettingsBaseUrl.text?.toString() ?: "")
        if (!Patterns.WEB_URL.matcher(url).matches()) {
            Toast.makeText(this, R.string.slsk_settings_error_no_url, Toast.LENGTH_SHORT).show()
            return null
        }
        val saved = SlskSessionAccess.currentConfig()
        val key = bind.slskSettingsApiKey.text?.toString()?.trim().orEmpty()
        val pinned = bind.slskSettingsPinnedCert.text?.toString()?.trim()?.lowercase()?.ifEmpty { null }
        // Empty API field keeps the existing key so a re-save never corrupts auto-scan prefs
        val apiKey = key.ifEmpty { saved?.apiKey ?: "" }
        return SlskdConfig(baseUrl = url, apiKey = apiKey, pinnedCertSha256 = pinned)
    }

    private fun save() {
        val config = buildConfig() ?: return
        SlskSessionAccess.save(config)
        Toast.makeText(this, R.string.slsk_settings_save, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val config = buildConfig() ?: return
        // probe what the user typed without overwriting the store first
        bind.slskSettingsTest.isEnabled = false
        bind.slskSettingsResult.setText(R.string.slsk_settings_testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val gatewayBefore = SlskSessionAccess.gatewayOrNull()
                    // save first so the gateway speaks to THIS address, then restore
                    val previous = SlskSessionAccess.currentConfig()
                    SlskSessionAccess.save(config)
                    val gateway = SlskSessionAccess.gatewayOrNull()
                    if (previous != null && !sameConfig(previous, config)) SlskSessionAccess.save(previous)
                    gateway?.let { SlskSessionAccess.gateway.health() }
                } catch (t: Throwable) {
                    null
                }
            }
            bind.slskSettingsTest.isEnabled = true
            bind.slskSettingsResult.text = when {
                result == null -> getString(R.string.slsk_settings_result_unreachable, "network error")
                else -> getString(
                    R.string.slsk_settings_result_ok,
                    result.version,
                    getString(
                        if (result.isConnected) R.string.slsk_settings_connected_yes
                        else R.string.slsk_settings_connected_no
                    ),
                )
            }
        }
    }

    private fun sameConfig(a: SlskdConfig, b: SlskdConfig): Boolean =
        a == b

    private fun openActivity() {
        val slot = android.view.View.generateViewId()
        val container = android.widget.FrameLayout(this).apply { id = slot }
        (findViewById<ViewGroup>(android.R.id.content)).addView(
            container,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        supportFragmentManager.beginTransaction()
            .add(slot, SlskActivityFragment())
            .commit()
    }
}
