package com.pawchive.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.error.AppError
import com.pawchive.core.api.ApiCallHandler
import com.pawchive.core.api.ApiClient
import com.pawchive.core.model.Creator
import com.pawchive.core.model.Post
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.OfflineArchiveRepository
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import javax.inject.Inject

/**
 * 帖子排序方式（搜索页与 ViewModel 共享）。
 */
enum class PostSortOption(@StringRes val displayNameRes: Int) {
    RELEVANCE(com.pawchive.common.R.string.sort_relevance),
    NEWEST_PUBLISHED(com.pawchive.common.R.string.sort_newest_published),
    OLDEST_PUBLISHED(com.pawchive.common.R.string.sort_oldest_published),
    NEWEST_EDITED(com.pawchive.common.R.string.sort_newest_edited),
    OLDEST_EDITED(com.pawchive.common.R.string.sort_oldest_edited)
}

/**
 * 创作者排序方式（搜索页与 ViewModel 共享）。
 */
enum class CreatorSortOption(@StringRes val displayNameRes: Int) {
    NAME_ASC(com.pawchive.common.R.string.sort_name_asc),
    NAME_DESC(com.pawchive.common.R.string.sort_name_desc)
}

/**
 * 搜索页 UI 状态（P2 FRONTEND-008）。
 *
 * - [postResults] / [creatorResults]：当前搜索结果（已应用屏蔽过滤与排序）
 * - [creatorsCacheLoaded]：创作者全量缓存是否就绪，未就绪时本地过滤需等待
 * - [isLoading]：网络请求中（用于 ProgressBar 显示）
 * - [errorMessage]：友好错误文案，非 null 时由 Fragment 决定如何展示
 * - [emptyHintResId]：空结果时的提示文案资源 id（如"未找到帖子"/"输入关键词搜索"）
 */
data class SearchUiState(
    val postResults: List<Post> = emptyList(),
    val creatorResults: List<Creator> = emptyList(),
    val creatorsCacheLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val emptyHintResId: Int? = null,
    val selectedService: String? = null,
    val hasAttachmentOnly: Boolean = false,
    val bookmarkedOnly: Boolean = false,
    // ARCH-FEATURE-001：离线全文搜索模式（搜索收藏内容本地索引）
    val offlineMode: Boolean = false
)

/**
 * 搜索页 ViewModel（P2 FRONTEND-008）。
 *
 * 职责划分：
 * - ViewModel：网络请求、内存缓存、屏蔽过滤、排序、错误转换
 * - Fragment：Tab 切换、SearchView 输入、历史记录、Toast/ProgressBar 渲染、对话框
 *
 * 创作者全量数据一次性加载到内存（[allCreatorsCache]），后续本地过滤；
 * 帖子搜索每次发请求，支持取消上一次未完成请求（[searchJob]）。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    application: Application,
    private val blockedCreatorManager: BlockedCreatorManager,
    private val bookmarkManager: BookmarkManager,
    private val offlineArchiveRepository: OfflineArchiveRepository
) : AndroidViewModel(application) {

    private val api = ApiClient.publicApi
    private val gson = Gson()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // 创作者全量缓存：一次性加载，后续本地过滤使用
    private var allCreatorsCache: List<Creator> = emptyList()

    // 当前搜索任务：新搜索发起时取消上一次未完成的请求
    private var searchJob: kotlinx.coroutines.Job? = null

    // 当前排序方式（由 Fragment 设置，ViewModel 在过滤/排序时使用）
    private var currentPostSort: PostSortOption = PostSortOption.RELEVANCE
    private var currentCreatorSort: CreatorSortOption = CreatorSortOption.NAME_ASC

    // 最近一次帖子搜索的原始结果（未过滤、未排序），用于切换排序时重新计算
    private var rawPostResults: List<Post> = emptyList()

    fun setPostSort(sort: PostSortOption) {
        currentPostSort = sort
        applyPostSort()
    }

    // ===== 搜索筛选（FEATURE-004）=====

    fun setServiceFilter(service: String?) {
        _uiState.value = _uiState.value.copy(selectedService = service)
        applyPostSort()
    }

    fun setHasAttachmentOnly(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hasAttachmentOnly = enabled)
        applyPostSort()
    }

    fun setBookmarkedOnly(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(bookmarkedOnly = enabled)
        applyPostSort()
    }

    fun resetFilters() {
        _uiState.value = _uiState.value.copy(
            selectedService = null,
            hasAttachmentOnly = false,
            bookmarkedOnly = false
        )
        applyPostSort()
    }

    fun setCreatorSort(sort: CreatorSortOption) {
        currentCreatorSort = sort
        applyCreatorSort()
    }

    /**
     * 加载创作者全量缓存。仅加载一次，后续重复调用为空操作。
     * 加载完成后若 [pendingQuery] 非空，自动触发本地过滤。
     */
    fun loadCreatorsCache(pendingQuery: String? = null) {
        if (_uiState.value.creatorsCacheLoaded) {
            pendingQuery?.let { filterCreatorsLocal(it) }
            return
        }
        viewModelScope.launch {
            // 创作者列表是辅助数据，失败时降级为空列表，不阻塞主流程
            val result = ApiCallHandler.runCatchingDirect { api.getCreators() }
            result.onSuccess { creators ->
                allCreatorsCache = creators
            }.onFailure {
                // 静默降级：创作者搜索不可用不影响帖子搜索
            }
            _uiState.value = _uiState.value.copy(creatorsCacheLoaded = true)
            pendingQuery?.let { filterCreatorsLocal(it) }
        }
    }

    /**
     * 搜索帖子。取消上一次未完成的搜索请求。
     */
    fun searchPosts(query: String) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, emptyHintResId = null)
        searchJob = viewModelScope.launch {
            val result = ApiCallHandler.runCatchingDirect { api.getRecentPosts(query = query) }
            result.onSuccess { posts ->
                rawPostResults = posts
                applyPostSort()
                if (posts.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        emptyHintResId = com.pawchive.common.R.string.no_posts_found
                    )
                }
            }.onFailure { error ->
                val message = (error as? AppError ?: AppError.from(error)).toMessage(getApplication())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = message,
                    emptyHintResId = com.pawchive.common.R.string.search_initial_hint
                )
            }
        }
    }

    /**
     * 离线全文搜索（ARCH-FEATURE-001）：搜索收藏内容本地索引（Room FTS），
     * 无需网络。结果解析 postJson 还原为 Post 后复用既有排序/屏蔽/筛选管线。
     */
    fun searchOffline(query: String) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, emptyHintResId = null)
        searchJob = viewModelScope.launch {
            runCatching { offlineArchiveRepository.search(query) }
                .onSuccess { entries ->
                    rawPostResults = entries.mapNotNull { entry ->
                        runCatching { gson.fromJson(entry.postJson, Post::class.java) }.getOrNull()
                    }
                    applyPostSort()
                    if (rawPostResults.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            emptyHintResId = com.pawchive.common.R.string.no_posts_found
                        )
                    }
                }
                .onFailure { error ->
                    val message = (error as? AppError ?: AppError.from(error)).toMessage(getApplication())
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = message,
                        emptyHintResId = com.pawchive.common.R.string.search_initial_hint
                    )
                }
        }
    }

    /**
     * 切换离线全文搜索模式（ARCH-FEATURE-001）。仅记录状态，
     * 重新搜索由 Fragment 在 chip 变化后按当前查询触发。
     */
    fun setOfflineMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(offlineMode = enabled)
    }

    /**
     * 本地过滤创作者。若缓存未就绪，标记为待处理（返回 false 由 Fragment 决定是否等待）。
     * 返回 true 表示已处理（无论是否有结果）。
     */
    fun filterCreatorsLocal(query: String): Boolean {
        if (!_uiState.value.creatorsCacheLoaded) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            return false
        }
        val filtered = allCreatorsCache.filter {
            (it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true))
                    && !blockedCreatorManager.isCreatorBlocked(it.service, it.id)
        }
        applyCreatorSort(filtered)
        if (filtered.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                emptyHintResId = com.pawchive.common.R.string.no_creators_found
            )
        }
        return true
    }

    /**
     * 屏蔽状态变化后重新过滤已加载的帖子数据（无需重新请求网络）。
     */
    fun refreshBlockedFilter() {
        if (rawPostResults.isNotEmpty()) {
            applyPostSort()
        }
        if (allCreatorsCache.isNotEmpty()) {
            // 创作者列表无需重算，Fragment 会在 Tab 切换时重新过滤
        }
    }

    /**
     * 重置为初始状态（清空搜索结果）。
     */
    fun resetResults() {
        rawPostResults = emptyList()
        _uiState.value = _uiState.value.copy(
            postResults = emptyList(),
            creatorResults = emptyList(),
            isLoading = false,
            errorMessage = null,
            emptyHintResId = com.pawchive.common.R.string.search_initial_hint
        )
    }

    /**
     * 清除错误状态（Fragment 显示 Toast 后调用）。
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun applyPostSort() {
        val state = _uiState.value
        val sorted = when (currentPostSort) {
            PostSortOption.RELEVANCE -> rawPostResults
            PostSortOption.NEWEST_PUBLISHED -> rawPostResults.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> rawPostResults.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> rawPostResults.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> rawPostResults.sortedBy { it.edited ?: it.published }
        }
        val filtered = sorted
            // 屏蔽创作者过滤
            .filter { !blockedCreatorManager.isCreatorBlocked(it.service, it.user) }
            // 来源筛选（FEATURE-004）
            .filter { state.selectedService == null || it.service == state.selectedService }
            // 仅含附件筛选（FEATURE-004）
            .filter { !state.hasAttachmentOnly || (!it.attachments.isNullOrEmpty() || it.file != null) }
            // 仅看收藏筛选（FEATURE-004）
            .filter { !state.bookmarkedOnly || bookmarkManager.isPostBookmarked(it.service, it.user, it.id) }
        _uiState.value = _uiState.value.copy(postResults = filtered, isLoading = false)
    }

    private fun applyCreatorSort(input: List<Creator>? = null) {
        val source = input ?: _uiState.value.creatorResults
        val sorted = when (currentCreatorSort) {
            CreatorSortOption.NAME_ASC -> source.sortedBy { it.name.lowercase() }
            CreatorSortOption.NAME_DESC -> source.sortedByDescending { it.name.lowercase() }
        }
        _uiState.value = _uiState.value.copy(creatorResults = sorted, isLoading = false)
    }
}
