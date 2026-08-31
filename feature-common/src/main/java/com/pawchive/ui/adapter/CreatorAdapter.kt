package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.common.R
import com.pawchive.core.model.Creator
import com.pawchive.common.databinding.ItemCreatorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 创作者列表 Adapter，支持多选屏蔽模式。
 *
 * 两种模式：
 * - 普通模式：点击进入创作者详情，长按进入多选模式
 * - 多选模式：点击切换选中状态，顶部出现 ActionBar（退出/全选/屏蔽）
 */
class CreatorAdapter(
    private val onCreatorClicked: (Creator) -> Unit,
    private val onCreatorLongClicked: (Creator) -> Unit = {}
) : ListAdapter<Creator, CreatorAdapter.CreatorViewHolder>(DIFF_CALLBACK) {

    private var selectionMode = false
    private val selectedPositions = mutableSetOf<Int>()

    /** 进入/退出多选模式 */
    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedPositions.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION_CHANGED)
    }

    fun isInSelectionMode() = selectionMode

    /** 切换指定位置的选中状态（多选模式下） */
    fun toggleSelection(position: Int) {
        if (position !in 0 until itemCount) return
        if (position in selectedPositions) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED)
    }

    /** 全选/取消全选 */
    fun selectAll() {
        if (selectedPositions.size == itemCount) {
            selectedPositions.clear()
        } else {
            selectedPositions.clear()
            selectedPositions.addAll(0 until itemCount)
        }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION_CHANGED)
    }

    fun isAllSelected() = selectedPositions.size == itemCount && itemCount > 0

    fun getSelectedCount() = selectedPositions.size

    /** 获取所有被选中的 Creator */
    fun getSelectedCreators(): List<Creator> {
        return selectedPositions.sorted().map { getItem(it) }
    }

    /** 退出多选模式并重置选中状态 */
    fun exitSelection() {
        setSelectionMode(false)
    }

    fun updateCreators(newCreators: List<Creator>) {
        submitList(newCreators)
        // 列表变化时清除选中（位置可能错位）
        selectedPositions.clear()
    }

    companion object {
        private const val PAYLOAD_SELECTION_CHANGED = "payload_selection_changed"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Creator>() {
            override fun areItemsTheSame(oldItem: Creator, newItem: Creator) =
                oldItem.id == newItem.id && oldItem.service == newItem.service
            override fun areContentsTheSame(oldItem: Creator, newItem: Creator) = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CreatorViewHolder {
        val binding = ItemCreatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CreatorViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CreatorViewHolder, position: Int) {
        holder.bind(getItem(position), selectionMode, position in selectedPositions)
    }

    override fun onBindViewHolder(
        holder: CreatorViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
            holder.bindSelectionState(selectionMode, position in selectedPositions)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class CreatorViewHolder(private val binding: ItemCreatorBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var boundCreator: Creator? = null

        fun bind(creator: Creator, inSelectionMode: Boolean, isSelected: Boolean) {
            boundCreator = creator
            binding.tvCreatorName.text = creator.name
            binding.tvCreatorId.text = "ID: ${creator.id}"
            binding.tvService.text = creator.service.uppercase()

            val updateTime = creator.updated ?: creator.indexed
            if (updateTime != null && updateTime > 0) {
                binding.tvUpdated.visibility = View.VISIBLE
                binding.tvUpdated.text = binding.root.context.getString(
                    R.string.creator_last_updated, formatTimestamp(updateTime)
                )
            } else {
                binding.tvUpdated.visibility = View.GONE
            }

            setServiceBadgeColor(creator.service, binding.root.context)

            val avatarUrl = "https://pawchive.pw/icons/${creator.service}/${creator.id}"
            binding.ivAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_image)
                error(R.drawable.ic_image_off)
            }

            val backgroundUrl = "https://pawchive.pw/banners/${creator.service}/${creator.id}"
            binding.ivCreatorBackground.load(backgroundUrl) {
                crossfade(true)
                placeholder(R.color.thumbnail_placeholder)
                error(R.color.thumbnail_placeholder)
            }

            bindSelectionState(inSelectionMode, isSelected)

            // 点击/长按：根据模式分发
            binding.root.setOnClickListener {
                if (selectionMode) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) toggleSelection(pos)
                } else {
                    boundCreator?.let { onCreatorClicked(it) }
                }
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) {
                    boundCreator?.let {
                        onCreatorLongClicked(it)
                        setSelectionMode(true)
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) toggleSelection(pos)
                    }
                    true
                } else {
                    false
                }
            }
        }

        /** 仅更新选中状态视觉（payload 优化，避免全量 rebind） */
        fun bindSelectionState(inSelectionMode: Boolean, isSelected: Boolean) {
            val card = binding.root
            if (inSelectionMode) {
                val strokeColor = if (isSelected) {
                    getThemeColor(binding.root.context, com.google.android.material.R.attr.colorPrimary)
                } else {
                    getThemeColor(binding.root.context, com.google.android.material.R.attr.colorOutline)
                }
                val bgColor = if (isSelected) {
                    getThemeColor(binding.root.context, com.google.android.material.R.attr.colorPrimaryContainer)
                } else {
                    getThemeColor(binding.root.context, com.google.android.material.R.attr.colorSurface)
                }
                card.strokeColor = strokeColor
                card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(bgColor))
            } else {
                val defaultStroke = getThemeColor(binding.root.context, com.google.android.material.R.attr.colorOutline)
                val defaultBg = getThemeColor(binding.root.context, com.google.android.material.R.attr.colorSurface)
                card.strokeColor = defaultStroke
                card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(defaultBg))
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val millis = if (timestamp < 1_000_000_000_000L) timestamp * 1000 else timestamp
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
        }

        private fun setServiceBadgeColor(service: String, context: Context) {
            val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            val (bgColorRes, textColorRes) = when (service.lowercase()) {
                "patreon" -> if (isDarkMode) {
                    (R.color.patreon_bg_dark to R.color.patreon_text_dark)
                } else {
                    (R.color.patreon_bg_light to R.color.patreon_text_light)
                }
                "fanbox" -> if (isDarkMode) {
                    (R.color.fanbox_bg_dark to R.color.fanbox_text_dark)
                } else {
                    (R.color.fanbox_bg_light to R.color.fanbox_text_light)
                }
                else -> if (isDarkMode) {
                    (R.color.service_bg_default_dark to R.color.service_text_default_dark)
                } else {
                    (R.color.service_bg_default_light to R.color.service_text_default_light)
                }
            }

            binding.cardServiceBadge.setCardBackgroundColor(context.getColor(bgColorRes))
            binding.tvService.setTextColor(context.getColor(textColorRes))
        }

        private fun getThemeColor(context: Context, attr: Int): Int {
            val tv = TypedValue()
            context.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }
    }
}
