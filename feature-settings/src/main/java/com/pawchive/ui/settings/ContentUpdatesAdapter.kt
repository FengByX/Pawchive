package com.pawchive.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.R
import com.pawchive.common.databinding.ItemContentUpdateBinding
import com.pawchive.data.repository.ContentUpdateWithCreator

/**
 * 内容更新列表适配器（ARCH-FEATURE-003）。
 *
 * 使用 ListAdapter + DiffUtil.ItemCallback 实现高效差量更新。
 * - 未读条目左侧显示圆点标记
 * - 点击条目由 Fragment 处理（标记已读 + 跳转帖子详情）
 */
class ContentUpdatesAdapter(
    private val onItemClick: (ContentUpdateWithCreator) -> Unit
) : ListAdapter<ContentUpdateWithCreator, ContentUpdatesAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentUpdateWithCreator>() {
            override fun areItemsTheSame(oldItem: ContentUpdateWithCreator, newItem: ContentUpdateWithCreator): Boolean =
                oldItem.update.id == newItem.update.id

            override fun areContentsTheSame(oldItem: ContentUpdateWithCreator, newItem: ContentUpdateWithCreator): Boolean =
                oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemContentUpdateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContentUpdateWithCreator) {
            val context = binding.root.context
            val update = item.update

            binding.tvUpdateCreator.text = item.creatorName ?: update.creatorId
            binding.tvUpdateTitle.text = update.postTitle ?: update.postId
            binding.tvUpdateTime.text = formatTime(context, update.discoveredAt)
            binding.viewUnreadDot.visibility = if (update.read) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onItemClick(item) }
        }

        private fun formatTime(context: android.content.Context, timestamp: Long): String {
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
        return ViewHolder(ItemContentUpdateBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
