package com.pawchive.ui.home

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.common.R
import com.pawchive.core.api.ApiCallHandler
import com.pawchive.core.api.ApiClient
import com.pawchive.core.error.AppError
import com.pawchive.core.model.Post
import com.pawchive.core.store.SettingsManager
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页帖子排序方式（首页与 ViewModel 共享）。
 */
enum class HomePostSortOption(@StringRes val displayNameRes: Int) {
    NEWEST_PUBLISHED(R.string.sort_newest_published),
    OLDEST_PUBLISHED(R.string.sort_oldest_published),
    NEWEST_EDITED(R.string.sort_newest_edited),
    OLDEST_EDITED(R.string.sort_oldest_edited)
}

/**
 * 首页 UI 状态（ARCH-006 / FRONTEND-008）。
 *
 * - [posts]：当前展示的帖子（已按 [HomePostSortOption] 排序、已过滤被屏蔽创作者）
 * - [isLoading]：网络请求中（ProgressBar 显示；下拉刷新由 Fragment 的 swipeRefresh 指示）
 * - [errorMessage]：友好错误文案，非 null 时由 Fragment 决定展示方式（错误页 / Toast）
 * - [hasMore]：是否还有下一页（底部"加载更多" footer 显示）
 * - [emptyHintResId]：空列表时的提示文案资源 id（网络模式"暂无帖子"，收藏模式"暂无收藏"）
 * - [showBookmarksOnly]：是否仅展示本地收藏（收藏 Tab 复用首页布局）
 */
data class HomeUiState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasMore: Boolean = false,
    val emptyHintResId: Int = R.string.no_posts_found,
    val showBookmarksOnly: Boolean = false
)

/**
 * 首页 ViewModel（ARCH-006 / FRONTEND-008）。
 *
 * 职责划分：
 * - ViewModel：网络请求、分页、屏蔽过滤、排序、收藏模式加载、错误转换
 * - Fragment：RecyclerView 渲染、下拉刷新指示、排序对话框、错误页/Toast 展示、创作者名预取
 *
 * 服务端 /posts 接口固定每页返回 50 条（仅支持 offset，无 limit 参数），
 * 因此 [pageSize] 与服务端保持一致，用于判断是否还有下一页。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val blockedCreatorManager: BlockedCreatorManager,
    private val bookmarkManager: BookmarkManager,
    private val settingsManager: SettingsManager,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val api = ApiClient.publicApi
    private val pageSize = 50

    private val loadedPosts = mutableListOf<Post>()
    private var currentOffset = 0
    private var currentSort = HomePostSortOption.NEWEST_PUBLISHED
    private var showBookmarksOnly = false
    private var initialized = false

    /**
     * 云端收藏创作者缓存（ARCH-FEATURE-006 联动遗留项）。
     * 首页"隐藏已收藏作者的帖子"过滤时与本地收藏合并（覆盖其他设备收藏、本地未同步的场景）。
     * 拉取失败保留旧值，仅静默降级为本地过滤，不影响浏览。
     */
    private var cloudFavoriteCreators: Set<Pair<String, String>> = emptySet()

    /**
     * 异步拉取云端收藏创作者并合并进过滤集合（仅登录态生效）。
     * 成功且集合变化时重排列表；失败静默。init/refresh/返回首页时调用。
     */
    private fun refreshCloudFavoriteCreators() {
        viewModelScope.launch {
            if (!authRepository.isLoggedIn()) return@launch
            val result = authRepository.syncFavoriteCreators()
            if (result.isSuccess) {
                val cloud = result.getOrNull()
                    ?.map { it.service to it.id }
                    ?.toSet() ?: emptySet()
                if (cloud != cloudFavoriteCreators) {
                    cloudFavoriteCreators = cloud
                    applySort(emit = true)
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * 初始化（幂等）：Fragment 在 onViewCreated 调用一次，
     * 传入是否仅展示本地收藏（showBookmarksOnly）。旋转重建时 ViewModel 存活，不会重复加载。
     */
    fun init(showBookmarksOnly: Boolean) {
        this.showBookmarksOnly = showBookmarksOnly
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(
            showBookmarksOnly = showBookmarksOnly,
            emptyHintResId = if (showBookmarksOnly) R.string.no_favorites else R.string.no_posts_found
        )
        if (showBookmarksOnly) {
            loadBookmarks()
        } else {
            loadInitial()
            // ARCH-FEATURE-006：预取云端收藏创作者，合并进首页过滤
            refreshCloudFavoriteCreators()
        }
    }

    fun loadInitial() {
        if (loadedPosts.isNotEmpty()) return
        fetchPosts(reset = true)
    }

    fun refresh() {
        fetchPosts(reset = true, cacheControl = "no-cache")
        refreshCloudFavoriteCreators()
    }

    fun loadMore() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        currentOffset += pageSize

        viewModelScope.launch {
            // 使用统一错误处理：失败时回滚 offset 并通过 AppError 映射友好文案（P2 BACKEND-007）
            val result = ApiCallHandler.runCatchingDirect {
                api.getRecentPosts(offset = currentOffset)
            }
            result.onSuccess { morePosts ->
                loadedPosts.addAll(morePosts)
                applySort(emit = false)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasMore = morePosts.size >= pageSize
                )
            }.onFailure { error ->
                currentOffset -= pageSize
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = (error as? AppError ?: AppError.from(error))
                        .toMessage(getApplication())
                )
            }
        }
    }

    fun setSort(sort: HomePostSortOption) {
        currentSort = sort
        applySort()
    }

    /**
     * 屏蔽状态变化后重新过滤已加载的数据（无需重新请求网络）。
     * 同时刷新云端收藏缓存（登录/收藏状态可能变化，ARCH-FEATURE-006）。
     */
    fun refreshBlockedFilter() {
        refreshCloudFavoriteCreators()
        if (loadedPosts.isNotEmpty()) {
            applySort()
        }
    }

    /**
     * 批量屏蔽创作者（FEATURE 首页批量屏蔽）。
     * 逐个写入屏蔽列表，随后重新过滤已加载数据，屏蔽的帖子立即从首页消失。
     */
    fun blockCreators(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            pairs.forEach { (service, creatorId) ->
                blockedCreatorManager.blockCreator(service, creatorId)
            }
            refreshBlockedFilter()
        }
    }

    /**
     * 收藏状态变化（PostAdapter 回调）：收藏模式下重新加载本地收藏。
     */
    fun onBookmarkChanged() {
        if (showBookmarksOnly) {
            loadBookmarks()
        }
    }

    /**
     * 清除错误状态（Fragment 展示错误页 / Toast 后调用）。
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun fetchPosts(reset: Boolean, cacheControl: String? = null) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        if (reset) {
            currentOffset = 0
        }

        viewModelScope.launch {
            val result = ApiCallHandler.runCatchingDirect {
                api.getRecentPosts(offset = currentOffset, cacheControl = cacheControl)
            }
            result.onSuccess { posts ->
                if (reset) {
                    loadedPosts.clear()
                }
                loadedPosts.addAll(posts)
                applySort(emit = false)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasMore = posts.size >= pageSize
                )
            }.onFailure { error ->
                if (reset) {
                    currentOffset = 0
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = (error as? AppError ?: AppError.from(error))
                        .toMessage(getApplication())
                )
            }
        }
    }

    /**
     * 收藏模式：从本地 BookmarkManager 读取并排序（无网络请求）。
     */
    fun loadBookmarks() {
        val posts = bookmarkManager.getBookmarkedPosts()
        _uiState.value = _uiState.value.copy(
            posts = sortPosts(posts),
            isLoading = false,
            hasMore = false,
            errorMessage = null,
            emptyHintResId = R.string.no_favorites
        )
    }

    private fun applySort(emit: Boolean = true) {
        val source = if (showBookmarksOnly) {
            bookmarkManager.getBookmarkedPosts()
        } else {
            loadedPosts.toList()
        }
        val sorted = sortPosts(source)
        if (emit) {
            _uiState.value = _uiState.value.copy(posts = sorted, isLoading = false)
        } else {
            _uiState.value = _uiState.value.copy(posts = sorted)
        }
    }

    /**
     * 排序 + 过滤（收藏模式不应用过滤，保持原行为）。
     * - 屏蔽创作者过滤（既有行为）
     * - ARCH-FEATURE-006：开启"隐藏已收藏作者的帖子"时，过滤掉已收藏创作者
     *   （本地收藏 ∪ 云端收藏，覆盖其他设备收藏/本地未同步场景）
     * - FEATURE：开启"同作者仅显示一条"时，同一创作者只保留最新的一条
     */
    private fun sortPosts(posts: List<Post>): List<Post> {
        val sorted = when (currentSort) {
            HomePostSortOption.NEWEST_PUBLISHED -> posts.sortedByDescending { it.published }
            HomePostSortOption.OLDEST_PUBLISHED -> posts.sortedBy { it.published }
            HomePostSortOption.NEWEST_EDITED -> posts.sortedByDescending { it.edited ?: it.published }
            HomePostSortOption.OLDEST_EDITED -> posts.sortedBy { it.edited ?: it.published }
        }
        if (showBookmarksOnly) return sorted

        val hideBookmarked = settingsManager.isHideBookmarkedCreatorsEnabled()
        val bookmarkedCreators =
            if (hideBookmarked) bookmarkManager.getBookmarkedCreators() + cloudFavoriteCreators
            else emptySet()

        var filtered = sorted.filter { post ->
            !blockedCreatorManager.isCreatorBlocked(post.service, post.user) &&
                (post.service to post.user) !in bookmarkedCreators
        }

        // 同作者仅显示一条：按排序结果保留每个创作者最新的一条
        if (settingsManager.isDedupeByCreatorEnabled()) {
            val seen = HashSet<Pair<String, String>>()
            filtered = filtered.filter { post ->
                val key = post.service to post.user
                if (key in seen) false else { seen.add(key); true }
            }
        }
        return filtered
    }
}
