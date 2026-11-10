package com.github.pandeiro.tempore.slsk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Renders one search run. Owns filters (persisted only in memory for MVP — filters are
 * per-run adjustable, spec §5.4 "all adjustable"), the streaming progress state and the
 * honest banner state.
 */
class SlskResultsViewModel(
    val searchId: String,
    val fallbackQuery: String?,
) : ViewModel() {

    enum class Banner { NONE, UNREACHABLE, UNAUTHORIZED, DISCONNECTED, NOT_CONFIGURED, RAN_OUT }

    data class UiModel(
        val run: SlskSearchCoordinator.SearchRun? = null,
        val groups: List<ResultGroup> = emptyList(),
        val responders: Int = 0,
        val fileCount: Int = 0,
        val isComplete: Boolean = false,
        val banner: Banner = Banner.NONE,
        val bannerDetail: String? = null,
        val filters: SlskResultFilters = SlskResultFilters(),
    )

    private val _ui = MutableStateFlow(UiModel())
    val ui: StateFlow<UiModel> = _ui

    /** LRU-pinned edit window: all groups from slskd (spec §5.4 cap handled at render). */
    private var allGroups: List<ResultGroup> = emptyList()

    private val visibleLimit = MutableStateFlow(100)
    val visibleLimitState: StateFlow<Int> = visibleLimit

    init {
        setup()
    }

    private fun setup() {
        val run = SlskSearchCoordinator.find(searchId) ?: run {
            if (fallbackQuery.isNullOrBlank()) {
                _ui.value = _ui.value.copy(banner = Banner.RAN_OUT)
                return
            }
            // Process death recovered with the id-only policy: restart the same query fresh.
            SlskSearchCoordinator.start(fallbackQuery)
        }

        _ui.value = _ui.value.copy(run = run)
        bindRun(run)
    }

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
        val f = _ui.value.filters
        return allGroups.filter { f.matches(it) }.take(visibleLimit.value)
    }

    fun setFilters(filters: SlskResultFilters) {
        _ui.value = _ui.value.copy(filters = filters)
        _ui.value = _ui.value.copy(groups = applyFilters())
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
        val f = _ui.value.filters
        return allGroups.count { f.matches(it) } > visibleLimit.value
    }

    /** Re-runs the same query when the slskd search failed to produce anything. */
    fun retry() {
        val run = _ui.value.run ?: return
        clearBanner()
        val fresh = SlskSearchCoordinator.start(run.query)
        bindRun(fresh)
    }

    private fun bindRun(run: SlskSearchCoordinator.SearchRun) {
        _ui.value = _ui.value.copy(run = run, groups = emptyList(), responders = 0, fileCount = 0, isComplete = false)
        viewModelScope.launch {
            run.failure.collect { cause ->
                if (cause != null) {
                    _ui.value = _ui.value.copy(banner = classify(cause), bannerDetail = cause.message)
                }
            }
        }
        viewModelScope.launch {
            run.progress.collect { progress -> ingest(progress) }
        }
    }

    fun stopSearch() {
        _ui.value.run?.cancel()
        clearBanner()
    }

    private suspend fun classify(cause: Throwable): Banner {
        // Spring to a health check when the transport already failed: slskd down vs
        // slskd-up-but-disconnected produce different banners (§5.5).
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
