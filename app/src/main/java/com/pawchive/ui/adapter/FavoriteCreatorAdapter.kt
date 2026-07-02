package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.FavoriteCreator
import com.pawchive.databinding.ItemCreatorBinding

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

            val favSeq = creator.favedSeq ?: 0
            binding.tvFavCount.text = "收藏序号: $favSeq"

            val avatarUrl = "https://pawchive.st/icons/${creator.service}/${creator.id}"
            binding.ivAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_report_image)
            }

            binding.root.setOnClickListener {
                onCreatorClicked(creator.service, creator.id)
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