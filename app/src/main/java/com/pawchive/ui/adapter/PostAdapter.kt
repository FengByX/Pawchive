package com.pawchive.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pawchive.R
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.ItemLoadMoreFooterBinding
import com.pawchive.databinding.ItemPostBinding
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
    private val onLoadMore: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var showFooter = false

    companion object {
        private const val TYPE_POST = 0
        private const val TYPE_FOOTER = 1
        // 缩略图 View 为 80dp，解码到 160px 在高密度屏也足够清晰，同时大幅降低内存占用
        private const val THUMBNAIL_SIZE_PX = 160
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
     * DiffUtil 无法感知其变化，因此用 notifyItemRangeChanged 仅刷新数据区条目，
     * 避免整表重绘。
     */
    fun refreshCreatorNames() {
        if (posts.isNotEmpty()) {
            notifyItemRangeChanged(0, posts.size)
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

            // Set service badge color based on platform
            setServiceBadgeColor(binding, post.service, binding.root.context)

            // Simple HTML tag removal for content preview
            val plainText = post.content?.replace(Regex("<[^>]*>"), "") ?: ""
            binding.tvPreview.text = if (plainText.length > 120) plainText.take(120) + "..." else plainText

            // Load thumbnail if main file or attachments are images
            val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
            
            fun isImage(path: String?): Boolean {
                val lowerPath = path?.lowercase().orEmpty()
                return imageExtensions.any { ext -> lowerPath.endsWith(ext) }
            }
            
            val imagePath = if (isImage(post.file?.path)) {
                post.file?.path
            } else {
                post.attachments?.firstOrNull { isImage(it.path) }?.path
            }

            if (!imagePath.isNullOrEmpty()) {
                binding.ivThumbnail.visibility = View.VISIBLE
                val fullUrl = "https://img.pawchive.pw/thumbnail/data$imagePath"
                binding.ivThumbnail.load(fullUrl) {
                    // 缩略图 View 固定 80dp，限制解码尺寸避免加载原图导致的内存与耗时浪费
                    size(THUMBNAIL_SIZE_PX)
                    crossfade(150)
                    placeholder(R.color.thumbnail_placeholder)
                    error(R.color.thumbnail_placeholder)
                }
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
    }
}
