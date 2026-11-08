package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.core.domain.SearchOptions
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import java.util.UUID
import kotlin.collections.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One active search at a time (spec §7); the run lives app-scope so leaving the results
 * screen does not kill it, while the 1.5 s cadence lives inside the core gateway flow.
 * Keeps an in-memory LRU of the last 3 runs (progress state is intentionally not
 * carried across in bundles/SavedStateHandle — ids only).
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
     * Starts a search (client-generated UUID id) and pumps core gateway progress onto
     * `run.progress`; failures surface on `run.failure`. Collector-safe: never throws.
     */
    fun start(
        query: String,
        searchId: String = UUID.randomUUID().toString(),
        options: SearchOptions = SearchOptions(searchText = query, searchId = searchId),
    ): SearchRun {
        active.value?.let { stop(it) }
        val shared = MutableSharedFlow<SearchProgress>(replay = 1, extraBufferCapacity = 64)
        val failures = MutableStateFlow<Throwable?>(null)
        var runRef: SearchRun? = null
        val job = scope.launch {
            try {
                SlskSessionAccess.gateway.search(query, options).collect { shared.tryEmit(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures.value = t
            }
        }
        job.invokeOnCompletion { runRef?.let { synchronized(recent) { recent.remove(it.searchId) } } }
        val run = SearchRun(searchId, query, shared, failures, job, ::stop).also { runRef = it }
        synchronized(recent) { recent[searchId] = run }
        _active.value = run
        return run
    }

    private fun stop(run: SearchRun) {
        run.job.cancel()
        if (_active.value === run) _active.value = null
    }
}
