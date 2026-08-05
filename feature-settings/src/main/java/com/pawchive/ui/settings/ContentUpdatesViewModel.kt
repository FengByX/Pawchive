package com.pawchive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.data.repository.ContentUpdateWithCreator
import com.pawchive.data.repository.CreatorSubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 内容更新页 UI 状态（ARCH-FEATURE-003）。
 */
data class ContentUpdatesUiState(
    val updates: List<ContentUpdateWithCreator> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * 内容更新页 ViewModel（ARCH-FEATURE-003）。
 *
 * 观察更新列表（Room Flow 自动刷新）；标记单条/全部已读失败仅记日志，不阻塞 UI。
 */
@HiltViewModel
class ContentUpdatesViewModel @Inject constructor(
    private val subscriptionRepository: CreatorSubscriptionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContentUpdatesUiState())
    val uiState: StateFlow<ContentUpdatesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            subscriptionRepository.observeUpdates().collect { updates ->
                _uiState.value = ContentUpdatesUiState(updates = updates, isLoading = false)
            }
        }
    }

    fun markRead(id: Long) {
        viewModelScope.launch {
            runCatching { subscriptionRepository.markRead(id) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            runCatching { subscriptionRepository.markAllRead() }
                .onFailure { it.printStackTrace() }
        }
    }
}
