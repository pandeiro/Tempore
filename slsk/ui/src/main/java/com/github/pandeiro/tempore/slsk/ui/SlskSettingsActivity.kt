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
        // PR review #2: plain-HTTP setup carries the API key unencrypted — warn inline.
        updateTransportWarning()
        bind.slskSettingsBaseUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateTransportWarning()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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

    private fun updateTransportWarning() {
        val effective = normalizeBaseUrl(bind.slskSettingsBaseUrl.text?.toString() ?: "")
        bind.slskTransportWarning.visibility =
            if (effective.startsWith("http://")) android.view.View.VISIBLE else android.view.View.GONE
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
        try {
            SlskSessionAccess.save(config)
            Toast.makeText(this, R.string.slsk_settings_save, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // PR review #4: a broken Keystore must surface here, not crash the screen.
            android.util.Log.e("SlskSettings", "failed to store slsk credentials", e)
            bind.slskSettingsResult.text = getString(
                R.string.slsk_settings_result_unreachable,
                e.message ?: "secure storage error",
            )
        }
    }

    private fun testConnection() {
        val config = buildConfig() ?: return
        // Probe the *typed* configuration through a throwaway gateway; the store is only
        // written by Save (PR review #3).
        bind.slskSettingsTest.isEnabled = false
        bind.slskSettingsResult.setText(R.string.slsk_settings_testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    SlskSessionAccess.probeGateway(config).health()
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
