package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup

/**
 * Release cards (spec §5.4): cleaned title, best-quality pill, slot status, queue depth,
 * speed, file/size meta. Click opens the folder sheet.
 */
class SlskResultGroupAdapter(
    private val onOpen: (ResultGroup) -> Unit,
) : RecyclerView.Adapter<SlskResultGroupAdapter.Holder>() {

    private val items = ArrayList<ResultGroup>()

    fun submitGroups(groups: List<ResultGroup>) {
        items.clear()
        items.addAll(groups)
        notifyDataSetChanged()
    }

    class Holder(val bind: SlskItemResultGroupBinding) : RecyclerView.ViewHolder(bind.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = SlskItemResultGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
        val group = items[position]
        val bind = holder.bind
        bind.root.setOnClickListener { onOpen(group) }
        bind.slskGroupTitle.text = cleanTitle(SlskRanking.firstLineLabel(group))
        val quality = SlskRanking.bestQuality(group)
        bind.slskGroupQuality.text = SlskRanking.label(quality)
        bind.slskGroupUsername.text = group.username
        bind.slskGroupQueue.text = group.queueLength.takeIf { it > 0 }?.let {
            holder.bind.root.context.getString(R.string.slsk_group_queue_ahead, it)
        } ?: ""
        bind.slskGroupSlot.setText(
            if (group.hasFreeUploadSlot) R.string.slsk_group_free_slot else R.string.slsk_group_busy_slot
        )
        bind.slskGroupSlot.setTextColor(
            holder.bind.root.context.getColor(
                if (group.hasFreeUploadSlot) R.color.slsk_status_good else R.color.slsk_status_warn
            )
        )
        bind.slskGroupSpeed.text = formatSpeed(group.uploadSpeedBps)
        val size = group.allowedFiles.sumOf { it.size }
        bind.slskGroupMeta.text = holder.bind.root.context.getString(
            R.string.slsk_group_files_videos, group.allowedFiles.size, formatBytes(size)
        )
    }
}
