package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
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

class CreatorAdapter(
    private val onCreatorClicked: (Creator) -> Unit
) : ListAdapter<Creator, CreatorAdapter.CreatorViewHolder>(DIFF_CALLBACK) {

    fun updateCreators(newCreators: List<Creator>) {
        submitList(newCreators)
    }

    companion object {
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
        holder.bind(getItem(position))
    }

    inner class CreatorViewHolder(private val binding: ItemCreatorBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(creator: Creator) {
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

            binding.root.setOnClickListener {
                onCreatorClicked(creator)
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
    }
}
