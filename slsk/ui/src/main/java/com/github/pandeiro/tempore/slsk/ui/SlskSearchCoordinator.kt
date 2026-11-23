package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.core.domain.SearchOptions
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import com.github.pandeiro.tempore.slsk.core.store.SlskSearchScope
import java.util.UUID
import kotlin.collections.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One active search at a time (spec §7); the run lives app-scope so leaving the results
 * screen does not kill it, while the 1.5 s cadence lives inside the core gateway flow.
 *
 * Keeps an in-memory LRU of the last 3 runs — finished runs included, so going back to a
 * search never re-runs it (PLAN.md §8, I1). Beyond memory, [reattach] reopens the search from
 * slskd itself by id; only an expired search needs an explicit re-run. Progress state is
 * intentionally not carried across in bundles/SavedStateHandle — ids only.
 */
object SlskSearchCoordinator {

    class SearchRun internal constructor(
        val searchId: String,
        val query: String,
        val progress: SharedFlow<SearchProgress>,
        val failure: StateFlow<Throwable?>,
        val job: Job,
        private val onDone: (SearchRun) -> Unit,
    ) {
        /** Latest snapshot (finished runs keep their last one). */
        val latest: SearchProgress? get() = progress.replayCache.lastOrNull()

        /** Still polling slskd. */
        val isLive: Boolean get() = job.isActive

        fun cancel() = onDone(this)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow<SearchRun?>(null)
    val active: StateFlow<SearchRun?> = _active

    private val recent = object : LinkedHashMap<String, SearchRun>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchRun>): Boolean = size > 3
    }

    fun find(searchId: String): SearchRun? = synchronized(recent) { recent[searchId] }

    /**
     * Starts a NEW search (client-generated UUID id), stopping the previous active one, and
     * records it in the Soulseek history with its id. Collector-safe: never throws.
     */
    fun start(
        query: String,
        searchId: String = UUID.randomUUID().toString(),
        options: SearchOptions = SearchOptions(searchText = query, searchId = searchId),
    ): SearchRun {
        active.value?.let { stop(it) }
        scope.launch {
            runCatching { SlskSessionAccess.stores.addSearch(query, SlskSearchScope.SOULSEEK, searchId) }
        }
        val run = launch(searchId, query) { SlskSessionAccess.gateway.search(query, options) }
        _active.value = run
        return run
    }

    /**
     * The run for `searchId` without starting a new Soulseek search: the in-memory run when it
     * has not failed, otherwise the search re-read from slskd ([run.failure] carries
     * `SearchExpiredException` when slskd no longer has it).
     */
    fun reattach(searchId: String, query: String?): SearchRun {
        find(searchId)?.takeIf { it.failure.value == null }?.let { return it }
        return launch(searchId, query.orEmpty()) { SlskSessionAccess.gateway.reopen(searchId) }
    }

    private fun launch(searchId: String, query: String, source: () -> Flow<SearchProgress>): SearchRun {
        val shared = MutableSharedFlow<SearchProgress>(replay = 1, extraBufferCapacity = 64)
        val failures = MutableStateFlow<Throwable?>(null)
        val job = scope.launch {
            try {
                source().collect { shared.tryEmit(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures.value = t
            }
        }
        job.invokeOnCompletion {
            val last = shared.replayCache.lastOrNull() ?: return@invokeOnCompletion
            // A stopped (superseded/cancelled) run renders as finished with what it found.
            if (!last.isComplete) shared.tryEmit(last.copy(isComplete = true))
            scope.launch {
                runCatching { SlskSessionAccess.stores.setSearchSummary(searchId, last.groups.size) }
            }
        }
        val run = SearchRun(searchId, query, shared, failures, job, ::stop)
        synchronized(recent) { recent[searchId] = run }
        return run
    }

    private fun stop(run: SearchRun) {
        run.job.cancel()
        if (_active.value === run) _active.value = null
    }
}
