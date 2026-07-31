package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.FavoriteCreator
import com.pawchive.databinding.ItemCreatorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteCreatorAdapter(
    private var creators: List<FavoriteCreator>,
    private val onCreatorClicked: (String, String) -> Unit
) : RecyclerView.Adapter<FavoriteCreatorAdapter.FavoriteCreatorViewHolder>() {

    fun updateCreators(newCreators: List<FavoriteCreator>) {
        creators = newCreators
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteCreatorViewHolder {
        val binding = ItemCreatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FavoriteCreatorViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteCreatorViewHolder, position: Int) {
        holder.bind(creators[position])
    }

    override fun getItemCount(): Int = creators.size

    inner class FavoriteCreatorViewHolder(private val binding: ItemCreatorBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(creator: FavoriteCreator) {
            binding.tvCreatorName.text = creator.name
            binding.tvService.text = creator.service.uppercase()

            // Set service badge color based on platform
            setServiceBadgeColor(creator.service, binding.root.context)

            // 修复：设置真实创作者 ID
            binding.tvCreatorId.text = "ID: ${creator.id}"

            val favSeq = creator.favedSeq ?: 0
            binding.tvFavCount.text = "收藏序号: $favSeq"

            // 显示最近更新时间（优先 updated，回退 indexed）
            val updateStr = creator.updated ?: creator.indexed
            if (!updateStr.isNullOrEmpty()) {
                val formatted = formatTimestamp(updateStr)
                if (formatted.isNotEmpty()) {
                    binding.tvUpdated.visibility = View.VISIBLE
                    binding.tvUpdated.text = binding.root.context.getString(
                        R.string.creator_last_updated, formatted
                    )
                } else {
                    binding.tvUpdated.visibility = View.GONE
                }
            } else {
                binding.tvUpdated.visibility = View.GONE
            }

            val avatarUrl = "https://pawchive.pw/icons/${creator.service}/${creator.id}"
            binding.ivAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_image)
                error(R.drawable.ic_image_off)
            }

            binding.root.setOnClickListener {
                onCreatorClicked(creator.service, creator.id)
            }
        }

        /**
         * 格式化时间戳字符串（秒级/毫秒级 Unix 时间戳，或 ISO 日期）为 yyyy-MM-dd
         */
        private fun formatTimestamp(timestamp: String): String {
            return try {
                val ts = timestamp.toLong()
                val millis = if (ts < 1_000_000_000_000L) ts * 1000 else ts
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
            } catch (_: NumberFormatException) {
                // 非 Unix 时间戳，尝试取 ISO 日期部分
                timestamp.split("T").firstOrNull()?.takeIf { it.isNotEmpty() } ?: ""
            }
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
    }
}