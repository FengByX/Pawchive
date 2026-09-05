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

    // 缓存加载是否失败（用于区分"无结果"和"加载失败"）
    private var creatorsCacheLoadFailed: Boolean = false

    // 缓存未就绪时用户输入的创作者搜索关键词，缓存加载完后自动重过滤
    private var pendingCreatorQuery: String? = null

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
     * 加载完成后自动重过滤 [pendingCreatorQuery]（若存在）。
     */
    fun loadCreatorsCache() {
        if (_uiState.value.creatorsCacheLoaded) return
        fetchCreatorsCache()
    }

    /**
     * 强制重新拉取创作者缓存（忽略 creatorsCacheLoaded 标记）。
     * 用于下拉刷新或网络恢复后重试。
     */
    fun reloadCreatorsCache() {
        _uiState.value = _uiState.value.copy(creatorsCacheLoaded = false)
        fetchCreatorsCache()
    }

    private fun fetchCreatorsCache() {
        viewModelScope.launch {
            val result = ApiCallHandler.runCatchingDirect { api.getCreators() }
            result.onSuccess { creators ->
                allCreatorsCache = creators
                creatorsCacheLoadFailed = false
            }.onFailure {
                allCreatorsCache = emptyList()
                creatorsCacheLoadFailed = true
            }
            _uiState.value = _uiState.value.copy(creatorsCacheLoaded = true)
            // 加载完成后，若有等待中的查询立即执行
            pendingCreatorQuery?.let { query ->
                pendingCreatorQuery = null
                filterCreatorsLocal(query)
            }
        }
    }

    /**
     * 本地过滤创作者。
     * - 缓存已就绪：立即过滤并返回 true
     * - 缓存未就绪：记住 query 等待缓存加载完自动重过滤，返回 false
     * - 缓存加载失败：显示错误提示，引导用户下拉刷新重试
     */
    fun filterCreatorsLocal(query: String): Boolean {
        if (!_uiState.value.creatorsCacheLoaded) {
            pendingCreatorQuery = query
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            return false
        }
        if (creatorsCacheLoadFailed) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = getApplication<Application>().getString(
                    com.pawchive.common.R.string.creator_cache_load_failed
                )
            )
            return true
        }
        val filtered = allCreatorsCache.filter {
            (it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true))
                    && !blockedCreatorManager.isCreatorBlocked(it.service, it.id)
        }
        applyCreatorSort(filtered)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            emptyHintResId = if (filtered.isEmpty()) com.pawchive.common.R.string.no_creators_found else null
        )
        return true
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
     * 批量屏蔽创作者。逐个调用 blockCreator，全部完成后通知 Fragment 刷新列表。
     * 去重：同一 service+id 只屏蔽一次。
     */
    fun blockCreators(creators: List<Creator>) {
        if (creators.isEmpty()) return
        viewModelScope.launch {
            // 去重：按 service+id 去重
            val unique = creators.distinctBy { "${it.service}|${it.id}" }
            unique.forEach { creator ->
                blockedCreatorManager.blockCreator(creator.service, creator.id)
            }
            // 屏蔽后立即重新过滤帖子结果（已加载的）
            refreshBlockedFilter()
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
