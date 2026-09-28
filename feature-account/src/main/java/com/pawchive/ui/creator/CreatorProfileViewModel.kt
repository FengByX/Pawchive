package com.pawchive.ui.creator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.error.AppError
import com.pawchive.core.api.ApiCallHandler
import com.pawchive.core.api.ApiClient
import com.pawchive.core.model.Announcement
import com.pawchive.core.model.CreatorProfile
import com.pawchive.core.model.Post
import com.pawchive.core.model.SimilarCreator
import com.pawchive.core.store.AppMemoryCache
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.SimilarCreatorsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreatorProfileUiState(
    val name: String = "",
    val posts: List<Post> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val links: List<CreatorProfile> = emptyList(),
    // 相似作者（FEAT-SIMILAR-CREATORS，加载失败降级为空列表不展示）
    val similarCreators: List<SimilarCreator> = emptyList(),
    // 该作者是否在登录账号的云端收藏中（BUG-IMPORT-STATE：网页端收藏/导入的作者主页此前恒显示未收藏）
    val cloudFavorited: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasMore: Boolean = false
)

@HiltViewModel
class CreatorProfileViewModel @Inject constructor(
    application: Application,
    private val memoryCache: AppMemoryCache,
    private val authRepository: AuthRepository,
    private val bookmarkManager: BookmarkManager,
    private val similarCreatorsRepository: SimilarCreatorsRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CreatorProfileUiState())
    val uiState: StateFlow<CreatorProfileUiState> = _uiState.asStateFlow()

    private val api = ApiClient.publicApi

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
                loadExtras(service, creatorId)
                return
            }
        }

        _uiState.value = CreatorProfileUiState(isLoading = true, errorMessage = null)
        loadExtras(service, creatorId)

        viewModelScope.launch(exceptionHandler("loadCreator $service/$creatorId")) {
            runCatching {
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
                    return@runCatching
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
            }.onFailure { err ->
                android.util.Log.e("CreatorProfileVM", "loadCreator $service/$creatorId", err)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = (err as? AppError ?: AppError.from(err)).toMessage(getApplication())
                )
            }
        }
    }

    /**
     * 附加状态：相似作者 + 云端收藏状态。
     *
     * 与主请求并行异步加载，完成后增量更新 UI 状态；均失败降级（不弹错、不阻塞帖子展示）。
     */
    private fun loadExtras(service: String, creatorId: String) {
        viewModelScope.launch { loadSimilarCreators(service, creatorId) }
        viewModelScope.launch { syncCloudFavoriteState(service, creatorId) }
    }

    /** 相似作者：内存缓存优先，网页版 /recommended 抓取失败降级为空列表。 */
    private suspend fun loadSimilarCreators(service: String, creatorId: String) {
        val cacheKey = "creator_recommended:$service|$creatorId"
        val cached: List<SimilarCreator>? = memoryCache.get(cacheKey)
        if (cached != null) {
            _uiState.update { it.copy(similarCreators = cached) }
            return
        }
        val similar = runCatching {
            similarCreatorsRepository.fetchSimilarCreators(service, creatorId)
        }.getOrElse { err ->
            android.util.Log.i("CreatorProfileVM", "similar creators unavailable $service/$creatorId: ${err.message}")
            emptyList()
        }
        if (similar.isNotEmpty()) {
            memoryCache.put(cacheKey, similar)
        }
        _uiState.update { it.copy(similarCreators = similar) }
    }

    /**
     * 云端收藏状态（BUG-IMPORT-STATE）。
     *
     * 作者主页此前只读本地收藏（BookmarkManager）与本地订阅（Room），网页端收藏
     * 或关注列表导入的作者在主页恒显示"未收藏"。这里拉取账号云端收藏创作者，
     * 命中时视为已收藏并回写本地收藏（首页隐藏过滤等本地逻辑随之生效）。
     */
    private suspend fun syncCloudFavoriteState(service: String, creatorId: String) {
        if (!authRepository.isLoggedIn()) return
        val result = runCatching { authRepository.syncFavoriteCreators() }.getOrElse { return }
        result.onSuccess { favorites ->
            val favorited = favorites.any {
                it.service.equals(service, ignoreCase = true) && it.id == creatorId
            }
            if (favorited && !bookmarkManager.isCreatorBookmarked(service, creatorId)) {
                bookmarkManager.bookmarkCreator(service, creatorId)
            }
            _uiState.update { it.copy(cloudFavorited = favorited) }
        }
    }

    /**
     * 在本页取消收藏成功后调用（云端已移除，本地合并状态同步清除），
     * 避免后续 UI 重渲染仍按旧的云端状态显示"已收藏"。
     */
    fun onCreatorUnfavorited() {
        _uiState.update { it.copy(cloudFavorited = false) }
    }

    fun loadMorePosts() {        if (_uiState.value.isLoading) return
        currentOffset += pageSize

        viewModelScope.launch(exceptionHandler("loadMore $currentService/$currentCreatorId")) {
            runCatching {
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
            }.onFailure { err ->
                android.util.Log.e("CreatorProfileVM", "loadMore crashed", err)
                currentOffset -= pageSize
                _uiState.value = _uiState.value.copy(
                    errorMessage = (err as? AppError ?: AppError.from(err)).toMessage(getApplication())
                )
            }
        }
    }

    private fun exceptionHandler(tag: String) = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("CreatorProfileVM", "Unhandled coroutine error [$tag]", throwable)
        val msg = (throwable as? AppError ?: AppError.from(throwable)).toMessage(getApplication())
        val current = _uiState.value
        if (current.errorMessage == null && !current.isLoading) {
            _uiState.value = current.copy(errorMessage = msg)
        }
    }
}
