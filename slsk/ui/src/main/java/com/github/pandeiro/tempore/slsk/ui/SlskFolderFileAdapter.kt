package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.QualityClass

/**
 * Per-file rows inside the folder sheet: checkbox selection, name, quality, size,
 * locked-file marker.
 */
class SlskFolderFileAdapter(
    private val files: List<RemoteFile>,
    private val onToggle: (RemoteFile) -> Unit,
    private val isSelected: (RemoteFile) -> Boolean = { false },
) : RecyclerView.Adapter<SlskFolderFileAdapter.Holder>() {

    class Holder(val bind: SlskItemFolderFileBinding) : RecyclerView.ViewHolder(bind.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = SlskItemFolderFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
        val file = files[position]
        val bind = holder.bind
        bind.slskFileName.text = file.filename.substringAfterLast('\\')
        bind.slskFileCheck.isChecked = isSelected(file)
        bind.slskFileCheck.setOnCheckedChangeListener(null)
        bind.slskFileCheck.setOnCheckedChangeListener { _, checked ->
            // PR review #5: unchecked must also toggle off; locked stays pinned to its state.
            if (file.isLocked) {
                bind.slskFileCheck.isChecked = isSelected(file)
                return@setOnCheckedChangeListener
            }
            onToggle(file)
        }
        bind.slskFileSize.text = formatBytes(file.size)
        bind.slskFileQuality.text = buildString {
            append(SlskRanking.label(file.qualityClass))
            if (file.extension.isNotBlank()) append(" · ").append(file.extension.uppercase())
        }
        bind.slskFileLocked.visibility = if (file.isLocked) android.view.View.VISIBLE else android.view.View.GONE
        bind.root.setOnClickListener { onToggle(file) }
    }
}
