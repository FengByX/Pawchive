package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.common.R
import com.pawchive.core.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.common.databinding.ItemLoadMoreFooterBinding
import com.pawchive.common.databinding.ItemPostBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PostAdapter(
    private var posts: List<Post>,
    private val bookmarkManager: BookmarkManager,
    private val authRepository: AuthRepository?,
    private val lifecycleScope: CoroutineScope?,
    private val onPostClicked: (Post) -> Unit,
    private val onCreatorClicked: (String, String) -> Unit,
    private val onBookmarkChanged: (Post, Boolean) -> Unit,
    private val onLoadMore: () -> Unit = {},
    private val showCreatorInfo: Boolean = true
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var showFooter = false

    companion object {
        private const val TYPE_POST = 0
        private const val TYPE_FOOTER = 1
        private const val TAG = "PostAdapter"
        // 缩略图 View 为 80dp，解码到 160px 在高密度屏也足够清晰，同时大幅降低内存占用
        private const val THUMBNAIL_SIZE_PX = 160
        // PERF-010：创作者名称局部更新 payload，仅刷新名称文本，避免全量 rebind 重载图片
        private const val PAYLOAD_CREATOR_NAME = "payload_creator_name"
    }

    fun updatePosts(newPosts: List<Post>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = posts.size
            override fun getNewListSize(): Int = newPosts.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = posts[oldItemPosition]
                val n = newPosts[newItemPosition]
                return o.service == n.service && o.user == n.user && o.id == n.id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = posts[oldItemPosition]
                val n = newPosts[newItemPosition]
                return o.title == n.title &&
                    o.content == n.content &&
                    o.edited == n.edited &&
                    o.published == n.published &&
                    o.file?.path == n.file?.path
            }
        }
        val diff = DiffUtil.calculateDiff(diffCallback)
        posts = newPosts
        // 使用局部差量更新，仅刷新变化的条目，避免整表重绘
        diff.dispatchUpdatesTo(this)
    }

    /**
     * 创作者名称预取完成后调用：名称来自外部缓存（不在 Post 字段内），
     * DiffUtil 无法感知其变化，因此用 payload 局部刷新（仅更新名称文本），
     * 避免整表重绘导致可见项图片重新加载（PERF-010）。
     */
    fun refreshCreatorNames() {
        if (posts.isNotEmpty()) {
            notifyItemRangeChanged(0, posts.size, PAYLOAD_CREATOR_NAME)
        }
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
            TYPE_FOOTER -> FooterViewHolder(
                ItemLoadMoreFooterBinding.inflate(inflater, parent, false)
            )
            else -> PostViewHolder(
                ItemPostBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PostViewHolder) {
            holder.bind(posts[position])
        } else if (holder is FooterViewHolder) {
            holder.bind(onLoadMore)
        }
    }

    /**
     * PERF-010：payload 局部更新。创作者名称预取完成时仅刷新名称文本，
     * 跳过完整 bind（图片、书签、HTML 解析等重逻辑不重复执行）。
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) {
            if (holder is PostViewHolder && payloads.contains(PAYLOAD_CREATOR_NAME)) {
                holder.updateCreatorName(posts[position])
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = posts.size + if (showFooter) 1 else 0

    inner class FooterViewHolder(private val binding: ItemLoadMoreFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(onLoadMore: () -> Unit) {
            binding.btnLoadMore.setOnClickListener { onLoadMore() }
        }
    }

    inner class PostViewHolder(private val binding: ItemPostBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: Post) {
            binding.tvTitle.text = post.title ?: ""
            binding.tvCreatorName.text = CreatorNameCache.getCachedName(post.service, post.user) ?: post.user
            binding.tvService.text = post.service.uppercase()
            val dateStr = post.published?.split("T")?.firstOrNull()
                ?: post.added?.split("T")?.firstOrNull()
                ?: binding.root.context.getString(R.string.date_unknown)
            binding.tvDate.text = binding.root.context.getString(R.string.date_published, dateStr)

            // 显示附件数量：主文件 + 附件列表，帮助用户判断是压缩包还是图片合集
            val attachmentCount = (if (post.file?.path != null) 1 else 0) + (post.attachments?.size ?: 0)
            if (attachmentCount > 0) {
                binding.tvAttachmentCount.text = binding.root.context.getString(
                    R.string.attachment_count_format, attachmentCount
                )
                binding.tvAttachmentCount.visibility = View.VISIBLE
            } else {
                binding.tvAttachmentCount.visibility = View.GONE
            }

            // 在创作者详情页等场景下隐藏卡片内的创作者信息，避免冗余
            binding.topRow.visibility = if (showCreatorInfo) View.VISIBLE else View.GONE

            // Set service badge color based on platform
            setServiceBadgeColor(binding, post.service, binding.root.context)

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

            // 优先取主文件，主文件非图片时取第一张图片附件
            val imagePath: String? = if (isImage(post.file?.path, post.file?.name)) {
                post.file?.path
            } else {
                post.attachments?.firstOrNull { isImage(it.path, it.name) }?.path
            }

            if (!imagePath.isNullOrEmpty()) {
                binding.ivThumbnail.visibility = View.VISIBLE

                // 若后端已经返回完整 URL（如 https://.../xxx.jpg），直接使用；
                // 否则将路径规范化，拼接到 Pawchive 的 CDN 域名上。
                val candidateUrls = buildCandidateUrls(imagePath)

                loadThumbnailWithFallback(binding, candidateUrls, 0)
            } else {
                binding.ivThumbnail.visibility = View.GONE
            }

            // Bookmark setup
            val isBookmarked = bookmarkManager.isPostBookmarked(post.service, post.user, post.id)
            updateBookmarkIcon(isBookmarked)

            binding.btnBookmark.setOnClickListener {
                val newStatus = !bookmarkManager.isPostBookmarked(post.service, post.user, post.id)
                // 本地立即更新，保持界面响应性
                if (newStatus) {
                    bookmarkManager.bookmarkPost(post)
                } else {
                    bookmarkManager.unbookmarkPost(post.service, post.user, post.id)
                }
                updateBookmarkIcon(newStatus)
                onBookmarkChanged(post, newStatus)

                // 登录状态下同步到服务器
                if (authRepository != null && authRepository.isLoggedIn() && lifecycleScope != null) {
                    lifecycleScope.launch {
                        val result = if (newStatus) {
                            authRepository.addPostToFavorites(post.service, post.user, post.id)
                        } else {
                            authRepository.removePostFromFavorites(post.service, post.user, post.id)
                        }
                        if (result.isFailure) {
                            // 服务器同步失败，回滚本地状态
                            val rolledBack = !newStatus
                            if (rolledBack) {
                                bookmarkManager.bookmarkPost(post)
                            } else {
                                bookmarkManager.unbookmarkPost(post.service, post.user, post.id)
                            }
                            updateBookmarkIcon(rolledBack)
                            onBookmarkChanged(post, rolledBack)
                            Toast.makeText(
                                binding.root.context,
                                binding.root.context.getString(R.string.connection_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            binding.tvCreatorName.setOnClickListener {
                onCreatorClicked(post.service, post.user)
            }

            binding.root.setOnClickListener {
                onPostClicked(post)
            }
        }

        /**
         * PERF-010：仅更新创作者名称文本（预取完成后的局部刷新），
         * 与 [bind] 中的名称解析逻辑保持一致。
         */
        fun updateCreatorName(post: Post) {
            binding.tvCreatorName.text = CreatorNameCache.getCachedName(post.service, post.user) ?: post.user
        }

        private fun setServiceBadgeColor(binding: ItemPostBinding, service: String, context: Context) {
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

        private fun updateBookmarkIcon(isBookmarked: Boolean) {
            binding.btnBookmark.setImageResource(
                if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
        }

        /**
         * 构建候选 URL 列表，按优先级依次尝试：
         *  1. img.pawchive.pw/thumbnail/data...  （缩略图 CDN）
         *  2. img.pawchive.pw/data...            （原图标清 CDN）
         *  3. file.pawchive.pw/data...           （file 下载域名原图）
         *
         * 若 [imagePath] 已是完整的 https URL，则直接返回单元素列表。
         */
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

        /**
         * 顺序重试加载：按 [urls] 顺序依次尝试，直到某一个 URL 加载成功；
         * 所有候选都失败时，显示 error 占位。
         */
        private fun loadThumbnailWithFallback(
            binding: ItemPostBinding,
            urls: List<String>,
            index: Int
        ) {
            if (index >= urls.size) {
                return
            }
            val url = urls[index]
            val isLast = index == urls.size - 1
            binding.ivThumbnail.load(url) {
                size(THUMBNAIL_SIZE_PX)
                crossfade(150)
                placeholder(R.color.thumbnail_placeholder)
                error(if (isLast) R.color.thumbnail_placeholder else R.color.thumbnail_placeholder)
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
