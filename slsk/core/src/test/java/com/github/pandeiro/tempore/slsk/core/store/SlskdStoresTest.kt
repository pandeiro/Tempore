package com.github.pandeiro.tempore.slsk.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SlskdStoresTest {

    private lateinit var scope: CoroutineScope
    private lateinit var tmpDir: File
    private lateinit var stores: SlskdStores

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO)
        tmpDir = Files.createTempDirectory("slsk-stores").toFile()
        stores = SlskdStores(
            dataStore(tmpDir, name = "slsk_preferences"),
            dataStore(tmpDir, name = "slsk_history"),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun sort_and_filter_flags_default_and_roundtrip() = runBlocking {
        assertEquals(SlskSortMode.PREFERRED, stores.sortMode().first())
        assertEquals(false, stores.onlyLossless().first())
        assertEquals(true, stores.autoScanAfterEnqueue().first())
        assertEquals("tempore", stores.slskIdPrefix().first())

        stores.setSortMode(SlskSortMode.UPLOAD_SPEED)
        stores.setOnlyLossless(true)
        stores.setHideLocked(true)
        stores.setHideNoSlotPeers(true)
        stores.setFirstUseNoticeShown()
        stores.setAutoScanAfterEnqueue(false)
        stores.setSlskIdPrefix("custom-")

        assertEquals(SlskSortMode.UPLOAD_SPEED, stores.sortMode().first())
        assertTrue(stores.onlyLossless().first())
        assertTrue(stores.hideLocked().first())
        assertTrue(stores.hideNoSlotPeers().first())
        assertTrue(stores.firstUseNoticeShown().first())
        assertEquals(false, stores.autoScanAfterEnqueue().first())
        assertEquals("custom-", stores.slskIdPrefix().first())
    }

    @Test
    fun search_history_roundtrip() = runBlocking {
        stores.addSearch("Abba")
        stores.addSearch("AC/DC")
        stores.addSearch("Abba")
        val entries = stores.searchHistory().first()
        // "Abba" moved to the top, no case-insensitive duplicates
        assertEquals(listOf("Abba", "AC/DC"), entries.map { it.query })
        assertTrue(entries.all { it.atEpochMillis > 0 })
    }

    @Test
    fun completions_pruned_to_30d_window() = runBlocking {
        val now = 1_700_000_000_000L
        val old = now - SlskdStoreLimits.COMPLETION_LIMIT_MILLIS - 1
        val fixed = SlskdStores(
            dataStore(tmpDir, "prefs2"),
            dataStore(tmpDir, "hist2"),
            clock = { now },
        )
        fixed.addCompletion(SlskCompletionEntry(old, "PEER", "D\\A", true, 3))
        fixed.addCompletion(SlskCompletionEntry(now, "PEER2", "D\\B", true, 1))
        val out = fixed.completions().first()
        // 30-day window dropped the pre-window entry
        assertEquals(1, out.size)
        assertEquals("PEER2", out[0].username)
        assertEquals(1, out[0].fileCount)
    }
}

private fun dataStore(dir: File, name: String): DataStore<Preferences> {
    val files = File(dir, name).apply { mkdirs() }
    return PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO),
        produceFile = { File(files, "$name.preferences_pb") },
    )
}
