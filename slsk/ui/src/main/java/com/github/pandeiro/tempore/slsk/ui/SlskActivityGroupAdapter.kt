package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.github.pandeiro.tempore.slsk.core.domain.Transfer
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle
import kotlin.math.roundToInt

/**
 * Transfer folder cards with dynamic per-file rows; state tokens rendered per §5.5
 * ("Queued, remotely", progress, failed + retry), handoff chip row per §8.2.
 */
class SlskActivityGroupAdapter(
    private val onRetry: (TransferGroup) -> Unit,
    private val onCancel: (Transfer) -> Unit,
    private val onOpenAlbum: (TransferGroup) -> Unit,
    private val onRescan: () -> Unit,
) : RecyclerView.Adapter<SlskActivityGroupAdapter.Holder>() {

    class Item(val group: TransferGroup)

    private val items = ArrayList<Item>()
    private var vm: SlskActivityViewModel? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(groups: List<TransferGroup>, viewModel: SlskActivityViewModel) {
        this.vm = viewModel
        items.clear()
        items.addAll(groups.map { Item(it) })
        notifyDataSetChanged()
    }

    class Holder(val bind: SlskItemTransferGroupBinding) : RecyclerView.ViewHolder(bind.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = SlskItemTransferGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
        val item = items[position]
        val group = item.group
        val bind = holder.bind
        val context = bind.root.context
        val model = vm ?: return

        bind.slskTgFolder.text = SlskRanking.firstLinePath(group.directory)
        bind.slskTgUsername.text = group.username

        val succeeded = group.transfers.count { it.lifecycle == TransferLifecycle.SUCCEEDED }
        bind.slskTgStatus.text =
            if (group.transfers.all { it.lifecycle == TransferLifecycle.SUCCEEDED }) {
                context.getString(R.string.slsk_activity_state_succeeded)
            } else {
                "$succeeded / ${group.transfers.size}"
            }
        val progress = if (group.transferredBytes == 0L) 0 else
            (group.transferredBytes * 100.0 / group.totalBytes.coerceAtLeast(1)).roundToInt()
        bind.slskTgProgress.progress = progress

        // Handoff row (§8.2)
        renderHandoff(bind, group, model)

        // Per-file rows
        bind.slskTgRows.removeAllViews()
        val inflater = LayoutInflater.from(context)
        group.transfers.forEach { transfer ->
            val row = SlskItemTransferFileBinding.inflate(inflater, bind.slskTgRows, true)
            row.slskTfName.text = transfer.filename.substringAfterLast('\\')
            row.slskTfState.text = stateLabel(context, transfer)
            when (transfer.lifecycle) {
                TransferLifecycle.FAILED -> {
                    row.slskTfState.setTextColor(context.getColor(R.color.slsk_status_bad))
                    row.slskTfRetry.visibility = View.VISIBLE
                    row.slskTfRetry.setOnClickListener { onRetry(group) }
                    row.slskTfCancel.visibility = View.GONE
                }
                TransferLifecycle.SUCCEEDED -> {
                    row.slskTfState.setTextColor(context.getColor(R.color.slsk_status_good))
                    row.slskTfRetry.visibility = View.GONE
                    row.slskTfCancel.visibility = View.GONE
                }
                TransferLifecycle.DOWNLOADING, TransferLifecycle.QUEUED_LOCAL, TransferLifecycle.QUEUED_REMOTE -> {
                    row.slskTfRetry.visibility = View.GONE
                    row.slskTfCancel.visibility = View.VISIBLE
                    row.slskTfCancel.setOnClickListener { onCancel(transfer) }
                }
                else -> {
                    row.slskTfRetry.visibility = View.GONE
                    row.slskTfCancel.visibility = View.GONE
                }
            }
        }
    }

    private fun renderHandoff(
        bind: SlskItemTransferGroupBinding,
        group: TransferGroup,
        model: SlskActivityViewModel,
    ) {
        val context = bind.root.context
        val stage = model.handoffStage(group)
        if (stage == SlskActivityViewModel.Handoff.NONE || !group.allSucceeded) {
            bind.slskTgHandoffRow.visibility = View.GONE
            bind.slskTgHandoffHint.visibility = View.GONE
            return
        }
        bind.slskTgHandoffRow.visibility = View.VISIBLE
        when (stage) {
            SlskActivityViewModel.Handoff.IN_LIBRARY -> {
                bind.slskTgHandoffChip.visibility = View.VISIBLE
                bind.slskTgHandoffChip.setText(R.string.slsk_activity_in_library)
                bind.slskTgHandoffAction.visibility = View.VISIBLE
                bind.slskTgHandoffHint.visibility = View.GONE
                bind.slskTgHandoffAction.setOnClickListener { model.openInLibrary(group) }
            }
            SlskActivityViewModel.Handoff.NOT_VISIBLE, SlskActivityViewModel.Handoff.SCAN_FAILED -> {
                bind.slskTgHandoffRow.visibility = View.VISIBLE
                bind.slskTgHandoffChip.visibility = View.GONE
                bind.slskTgHandoffAction.visibility = View.VISIBLE
                bind.slskTgHandoffAction.setText(R.string.slsk_activity_rescan)
                bind.slskTgHandoffAction.setOnClickListener { model.startManualRescan() }
                bind.slskTgHandoffHint.visibility = View.VISIBLE
            }
            else -> {
                bind.slskTgHandoffRow.visibility = View.VISIBLE
                bind.slskTgHandoffChip.visibility = View.VISIBLE
                bind.slskTgHandoffChip.setText(R.string.slsk_activity_scanning)
                bind.slskTgHandoffAction.visibility = View.GONE
                bind.slskTgHandoffHint.visibility = View.GONE
            }
        }
    }

    private fun stateLabel(context: android.content.Context, transfer: Transfer): String = when (transfer.lifecycle) {
        TransferLifecycle.QUEUED_REMOTE -> context.getString(R.string.slsk_activity_state_queued_remote) +
            transfer.remainingTimeSeconds?.takeIf { it > 0 }?.let { " · " + formatEta(it) }.orEmpty()
        TransferLifecycle.QUEUED_LOCAL -> context.getString(R.string.slsk_activity_state_queued_local)
        TransferLifecycle.DOWNLOADING -> context.getString(
            R.string.slsk_activity_state_downloading, transfer.percentComplete ?: 0
        )
        TransferLifecycle.SUCCEEDED -> context.getString(R.string.slsk_activity_state_succeeded)
        TransferLifecycle.FAILED -> context.getString(
            R.string.slsk_activity_transfer_failed,
            transfer.rawState.ifBlank { "-" },
        )
        TransferLifecycle.CANCELLED -> context.getString(R.string.slsk_activity_state_queued_local)
        TransferLifecycle.UNKNOWN -> context.getString(R.string.slsk_activity_state_unknown, transfer.rawState)
    }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 86_400 -> "%.1f d".format(seconds / 86_400.0)
        seconds >= 3_600 -> "%.1f h".format(seconds / 3_600.0)
        seconds >= 60 -> "%d min".format(seconds / 60)
        else -> "%d s".format(seconds)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.bind.slskTgRows.removeAllViews()
        super.onViewRecycled(holder)
    }
}
