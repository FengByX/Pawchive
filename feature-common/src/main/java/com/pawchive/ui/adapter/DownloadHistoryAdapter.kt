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
 *
 * 多选模式（FEAT-DOWNLOAD-MULTISELECT）：长按任意条目进入，勾选框替代操作按钮，
 * 点击整行切换选中；退出多选后恢复常规交互。
 */
class DownloadHistoryAdapter(
    private val onCancel: (DownloadRecord) -> Unit,
    private val onRetry: (DownloadRecord) -> Unit,
    private val onOpen: (DownloadRecord) -> Unit,
    private val onShare: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit,
    private val onSelectionCountChanged: () -> Unit = {},
    private val onEnterSelectionMode: () -> Unit = {}
) : ListAdapter<DownloadRecord, DownloadHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord): Boolean =
                oldItem == newItem
        }
    }

    // 选中集合：按 record.id 记录，跨列表刷新保持（已删除的 id 在删除时统一消费）
    private val selectedIds = LinkedHashSet<String>()

    private var selectionMode = false

    fun isSelectionMode(): Boolean = selectionMode

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<String> = selectedIds.toSet()

    fun getSelectedCount(): Int = selectedIds.size

    fun isAllSelected(): Boolean =
        currentList.isNotEmpty() && currentList.all { it.id in selectedIds }

    fun selectAll() {
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectionCountChanged()
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        if (selectionMode) notifyDataSetChanged()
        onSelectionCountChanged()
    }

    private fun toggleSelection(record: DownloadRecord) {
        if (!selectedIds.remove(record.id)) {
            selectedIds.add(record.id)
        }
        val position = currentList.indexOfFirst { it.id == record.id }
        if (position >= 0) notifyItemChanged(position)
        onSelectionCountChanged()
    }

    inner class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            val context = binding.root.context

            binding.tvFileName.text = record.fileName
            binding.tvStatus.text = formatStatus(record)
            binding.ivTypeIcon.setImageResource(typeIcon(record.type))

            // 进度条仅在进行中/等待中/已暂停显示
            when (record.status) {
                DownloadStatus.PENDING, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = record.progress
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                }
            }

            if (selectionMode) {
                bindSelectionMode(record)
            } else {
                bindNormalMode(record, context)
            }
        }

        /** 多选模式：隐藏操作按钮，显示勾选框，整行点击切换选中。 */
        private fun bindSelectionMode(record: DownloadRecord) {
            binding.btnAction.visibility = View.GONE
            binding.btnOpen.visibility = View.GONE
            binding.btnShare.visibility = View.GONE
            binding.btnDelete.visibility = View.GONE
            binding.cbSelect.visibility = View.VISIBLE
            // 先解除监听再置位，避免绑定期间误触发 toggle
            binding.cbSelect.setOnCheckedChangeListener(null)
            binding.cbSelect.isChecked = record.id in selectedIds
            binding.cbSelect.setOnCheckedChangeListener { _, _ -> toggleSelection(record) }
            binding.root.setOnClickListener { toggleSelection(record) }
            binding.root.setOnLongClickListener(null)
        }

        private fun bindNormalMode(record: DownloadRecord, context: android.content.Context) {
            binding.cbSelect.visibility = View.GONE
            binding.cbSelect.setOnCheckedChangeListener(null)
            binding.root.setOnClickListener(null)
            // 长按进入多选模式并选中当前条目（与搜索页批量操作交互一致）
            binding.root.setOnLongClickListener {
                onEnterSelectionMode()
                toggleSelection(record)
                true
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
                DownloadStatus.PAUSED -> {
                    // 继续按钮（复用 retry 回调：暂停标志在位时 HttpDownloadManager
                    // 会以临时文件长度为起点走 Range 续传）+ 删除
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.setImageResource(R.drawable.ic_retry)
                    binding.btnAction.contentDescription = context.getString(R.string.action_resume)
                    binding.btnAction.setOnClickListener { onRetry(record) }
                    binding.btnOpen.visibility = View.GONE
                    binding.btnShare.visibility = View.GONE
                    binding.btnDelete.visibility = View.VISIBLE
                    binding.btnDelete.setOnClickListener { onDelete(record) }
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
                DownloadStatus.PAUSED -> ctx.getString(R.string.status_paused, record.progress)
                DownloadStatus.COMPLETED -> ctx.getString(R.string.status_completed)
                DownloadStatus.FAILED -> {
                    val base = ctx.getString(R.string.status_failed)
                    when {
                        // "跳过下载"标记：站点仅收录预览、原图未导入（FEAT-PREVIEW-ONLY-SKIP）
                        record.errorMessage == DownloadRecord.ERROR_PREVIEW_ONLY ->
                            ctx.getString(R.string.error_preview_only_not_imported)
                        !record.errorMessage.isNullOrBlank() -> "$base: ${record.errorMessage}"
                        else -> base
                    }
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
