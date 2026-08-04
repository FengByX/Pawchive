package com.pawchive.ui.creator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.data.AppError
import com.pawchive.data.api.ApiCallHandler
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Announcement
import com.pawchive.data.model.CreatorProfile
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AppMemoryCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreatorProfileUiState(
    val name: String = "",
    val posts: List<Post> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val links: List<CreatorProfile> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasMore: Boolean = false
)

class CreatorProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CreatorProfileUiState())
    val uiState: StateFlow<CreatorProfileUiState> = _uiState.asStateFlow()

    private val api = ApiClient.publicApi
    private val memoryCache = AppMemoryCache.getInstance()

    private var currentService = ""
    private var currentCreatorId = ""
    private var currentOffset = 0
    private val pageSize = 50

    /**
     * 加载创作者详情。优先读取内存缓存，无缓存再请求网络。
     * @param forceRefresh 为 true 时跳过缓存直接请求
     */
    fun loadCreator(service: String, creatorId: String, forceRefresh: Boolean = false) {
        currentService = service
        currentCreatorId = creatorId
        currentOffset = 0

        val cacheKeyProfile = "creator_profile:$service|$creatorId"
        val cacheKeyPosts = "creator_posts:$service|$creatorId|0"
        val cacheKeyAnnouncements = "creator_announcements:$service|$creatorId"
        val cacheKeyLinks = "creator_links:$service|$creatorId"

        if (!forceRefresh) {
            val cachedName: String? = memoryCache.get(cacheKeyProfile)
            val cachedPosts: List<Post>? = memoryCache.get(cacheKeyPosts)

            if (cachedPosts != null) {
                val cachedAnnouncements: List<Announcement>? = memoryCache.get(cacheKeyAnnouncements)
                val cachedLinks: List<CreatorProfile>? = memoryCache.get(cacheKeyLinks)

                _uiState.value = CreatorProfileUiState(
                    name = cachedName ?: creatorId,
                    posts = cachedPosts,
                    announcements = cachedAnnouncements ?: emptyList(),
                    links = cachedLinks ?: emptyList(),
                    isLoading = false,
                    errorMessage = null,
                    hasMore = cachedPosts.size >= pageSize
                )
                return
            }
        }

        _uiState.value = CreatorProfileUiState(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            // 主请求失败时直接展示错误；附属数据（公告/链接/昵称）失败降级为空（P2 BACKEND-007）
            val postsResult = ApiCallHandler.runCatchingDirect {
                api.getCreatorPosts(service, creatorId, offset = 0)
            }
            val posts = postsResult.getOrNull()
            if (posts == null) {
                val error = postsResult.exceptionOrNull()?.let { it as? AppError ?: AppError.from(it) }
                    ?: AppError.Unknown()
                _uiState.value = CreatorProfileUiState(
                    isLoading = false,
                    errorMessage = error.toMessage(getApplication())
                )
                return@launch
            }
            memoryCache.put(cacheKeyPosts, posts)

            // 附属数据：失败不影响主流程，降级为空列表/使用 creatorId 作为名称
            val announcements = runCatching { api.getCreatorAnnouncements(service, creatorId) }
                .getOrDefault(emptyList())
            memoryCache.put(cacheKeyAnnouncements, announcements)

            val links = runCatching { api.getCreatorLinks(service, creatorId) }
                .getOrDefault(emptyList())
            memoryCache.put(cacheKeyLinks, links)

            val name = runCatching { api.getCreatorProfile(service, creatorId).name }
                .getOrDefault(creatorId)
            memoryCache.put(cacheKeyProfile, name)

            _uiState.value = CreatorProfileUiState(
                name = name,
                posts = posts,
                announcements = announcements,
                links = links,
                isLoading = false,
                errorMessage = null,
                hasMore = posts.size >= pageSize
            )
        }
    }

    fun loadMorePosts() {
        if (_uiState.value.isLoading) return
        currentOffset += pageSize

        viewModelScope.launch {
            val result = ApiCallHandler.runCatchingDirect {
                api.getCreatorPosts(currentService, currentCreatorId, offset = currentOffset)
            }
            result.onSuccess { morePosts ->
                val allPosts = _uiState.value.posts + morePosts
                val cacheKey = "creator_posts:$currentService|$currentCreatorId|0"
                memoryCache.put(cacheKey, allPosts)
                _uiState.value = _uiState.value.copy(
                    posts = allPosts,
                    hasMore = morePosts.size >= pageSize
                )
            }.onFailure { error ->
                currentOffset -= pageSize
                _uiState.value = _uiState.value.copy(
                    errorMessage = (error as? AppError ?: AppError.from(error)).toMessage(getApplication())
                )
            }
        }
    }
}
