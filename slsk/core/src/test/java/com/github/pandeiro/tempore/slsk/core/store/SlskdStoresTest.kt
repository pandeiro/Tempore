package com.github.pandeiro.tempore.slsk.core.store

import androidx.datastore.core.DataStore
import com.github.pandeiro.tempore.slsk.core.domain.SlskResultFilters
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
        // Production constructor: exercises the real on-disk layout (file names must end in
        // `.preferences_pb` or DataStore throws on first access).
        stores = SlskdStores(tmpDir, scope)
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
    fun result_filters_default_and_persist_normalized() = runBlocking {
        assertEquals(SlskResultFilters(), stores.resultFilters().first())
        stores.setResultFilters(
            SlskResultFilters(extensions = setOf("FLAC", "mp3"), bitrateMinKbps = 320, bitrateMaxKbps = 192, freeSlotOnly = true),
        )
        assertEquals(
            SlskResultFilters(extensions = setOf("flac", "mp3"), bitrateMinKbps = 192, bitrateMaxKbps = 320, freeSlotOnly = true),
            stores.resultFilters().first(),
        )
    }

    @Test
    fun search_history_roundtrip() = runBlocking {
        stores.addSearch("Abba", SlskSearchScope.SOULSEEK)
        stores.addSearch("AC/DC", SlskSearchScope.SOULSEEK)
        stores.addSearch("abba", SlskSearchScope.SOULSEEK)
        val entries = stores.searchHistory(SlskSearchScope.SOULSEEK).first()
        // re-submitted "abba" moved to the top, no case-insensitive duplicates
        assertEquals(listOf("abba", "AC/DC"), entries.map { it.query })
        assertTrue(entries.all { it.atEpochMillis > 0 })
    }

    @Test
    fun search_history_scopes_never_mix() = runBlocking {
        stores.addSearch("geogaddi flac", SlskSearchScope.SOULSEEK, searchId = "s-1")
        stores.addSearch("Boards of Canada", SlskSearchScope.LIBRARY)
        stores.addSearch("geogaddi flac", SlskSearchScope.LIBRARY)
        assertEquals(
            listOf("geogaddi flac", "Boards of Canada"),
            stores.searchHistory(SlskSearchScope.LIBRARY).first().map { it.query },
        )
        val soulseek = stores.searchHistory(SlskSearchScope.SOULSEEK).first()
        // the same text in the other scope did not replace the Soulseek entry
        assertEquals(listOf("geogaddi flac"), soulseek.map { it.query })
        assertEquals("s-1", soulseek.single().searchId)
    }

    @Test
    fun search_history_summary_rerun_and_remove() = runBlocking {
        stores.addSearch("aphex saw 2", SlskSearchScope.SOULSEEK, searchId = "s-1")
        stores.setSearchSummary("s-1", folderCount = 41)
        assertEquals(41, stores.searchHistory(SlskSearchScope.SOULSEEK).first().single().folderCount)

        // re-running takes the new id and forgets the old summary
        stores.addSearch("Aphex SAW 2", SlskSearchScope.SOULSEEK, searchId = "s-2")
        val rerun = stores.searchHistory(SlskSearchScope.SOULSEEK).first().single()
        assertEquals("s-2", rerun.searchId)
        assertEquals(null, rerun.folderCount)

        stores.removeSearch("aphex saw 2", SlskSearchScope.SOULSEEK)
        assertTrue(stores.searchHistory(SlskSearchScope.SOULSEEK).first().isEmpty())
    }

    @Test
    fun search_history_caps_each_scope_independently() = runBlocking {
        repeat(SlskdStoreLimits.SEARCH_HISTORY_LIMIT + 5) { stores.addSearch("lib $it", SlskSearchScope.LIBRARY) }
        stores.addSearch("only slsk", SlskSearchScope.SOULSEEK)
        val library = stores.searchHistory(SlskSearchScope.LIBRARY).first()
        assertEquals(SlskdStoreLimits.SEARCH_HISTORY_LIMIT, library.size)
        assertEquals("lib ${SlskdStoreLimits.SEARCH_HISTORY_LIMIT + 4}", library.first().query)
        assertEquals(listOf("only slsk"), stores.searchHistory(SlskSearchScope.SOULSEEK).first().map { it.query })
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
