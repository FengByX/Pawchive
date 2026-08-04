package com.pawchive.ui.downloads

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.data.model.DownloadRecord
import com.pawchive.data.model.DownloadStatus
import com.pawchive.data.model.DownloadType
import com.pawchive.data.repository.DownloadCenter
import com.pawchive.data.repository.DownloadHistoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val records: List<DownloadRecord> = emptyList(),
    val filterStatus: DownloadStatus? = null
)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val historyManager = DownloadHistoryManager.getInstance(application)
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            historyManager.records.collect { records ->
                _uiState.value = _uiState.value.copy(
                    records = applyFilter(records, _uiState.value.filterStatus)
                )
            }
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

    fun cancelDownload(url: String, fileName: String, mimeType: String, type: DownloadType) {
        val context = getApplication<Application>()
        DownloadCenter.cancel(context, url)
    }

    fun retryDownload(url: String, fileName: String, mimeType: String, type: DownloadType) {
        viewModelScope.launch {
            DownloadCenter.retry(getApplication(), url, fileName, mimeType, type)
        }
    }

    fun removeRecord(url: String) {
        viewModelScope.launch {
            DownloadCenter.removeHistory(getApplication(), url)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            DownloadCenter.clearAllHistory(getApplication())
        }
    }

    fun openFile(filePath: String?) {
        if (filePath.isNullOrEmpty()) return
        val context = getApplication<Application>()
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(filePath), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun shareFile(filePath: String?) {
        if (filePath.isNullOrEmpty()) return
        val context = getApplication<Application>()
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(filePath))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }
}
