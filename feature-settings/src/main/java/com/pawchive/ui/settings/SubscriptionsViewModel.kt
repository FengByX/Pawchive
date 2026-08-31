package com.pawchive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.model.CreatorSubscriptionEntity
import com.pawchive.data.repository.CreatorSubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 订阅管理页 UI 状态（ARCH-FEATURE-003 遗留项）。
 */
data class SubscriptionsUiState(
    val subscriptions: List<CreatorSubscriptionEntity> = emptyList()
)

/**
 * 订阅管理页 ViewModel（ARCH-FEATURE-003 遗留项）。
 *
 * 观察全部订阅列表（Room Flow 自动刷新，订阅时间倒序）；
 * 退订操作委托 [CreatorSubscriptionRepository.unsubscribe]，失败仅记日志不阻塞 UI。
 */
@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptionRepository: CreatorSubscriptionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            subscriptionRepository.observeSubscriptions().collect { list ->
                _uiState.value = SubscriptionsUiState(subscriptions = list)
            }
        }
    }

    fun unsubscribe(service: String, creatorId: String) {
        viewModelScope.launch {
            runCatching { subscriptionRepository.unsubscribe(service, creatorId) }
                .onFailure { it.printStackTrace() }
        }
    }
}
