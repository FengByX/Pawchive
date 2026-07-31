package com.pawchive.ui.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class CreatorProfileViewModel : ViewModel() {

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
            try {
                val posts = api.getCreatorPosts(service, creatorId, offset = 0)
                memoryCache.put(cacheKeyPosts, posts)

                val announcements = try {
                    api.getCreatorAnnouncements(service, creatorId)
                } catch (_: Exception) {
                    emptyList()
                }
                memoryCache.put(cacheKeyAnnouncements, announcements)

                val links = try {
                    api.getCreatorLinks(service, creatorId)
                } catch (_: Exception) {
                    emptyList()
                }
                memoryCache.put(cacheKeyLinks, links)

                val name = try {
                    api.getCreatorProfile(service, creatorId).name
                } catch (_: Exception) {
                    creatorId
                }
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
            } catch (e: Exception) {
                _uiState.value = CreatorProfileUiState(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun loadMorePosts() {
        if (_uiState.value.isLoading) return
        currentOffset += pageSize

        viewModelScope.launch {
            try {
                val morePosts = api.getCreatorPosts(currentService, currentCreatorId, offset = currentOffset)
                val allPosts = _uiState.value.posts + morePosts

                // 更新缓存
                val cacheKey = "creator_posts:$currentService|$currentCreatorId|0"
                memoryCache.put(cacheKey, allPosts)

                _uiState.value = _uiState.value.copy(
                    posts = allPosts,
                    hasMore = morePosts.size >= pageSize
                )
            } catch (e: Exception) {
                currentOffset -= pageSize
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }
}
