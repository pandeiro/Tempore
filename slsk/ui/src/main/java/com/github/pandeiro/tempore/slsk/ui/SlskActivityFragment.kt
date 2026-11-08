package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.NonNull
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.Transfer
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Soulseek activity (spec §5.3 step 6, §5.5): per-folder transfer cards, retry failed,
 * cancel, clear completed, queue-position style row states, handoff chips.
 */
class SlskActivityFragment : Fragment() {

    private var _bind: SlskFragmentActivityBinding? = null
    private val bind get() = _bind!!

    private val viewModel by viewModels<SlskActivityViewModel>()

    private lateinit var adapter: SlskActivityGroupAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _bind = SlskFragmentActivityBinding.inflate(inflater, container, false)
        adapter = SlskActivityGroupAdapter(
            onRetry = ::retryGroup,
            onCancel = ::cancelTransfer,
            onOpenAlbum = viewModel::openInLibrary,
            onRescan = viewModel::startManualRescan,
        )
        bind.slskActivityList.layoutManager = LinearLayoutManager(requireContext())
        bind.slskActivityList.adapter = adapter
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind.slskActivityToolbar.setNavigationOnClickListener {
            try {
                Navigation.findNavController(bind.root).popBackStack()
            } catch (e: IllegalStateException) {
                // embedded outside a NavHost (settings entry): remove the fragment instead
                requireActivity().supportFragmentManager.beginTransaction()
                    .remove(this).commit()
            }
        }
        bind.slskActivityErrorAction.setOnClickListener { viewModel.startManualRescan() }
        bind.slskActivityClear.setOnClickListener { clearCompleted() }
        bind.slskActivityClear.visibility = View.VISIBLE

        lifecycleScope.launch {
            viewModel.setAutoscan(SlskPrefs.autoscanEnabled(requireContext()))
        }
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var misses = 0
                while (kotlin.coroutines.coroutineContext.isActive) {
                    val groups = viewModel.refresh()
                    val ok = viewModel.lastRefreshSucceeded
                    adapter.submit(groups, viewModel)
                    bind.slskActivityEmpty.visibility =
                        if (groups.isEmpty() && ok) View.VISIBLE else View.GONE
                    bind.slskActivityClear.visibility =
                        if (groups.any { it.allSucceeded }) View.VISIBLE else View.GONE
                    if (ok) bind.slskActivityErrorBanner.visibility = View.GONE
                    if (!ok) bind.slskActivityErrorBanner.visibility = View.VISIBLE
                    if (!ok && ++misses == 1) bind.slskActivityErrorText.setText(R.string.slsk_error_unreachable)
                    if (!ok) {
                        // §6.3 rule 6: exponential backoff on errors, ceiling 30 s
                        delay(minOf(3_000L * misses, 30_000L))
                    } else {
                        misses = 0
                        delay(3_000)
                    }
                }
            }
        }
    }

    private fun retryGroup(group: TransferGroup) {
        lifecycleScope.launch {
            val gateway = SlskSessionAccess.gatewayOrNull() ?: return@launch
            val failed = group.transfers.filter { it.lifecycle == TransferLifecycle.FAILED }
            val remote = failed.map { t ->
                RemoteFile(
                    filename = t.filename,
                    size = t.size,
                    extension = t.filename.substringAfterLast('.').lowercase(),
                )
            }
            val outcome = withContext(Dispatchers.IO) {
                try {
                    gateway.enqueue(group.username, remote, null)
                } catch (_: Throwable) {
                    null
                }
            }
            if (outcome != null && outcome.accepted) {
                Snackbar.make(
                    bind.root,
                    getString(R.string.slsk_sheet_enqueue_done, remote.size - outcome.failureCount),
                    Snackbar.LENGTH_SHORT,
                ).show()
            } else {
                Snackbar.make(
                    bind.root,
                    getString(
                        R.string.slsk_sheet_enqueue_partial,
                        remote.size - (outcome?.failureCount ?: remote.size),
                        outcome?.failureCount ?: remote.size,
                        outcome?.failures?.firstOrNull()?.reason ?: "-",
                    ),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            adapter.submit(viewModel.refresh(), viewModel)
        }
    }

    private fun cancelTransfer(transfer: Transfer) {
        lifecycleScope.launch {
            val gateway = SlskSessionAccess.gatewayOrNull() ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    gateway.cancel(transfer.username, transfer.id, true)
                } catch (_: Throwable) {
                }
            }
            adapter.submit(viewModel.refresh(), viewModel)
        }
    }

    private fun clearCompleted() {
        lifecycleScope.launch {
            val gateway = SlskSessionAccess.gatewayOrNull() ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    gateway.clearCompleted()
                } catch (_: Throwable) {
                }
            }
            adapter.submit(viewModel.refresh(), viewModel)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _bind = null
    }
}
