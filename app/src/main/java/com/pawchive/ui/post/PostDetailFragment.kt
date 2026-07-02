package com.pawchive.ui.post

import android.content.ContentValues
import android.content.Intent
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.pawchive.R
import com.pawchive.data.api.PawchiveApi
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.databinding.FragmentPostDetailBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.CommentAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class PostDetailFragment : Fragment() {

    private var _binding: FragmentPostDetailBinding? = null
    private val binding get() = _binding!!

    private val api = PawchiveApi.create()
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository
    private lateinit var commentAdapter: CommentAdapter

    private var service: String = ""
    private var creatorId: String = ""
    private var postId: String = ""

    private var currentPost: Post? = null
    
    private var videoList = mutableListOf<Pair<String, String>>()
    private var currentVideoIndex = 0
    
    private var player: ExoPlayer? = null
    private var isPlayerInitialized = false

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarkManager = BookmarkManager(requireContext())
        authRepository = AuthRepository(requireContext())

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupVideoPlayer()
        setupCommentsRecyclerView()
        loadPostDetails()
    }

    override fun onStart() {
        super.onStart()
        if (binding.videoPlayerContainer.visibility == View.VISIBLE) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.videoPlayerContainer.visibility == View.VISIBLE) {
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }

    private fun setupCommentsRecyclerView() {
        commentAdapter = CommentAdapter(emptyList())
        binding.rvComments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvComments.adapter = commentAdapter
    }

    private fun loadPostDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val post = api.getPostDetails(service, creatorId, postId)
                currentPost = post
                displayPost(post)
                setupBookmarkButton(post)
                setupNavigationButtons(post)
                loadComments()
                loadRevisions()
                binding.nestedScrollView.scrollTo(0, 0)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error fetching post details: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayPost(post: Post) {
        binding.tvPostTitle.text = post.title
        binding.tvPostCreator.text = post.user
        binding.tvPostService.text = post.service.uppercase()
        binding.tvPostDate.text = post.published?.split("T")?.firstOrNull() ?: post.added?.split("T")?.firstOrNull() ?: ""

        setServiceBadgeColor(post.service)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            binding.tvPostContent.text = Html.fromHtml(post.content, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            binding.tvPostContent.text = Html.fromHtml(post.content)
        }
        binding.tvPostContent.movementMethod = LinkMovementMethod.getInstance()

        binding.tvPostContent.text = binding.tvPostContent.text.let {
            if (it is android.text.Spannable) {
                val spannable = android.text.SpannableString(it)
                val urls = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
                for (urlSpan in urls) {
                    val start = spannable.getSpanStart(urlSpan)
                    val end = spannable.getSpanEnd(urlSpan)
                    val flags = spannable.getSpanFlags(urlSpan)
                    val url = urlSpan.url
                    spannable.removeSpan(urlSpan)
                    spannable.setSpan(object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            startActivity(intent)
                        }
                    }, start, end, flags)
                }
                spannable
            } else {
                it
            }
        }

        val filePath = post.file?.path
        if (!filePath.isNullOrEmpty()) {
            val fullUrl = "https://file.pawchive.st/data$filePath"
            if (isVideoFile(filePath)) {
                videoList.add(Pair(fullUrl, post.file?.name ?: "video.mp4"))
                
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
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_report_image)
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

            val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
            val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts")
            
            val imageAttachments = attachments.filter { att ->
                imageExtensions.any { ext ->
                    att.path?.endsWith(ext, true) == true
                }
            }
            
            val videoAttachments = attachments.filter { att ->
                videoExtensions.any { ext ->
                    att.path?.endsWith(ext, true) == true
                }
            }
            
            val otherAttachments = attachments.filterNot { att ->
                imageExtensions.any { ext -> att.path?.endsWith(ext, true) == true } ||
                videoExtensions.any { ext -> att.path?.endsWith(ext, true) == true }
            }

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
                    val fullUrl = "https://file.pawchive.st/data${attachment.path}"
                    load(fullUrl) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                    setOnClickListener {
                        openImageViewer(fullUrl, attachment.name ?: "image.jpg")
                    }
                }
                binding.layoutAttachments.addView(imageView)
            }

            if (videoAttachments.isNotEmpty()) {
                val videoHeader = TextView(requireContext()).apply {
                    text = getString(R.string.video_attachments)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                binding.layoutAttachments.addView(videoHeader)

                for (attachment in videoAttachments) {
                    val fullUrl = "https://file.pawchive.st/data${attachment.path}"
                    videoList.add(Pair(fullUrl, attachment.name ?: "video.mp4"))
                }

                for ((index, attachment) in videoAttachments.withIndex()) {
                    val fullUrl = "https://file.pawchive.st/data${attachment.path}"
                    val videoItemView = createVideoAttachmentItem(fullUrl, attachment.name ?: "video.mp4", videoList.size - videoAttachments.size + index)
                    binding.layoutAttachments.addView(videoItemView)
                }
            }

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
                        text = attachment.name
                        setTextColor(resources.getColor(R.color.accent_light, null))
                        textSize = 14f
                        setPadding(0, 8, 0, 8)
                        setOnClickListener {
                            val url = "https://file.pawchive.st/data${attachment.path}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        }
                    }
                    binding.layoutAttachments.addView(textView)
                }
            }
        } else {
            binding.tvAttachmentsHeader.visibility = View.GONE
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
        updateBookmarkIcon(isBookmarked)

        binding.btnPostBookmark.setOnClickListener {
            val newStatus = !bookmarkManager.isPostBookmarked(service, creatorId, postId)
            
            if (newStatus) {
                bookmarkManager.bookmarkPost(post)
            } else {
                bookmarkManager.unbookmarkPost(service, creatorId, postId)
            }
            
            if (authRepository.isLoggedIn()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = if (newStatus) {
                        authRepository.addPostToFavorites(service, creatorId, postId)
                    } else {
                        authRepository.removePostFromFavorites(service, creatorId, postId)
                    }
                    
                    if (result.isFailure) {
                        Toast.makeText(context, "同步失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        if (newStatus) {
                            bookmarkManager.unbookmarkPost(service, creatorId, postId)
                            updateBookmarkIcon(false)
                        } else {
                            bookmarkManager.bookmarkPost(post)
                            updateBookmarkIcon(true)
                        }
                        return@launch
                    }
                }
            }
            
            updateBookmarkIcon(newStatus)
            Toast.makeText(
                context,
                if (newStatus) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        binding.btnPostBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
    }

    private fun setupNavigationButtons(post: Post) {
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

    private fun loadRevisions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val revisions = api.getPostRevisions(service, creatorId, postId)
                if (revisions.isNotEmpty()) {
                    binding.tvRevisionsHeader.visibility = View.VISIBLE
                    binding.layoutRevisions.removeAllViews()
                    for (revision in revisions) {
                        val textView = TextView(requireContext()).apply {
                            text = "Revision #${revision.revisionId} - ${revision.added?.split("T")?.firstOrNull() ?: ""}"
                            setTextColor(resources.getColor(R.color.text_secondary, null))
                            textSize = 13f
                            setPadding(0, 8, 0, 8)
                            background = resources.getDrawable(R.drawable.comment_bg, null)
                            setPadding(12, 12, 12, 12)
                        }
                        binding.layoutRevisions.addView(textView)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadComments() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val comments = api.getPostComments(service, creatorId, postId)
                if (comments.isNotEmpty()) {
                    binding.tvCommentsHeader.visibility = View.VISIBLE
                    commentAdapter = CommentAdapter(comments)
                    binding.rvComments.adapter = commentAdapter
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 初始化 ExoPlayer
     */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayer() {
        if (player != null) return
        
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
            })
            .build()
        
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        
        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        
        binding.playerView.player = player
        
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.videoLoadingProgress.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        binding.videoLoadingProgress.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                        val duration = player?.duration ?: 0
                        if (duration > 0) {
                            binding.seekbarVideo.max = duration.toInt()
                            binding.tvDuration.text = formatTime(duration.toInt())
                        }
                        updateVideoSize()
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
            
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateVideoSize()
            }
            
            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                binding.videoLoadingProgress.visibility = View.GONE
                Toast.makeText(context, "${getString(R.string.video_play_failed)}: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
        
        isPlayerInitialized = true
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateVideoSize() {
        val videoSize = player?.videoSize ?: return
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

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun releasePlayer() {
        player?.release()
        player = null
        isPlayerInitialized = false
    }

    /**
     * 设置视频播放器控件
     */
    private fun setupVideoPlayer() {
        binding.btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
        }
        
        binding.videoPlayButton.setOnClickListener {
            player?.play()
            binding.videoPlayButton.visibility = View.GONE
        }
        
        binding.btnCloseVideo.setOnClickListener {
            stopAndHideVideoPlayer()
        }
        
        binding.seekbarVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    binding.tvCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
        }
        
        binding.btnCast.setOnClickListener {
            Toast.makeText(context, getString(R.string.cast), Toast.LENGTH_SHORT).show()
        }
        
        binding.btnDownload.setOnClickListener {
            downloadCurrentVideo()
        }
        
        binding.btnFullscreen.setOnClickListener {
            Toast.makeText(context, getString(R.string.fullscreen), Toast.LENGTH_SHORT).show()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                if (player != null && binding.videoPlayerContainer.visibility == View.VISIBLE) {
                    val currentPos = player?.currentPosition ?: 0
                    val bufferedPos = player?.bufferedPosition ?: 0
                    
                    binding.seekbarVideo.progress = currentPos.toInt()
                    binding.tvCurrentTime.text = formatTime(currentPos.toInt())
                    
                    binding.seekbarVideo.secondaryProgress = bufferedPos.toInt()
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /**
     * 显示倍速选择对话框
     */
    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        
        val currentSpeed = player?.playbackParameters?.speed ?: 1.0f
        val currentIndex = speedValues.indexOfFirst { it == currentSpeed }.takeIf { it >= 0 } ?: 2
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.speed))
            .setSingleChoiceItems(speeds, currentIndex) { dialog, which ->
                player?.setPlaybackParameters(PlaybackParameters(speedValues[which]))
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 下载当前视频到系统相册
     */
    private fun downloadCurrentVideo() {
        if (currentVideoIndex >= videoList.size) return
        
        val (url, fileName) = videoList[currentVideoIndex]
        
        Toast.makeText(context, getString(R.string.saving_image), Toast.LENGTH_SHORT).show()
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
                
                val inputStream = response.body?.byteStream()
                    ?: throw Exception("Empty response body")
                
                val mimeType = when {
                    fileName.endsWith(".mp4", true) -> "video/mp4"
                    fileName.endsWith(".webm", true) -> "video/webm"
                    fileName.endsWith(".mov", true) -> "video/quicktime"
                    fileName.endsWith(".mkv", true) -> "video/x-matroska"
                    fileName.endsWith(".avi", true) -> "video/x-msvideo"
                    else -> "video/mp4"
                }
                
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Pawchive")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                
                val resolver = requireContext().contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                
                val uri = resolver.insert(collection, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            getString(R.string.video_downloaded),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 播放指定视频
     */
    private fun playVideoAtIndex(index: Int) {
        if (index < 0 || index >= videoList.size) return
        
        currentVideoIndex = index
        val (url, fileName) = videoList[index]
        
        binding.videoPlayerContainer.visibility = View.VISIBLE
        binding.videoLoadingProgress.visibility = View.VISIBLE
        binding.videoPlayButton.visibility = View.GONE
        binding.tvVideoName.text = fileName
        binding.tvVideoCount.text = "${index + 1}/${videoList.size}"
        
        initializePlayer()
        
        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
        
        binding.nestedScrollView.post {
            binding.nestedScrollView.scrollTo(0, 0)
        }
    }

    /**
     * 停止并隐藏视频播放器
     */
    private fun stopAndHideVideoPlayer() {
        player?.stop()
        binding.videoPlayerContainer.visibility = View.GONE
    }

    /**
     * 创建视频附件项视图
     */
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

    /**
     * 判断是否为视频文件
     */
    private fun isVideoFile(path: String): Boolean {
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts")
        return videoExtensions.any { path.lowercase().endsWith(it) }
    }

    /**
     * 格式化时间
     */
    private fun formatTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val ARG_SERVICE = "service"
        private const val ARG_CREATOR_ID = "creator_id"
        private const val ARG_POST_ID = "post_id"

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