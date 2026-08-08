package com.pawchive.ui.post

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.error.AppError
import com.pawchive.core.api.ApiCallHandler
import com.pawchive.core.api.ApiClient
import com.pawchive.core.model.Comment
import com.pawchive.core.model.Post
import com.pawchive.core.model.PostRevision
import com.pawchive.core.store.AppMemoryCache
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.core.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val revisions: List<PostRevision> = emptyList(),
    val isBookmarked: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val videoList: List<Pair<String, String>> = emptyList(),
    val currentVideoIndex: Int = 0,
    val isOfflineMode: Boolean = false
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    application: Application,
    private val memoryCache: AppMemoryCache,
    private val bookmarkManager: BookmarkManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    private val api = ApiClient.publicApi

    /**
     * 加载帖子详情。优先读取内存缓存，无缓存再请求网络。
     * @param forceRefresh 为 true 时跳过缓存直接请求（如下拉刷新）
     */
    fun loadPostDetails(service: String, creatorId: String, postId: String, forceRefresh: Boolean = false) {
        val cacheKeyPost = "post:$service|$creatorId|$postId"
        val cacheKeyComments = "comments:$service|$creatorId|$postId"
        val cacheKeyRevisions = "revisions:$service|$creatorId|$postId"

        if (!forceRefresh) {
            val cachedPost: Post? = memoryCache.get(cacheKeyPost)
            val cachedComments: List<Comment>? = memoryCache.get(cacheKeyComments)
            val cachedRevisions: List<PostRevision>? = memoryCache.get(cacheKeyRevisions)

            if (cachedPost != null) {
                val videoList = extractVideoUrls(cachedPost)
                _uiState.value = _uiState.value.copy(
                    post = cachedPost,
                    comments = cachedComments ?: emptyList(),
                    revisions = cachedRevisions ?: emptyList(),
                    videoList = videoList,
                    isLoading = false,
                    errorMessage = null
                )
                return
            }
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            // 网络不可用时尝试从本地收藏加载（FEATURE-002 离线阅读）
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                val bookmarkedPost = bookmarkManager
                    .getBookmarkedPost(service, creatorId, postId)
                if (bookmarkedPost != null) {
                    val videoList = extractVideoUrls(bookmarkedPost)
                    _uiState.value = _uiState.value.copy(
                        post = bookmarkedPost,
                        comments = emptyList(),
                        revisions = emptyList(),
                        videoList = videoList,
                        isLoading = false,
                        errorMessage = null,
                        isOfflineMode = true
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = getApplication<Application>().getString(
                        com.pawchive.common.R.string.offline_not_cached
                    ),
                    isOfflineMode = true
                )
                return@launch
            }

            // 主请求失败时直接展示错误；附属数据（评论/修订）失败降级为空（P2 BACKEND-007）
            val postResult = ApiCallHandler.runCatchingDirect {
                api.getPostDetails(service, creatorId, postId)
            }
            val post = postResult.getOrNull()
            if (post == null) {
                // 网络请求失败时尝试从本地收藏回退（FEATURE-002）
                val bookmarkedPost = bookmarkManager
                    .getBookmarkedPost(service, creatorId, postId)
                if (bookmarkedPost != null) {
                    val videoList = extractVideoUrls(bookmarkedPost)
                    _uiState.value = _uiState.value.copy(
                        post = bookmarkedPost,
                        comments = emptyList(),
                        revisions = emptyList(),
                        videoList = videoList,
                        isLoading = false,
                        errorMessage = null,
                        isOfflineMode = true
                    )
                    return@launch
                }
                val error = postResult.exceptionOrNull()?.let { it as? AppError ?: AppError.from(it) }
                    ?: AppError.Unknown()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.toMessage(getApplication())
                )
                return@launch
            }
            val videoList = extractVideoUrls(post)
            memoryCache.put(cacheKeyPost, post)

            // 主帖数据优先渲染，评论/修订并发拉取后填充，缩短首屏等待（感知优化）
            _uiState.value = _uiState.value.copy(
                post = post,
                comments = emptyList(),
                revisions = emptyList(),
                videoList = videoList,
                isLoading = false,
                errorMessage = null,
                isOfflineMode = false
            )

            coroutineScope {
                val commentsDeferred = async {
                    runCatching { api.getPostComments(service, creatorId, postId) }
                        .getOrDefault(emptyList())
                }
                val revisionsDeferred = async {
                    runCatching { api.getPostRevisions(service, creatorId, postId) }
                        .getOrDefault(emptyList())
                }
                val comments = commentsDeferred.await()
                val revisions = revisionsDeferred.await()
                memoryCache.put(cacheKeyComments, comments)
                memoryCache.put(cacheKeyRevisions, revisions)
                _uiState.value = _uiState.value.copy(comments = comments, revisions = revisions)
            }
        }
    }

    fun setCurrentVideoIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentVideoIndex = index)
    }

    fun setBookmarked(isBookmarked: Boolean) {
        _uiState.value = _uiState.value.copy(isBookmarked = isBookmarked)
    }

    private fun extractVideoUrls(post: Post): List<Pair<String, String>> {
        val videoList = mutableListOf<Pair<String, String>>()
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".3gp", ".ts", ".flv", ".wmv", ".ogv", ".m4a")

        fun isVideo(path: String?, name: String?): Boolean {
            val lowerPath = path?.lowercase().orEmpty()
            val lowerName = name?.lowercase().orEmpty()
            return videoExtensions.any { ext ->
                lowerPath.endsWith(ext) || lowerName.endsWith(ext)
            }
        }

        val filePath = post.file?.path
        val fileName = post.file?.name
        if (isVideo(filePath, fileName)) {
            val fullUrl = buildFileUrl(filePath)
            videoList.add(Pair(fullUrl, fileName ?: "video.mp4"))
        }

        val attachments = post.attachments
        if (!attachments.isNullOrEmpty()) {
            for (attachment in attachments) {
                if (isVideo(attachment.path, attachment.name)) {
                    val fullUrl = buildFileUrl(attachment.path)
                    videoList.add(Pair(fullUrl, attachment.name ?: "video.mp4"))
                }
            }
        }

        return videoList
    }

    /**
     * 构造 file.pawchive.pw 直链：对 path 做 URL 编码（保留斜杠），避免空格/特殊字符导致请求失败。
     */
    private fun buildFileUrl(path: String?): String {
        return "https://file.pawchive.pw/data${Uri.encode(path.orEmpty(), "/")}"
    }
}
