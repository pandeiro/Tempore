package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.github.pandeiro.tempore.slsk.core.domain.FileAllowlist
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.SlskResultFilters
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Results filter sheet (spec §2.3): audio types, lossy bitrate range, free slot. Reads and
 * writes the persisted [SlskResultFilters] directly; the results ViewModel collects them.
 */
class SlskFilterSheetFragment : BottomSheetDialogFragment() {

    private var _bind: SlskSheetFilterBinding? = null
    private val bind get() = _bind!!

    private val chips = LinkedHashMap<String, Chip>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _bind = SlskSheetFilterBinding.inflate(inflater, container, false)
        val lossless = RemoteFile.losslessExts.filter { it in FileAllowlist.AUDIO }
        val lossy = FileAllowlist.AUDIO.filter { it !in RemoteFile.losslessExts }
        addChips(inflater, bind.slskFilterLosslessGroup, lossless)
        addChips(inflater, bind.slskFilterLossyGroup, lossy)
        return bind.root
    }

    private fun addChips(inflater: LayoutInflater, group: ChipGroup, extensions: List<String>) {
        extensions.forEach { ext ->
            val chip = SlskItemFilterChipBinding.inflate(inflater, group, false).root
            chip.text = ext
            chip.id = View.generateViewId()
            group.addView(chip)
            chips[ext] = chip
        }
    }

    override fun onStart() {
        super.onStart()
        // Bitrate fields sit at the bottom: open fully and let the IME push the sheet up.
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.let { d ->
            @Suppress("DEPRECATION")
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            d.behavior.skipCollapsed = true
            d.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind.slskFilterApply.isEnabled = false
        bind.slskFilterReset.setOnClickListener { persistAndDismiss(SlskResultFilters()) }
        bind.slskFilterApply.setOnClickListener { persistAndDismiss(readForm()) }
        // Chips carry generated ids (no view-state restore), so always render the stored value.
        viewLifecycleOwner.lifecycleScope.launch {
            val current = runCatching { SlskSessionAccess.stores.resultFilters().first() }
                .getOrDefault(SlskResultFilters())
            render(current)
            bind.slskFilterApply.isEnabled = true
        }
    }

    private fun render(f: SlskResultFilters) {
        chips.forEach { (ext, chip) -> chip.isChecked = f.extensions.any { it.equals(ext, ignoreCase = true) } }
        bind.slskFilterBitrateMin.setText(f.bitrateMinKbps?.toString().orEmpty())
        bind.slskFilterBitrateMax.setText(f.bitrateMaxKbps?.toString().orEmpty())
        bind.slskFilterFreeSlot.isChecked = f.freeSlotOnly
    }

    private fun readForm(): SlskResultFilters = SlskResultFilters(
        extensions = chips.filterValues { it.isChecked }.keys.toSet(),
        bitrateMinKbps = bind.slskFilterBitrateMin.text?.toString()?.trim()?.toIntOrNull(),
        bitrateMaxKbps = bind.slskFilterBitrateMax.text?.toString()?.trim()?.toIntOrNull(),
        freeSlotOnly = bind.slskFilterFreeSlot.isChecked,
    ).normalized()

    /** Waits for the DataStore write before dismissing so the edit is never cancelled. */
    private fun persistAndDismiss(filters: SlskResultFilters) {
        bind.slskFilterApply.isEnabled = false
        bind.slskFilterReset.isEnabled = false
        lifecycleScope.launch {
            runCatching { SlskSessionAccess.stores.setResultFilters(filters) }
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _bind = null
        chips.clear()
    }
}
