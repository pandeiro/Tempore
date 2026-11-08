package com.github.pandeiro.tempore.slsk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pandeiro.tempore.slsk.core.gateway.ScanStatus
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.gateway.LibraryAlbumMatch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Soulseek activity screen state: 3 s transfer snapshots (§6.3 rule 6), per-group
 * handoff lifecycle (§5.3 step 7, §8.2), retry/cancel/clear actions.
 */
class SlskActivityViewModel : ViewModel() {

    enum class Handoff { NONE, PENDING, SCANNING, IN_LIBRARY, NOT_VISIBLE, SCAN_FAILED }

    data class GroupUi(
        val group: TransferGroup,
        val handoff: Handoff,
        val albumId: String?,
    )

    private val _groups = MutableStateFlow<List<TransferGroup>>(emptyList())
    val groups: StateFlow<List<TransferGroup>> = _groups

    /** False when the last snapshot could not be fetched (unreachable slskd etc.). */
    var lastRefreshSucceeded: Boolean = false
        private set

    private data class HandoffState(
        var stage: Handoff = Handoff.NONE,
        val albumMatch: LibraryAlbumMatch? = null,
    )

    private val handoffs = ConcurrentHashMap<String, HandoffState>()

    fun handoffStage(group: TransferGroup): Handoff =
        handoffs[key(group)]?.stage ?: Handoff.NONE

    fun albumIdOf(group: TransferGroup): String? = handoffs[key(group)]?.albumMatch?.id

    private val _handoffTicks = MutableStateFlow(0)
    val handoffTicks: StateFlow<Int> = _handoffTicks

    private val _rescanFailed = MutableStateFlow(false)
    val rescanFailed: StateFlow<Boolean> = _rescanFailed

    /** Autoscan flag pushed from the fragment (it owns a Context for the prefs). */
    @Volatile
    var autoscanCached: Boolean = true
        private set

    fun setAutoscan(enabled: Boolean) {
        autoscanCached = enabled
    }

    /** Pulls one transfers() snapshot off the 3 s ticker the fragment runs. */
    suspend fun refresh(): List<TransferGroup> {
        val list = withContext(Dispatchers.IO) {
            try {
                SlskSessionAccess.gatewayOrNull()?.transfers()?.first()
            } catch (_: Throwable) {
                null
            }
        }
        if (list != null) {
            lastRefreshSucceeded = true
            _groups.value = list
            scheduleHandoffs(list)
        } else {
            lastRefreshSucceeded = false
        }
        return list ?: emptyList()
    }

    /** Per-group trigger bookkeeping so a completed album scans exactly once. */
    private val triggered = java.util.Collections.newSetFromMap<String>(ConcurrentHashMap())

    private fun scheduleHandoffs(list: List<TransferGroup>) {
        if (!autoscanCached) return
        for (group in list) {
            if (!group.allSucceeded) continue
            if (!triggered.add(key(group))) continue
            startHandoff(group)
        }
    }

    private fun key(group: TransferGroup): String = "${group.username}:${group.directory}"

    private fun startHandoff(group: TransferGroup) {
        val k = key(group)
        handoffs[k] = HandoffState(Handoff.PENDING)
        bumpTicks()
        viewModelScope.launch {
            val library = SlskSessionAccess.libraryGateway
            if (library == null) {
                // Navidrome unreachable / not signed in: skip the scan, mark downloaded.
                handoffs[k] = HandoffState(Handoff.NOT_VISIBLE)
                bumpTicks()
                return@launch
            }
            delay(5_000)
            val started = withContext(Dispatchers.IO) {
                try {
                    library.startScan()
                } catch (_: Throwable) {
                    false
                }
            }
            handoffs[k] = if (started) HandoffState(Handoff.SCANNING) else HandoffState(Handoff.SCAN_FAILED)
            bumpTicks()
            if (!started) return@launch
            // 2 s cadence up to 2 minutes (§6.3).
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                val status = withContext(Dispatchers.IO) {
                    try {
                        library.scanStatus()
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (status !is ScanStatus.InProgress) break
            }
            val (artist, album) = parseFolderName(group.directory)
            val match: LibraryAlbumMatch? = withContext(Dispatchers.IO) {
                try {
                    library.findAlbum(artist, album)
                } catch (_: Throwable) {
                    null
                }
            }
            handoffs[k] = HandoffState(if (match != null) Handoff.IN_LIBRARY else Handoff.NOT_VISIBLE, match)
            bumpTicks()
        }
    }

    fun openInLibrary(group: TransferGroup) {
        val state = handoffs[key(group)] ?: return
        val id = state.albumMatch?.id ?: return
        val (artist, album) = parseFolderName(group.directory)
        SlskSessionAccess.host?.openAlbum(id, state.albumMatch?.name ?: album, artist)
    }

    fun startManualRescan() {
        viewModelScope.launch {
            val library = SlskSessionAccess.libraryGateway
            _rescanFailed.value = library != null && !withContext(Dispatchers.IO) {
                try {
                    library.startScan()
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    private fun bumpTicks() {
        _handoffTicks.value = _handoffTicks.value + 1
    }

    /** "Artist\\Album" or "Artist - Album (2019)" → (artist, album). */
    fun parseFolderName(directory: String): Pair<String, String> {
        val segments = directory.split('\\').map { it.trim() }.filter { it.isNotEmpty() }
        val tail = segments.takeLast(2)
        if (tail.size == 2) return tail[0] to tail[1]
        val cleaned = cleanTitle(segments.lastOrNull() ?: "")
            .replace(" - ", " § ")
        val parts = cleaned.split(" § ")
        return if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(" ").trim()
        else "" to cleaned.trim()
    }
}
