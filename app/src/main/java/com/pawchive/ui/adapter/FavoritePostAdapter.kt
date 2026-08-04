package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.FavoritePost
import com.pawchive.databinding.ItemLoadMoreFooterBinding
import com.pawchive.databinding.ItemPostBinding

class FavoritePostAdapter(
    private val onPostClicked: (FavoritePost) -> Unit,
    private val onCreatorClicked: (String, String) -> Unit,
    private val onRemoveFavorite: (FavoritePost) -> Unit,
    private val onLoadMore: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var posts: List<FavoritePost> = emptyList()
    private var showFooter = false

    companion object {
        private const val TYPE_POST = 0
        private const val TYPE_FOOTER = 1
        private const val TAG = "FavPostAdapter"
        private const val THUMBNAIL_SIZE_PX = 160
    }

    fun updatePosts(newPosts: List<FavoritePost>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = posts.size
            override fun getNewListSize(): Int = newPosts.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                posts[oldPos].id == newPosts[newPos].id && posts[oldPos].service == newPosts[newPos].service
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                posts[oldPos] == newPosts[newPos]
        }
        val result = DiffUtil.calculateDiff(diffCallback)
        posts = newPosts
        result.dispatchUpdatesTo(this)
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
            val dateStr = post.published?.split("T")?.firstOrNull()
                ?: post.added?.split("T")?.firstOrNull()
                ?: binding.root.context.getString(R.string.date_unknown)
            binding.tvDate.text = binding.root.context.getString(R.string.date_published, dateStr)

            // 显示附件数量：主文件 + 附件列表
            val attachmentCount = (if (post.file?.path != null) 1 else 0) + (post.attachments?.size ?: 0)
            if (attachmentCount > 0) {
                binding.tvAttachmentCount.text = binding.root.context.getString(
                    R.string.attachment_count_format, attachmentCount
                )
                binding.tvAttachmentCount.visibility = View.VISIBLE
            } else {
                binding.tvAttachmentCount.visibility = View.GONE
            }

            // Set service badge color based on platform
            setServiceBadgeColor(post.service, binding.root.context)

            // Simple HTML tag removal for content preview
            val plainText = post.content?.replace(Regex("<[^>]*>"), "") ?: ""
            binding.tvPreview.text = if (plainText.length > 120) plainText.take(120) + "..." else plainText

            // Load thumbnail if main file or attachments are images
            val imageExtensions = listOf(
                ".jpg", ".jpeg", ".jpe", ".png", ".gif", ".webp", ".bmp",
                ".tif", ".tiff", ".heic", ".heif"
            )

            fun isImage(path: String?, name: String? = null): Boolean {
                val lowerPath = path?.lowercase().orEmpty()
                val lowerName = name?.lowercase().orEmpty()
                return imageExtensions.any { ext ->
                    lowerPath.endsWith(ext) || lowerName.endsWith(ext)
                }
            }

            val imagePath: String? = if (isImage(post.file?.path, post.file?.name)) {
                post.file?.path
            } else {
                post.attachments?.firstOrNull { isImage(it.path, it.name) }?.path
            }

            if (!imagePath.isNullOrEmpty()) {
                binding.ivThumbnail.visibility = View.VISIBLE

                val candidateUrls = buildCandidateUrls(imagePath)
                loadThumbnailWithFallback(binding, candidateUrls, 0)
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

        private fun buildCandidateUrls(imagePath: String): List<String> {
            val trimmed = imagePath.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return listOf(trimmed)
            }
            val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return listOf(
                "https://img.pawchive.pw/thumbnail/data$normalized",
                "https://img.pawchive.pw/data$normalized",
                "https://file.pawchive.pw/data$normalized"
            )
        }

        private fun loadThumbnailWithFallback(
            binding: ItemPostBinding,
            urls: List<String>,
            index: Int
        ) {
            if (index >= urls.size) return
            val url = urls[index]
            val isLast = index == urls.size - 1
            binding.ivThumbnail.load(url) {
                size(THUMBNAIL_SIZE_PX)
                crossfade(150)
                placeholder(R.color.thumbnail_placeholder)
                error(R.color.thumbnail_placeholder)
                listener(
                    onError = { _, throwable ->
                        Log.w(
                            TAG,
                            "Thumbnail load failed for $url (attempt ${index + 1}/${urls.size}): $throwable"
                        )
                        if (!isLast) {
                            loadThumbnailWithFallback(binding, urls, index + 1)
                        }
                    }
                )
            }
        }
    }
}