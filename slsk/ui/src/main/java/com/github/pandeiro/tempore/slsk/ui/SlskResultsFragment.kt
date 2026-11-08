package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup
import kotlinx.coroutines.launch

/**
 * Soulseek results screen (spec §5.3 step 2/3, §5.4, §5.5): tinted surface, persistent
 * banner, streaming progress, filters, empty/error states, first-use sheet once.
 */
class SlskResultsFragment : Fragment() {

    private var _bind: SlskFragmentResultsBinding? = null
    private val bind get() = _bind!!

    val searchId: String
        get() = requireArguments().getString("slsk_search_id") ?: ""

    private val viewModel by viewModels<SlskResultsViewModel> {
        SlskResultsViewModel.Factory(searchId, requireArguments().getString("slsk_fallback_query"))
    }

    private lateinit var adapter: SlskResultGroupAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _bind = SlskFragmentResultsBinding.inflate(inflater, container, false)
        adapter = SlskResultGroupAdapter(::openFolder)
        bind.slskResultsList.layoutManager = LinearLayoutManager(requireContext())
        bind.slskResultsList.adapter = adapter
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind.slskResultsToolbar.inflateMenu(R.menu.slsk_menu_results)
        bind.slskResultsToolbar.setNavigationOnClickListener {
            Navigation.findNavController(bind.root).popBackStack()
        }
        bind.slskResultsToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.slsk_action_filter -> { showFilterMenu(); true }
                R.id.slsk_action_activity -> { openActivity(); true }
                else -> false
            }
        }
        bind.slskResultsErrorAction.setOnClickListener {
            if (viewModel.ui.value.banner == SlskResultsViewModel.Banner.UNAUTHORIZED) openSettings()
            else viewModel.retry()
        }
        bind.slskResultsLoadMore.setOnClickListener { viewModel.loadMore() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.ui.collect { render(it) } }
                launch {
                    SlskTransferWatcher.activeCount.collect { count ->
                        view ?: return@collect
                        if (count > 0) {
                            bind.slskResultsToolbar.subtitle =
                                activeCountLabel(count)
                        } else {
                            bind.slskResultsToolbar.subtitle = null
                        }
                    }
                }
            }
        }
        maybeShowFirstUseSheet()
    }

    private fun activeCountLabel(count: Int): String =
        getString(R.string.slsk_results_active_transfers, count)

    private fun render(model: SlskResultsViewModel.UiModel) {
        adapter.submitGroups(model.groups)

        val searching = model.run != null && !model.isComplete &&
            model.banner == SlskResultsViewModel.Banner.NONE

        if (searching) {
            bind.slskResultsProgress.visibility = View.VISIBLE
            bind.slskResultsProgress.text = getString(
                R.string.slsk_results_searching_counts, model.responders, model.fileCount
            )
        } else {
            bind.slskResultsProgress.visibility = View.GONE
        }

        val hasContent = model.groups.isNotEmpty()
        val anyError = model.banner != SlskResultsViewModel.Banner.NONE
        val showEmpty = !hasContent && !searching && !anyError

        bind.slskResultsList.visibility = View.VISIBLE
        bind.slskResultsEmptyGroup.visibility = if (showEmpty) View.VISIBLE else View.GONE
        bind.slskResultsLoadMore.visibility =
            if (hasContent && viewModel.canLoadMore()) View.VISIBLE else View.GONE

        bind.slskResultsErrorBanner.visibility = if (anyError) View.VISIBLE else View.GONE
        bind.slskResultsErrorText.text = when (model.banner) {
            SlskResultsViewModel.Banner.UNREACHABLE -> getString(R.string.slsk_error_unreachable)
            SlskResultsViewModel.Banner.UNAUTHORIZED -> getString(R.string.slsk_error_unauthorized)
            SlskResultsViewModel.Banner.DISCONNECTED -> getString(R.string.slsk_error_disconnected)
            SlskResultsViewModel.Banner.RAN_OUT -> getString(R.string.slsk_error_search_failed)
            else -> ""
        }
        bind.slskResultsErrorAction.visibility =
            if (model.banner in listOf(
                    SlskResultsViewModel.Banner.UNREACHABLE,
                    SlskResultsViewModel.Banner.UNAUTHORIZED,
                    SlskResultsViewModel.Banner.RAN_OUT,
                )
            ) View.VISIBLE else View.GONE
        bind.slskResultsErrorAction.text =
            if (model.banner == SlskResultsViewModel.Banner.UNAUTHORIZED) {
                getString(R.string.slsk_error_open_settings)
            } else {
                getString(R.string.slsk_retry)
            }
    }

    private fun maybeShowFirstUseSheet() {
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (SlskPrefs.isFirstUse(requireContext())) {
                    SlskFirstUseSheetFragment().show(parentFragmentManager, "slsk_first_use")
                    SlskPrefs.setFirstUseShown(requireContext())
                }
            }
        }
    }

    private fun showFilterMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), bind.slskResultsToolbar)
        popup.menuInflater.inflate(R.menu.slsk_menu_filters, popup.menu)
        val f = viewModel.ui.value.filters
        popup.menu.findItem(R.id.slsk_filter_lossless).isChecked = f.losslessOnly
        popup.menu.findItem(R.id.slsk_filter_320).isChecked = f.min320Kbps
        popup.menu.findItem(R.id.slsk_filter_free).isChecked = f.freeSlotOnly
        popup.menu.getItem(0).isChecked = true
        popup.setOnMenuItemClickListener { item ->
            val current = viewModel.ui.value.filters
            when (item.itemId) {
                R.id.slsk_filter_lossless -> viewModel.setFilters(current.copy(losslessOnly = !current.losslessOnly))
                R.id.slsk_filter_320 -> viewModel.setFilters(current.copy(min320Kbps = !current.min320Kbps))
                R.id.slsk_filter_free -> viewModel.setFilters(current.copy(freeSlotOnly = !current.freeSlotOnly))
                R.id.slsk_filter_format_order -> { /* default sort already applied */ }
            }
            true
        }
        popup.show()
    }

    private fun openFolder(group: ResultGroup) {
        val args = Bundle().apply {
            putString("slsk_username", group.username)
            putString("slsk_directory", group.directory)
            putString("slsk_search_id", searchId)
        }
        SlskFolderSheetFragment().apply { arguments = args }
            .show(parentFragmentManager, "slsk_folder")
    }

    private fun openActivity() {
        Navigation.findNavController(bind.root).navigate(R.id.slskActivityFragment)
    }

    private fun openSettings() {
        startActivity(android.content.Intent(requireContext(), SlskSettingsActivity::class.java))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _bind = null
    }
}
