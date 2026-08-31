package com.pawchive.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.R
import com.pawchive.core.model.CreatorSubscriptionEntity
import com.pawchive.common.databinding.ItemSubscriptionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅管理列表适配器（ARCH-FEATURE-003 遗留项）。
 *
 * ListAdapter + DiffUtil.ItemCallback 差量更新：
 * - 平台徽标按 service 品牌色着色（与 CreatorAdapter 同口径）
 * - 点击行跳转创作者主页（由 Fragment 处理）
 * - 退订按钮由 Fragment 弹确认对话框后调用
 */
class SubscriptionsAdapter(
    private val onItemClick: (CreatorSubscriptionEntity) -> Unit,
    private val onUnsubscribe: (CreatorSubscriptionEntity) -> Unit
) : ListAdapter<CreatorSubscriptionEntity, SubscriptionsAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CreatorSubscriptionEntity>() {
            override fun areItemsTheSame(
                oldItem: CreatorSubscriptionEntity,
                newItem: CreatorSubscriptionEntity
            ): Boolean = oldItem.service == newItem.service && oldItem.creatorId == newItem.creatorId

            override fun areContentsTheSame(
                oldItem: CreatorSubscriptionEntity,
                newItem: CreatorSubscriptionEntity
            ): Boolean = oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemSubscriptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CreatorSubscriptionEntity) {
            binding.tvCreatorName.text = item.name?.takeIf { it.isNotBlank() } ?: item.creatorId
            binding.tvService.text = item.service.uppercase()
            binding.tvSubscribedAt.text = binding.root.context.getString(
                R.string.subscriptions_subscribed_at,
                formatTimestamp(item.subscribedAt)
            )
            setServiceBadgeColor(item.service, binding.root.context)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnUnsubscribe.setOnClickListener { onUnsubscribe(item) }
        }

        private fun formatTimestamp(timestamp: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

        private fun setServiceBadgeColor(service: String, context: Context) {
            val isDarkMode =
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemSubscriptionBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
