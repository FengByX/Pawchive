package com.pawchive.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.R
import com.pawchive.core.model.OfflineArchiveEntity
import com.pawchive.common.databinding.ItemOfflineArchiveBinding

/**
 * 离线归档列表适配器（ARCH-FEATURE-001 遗留项）。
 *
 * ListAdapter + DiffUtil.ItemCallback：
 * - 展示标题 / 创作者名 / 收藏相对时间
 * - 点击行跳转帖子详情（离线阅读）
 * - 删除按钮由 Fragment 弹确认对话框后调用
 */
class OfflineArchivesAdapter(
    private val onItemClick: (OfflineArchiveEntity) -> Unit,
    private val onDelete: (OfflineArchiveEntity) -> Unit
) : ListAdapter<OfflineArchiveEntity, OfflineArchivesAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<OfflineArchiveEntity>() {
            override fun areItemsTheSame(
                oldItem: OfflineArchiveEntity,
                newItem: OfflineArchiveEntity
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: OfflineArchiveEntity,
                newItem: OfflineArchiveEntity
            ): Boolean = oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemOfflineArchiveBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OfflineArchiveEntity) {
            val context = binding.root.context
            binding.tvArchiveTitle.text = item.title?.takeIf { it.isNotBlank() } ?: item.postId
            binding.tvArchiveCreator.text = item.userName?.takeIf { it.isNotBlank() } ?: item.creatorId
            binding.tvArchiveTime.text = formatTime(context, item.favedAt)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDeleteArchive.setOnClickListener { onDelete(item) }
        }

        private fun formatTime(context: Context, timestamp: Long): String {
            val minutes = (System.currentTimeMillis() - timestamp) / 60_000
            return when {
                minutes < 1 -> context.getString(R.string.time_just_now)
                minutes < 60 -> context.getString(R.string.time_minutes_ago, minutes)
                minutes < 24 * 60 -> context.getString(R.string.time_hours_ago, minutes / 60)
                else -> context.getString(R.string.time_days_ago, minutes / (24 * 60))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemOfflineArchiveBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
