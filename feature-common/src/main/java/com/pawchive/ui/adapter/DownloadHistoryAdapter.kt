package com.pawchive.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.R
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.common.databinding.ItemDownloadBinding

/**
 * 下载历史列表适配器（FEATURE-001 下载中心）。
 *
 * 使用 ListAdapter + DiffUtil.ItemCallback 实现高效差量更新。
 * 根据下载状态显示不同的操作按钮组合：
 * - PENDING / RUNNING：取消按钮
 * - COMPLETED：打开、分享、删除按钮
 * - FAILED / CANCELLED：重试、删除按钮
 */
class DownloadHistoryAdapter(
    private val onCancel: (DownloadRecord) -> Unit,
    private val onRetry: (DownloadRecord) -> Unit,
    private val onOpen: (DownloadRecord) -> Unit,
    private val onShare: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean =
                oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            val context = binding.root.context

            binding.tvFileName.text = record.fileName
            binding.tvStatus.text = formatStatus(record)
            binding.ivTypeIcon.setImageResource(typeIcon(record.type))

            // 进度条仅在进行中/等待中显示
            when (record.status) {
                DownloadStatus.PENDING, DownloadStatus.RUNNING -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = record.progress
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                }
            }

            // 按状态切换操作按钮
            when (record.status) {
                DownloadStatus.PENDING, DownloadStatus.RUNNING -> {
                    // 取消按钮
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.setImageResource(R.drawable.ic_close)
                    binding.btnAction.contentDescription = context.getString(R.string.cancel)
                    binding.btnAction.setOnClickListener { onCancel(record) }
                    binding.btnOpen.visibility = View.GONE
                    binding.btnShare.visibility = View.GONE
                    binding.btnDelete.visibility = View.GONE
                }
                DownloadStatus.COMPLETED -> {
                    // 打开 / 分享 / 删除
                    binding.btnAction.visibility = View.GONE
                    binding.btnOpen.visibility = View.VISIBLE
                    binding.btnShare.visibility = View.VISIBLE
                    binding.btnDelete.visibility = View.VISIBLE
                    binding.btnOpen.setOnClickListener { onOpen(record) }
                    binding.btnShare.setOnClickListener { onShare(record) }
                    binding.btnDelete.setOnClickListener { onDelete(record) }
                }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    // 重试 / 删除
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.setImageResource(R.drawable.ic_retry)
                    binding.btnAction.contentDescription = context.getString(R.string.retry)
                    binding.btnAction.setOnClickListener { onRetry(record) }
                    binding.btnOpen.visibility = View.GONE
                    binding.btnShare.visibility = View.GONE
                    binding.btnDelete.visibility = View.VISIBLE
                    binding.btnDelete.setOnClickListener { onDelete(record) }
                }
            }
        }

        private fun formatStatus(record: DownloadRecord): String {
            val ctx = binding.root.context
            return when (record.status) {
                DownloadStatus.PENDING -> ctx.getString(R.string.status_pending)
                DownloadStatus.RUNNING -> ctx.getString(R.string.status_running, record.progress)
                DownloadStatus.COMPLETED -> ctx.getString(R.string.status_completed)
                DownloadStatus.FAILED -> {
                    val base = ctx.getString(R.string.status_failed)
                    if (!record.errorMessage.isNullOrBlank()) "$base: ${record.errorMessage}" else base
                }
                DownloadStatus.CANCELLED -> ctx.getString(R.string.status_cancelled)
            }
        }

        private fun typeIcon(type: DownloadType): Int = when (type) {
            DownloadType.IMAGE -> R.drawable.ic_image
            DownloadType.VIDEO -> R.drawable.ic_play_circle
            DownloadType.ATTACHMENT -> R.drawable.ic_paperclip
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemDownloadBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
