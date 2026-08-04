package com.pawchive.ui.post

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.pawchive.R
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentPostDetailBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.CommentAdapter
import com.pawchive.utils.ErrorStateViewHelper
import com.pawchive.utils.ErrorMessageHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class PostDetailFragment : Fragment() {

    private var _binding: FragmentPostDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PostDetailViewModel by viewModels()

    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository
    private lateinit var commentAdapter: CommentAdapter
    private lateinit var videoPlayerManager: VideoPlayerManager
    private lateinit var readingProgressManager: com.pawchive.data.repository.ReadingProgressManager

    private var service: String = ""
    private var creatorId: String = ""
    private var postId: String = ""

    private var currentPost: Post? = null
    private var videoList = mutableListOf<Pair<String, String>>()
    private var currentVideoIndex = 0
    private var isUserSeeking = false
    private var isFullscreen = false

    // 内嵌错误页（FEATURE-006）
    private lateinit var errorStateView: ErrorStateViewHelper.Bound

    // 视频下载：Android 13+ 需运行时申请通知权限以展示进度条
    private var pendingDownload: Pair<String, String>? = null
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            val ctx = context ?: return@registerForActivityResult
            val pending = pendingDownload ?: return@registerForActivityResult
            pendingDownload = null
            if (!granted) {
                Toast.makeText(ctx, R.string.download_no_permission, Toast.LENGTH_LONG).show()
            }
            enqueueVideoDownload(ctx, pending.first, pending.second)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            service = it.getString(ARG_SERVICE, "")
            creatorId = it.getString(ARG_CREATOR_ID, "")
            postId = it.getString(ARG_POST_ID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    @OptIn(UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarkManager = BookmarkManager.getInstance(requireContext())
        authRepository = AuthRepository(requireContext())
        videoPlayerManager = VideoPlayerManager(requireContext())
        readingProgressManager = com.pawchive.data.repository.ReadingProgressManager.getInstance(requireContext())

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSaveAllImages.setOnClickListener {
            currentPost?.let { saveAllImages(it) }
        }

        binding.tvPostCreator.setOnClickListener {
            val creatorFragment = com.pawchive.ui.creator.CreatorProfileFragment.newInstance(service, creatorId)
            (activity as? MainActivity)?.loadFragment(creatorFragment)
        }

        setupCommentsRecyclerView()
        setupVideoPlayer()
        // 绑定内嵌错误页（FEATURE-006）
        errorStateView = ErrorStateViewHelper.bind(binding.root) {
            loadPostDetails()
        }
        observeUiState()
        loadPostDetails()
    }

    override fun onStart() {
        super.onStart()
        if (binding.videoPlayerContainer.visibility == View.VISIBLE) {
            videoPlayerManager.attachPlayerView(binding.playerView)
            videoPlayerManager.restore()
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.videoPlayerContainer.visibility == View.VISIBLE) {
            videoPlayerManager.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        videoPlayerManager.pause()
    }

    override fun onStop() {
        super.onStop()
        // 持久化视频播放位置（FEATURE-005 视频记忆）
        videoPlayerManager.updateCurrentPosition()
        videoList.getOrNull(currentVideoIndex)?.first?.let { url ->
            val pos = videoPlayerManager.currentPosition
            if (pos > 1000) { // 仅保存超过 1 秒的位置
                readingProgressManager.saveVideoPosition(url, pos)
            }
        }
        // 持久化阅读滚动位置（FEATURE-005 阅读进度）
        if (postId.isNotEmpty()) {
            readingProgressManager.saveReadingScroll(postId, binding.nestedScrollView.scrollY)
        }
        videoPlayerManager.savePlaybackState()
        videoPlayerManager.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoPlayerManager.release()
        _binding = null
    }

    private fun setupCommentsRecyclerView() {
        commentAdapter = CommentAdapter()
        binding.rvComments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvComments.adapter = commentAdapter
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isLoading) {
                        // 加载中：隐藏错误页
                        errorStateView.hide()
                    } else if (state.errorMessage != null) {
                        if (state.post == null) {
                            // 无内容：展示内嵌错误页，提供重试入口（FEATURE-006）
                            binding.nestedScrollView.visibility = View.GONE
                            errorStateView.show(state.errorMessage)
                        } else {
                            // 已有内容：错误以 Toast 呈现，不打断浏览
                            binding.nestedScrollView.visibility = View.VISIBLE
                            errorStateView.hide()
                            Toast.makeText(
                                context,
                                ErrorMessageHelper.getFriendlyMessage(context, state.errorMessage),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        // 正常展示内容
                        binding.nestedScrollView.visibility = View.VISIBLE
                        errorStateView.hide()
                        state.post?.let { post ->
                            currentPost = post
                            videoList = state.videoList.toMutableList()
                            currentVideoIndex = state.currentVideoIndex
                            displayPost(post)
                            setupBookmarkButton(post)
                            setupNavigationButtons(post)
                            displayComments(state.comments)
                            displayRevisions(state.revisions)
                            binding.tvOfflineBanner.visibility =
                                if (state.isOfflineMode) View.VISIBLE else View.GONE
                            // 恢复阅读滚动位置（FEATURE-005 阅读进度）
                            val savedScroll = readingProgressManager.getReadingScroll(post.id)
                            if (savedScroll > 0) {
                                binding.nestedScrollView.post {
                                    binding.nestedScrollView.scrollTo(0, savedScroll)
                                }
                            } else {
                                binding.nestedScrollView.scrollTo(0, 0)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadPostDetails() {
        viewModel.loadPostDetails(service, creatorId, postId)
    }

    private fun displayPost(post: Post) {
        binding.tvPostTitle.text = post.title ?: ""
        binding.tvPostCreator.text = CreatorNameCache.getCachedName(post.service, post.user) ?: post.user
        binding.tvPostService.text = post.service.uppercase()
        binding.tvPostDate.text = post.published?.split("T")?.firstOrNull() ?: post.added?.split("T")?.firstOrNull() ?: ""

        if (CreatorNameCache.getCachedName(post.service, post.user) == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val profile = ApiClient.publicApi.getCreatorProfile(post.service, post.user)
                    CreatorNameCache.cacheCreatorName(post.service, post.user, profile.name)
                    if (_binding != null) {
                        binding.tvPostCreator.text = profile.name
                    }
                } catch (_: Exception) { }
            }
        }

        setServiceBadgeColor(post.service)

        val content = post.content ?: ""
        // 安全渲染：仅保留白名单标签与 https 链接，危险 scheme 链接被剥离为纯文本（P1）
        binding.tvPostContent.text = com.pawchive.utils.SafeHtmlHelper.render(content)
        // 外链提示：拦截链接点击，弹出确认对话框（FEATURE-005）
        binding.tvPostContent.movementMethod = LinkMovementMethod.getInstance()
        // 通过 URLSpan 拦截点击
        val spannable = binding.tvPostContent.text as? android.text.Spannable
        if (spannable != null) {
            val urlSpans = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
            for (span in urlSpans) {
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                val url = span.url
                spannable.removeSpan(span)
                spannable.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            showExternalLinkDialog(url)
                        }
                    },
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val filePath = post.file?.path
        val fileName = post.file?.name
        if (!filePath.isNullOrEmpty() || !fileName.isNullOrEmpty()) {
            val fullUrl = "https://file.pawchive.pw/data${filePath.orEmpty()}"
            if (isVideoFile(filePath, fileName)) {
                binding.imageCard.visibility = View.VISIBLE
                (binding.imageCard as ViewGroup).removeAllViews()

                val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val paddingPx = (16 * resources.displayMetrics.density).toInt()

                val innerLayout = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val playIcon = ImageView(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(32, 32).apply {
                        marginEnd = (12 * resources.displayMetrics.density).toInt()
                    }
                    setImageResource(R.drawable.ic_play)
                    setColorFilter(
                        resources.getColor(
                            if (isDarkMode) R.color.text_secondary else R.color.text_secondary_light,
                            null
                        )
                    )
                }
                innerLayout.addView(playIcon)

                val nameTextView = TextView(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    text = post.file?.name ?: "video.mp4"
                    textSize = 14f
                    setTextColor(
                        resources.getColor(
                            if (isDarkMode) R.color.text_primary else R.color.text_primary_light,
                            null
                        )
                    )
                }
                innerLayout.addView(nameTextView)

                val hintTextView = TextView(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = getString(R.string.play_video)
                    textSize = 12f
                    setTextColor(
                        resources.getColor(
                            if (isDarkMode) R.color.text_muted else R.color.text_muted_light,
                            null
                        )
                    )
                }
                innerLayout.addView(hintTextView)

                (binding.imageCard as ViewGroup).addView(innerLayout)

                binding.imageCard.setOnClickListener {
                    playVideoAtIndex(0)
                }
            } else {
                binding.imageCard.visibility = View.VISIBLE
                binding.ivPostImage.load(fullUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_image)
                    error(R.drawable.ic_image_off)
                }
                binding.ivPostImage.setOnClickListener {
                    openImageViewer(fullUrl, post.file?.name ?: "image.jpg")
                }
            }
        } else {
            binding.imageCard.visibility = View.GONE
        }

        val attachments = post.attachments
        if (!attachments.isNullOrEmpty()) {
            binding.tvAttachmentsHeader.visibility = View.VISIBLE
            binding.layoutAttachments.removeAllViews()

            val imageAttachments = attachments.filter { att ->
                isImageFile(att.path, att.name)
            }

            val videoAttachments = attachments.filter { att ->
                isVideoFile(att.path, att.name)
            }

            val otherAttachments = attachments.filterNot { att ->
                isImageFile(att.path, att.name) || isVideoFile(att.path, att.name)
            }

            // 先渲染可下载的文件(其他文件),置于顶部,避免大量预览图把下载入口挤到底部
            if (otherAttachments.isNotEmpty()) {
                val otherHeader = TextView(requireContext()).apply {
                    text = getString(R.string.other_files)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                binding.layoutAttachments.addView(otherHeader)

                for (attachment in otherAttachments) {
                    val textView = TextView(requireContext()).apply {
                        text = attachment.name ?: "file"
                        setTextColor(resources.getColor(R.color.accent_light, null))
                        textSize = 14f
                     setPadding(0, 8, 0, 8)
                        setOnClickListener {
                            val url = "https://file.pawchive.pw/data${attachment.path ?: ""}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        }
                    }
                    binding.layoutAttachments.addView(textView)
                }
            }

            // 其次渲染视频附件
            if (videoAttachments.isNotEmpty()) {
                val videoHeader = TextView(requireContext()).apply {
                    text = getString(R.string.video_attachments)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                binding.layoutAttachments.addView(videoHeader)

                var videoIndexOffset = if (isVideoFile(post.file?.path, post.file?.name)) 1 else 0

                for ((index, attachment) in videoAttachments.withIndex()) {
                    val fullUrl = "https://file.pawchive.pw/data${attachment.path ?: ""}"
                    val videoItemView = createVideoAttachmentItem(
                        fullUrl,
                        attachment.name ?: "video.mp4",
                        videoIndexOffset + index
                    )
                    binding.layoutAttachments.addView(videoItemView)
                }
            }

            // 最后渲染大量预览图,放在附件区底部
            if (imageAttachments.isNotEmpty()) {
                val imageHeader = TextView(requireContext()).apply {
                    text = getString(R.string.preview_images)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                binding.layoutAttachments.addView(imageHeader)

                for (attachment in imageAttachments) {
                    val imageView = ImageView(requireContext()).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = 12
                            bottomMargin = 12
                        }
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val fullUrl = "https://file.pawchive.pw/data${attachment.path}"
                        load(fullUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_image)
                            error(R.drawable.ic_image_off)
                        }
                        setOnClickListener {
                            openImageViewer(fullUrl, attachment.name ?: "image.jpg")
                        }
                    }
                    binding.layoutAttachments.addView(imageView)
                }
            }
        } else {
            binding.tvAttachmentsHeader.visibility = View.GONE
        }
    }

    private fun displayComments(comments: List<com.pawchive.data.model.Comment>) {
        if (comments.isNotEmpty()) {
            binding.tvCommentsHeader.visibility = View.VISIBLE
            commentAdapter.updateComments(comments)
        }
    }

    private fun displayRevisions(revisions: List<com.pawchive.data.model.PostRevision>) {
        if (revisions.isNotEmpty()) {
            binding.tvRevisionsHeader.visibility = View.VISIBLE
            binding.layoutRevisions.removeAllViews()
            for (revision in revisions) {
                val textView = TextView(requireContext()).apply {
                    text = "Revision #${revision.revisionId} - ${revision.added?.split("T")?.firstOrNull() ?: ""}"
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 13f
                    setPadding(0, 8, 0, 8)
                    background = requireContext().getDrawable(R.drawable.comment_bg)
                    setPadding(12, 12, 12, 12)
                }
                binding.layoutRevisions.addView(textView)
            }
        }
    }

    private fun openImageViewer(imageUrl: String, imageName: String) {
        val fragment = PhotoViewerFragment.newInstance(imageUrl, imageName)
        (activity as? MainActivity)?.loadFragment(fragment)
    }

    private fun setServiceBadgeColor(service: String) {
        val isDarkMode = (requireContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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

        binding.cardPostService.setCardBackgroundColor(requireContext().getColor(bgColorRes))
        binding.tvPostService.setTextColor(requireContext().getColor(textColorRes))
    }

    private fun setupBookmarkButton(post: Post) {
        val isBookmarked = bookmarkManager.isPostBookmarked(service, creatorId, postId)
        viewModel.setBookmarked(isBookmarked)
        updateBookmarkIcon(isBookmarked)

        binding.btnPostBookmark.setOnClickListener {
            // 防止同步进行中再次点击导致状态错乱
            if (binding.btnPostBookmark.isEnabled.not()) return@setOnClickListener
            val previousStatus = bookmarkManager.isPostBookmarked(service, creatorId, postId)
            val newStatus = !previousStatus

            // 先做乐观更新：本地立刻生效，UI 立刻反馈
            if (newStatus) {
                bookmarkManager.bookmarkPost(post)
            } else {
                bookmarkManager.unbookmarkPost(service, creatorId, postId)
            }
            viewModel.setBookmarked(newStatus)
            updateBookmarkIcon(newStatus)
            Toast.makeText(
                context,
                if (newStatus) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()

            if (authRepository.isLoggedIn()) {
                // 同步期间禁用按钮，避免在飞行中重复点击
                binding.btnPostBookmark.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = if (newStatus) {
                        authRepository.addPostToFavorites(service, creatorId, postId)
                    } else {
                        authRepository.removePostFromFavorites(service, creatorId, postId)
                    }

                    if (result.isFailure) {
                        // 同步失败：完整回滚本地状态、ViewModel 状态与图标
                        // 修复前只回滚了 icon，viewModel.setBookmarked 未回滚，重建 Fragment 后图标与实际不符
                        if (newStatus) {
                            bookmarkManager.unbookmarkPost(service, creatorId, postId)
                        } else {
                            bookmarkManager.bookmarkPost(post)
                        }
                        viewModel.setBookmarked(previousStatus)
                        updateBookmarkIcon(previousStatus)
                        // 修复前会再弹一次"已收藏/已取消收藏"，与"同步失败"形成双重 Toast，用户困惑
                        Toast.makeText(
                            context,
                            ErrorMessageHelper.getFriendlyMessage(context, result.exceptionOrNull()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    binding.btnPostBookmark.isEnabled = true
                }
            }
        }
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        binding.btnPostBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
    }

    /**
     * 外链提示对话框（FEATURE-005）。
     * 点击帖子内容中的链接时弹出确认，避免误触跳转外部站点。
     */
    private fun showExternalLinkDialog(url: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.external_link_warning)
            .setMessage(url)
            .setPositiveButton(R.string.ok) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.error_load_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 批量保存帖子中的所有图片（FEATURE-005）。
     * 遍历 file 和 attachments，对所有图片类型入队下载。
     */
    private fun saveAllImages(post: Post) {
        val ctx = context ?: return
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
        var count = 0

        // 检查主文件
        post.file?.let { file ->
            val name = file.name ?: ""
            if (imageExtensions.any { name.endsWith(it, true) }) {
                val fullUrl = "https://file.pawchive.pw/data${file.path.orEmpty()}"
                viewLifecycleOwner.lifecycleScope.launch {
                    com.pawchive.data.repository.DownloadCenter.enqueueImageDownload(
                        ctx, fullUrl, name
                    )
                }
                count++
            }
        }

        // 检查附件
        post.attachments?.forEach { attachment ->
            val name = attachment.name ?: ""
            if (imageExtensions.any { name.endsWith(it, true) }) {
                val fullUrl = "https://file.pawchive.pw/data${attachment.path.orEmpty()}"
                viewLifecycleOwner.lifecycleScope.launch {
                    com.pawchive.data.repository.DownloadCenter.enqueueImageDownload(
                        ctx, fullUrl, name
                    )
                }
                count++
            }
        }

        if (count == 0) {
            Toast.makeText(ctx, R.string.no_images_to_save, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, getString(R.string.saving_images_count, count), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNavigationButtons(post: Post) {
        binding.btnPrevPost.visibility = View.GONE
        binding.btnNextPost.visibility = View.GONE

        post.prev?.let { prevId ->
            if (prevId.isNotEmpty() && prevId != "null") {
                binding.btnPrevPost.visibility = View.VISIBLE
                binding.btnPrevPost.setOnClickListener {
                    val detailFragment = newInstance(service, creatorId, prevId)
                    (activity as? MainActivity)?.loadFragment(detailFragment)
                }
            }
        }

        post.next?.let { nextId ->
            if (nextId.isNotEmpty() && nextId != "null") {
                binding.btnNextPost.visibility = View.VISIBLE
                binding.btnNextPost.setOnClickListener {
                    val detailFragment = newInstance(service, creatorId, nextId)
                    (activity as? MainActivity)?.loadFragment(detailFragment)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun setupVideoPlayer() {
        videoPlayerManager.setListener(object : VideoPlayerManager.VideoPlayerListener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.videoLoadingProgress.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        binding.videoLoadingProgress.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                        val duration = videoPlayerManager.duration
                        if (duration > 0) {
                            binding.seekbarVideo.max = duration.toInt()
                            binding.tvDuration.text = videoPlayerManager.formatTime(duration)
                        }
                        updateVideoSize()
                        // 视频准备好后显示控制栏
                        showVideoControls()
                    }
                    Player.STATE_ENDED -> {
                        binding.videoLoadingProgress.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                        binding.videoPlayButton.visibility = View.VISIBLE
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                updateVideoSize()
            }

            override fun onError(message: String) {
                binding.videoLoadingProgress.visibility = View.GONE
                Toast.makeText(context, "${getString(R.string.video_play_failed)}: $message", Toast.LENGTH_SHORT).show()
            }
        })

        binding.btnPlayPause.setOnClickListener {
            if (videoPlayerManager.isPlaying) {
                videoPlayerManager.pause()
            } else {
                videoPlayerManager.resume()
            }
        }

        binding.videoPlayButton.setOnClickListener {
            videoPlayerManager.resume()
            binding.videoPlayButton.visibility = View.GONE
        }

        binding.btnCloseVideo.setOnClickListener {
            stopAndHideVideoPlayer()
        }

        binding.seekbarVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = videoPlayerManager.formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = false
                val progress = seekBar?.progress ?: 0
                videoPlayerManager.seekTo(progress.toLong())
                binding.tvCurrentTime.text = videoPlayerManager.formatTime(progress.toLong())
            }
        })

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
        }

        binding.btnDownload.setOnClickListener {
            downloadCurrentVideo()
        }

        // 点视频画面切换控制栏显示/隐藏（Bilibili 风格交互）
        binding.videoDisplayFrame.setOnClickListener {
            if (binding.videoControllerOverlay.visibility == View.VISIBLE) {
                hideVideoControls()
            } else {
                showVideoControls()
            }
        }

        // 进度条自动隐藏计时
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (videoPlayerManager.player != null && binding.videoPlayerContainer.visibility == View.VISIBLE) {
              if (!isUserSeeking) {
                            videoPlayerManager.updateCurrentPosition()
                            val currentPos = videoPlayerManager.currentPosition
                            val bufferedPos = videoPlayerManager.player?.bufferedPosition ?: 0

                            val currentPosInt = if (currentPos > Int.MAX_VALUE) Int.MAX_VALUE else currentPos.toInt()
                            val bufferedPosInt = if (bufferedPos > Int.MAX_VALUE) Int.MAX_VALUE else bufferedPos.toInt()
                            binding.seekbarVideo.progress = currentPosInt
                            binding.tvCurrentTime.text = videoPlayerManager.formatTime(currentPos)
                            binding.seekbarVideo.secondaryProgress = bufferedPosInt
                        }
                    }
                    kotlinx.coroutines.delay(200)
                }
            }
        }
    }

    private fun showVideoControls() {
        binding.videoControllerOverlay.visibility = View.VISIBLE
        binding.videoTopActions.visibility = View.VISIBLE
        // 3 秒后自动隐藏（仅在播放中）
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            if (videoPlayerManager.isPlaying) {
                hideVideoControls()
            }
        }
    }

    private fun hideVideoControls() {
        binding.videoControllerOverlay.visibility = View.GONE
        binding.videoTopActions.visibility = View.GONE
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        val currentSpeed = videoPlayerManager.playbackSpeed
        val currentIndex = speedValues.indexOfFirst { it == currentSpeed }.takeIf { it >= 0 } ?: 2

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.speed))
            .setSingleChoiceItems(speeds, currentIndex) { dialog, which ->
                videoPlayerManager.setPlaybackSpeed(speedValues[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun downloadCurrentVideo() {
        if (currentVideoIndex >= videoList.size) {
            Toast.makeText(context, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val (url, fileName) = videoList[currentVideoIndex]
        val ctx = context ?: return

        // Android 13+ 需运行时申请通知权限以展示下载进度条；
        // 即便用户拒绝，下载仍会进行（仅无可见通知）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = url to fileName
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        enqueueVideoDownload(ctx, url, fileName)
    }

    /**
     * 将视频下载任务交给 WorkManager 前台 Worker 执行（P2 FRONTEND-006）。
     * - App 切后台不中断下载；
     * - 通知栏展示进度条与完成/失败状态；
     * - 文件写入仍走 DownloadRepository（优先 SAF，回退 MediaStore）。
     */
    private fun enqueueVideoDownload(context: Context, url: String, fileName: String) {
        val data = androidx.work.Data.Builder()
            .putString(com.pawchive.work.DownloadWorker.KEY_URL, url)
            .putString(com.pawchive.work.DownloadWorker.KEY_FILE_NAME, fileName)
            .build()

        val request = androidx.work.OneTimeWorkRequestBuilder<com.pawchive.work.DownloadWorker>()
            .setInputData(data)
            // 保持下载任务存活，覆盖最近一次同文件名下载
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DOWNLOAD_WORK_TAG)
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "$DOWNLOAD_WORK_TAG:$fileName",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request
        )
    }

    @OptIn(UnstableApi::class)
    private fun playVideoAtIndex(index: Int) {
        if (index < 0 || index >= videoList.size) return

        currentVideoIndex = index
        viewModel.setCurrentVideoIndex(index)
        val (url, fileName) = videoList[index]

        binding.videoPlayerContainer.visibility = View.VISIBLE
        binding.videoLoadingProgress.visibility = View.VISIBLE
        binding.videoPlayButton.visibility = View.GONE
        binding.tvVideoName.text = fileName
        binding.tvVideoCount.text = "${index + 1}/${videoList.size}"

        videoPlayerManager.attachPlayerView(binding.playerView)
        videoPlayerManager.play(url)

        // 恢复上次播放位置（FEATURE-005 视频记忆）
        val savedPos = readingProgressManager.getVideoPosition(url)
        if (savedPos > 1000) {
            videoPlayerManager.seekTo(savedPos)
            Toast.makeText(context, R.string.video_resume_position, Toast.LENGTH_SHORT).show()
        }

        binding.nestedScrollView.post {
            binding.nestedScrollView.scrollTo(0, 0)
        }
    }

    private fun stopAndHideVideoPlayer() {
        if (isFullscreen) {
            exitFullscreen()
        }
        videoPlayerManager.stop()
        binding.videoPlayerContainer.visibility = View.GONE
    }

    private fun toggleFullscreen() {
        if (currentVideoIndex >= videoList.size) return

        val (url, fileName) = videoList[currentVideoIndex]
        val position = videoPlayerManager.currentPosition
        val isPlaying = videoPlayerManager.isPlaying

        // 进入全屏：暂停内嵌播放器（保留资源以便退出后无缝恢复）
        videoPlayerManager.pause()
        isFullscreen = true

        val dialog = FullscreenVideoDialog.newInstance(url, fileName, position, isPlaying)
        dialog.setListener(object : FullscreenVideoDialog.FullscreenVideoListener {
            override fun onFullscreenClosed(position: Long, isPlaying: Boolean) {
                // 退出全屏：从退出位置继续播放
                isFullscreen = false
                if (binding.videoPlayerContainer.visibility == View.VISIBLE) {
                    videoPlayerManager.seekTo(position)
                    if (isPlaying) {
                        videoPlayerManager.resume()
                    }
                }
            }
        })
        dialog.show(parentFragmentManager, "fullscreen_video")
    }

    private fun enterFullscreen() {
        isFullscreen = true
    }

    private fun exitFullscreen() {
        isFullscreen = false
    }

    private fun updateVideoSize() {
        val videoSize = videoPlayerManager.player?.videoSize ?: return
        val videoWidth = videoSize.width
        val videoHeight = videoSize.height

        if (videoWidth <= 0 || videoHeight <= 0) return

        val screenWidth = resources.displayMetrics.widthPixels
        val maxHeight = (240 * resources.displayMetrics.density).toInt()

        val calculatedHeight = (screenWidth.toFloat() / videoWidth * videoHeight).toInt()
        val finalHeight = minOf(calculatedHeight, maxHeight)

        val frameLp = binding.videoDisplayFrame.layoutParams
        frameLp.height = finalHeight
        binding.videoDisplayFrame.layoutParams = frameLp

        val playerViewLp = binding.playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        playerViewLp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        playerViewLp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        playerViewLp.gravity = android.view.Gravity.CENTER
        binding.playerView.layoutParams = playerViewLp
    }

    private fun createVideoAttachmentItem(url: String, fileName: String, index: Int): View {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val container = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            radius = 16f
            strokeColor = resources.getColor(
                if (isDarkMode) R.color.divider_dark else R.color.divider_light,
                null
            )
            strokeWidth = 1
            setCardBackgroundColor(
                resources.getColor(
                    if (isDarkMode) R.color.card_dark else R.color.card_light,
                    null
                )
            )
        }

        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val innerLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val playIcon = ImageView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(32, 32).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }
            setImageResource(R.drawable.ic_play)
            setColorFilter(
                resources.getColor(
                    if (isDarkMode) R.color.text_secondary else R.color.text_secondary_light,
                    null
                )
            )
        }
        innerLayout.addView(playIcon)

        val nameTextView = TextView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = fileName
            textSize = 14f
            setTextColor(
                resources.getColor(
                    if (isDarkMode) R.color.text_primary else R.color.text_primary_light,
                    null
                )
            )
        }
        innerLayout.addView(nameTextView)

        val countTextView = TextView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "${index + 1}/${videoList.size}"
            textSize = 12f
            setTextColor(
                resources.getColor(
                    if (isDarkMode) R.color.text_muted else R.color.text_muted_light,
                    null
                )
            )
        }
        innerLayout.addView(countTextView)

        container.addView(innerLayout)

        container.setOnClickListener {
            playVideoAtIndex(index)
        }

        return container
    }

    private fun isVideoFile(path: String?, name: String? = null): Boolean {
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts", ".flv", ".wmv", ".ogv")
        val lowerPath = path?.lowercase().orEmpty()
        val lowerName = name?.lowercase().orEmpty()
        return videoExtensions.any { ext ->
            lowerPath.endsWith(ext) || lowerName.endsWith(ext)
        }
    }
    
    private fun isImageFile(path: String?, name: String? = null): Boolean {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
        val lowerPath = path?.lowercase().orEmpty()
        val lowerName = name?.lowercase().orEmpty()
        return imageExtensions.any { ext ->
            lowerPath.endsWith(ext) || lowerName.endsWith(ext)
        }
    }

    companion object {
        private const val ARG_SERVICE = "service"
        private const val ARG_CREATOR_ID = "creator_id"
        private const val ARG_POST_ID = "post_id"
        private const val DOWNLOAD_WORK_TAG = "video_download"

        fun newInstance(service: String, creatorId: String, postId: String): PostDetailFragment {
            val fragment = PostDetailFragment()
            val args = Bundle().apply {
                putString(ARG_SERVICE, service)
                putString(ARG_CREATOR_ID, creatorId)
                putString(ARG_POST_ID, postId)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
