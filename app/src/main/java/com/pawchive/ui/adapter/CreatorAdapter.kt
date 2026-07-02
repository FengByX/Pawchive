package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.Creator
import com.pawchive.databinding.ItemCreatorBinding

class CreatorAdapter(
    private var creators: List<Creator>,
    private val onCreatorClicked: (Creator) -> Unit
) : RecyclerView.Adapter<CreatorAdapter.CreatorViewHolder>() {

    fun updateCreators(newCreators: List<Creator>) {
        creators = newCreators
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CreatorViewHolder {
        val binding = ItemCreatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CreatorViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CreatorViewHolder, position: Int) {
        holder.bind(creators[position])
    }

    override fun getItemCount(): Int = creators.size

    inner class CreatorViewHolder(private val binding: ItemCreatorBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(creator: Creator) {
            binding.tvCreatorName.text = creator.name
            binding.tvCreatorId.text = "ID: ${creator.id}"
            binding.tvService.text = creator.service.uppercase()

            // Set service badge color based on platform
            setServiceBadgeColor(creator.service, binding.root.context)

            val avatarUrl = "https://pawchive.st/icons/${creator.service}/${creator.id}"
            binding.ivAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_report_image)
            }

            binding.root.setOnClickListener {
                onCreatorClicked(creator)
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
