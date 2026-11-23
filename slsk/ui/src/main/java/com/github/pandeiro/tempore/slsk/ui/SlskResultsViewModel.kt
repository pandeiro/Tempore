package com.github.pandeiro.tempore.slsk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import com.github.pandeiro.tempore.slsk.core.domain.SlskResultFilters
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Renders one search run: the streaming progress state, the honest banner state and the
 * persisted result filters (spec §5.4 "filter defaults persist"). The DataStore is the
 * single source of truth for filters — the filter sheet writes it, this collects it.
 */
class SlskResultsViewModel(
    val searchId: String,
    val fallbackQuery: String?,
) : ViewModel() {

    enum class Banner { NONE, UNREACHABLE, UNAUTHORIZED, DISCONNECTED, NOT_CONFIGURED, RAN_OUT, EXPIRED }

    data class UiModel(
        val run: SlskSearchCoordinator.SearchRun? = null,
        val groups: List<ResultGroup> = emptyList(),
        val responders: Int = 0,
        val fileCount: Int = 0,
        val isComplete: Boolean = false,
        val banner: Banner = Banner.NONE,
        val bannerDetail: String? = null,
    )

    private val _ui = MutableStateFlow(UiModel())
    val ui: StateFlow<UiModel> = _ui

    /** LRU-pinned edit window: all groups from slskd (spec §5.4 cap handled at render). */
    private var allGroups: List<ResultGroup> = emptyList()

    private val visibleLimit = MutableStateFlow(100)
    val visibleLimitState: StateFlow<Int> = visibleLimit

    /** Mirror of the persisted filters; written only by the store collector below. */
    private var filters = SlskResultFilters()

    /** Collectors of the currently bound run (declared before init: setup() binds). */
    private var bindings: List<Job> = emptyList()

    init {
        setup()
        viewModelScope.launch {
            runCatching {
                SlskSessionAccess.stores.resultFilters().collect { persisted ->
                    filters = persisted
                    _ui.value = _ui.value.copy(groups = applyFilters())
                }
            }
        }
    }

    private fun setup() {
        // Reattach, never silently re-run (PLAN.md §8, I1): the in-memory run, else the search
        // re-read from slskd by id. An expired search offers an explicit "Run again".
        val run = when {
            searchId.isNotBlank() -> SlskSearchCoordinator.reattach(searchId, fallbackQuery)
            !fallbackQuery.isNullOrBlank() -> SlskSearchCoordinator.start(fallbackQuery)
            else -> {
                _ui.value = _ui.value.copy(banner = Banner.RAN_OUT)
                return
            }
        }
        bindRun(run)
    }

    /** Query this screen is about (the run's, or the deep link's for a reopened search). */
    val query: String
        get() = _ui.value.run?.query?.takeIf { it.isNotBlank() } ?: fallbackQuery.orEmpty()

    private fun ingest(progress: SearchProgress) {
        allGroups = SlskRanking.sort(progress.groups)
        _ui.value = _ui.value.copy(
            groups = applyFilters(),
            responders = progress.responseCount,
            fileCount = progress.fileCount,
            isComplete = progress.isComplete,
        )
    }

    private fun applyFilters(): List<ResultGroup> {
        val f = filters
        return allGroups.filter { f.matchesGroup(it) }.take(visibleLimit.value)
    }

    fun loadMore() {
        visibleLimit.value = (visibleLimit.value + 100).coerceAtMost(allGroups.size)
        refreshFilters()
    }

    fun refreshFilters() {
        _ui.value = _ui.value.copy(groups = applyFilters())
    }

    fun clearBanner() {
        _ui.value = _ui.value.copy(banner = Banner.NONE, bannerDetail = null)
    }

    fun canLoadMore(): Boolean {
        val f = filters
        return allGroups.count { f.matchesGroup(it) } > visibleLimit.value
    }

    /** Re-runs the same query when the slskd search failed to produce anything. */
    fun retry() {
        val text = query
        if (text.isBlank()) return
        clearBanner()
        bindRun(SlskSearchCoordinator.start(text))
    }

    private fun bindRun(run: SlskSearchCoordinator.SearchRun) {
        // Never flash the previous run's rows on retry()/process-death restart, and stop
        // listening to the previous run so a late snapshot can't land on the new one.
        bindings.forEach { it.cancel() }
        allGroups = emptyList()
        visibleLimit.value = 100
        _ui.value = _ui.value.copy(run = run, groups = emptyList(), responders = 0, fileCount = 0, isComplete = false)
        bindings = listOf(
            viewModelScope.launch {
                run.failure.collect { cause ->
                    if (cause != null) {
                        _ui.value = _ui.value.copy(banner = classify(cause), bannerDetail = cause.message)
                    }
                }
            },
            viewModelScope.launch {
                run.progress.collect { progress -> ingest(progress) }
            },
        )
    }

    fun stopSearch() {
        _ui.value.run?.cancel()
        clearBanner()
    }

    private suspend fun classify(cause: Throwable): Banner {
        // Spring to a health check when the transport already failed: slskd down vs
        // slskd-up-but-disconnected produce different banners (§5.5).
        if (cause is com.github.pandeiro.tempore.slsk.core.domain.SearchExpiredException) return Banner.EXPIRED
        val message = cause.message ?: ""
        if (message.contains("401") || message.contains("403")) {
            return Banner.UNAUTHORIZED
        }
        (cause as? retrofit2.HttpException)?.let {
            if (it.code() == 401 || it.code() == 403) return Banner.UNAUTHORIZED
        }
        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SlskSessionAccess.gateway.health()
            }
        }.map { health ->
            if (!health.isConnected) Banner.DISCONNECTED else Banner.UNREACHABLE
        }.getOrDefault(Banner.UNREACHABLE)
    }

    class Factory(private val searchId: String, private val fallbackQuery: String?) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SlskResultsViewModel(searchId, fallbackQuery) as T
    }
}
