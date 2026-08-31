package com.pawchive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.model.OfflineArchiveEntity
import com.pawchive.data.repository.OfflineArchiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 离线归档管理页 UI 状态（ARCH-FEATURE-001 遗留项）。
 */
data class OfflineArchivesUiState(
    val archives: List<OfflineArchiveEntity> = emptyList(),
    val isSearching: Boolean = false
)

/**
 * 离线归档管理页 ViewModel（ARCH-FEATURE-001 遗留项）。
 *
 * - 空查询：订阅 Room Flow（收藏/删除自动刷新）
 * - 输入查询：走 FTS 全文搜索（CJK bigram，见 OfflineArchiveRepository.search）
 * - 删除单条 / 清空全部仅移除离线副本，不影响收藏主数据
 *   （与 BookmarkManager 的收藏/取消收藏保持双向同步）
 */
@HiltViewModel
class OfflineArchivesViewModel @Inject constructor(
    private val archiveRepository: OfflineArchiveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineArchivesUiState())
    val uiState: StateFlow<OfflineArchivesUiState> = _uiState.asStateFlow()

    private val query = MutableStateFlow("")

    init {
        // 空查询时订阅全量 Flow；查询非空时以搜索结果覆盖展示
        viewModelScope.launch {
            archiveRepository.observeAll().collect { list ->
                if (query.value.isBlank()) {
                    _uiState.value = OfflineArchivesUiState(archives = list)
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            val list = if (text.isBlank()) {
                archiveRepository.observeAll().first()
            } else {
                archiveRepository.search(text.trim())
            }
            _uiState.value = OfflineArchivesUiState(archives = list)
        }
    }

    /** 删除单条离线归档（不影响收藏）。 */
    fun remove(service: String, creatorId: String, postId: String) {
        viewModelScope.launch {
            runCatching { archiveRepository.remove(service, creatorId, postId) }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 清空全部离线归档（不影响收藏）。 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { archiveRepository.clearAll() }
                .onFailure { it.printStackTrace() }
        }
    }
}
