package com.pawchive.ui.favorites

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.common.R
import com.pawchive.core.error.AppError
import com.pawchive.core.model.FavoriteCreator
import com.pawchive.core.model.FavoritePost
import com.pawchive.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 收藏页帖子排序方式（收藏页与 ViewModel 共享）。
 */
enum class FavoritePostSortOption(@StringRes val displayNameRes: Int) {
    FAV_NEWEST(R.string.sort_fav_newest),
    FAV_OLDEST(R.string.sort_fav_oldest),
    NEWEST_PUBLISHED(R.string.sort_newest_published),
    OLDEST_PUBLISHED(R.string.sort_oldest_published),
    NEWEST_EDITED(R.string.sort_newest_edited),
    OLDEST_EDITED(R.string.sort_oldest_edited)
}

/**
 * 收藏页创作者排序方式（收藏页与 ViewModel 共享）。
 */
enum class FavoriteCreatorSortOption(@StringRes val displayNameRes: Int) {
    FAV_NEWEST(R.string.sort_fav_newest),
    FAV_OLDEST(R.string.sort_fav_oldest),
    NEWEST_UPDATED(R.string.sort_newest_updated),
    OLDEST_UPDATED(R.string.sort_oldest_updated),
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc)
}

/**
 * 收藏页 UI 状态（ARCH-006 / FRONTEND-008）。
 *
 * - [posts] / [creators]：当前 Tab 的列表数据（已排序）
 * - [currentTab]：当前 Tab（0 = 帖子，1 = 创作者）
 * - [isLoading]：网络请求中（ProgressBar 显示）
 * - [errorMessage]：友好错误文案（失败时由 Fragment 以 Toast 展示）
 * - [toastMessage]：单次成功反馈文案（如"已取消收藏"，Fragment 展示后调用 [AccountFavoritesViewModel.consumeToast]）
 * - [hasMorePosts]：帖子列表是否还有下一页
 * - [emptyVisible]：当前 Tab 是否为空（由 ViewModel 在请求成功后明确设置）
 */
data class AccountFavoritesUiState(
    val posts: List<FavoritePost> = emptyList(),
    val creators: List<FavoriteCreator> = emptyList(),
    val currentTab: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val hasMorePosts: Boolean = false,
    val emptyVisible: Boolean = false
)

/**
 * 收藏页 ViewModel（ARCH-006 / FRONTEND-008）。
 *
 * 职责划分：
 * - ViewModel：云端收藏同步（帖子/创作者）、分页、排序、移除收藏、错误与反馈转换
 * - Fragment：RecyclerView 渲染、Tab 切换、排序对话框、登录态检查、Toast/ProgressBar 展示
 *
 * 服务端 syncFavoritePosts 固定每页返回 [pageSize] 条（仅支持 offset），
 * 使用"本次返回条数 >= pageSize"判断是否还有下一页（修复最后一页恰好满页的误判）。
 */
@HiltViewModel
class AccountFavoritesViewModel @Inject constructor(
    application: Application,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val pageSize = 50

    private val loadedPosts = mutableListOf<FavoritePost>()
    private val loadedCreators = mutableListOf<FavoriteCreator>()
    private var currentOffset = 0
    private var currentPostSort = FavoritePostSortOption.NEWEST_EDITED
    private var currentCreatorSort = FavoriteCreatorSortOption.NEWEST_UPDATED

    private val _uiState = MutableStateFlow(AccountFavoritesUiState())
    val uiState: StateFlow<AccountFavoritesUiState> = _uiState.asStateFlow()

    /**
     * 切换 Tab（0 = 帖子，1 = 创作者）。首次进入该 Tab 时发起加载，已有数据仅重排。
     */
    fun setTab(tab: Int) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
        if (tab == 0) {
            if (loadedPosts.isEmpty()) {
                loadPosts()
            } else {
                applyPostSort()
            }
        } else {
            if (loadedCreators.isEmpty()) {
                loadCreators()
            } else {
                applyCreatorSort()
            }
        }
    }

    /**
     * 下拉刷新：重置 offset 并重新拉取当前 Tab 数据。
     */
    fun refresh() {
        if (_uiState.value.currentTab == 0) {
            loadPosts(isRefresh = true)
        } else {
            loadCreators(isRefresh = true)
        }
    }

    fun loadPosts(isRefresh: Boolean = false) {
        if (_uiState.value.isLoading) return
        if (!isRefresh && loadedPosts.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        currentOffset = 0

        viewModelScope.launch {
            val result = authRepository.syncFavoritePosts()
            result.onSuccess { posts ->
                loadedPosts.clear()
                loadedPosts.addAll(posts)
                applyPostSort()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasMorePosts = posts.size >= pageSize,
                    emptyVisible = posts.isEmpty()
                )
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = friendlyMessage(error),
                        emptyVisible = false
                    )
                }
            }
        }
    }

    fun loadMorePosts() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        currentOffset += pageSize

        viewModelScope.launch {
            val result = authRepository.syncFavoritePosts(currentOffset)
            result.onSuccess { newPosts ->
                loadedPosts.addAll(newPosts)
                applyPostSort()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasMorePosts = newPosts.size >= pageSize
                )
            }.onFailure { error ->
                currentOffset -= pageSize
                if (error !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = friendlyMessage(error)
                    )
                }
            }
        }
    }

    fun loadCreators(isRefresh: Boolean = false) {
        if (_uiState.value.isLoading) return
        if (!isRefresh && loadedCreators.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val result = authRepository.syncFavoriteCreators()
            result.onSuccess { creators ->
                loadedCreators.clear()
                loadedCreators.addAll(creators)
                applyCreatorSort()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    emptyVisible = creators.isEmpty()
                )
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = friendlyMessage(error),
                        emptyVisible = false
                    )
                }
            }
        }
    }

    /**
     * 移除收藏：成功后从本地列表移除并更新空状态；失败提示友好错误。
     */
    fun removePost(post: FavoritePost) {
        viewModelScope.launch {
            val result = authRepository.removePostFromFavorites(post.service, post.user, post.id)
            result.onSuccess {
                loadedPosts.remove(post)
                applyPostSort()
                _uiState.value = _uiState.value.copy(
                    toastMessage = getApplication<Application>().getString(R.string.bookmark_removed),
                    emptyVisible = loadedPosts.isEmpty()
                )
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = friendlyMessage(error)
                    )
                }
            }
        }
    }

    fun setPostSort(sort: FavoritePostSortOption) {
        currentPostSort = sort
        applyPostSort()
    }

    fun setCreatorSort(sort: FavoriteCreatorSortOption) {
        currentCreatorSort = sort
        applyCreatorSort()
    }

    /**
     * 消费单次成功反馈（Fragment Toast 展示后调用）。
     */
    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    /**
     * 清除错误状态（Fragment Toast 展示后调用）。
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun applyPostSort() {
        if (loadedPosts.isEmpty()) {
            _uiState.value = _uiState.value.copy(posts = emptyList())
            return
        }
        val sorted = when (currentPostSort) {
            FavoritePostSortOption.FAV_NEWEST -> loadedPosts.sortedByDescending { it.favedSeq ?: 0 }
            FavoritePostSortOption.FAV_OLDEST -> loadedPosts.sortedBy { it.favedSeq ?: 0 }
            FavoritePostSortOption.NEWEST_PUBLISHED -> loadedPosts.sortedByDescending { it.published }
            FavoritePostSortOption.OLDEST_PUBLISHED -> loadedPosts.sortedBy { it.published }
            FavoritePostSortOption.NEWEST_EDITED -> loadedPosts.sortedByDescending { it.edited ?: it.published }
            FavoritePostSortOption.OLDEST_EDITED -> loadedPosts.sortedBy { it.edited ?: it.published }
        }
        _uiState.value = _uiState.value.copy(posts = sorted)
    }

    private fun applyCreatorSort() {
        if (loadedCreators.isEmpty()) {
            _uiState.value = _uiState.value.copy(creators = emptyList())
            return
        }
        val sorted = when (currentCreatorSort) {
            FavoriteCreatorSortOption.FAV_NEWEST -> loadedCreators.sortedByDescending { it.favedSeq ?: 0 }
            FavoriteCreatorSortOption.FAV_OLDEST -> loadedCreators.sortedBy { it.favedSeq ?: 0 }
            FavoriteCreatorSortOption.NEWEST_UPDATED -> loadedCreators.sortedByDescending { it.updated ?: it.indexed ?: "" }
            FavoriteCreatorSortOption.OLDEST_UPDATED -> loadedCreators.sortedBy { it.updated ?: it.indexed ?: "" }
            FavoriteCreatorSortOption.NAME_ASC -> loadedCreators.sortedBy { it.name.lowercase() }
            FavoriteCreatorSortOption.NAME_DESC -> loadedCreators.sortedByDescending { it.name.lowercase() }
        }
        _uiState.value = _uiState.value.copy(creators = sorted)
    }

    private fun friendlyMessage(error: Throwable): String {
        return (error as? AppError ?: AppError.from(error)).toMessage(getApplication())
    }
}
