package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.FavoritePost
import com.pawchive.databinding.ItemLoadMoreFooterBinding
import com.pawchive.databinding.ItemPostBinding

class FavoritePostAdapter(
    private var posts: List<FavoritePost>,
    private val onPostClicked: (FavoritePost) -> Unit,
    private val onCreatorClicked: (String, String) -> Unit,
    private val onRemoveFavorite: (FavoritePost) -> Unit,
    private val onLoadMore: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var showFooter = false

    companion object {
        private const val TYPE_POST = 0
        private const val TYPE_FOOTER = 1
    }

    fun updatePosts(newPosts: List<FavoritePost>) {
        posts = newPosts
        notifyDataSetChanged()
    }

    fun setFooterVisible(visible: Boolean) {
        if (showFooter == visible) return
        showFooter = visible
        if (visible) notifyItemInserted(posts.size) else notifyItemRemoved(posts.size)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < posts.size) TYPE_POST else TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOOTER -> FooterViewHolder(ItemLoadMoreFooterBinding.inflate(inflater, parent, false))
            else -> FavoritePostViewHolder(ItemPostBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FavoritePostViewHolder) {
            holder.bind(posts[position])
        } else if (holder is FooterViewHolder) {
            holder.bind(onLoadMore)
        }
    }

    override fun getItemCount(): Int = posts.size + if (showFooter) 1 else 0

    inner class FooterViewHolder(private val binding: ItemLoadMoreFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(onLoadMore: () -> Unit) {
            binding.btnLoadMore.setOnClickListener { onLoadMore() }
        }
    }

    inner class FavoritePostViewHolder(private val binding: ItemPostBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: FavoritePost) {
            binding.tvTitle.text = post.title ?: ""
            binding.tvCreatorName.text = post.user
            binding.tvService.text = post.service.uppercase()
            binding.tvDate.text = "Published: ${post.published?.split("T")?.firstOrNull() ?: post.added?.split("T")?.firstOrNull() ?: "Unknown"}"

            // Set service badge color based on platform
            setServiceBadgeColor(post.service, binding.root.context)

            // Simple HTML tag removal for content preview
            val plainText = post.content?.replace(Regex("<[^>]*>"), "") ?: ""
            binding.tvPreview.text = if (plainText.length > 120) plainText.take(120) + "..." else plainText

            // Load thumbnail if main file or attachments are images
            val imagePath = post.file?.path ?: post.attachments?.firstOrNull { 
                it.path?.endsWith(".jpg", true) == true || it.path?.endsWith(".png", true) == true || it.path?.endsWith(".gif", true) == true || it.path?.endsWith(".webp", true) == true
            }?.path

            if (!imagePath.isNullOrEmpty()) {
                binding.ivThumbnail.visibility = View.VISIBLE
                val fullUrl = "https://img.pawchive.pw/thumbnail/data$imagePath"
                binding.ivThumbnail.load(fullUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_report_image)
                }
            } else {
                binding.ivThumbnail.visibility = View.GONE
            }

            // 显示已收藏状态（账号收藏的帖子始终显示为已收藏）
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)

            binding.btnBookmark.setOnClickListener {
                onRemoveFavorite(post)
            }

            binding.tvCreatorName.setOnClickListener {
                onCreatorClicked(post.service, post.user)
            }

            binding.root.setOnClickListener {
                onPostClicked(post)
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