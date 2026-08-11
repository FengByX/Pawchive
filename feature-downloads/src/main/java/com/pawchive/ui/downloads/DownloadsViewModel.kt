package com.pawchive.ui.downloads

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.common.R
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.data.repository.DownloadCenter
import com.pawchive.data.repository.DownloadHistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val records: List<DownloadRecord> = emptyList(),
    val filterStatus: DownloadStatus? = null,
    // 一次性 Toast 文案（ARCH-008）：Fragment 展示后调用 consumeToast() 清除
    val toastMessage: String? = null
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    application: Application,
    private val historyManager: DownloadHistoryManager,
    private val downloadCenter: DownloadCenter
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "DownloadsViewModel"
    }

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            historyManager.records.collect { records ->
                _uiState.value = _uiState.value.copy(
                    records = applyFilter(records, _uiState.value.filterStatus)
                )
                // The first Room emission may arrive after init{}; heal then as well so
                // pending records restored from disk are not missed by a startup race.
                downloadCenter.selfHealPending()
            }
        }
        // 自愈：修复"永远等待中"（PENDING 但 Work 已消失）的卡死记录
        reenqueueStuckPending()
    }

    /**
     * 重新入队卡死的等待中任务（PENDING 但对应 Work 已消失）。
     * 下载页打开与下拉刷新时调用。
     */
    fun reenqueueStuckPending() {
        viewModelScope.launch {
            runCatching { downloadCenter.selfHealPending() }
        }
    }

    private fun applyFilter(records: List<DownloadRecord>, status: DownloadStatus?): List<DownloadRecord> {
        return when (status) {
            null -> records
            // 进行中：包含等待中和下载中
            DownloadStatus.RUNNING -> records.filter {
                it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING
            }
            // 失败：包含失败和已取消
            DownloadStatus.FAILED -> records.filter {
                it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
            }
            else -> records.filter { it.status == status }
        }
    }

    fun setFilter(status: DownloadStatus?) {
        val current = historyManager.records.value
        _uiState.value = _uiState.value.copy(
            filterStatus = status,
            records = applyFilter(current, status)
        )
    }

    // ARCH-005：下载记录主键为 UUID（record.id），操作按 id 而非 url
    fun cancelDownload(id: String) {
        downloadCenter.cancel(id)
    }

    fun retryDownload(id: String) {
        viewModelScope.launch {
            downloadCenter.retry(id)
        }
    }

    fun removeRecord(id: String) {
        viewModelScope.launch {
            downloadCenter.removeHistory(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            downloadCenter.clearAllHistory()
        }
    }

    fun openFile(record: DownloadRecord) {
        val filePath = record.filePath ?: return
        val context = getApplication<Application>()
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(filePath), record.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openFile failed: $filePath", e)
            _uiState.value = _uiState.value.copy(
                toastMessage = context.getString(R.string.operation_failed)
            )
        }
    }

    fun shareFile(record: DownloadRecord) {
        val filePath = record.filePath ?: return
        val context = getApplication<Application>()
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = record.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(filePath))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w(TAG, "shareFile failed: $filePath", e)
            _uiState.value = _uiState.value.copy(
                toastMessage = context.getString(R.string.operation_failed)
            )
        }
    }

    /**
     * 清除一次性 Toast 文案（Fragment 展示后调用，ARCH-008）。
     */
    fun consumeToast() {
        if (_uiState.value.toastMessage != null) {
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }
}
