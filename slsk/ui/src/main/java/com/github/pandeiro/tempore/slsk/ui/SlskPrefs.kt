package com.github.pandeiro.tempore.slsk.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.slskPrefs by preferencesDataStore(name = "slsk_ui_prefs")

/**
 * UI-scoped persistent flags: whether the first-use sheet has been shown once and whether
 * auto-scan after downloads is on. (The search scope is deliberately NOT persisted: it lives
 * per Search screen instance, PLAN.md §8 / D5.)
 */
object SlskPrefs {
    private val KEY_FIRST_USE_SHOWN = booleanPreferencesKey("first_use_sheet_shown")
    private val KEY_AUTOSCAN = booleanPreferencesKey("autoscan_after_download")

    suspend fun setFirstUseShown(context: Context) {
        context.slskPrefs.edit { it[KEY_FIRST_USE_SHOWN] = true }
    }

    suspend fun isFirstUse(context: Context): Boolean =
        context.slskPrefs.data.map { it[KEY_FIRST_USE_SHOWN] ?: false }.first().not()

    suspend fun setAutoscan(context: Context, enabled: Boolean) {
        context.slskPrefs.edit { it[KEY_AUTOSCAN] = enabled }
    }

    suspend fun autoscanEnabled(context: Context): Boolean =
        context.slskPrefs.data.map { it[KEY_AUTOSCAN] ?: true }.first()
}
