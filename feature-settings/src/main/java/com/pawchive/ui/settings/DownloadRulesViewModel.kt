package com.pawchive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.DownloadRuleFileType
import com.pawchive.data.repository.DownloadRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 下载规则管理页 UI 状态（ARCH-FEATURE-002）。
 */
data class DownloadRulesUiState(
    val rules: List<DownloadRuleEntity> = emptyList(),
    val toastMessage: String? = null
)

/**
 * 下载规则管理页 ViewModel（ARCH-FEATURE-002）。
 *
 * 职责：规则列表观察（Room Flow 自动更新）、新增/编辑/启用切换/删除、
 * 保存成功反馈（toastMessage，Fragment 展示后调用 [consumeToast]）。
 */
@HiltViewModel
class DownloadRulesViewModel @Inject constructor(
    private val ruleRepository: DownloadRuleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadRulesUiState())
    val uiState: StateFlow<DownloadRulesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ruleRepository.observeRules().collect { rules ->
                _uiState.value = _uiState.value.copy(rules = rules)
            }
        }
    }

    /** 新增规则（默认启用）。 */
    fun addRule(name: String, creatorId: String?, service: String?, fileType: DownloadRuleFileType) {
        viewModelScope.launch {
            runCatching { ruleRepository.addRule(name, creatorId, service, fileType) }
                .onSuccess { notifySaved() }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 编辑规则（整体覆盖，保留启用状态）。 */
    fun updateRule(
        rule: DownloadRuleEntity,
        name: String,
        creatorId: String?,
        service: String?,
        fileType: DownloadRuleFileType
    ) {
        viewModelScope.launch {
            runCatching {
                ruleRepository.updateRule(
                    rule.copy(
                        name = name,
                        creatorId = creatorId?.takeIf { it.isNotBlank() },
                        service = service?.takeIf { it.isNotBlank() },
                        fileType = fileType
                    )
                )
            }.onSuccess { notifySaved() }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 切换规则启用状态。 */
    fun toggleEnabled(rule: DownloadRuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { ruleRepository.toggleEnabled(rule.id, enabled) }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 删除规则。 */
    fun deleteRule(rule: DownloadRuleEntity) {
        viewModelScope.launch {
            runCatching { ruleRepository.deleteRule(rule.id) }
                .onFailure { it.printStackTrace() }
        }
    }

    /** 消费保存成功反馈（Fragment Toast 展示后调用）。 */
    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private fun notifySaved() {
        _uiState.value = _uiState.value.copy(toastMessage = "saved")
    }
}
