package com.github.pandeiro.tempore.slsk.core.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Core-scoped persistent state in TWO DataStore<Preferences> files:
 *
 *  - `slsk_preferences` — sort/filter defaults and setup flags: sortMode, lossless-only /
 *    hide-locked / hide-no-slot filter flags, first-use notice shown, autoScan-after-handoff,
 *    and the client id prefix used to attribute our own slskd searches;
 *  - `slsk_history` — the last [SlskdStoreLimits.SEARCH_HISTORY_LIMIT] searches and a
 *    completions log capped at [SlskdStoreLimits.COMPLETION_LIMIT_COUNT] entries /
 *    [SlskdStoreLimits.COMPLETION_LIMIT_DAYS] days (JSON via kotlinx-serialization).
 *
 * Timestamps are epoch milliseconds ONLY: upstream has no java.time desugaring (minSdk 24).
 */
enum class SlskSortMode { PREFERRED, UPLOAD_SPEED, QUEUE_LENGTH, NAME }

@kotlinx.serialization.Serializable
data class SlskSearchHistoryEntry(val query: String, val atEpochMillis: Long)

@kotlinx.serialization.Serializable
data class SlskCompletionEntry(
    val atEpochMillis: Long,
    val username: String,
    val directory: String,
    val succeeded: Boolean,
    val fileCount: Int,
)

object SlskdStoreLimits {
    const val SEARCH_HISTORY_LIMIT = 50
    const val COMPLETION_LIMIT_COUNT = 200
    const val COMPLETION_LIMIT_DAYS = 30L
    const val COMPLETION_LIMIT_MILLIS = COMPLETION_LIMIT_DAYS * 24L * 60L * 60L * 1000L
}

class SlskdStores(
    private val preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val history: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Production constructor; give it a long-lived scope (application scope). */
    constructor(filesDir: File, scope: CoroutineScope) : this(
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(filesDir, PREFS_FILE) }),
        history = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(filesDir, HISTORY_FILE) }),
    )

    // ------------------------------------------------------------ preferences

    fun sortMode(): Flow<SlskSortMode> = preferences.data.mapAndRecover { entry ->
        entry[Keys.SORT_MODE]?.let { raw ->
            SlskSortMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: SlskSortMode.PREFERRED
    }

    suspend fun setSortMode(mode: SlskSortMode) {
        preferences.edit { it[Keys.SORT_MODE] = mode.name }
    }

    fun onlyLossless(): Flow<Boolean> = booleanFlow(Keys.ONLY_LOSSLESS, false)

    suspend fun setOnlyLossless(value: Boolean) = preferences.edit { it[Keys.ONLY_LOSSLESS] = value }

    fun hideLocked(): Flow<Boolean> = booleanFlow(Keys.HIDE_LOCKED, false)

    suspend fun setHideLocked(value: Boolean) = preferences.edit { it[Keys.HIDE_LOCKED] = value }

    fun hideNoSlotPeers(): Flow<Boolean> = booleanFlow(Keys.HIDE_NO_SLOT, false)

    suspend fun setHideNoSlotPeers(value: Boolean) = preferences.edit { it[Keys.HIDE_NO_SLOT] = value }

    fun firstUseNoticeShown(): Flow<Boolean> = booleanFlow(Keys.FIRST_USE_SHOWN, false)

    suspend fun setFirstUseNoticeShown() = preferences.edit { it[Keys.FIRST_USE_SHOWN] = true }

    fun autoScanAfterEnqueue(): Flow<Boolean> = booleanFlow(Keys.AUTO_SCAN, true)

    suspend fun setAutoScanAfterEnqueue(value: Boolean) = preferences.edit { it[Keys.AUTO_SCAN] = value }

    fun slskIdPrefix(): Flow<String> = stringFlow(Keys.SLSK_ID_PREFIX, DEFAULT_ID_PREFIX)

    suspend fun setSlskIdPrefix(value: String) = preferences.edit { it[Keys.SLSK_ID_PREFIX] = value }

    // ------------------------------------------------------------ history

    fun searchHistory(): Flow<List<SlskSearchHistoryEntry>> = history.data.mapAndRecover { entry ->
        decodeHistory(entry[Keys.SEARCH_HISTORY]).take(SlskdStoreLimits.SEARCH_HISTORY_LIMIT)
    }

    /** Prepends `query`, drops case-insensitive duplicates, caps at 50. */
    suspend fun addSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        history.edit { entry ->
            val existing = decodeHistory(entry[Keys.SEARCH_HISTORY])
                .filterNot { it.query.equals(trimmed, ignoreCase = true) }
            entry[Keys.SEARCH_HISTORY] = JSON.encodeToString(
                ListSerializer(SlskSearchHistoryEntry.serializer()),
                (listOf(SlskSearchHistoryEntry(query = trimmed, atEpochMillis = clock())) + existing)
                    .take(SlskdStoreLimits.SEARCH_HISTORY_LIMIT),
            )
        }
    }

    fun completions(): Flow<List<SlskCompletionEntry>> = history.data.mapAndRecover { entry ->
        decodeCompletions(entry[Keys.COMPLETIONS])
            .filter { it.atEpochMillis >= clock() - SlskdStoreLimits.COMPLETION_LIMIT_MILLIS }
    }

    /** Adds an entry, prunes entries over the 30-day window, caps at 200. */
    suspend fun addCompletion(entry: SlskCompletionEntry) {
        history.edit { stored ->
            val windowEnd = clock() - SlskdStoreLimits.COMPLETION_LIMIT_MILLIS
            val existing = decodeCompletions(stored[Keys.COMPLETIONS])
                .filter { it.atEpochMillis >= windowEnd }
            stored[Keys.COMPLETIONS] = JSON.encodeToString(
                ListSerializer(SlskCompletionEntry.serializer()),
                (listOf(entry) + existing).take(SlskdStoreLimits.COMPLETION_LIMIT_COUNT),
            )
        }
    }

    // ------------------------------------------------------------ internals

    private fun booleanFlow(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        preferences.data.mapAndRecover { it[key] ?: default }

    private fun stringFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>, default: String): Flow<String> =
        preferences.data.mapAndRecover { it[key] ?: default }

    private object Keys {
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val ONLY_LOSSLESS = booleanPreferencesKey("filter_lossless_only")
        val HIDE_LOCKED = booleanPreferencesKey("filter_hide_locked")
        val HIDE_NO_SLOT = booleanPreferencesKey("filter_hide_no_slot")
        val FIRST_USE_SHOWN = booleanPreferencesKey("first_use_notice_shown")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan_after_enqueue")
        val SLSK_ID_PREFIX = stringPreferencesKey("slsk_id_prefix")
        val SEARCH_HISTORY = stringPreferencesKey("search_history_json")
        val COMPLETIONS = stringPreferencesKey("completions_json")
    }

    companion object {
        const val PREFS_FILE = "slsk_preferences"
        const val HISTORY_FILE = "slsk_history"
        const val DEFAULT_ID_PREFIX = "tempore"

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun decodeHistory(value: String?): List<SlskSearchHistoryEntry> =
            try {
                value
                    ?.let { JSON.decodeFromString(ListSerializer(SlskSearchHistoryEntry.serializer()), it) }
                    .orEmpty()
            } catch (_: Exception) {
                emptyList() // tolerate hand edits / corruption: history is disposable
            }

        private fun decodeCompletions(value: String?): List<SlskCompletionEntry> =
            try {
                value
                    ?.let { JSON.decodeFromString(ListSerializer(SlskCompletionEntry.serializer()), it) }
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
    }
}

/** Same error policy as the catalogue's other flows: partial corruption never crashes the screen. */
private fun <T> Flow<androidx.datastore.preferences.core.Preferences>.mapAndRecover(
    transform: (androidx.datastore.preferences.core.Preferences) -> T,
): Flow<T> =
    this
        .catch { emit(emptyPreferences()) }
        .map(transform)
