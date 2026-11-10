package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.pandeiro.tempore.slsk.core.domain.FileAllowlist
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder detail (spec §5.3 step 4/5): per-file selection with download button, optional
 * full-folder load, enqueue batch with per-file failure feedback.
 */
class SlskFolderSheetFragment : BottomSheetDialogFragment() {

    private var _bind: SlskSheetFolderBinding? = null
    private val bind get() = _bind!!

    private val username get() = requireArguments().getString("slsk_username") ?: ""
    private val directory get() = requireArguments().getString("slsk_directory") ?: ""
    private val searchId get() = requireArguments().getString("slsk_search_id") ?: ""

    private val files = ArrayList<RemoteFile>()
    private val selected = HashSet<RemoteFile>()
    private lateinit var adapter: SlskFolderFileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _bind = SlskSheetFolderBinding.inflate(inflater, container, false)
        adapter = SlskFolderFileAdapter(files, ::toggle, selected::contains)
        bind.slskSheetFiles.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        bind.slskSheetFiles.adapter = adapter
        bind.slskSheetTitle.text = getString(R.string.slsk_sheet_folder_title, username)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind.slskSheetSelectAll.setOnClickListener {
            selected.clear()
            selected.addAll(files.filter { !it.isLocked })
            adapter.notifyDataSetChanged()
            refreshDownloadLabel()
        }
        bind.slskSheetDownload.setOnClickListener { enqueueSelected() }
        bind.slskSheetLoadFullFolder.setOnClickListener { loadFullFolder() }
        loadMatchedFiles()
    }

    /** Default view: only the files matched by the search; "Load full folder" widens it. */
    private fun loadMatchedFiles() {
        val run = searchId.takeIf { it.isNotEmpty() }?.let { SlskSearchCoordinator.find(it) }
            ?: SlskSearchCoordinator.active.value
        val matched = run?.progress?.replayCache
            ?.firstOrNull()
            ?.groups
            ?.firstOrNull { it.username.equals(username, true) && it.directory == directory }
            ?.allowedFiles
        if (matched.isNullOrEmpty()) loadFullFolder() else setFiles(matched, allFiles = false)
    }

    private fun loadFullFolder() {
        lifecycleScope.launch {
            val gateway = SlskSessionAccess.gatewayOrNull()
            if (gateway == null) {
                Snackbar.make(bind.root, R.string.slsk_error_not_configured, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val loaded: List<RemoteFile>? = withContext(Dispatchers.IO) {
                try {
                    gateway.folderContents(username, directory)
                } catch (_: Throwable) {
                    null
                }
            }
            when {
                loaded == null -> Snackbar.make(bind.root, R.string.slsk_sheet_load_failed, Snackbar.LENGTH_LONG).show()
                loaded.isEmpty() -> Snackbar.make(bind.root, R.string.slsk_sheet_empty, Snackbar.LENGTH_SHORT).show()
                else -> setFiles(
                    loaded.filter { FileAllowlist.isAllowed(it.extension) },
                    allFiles = true,
                )
            }
        }
    }

    private fun setFiles(list: List<RemoteFile>, allFiles: Boolean) {
        files.clear()
        files.addAll(list)
        selected.clear()
        selected.addAll(files.filter { !it.isLocked })
        bind.slskSheetLoadFullFolder.visibility = if (allFiles) View.GONE else View.VISIBLE
        bind.slskSheetLockedHint.visibility =
            if (files.any { it.isLocked }) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        refreshDownloadLabel()
    }

    private fun toggle(file: RemoteFile) {
        if (file.isLocked) return
        if (!selected.remove(file)) selected.add(file)
        adapter.notifyDataSetChanged()
        refreshDownloadLabel()
    }

    private fun refreshDownloadLabel() {
        bind.slskSheetDownload.text = if (selected.isEmpty()) {
            getString(R.string.slsk_sheet_download_none)
        } else {
            getString(R.string.slsk_sheet_download, selected.size)
        }
    }

    private fun enqueueSelected() {
        if (selected.isEmpty()) return
        val chosen = selected.toList()
        lifecycleScope.launch {
            val gateway = SlskSessionAccess.gatewayOrNull()
            if (gateway == null) {
                Snackbar.make(bind.root, R.string.slsk_error_not_configured, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                try {
                    gateway.enqueue(username, chosen, searchId.ifEmpty { null })
                } catch (_: Throwable) {
                    null
                }
            }
            if (outcome == null) {
                Snackbar.make(bind.root, R.string.slsk_sheet_enqueue_failed_none, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            dismiss()
            SlskUiBus.notifyEnqueue(
                path = chosen.firstOrNull()?.directory ?: directory,
                queued = outcome.acceptedCount(chosen.size),
                failures = outcome.failures.map { it.reason },
            )
        }
    }

    private fun com.github.pandeiro.tempore.slsk.core.domain.EnqueueOutcome.acceptedCount(fallbackTotal: Int): Int =
        if (accepted) fallbackTotal - failureCount else 0
}
