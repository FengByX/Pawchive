package com.pawchive.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.common.R
import com.pawchive.core.store.SettingsManager
import com.pawchive.data.repository.CacheRepository
import com.pawchive.data.repository.OfflineArchiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 缓存管理页可清理类别（ARCH-FEATURE-004）。 */
enum class CacheCategory { IMAGE, OTHER, ARCHIVE, DOWNLOADS }

/**
 * 缓存管理页 UI 状态（ARCH-FEATURE-004）。
 *
 * - [imageCacheSize] / [otherCacheSize]：图片缓存与其他缓存大小（cacheDir 分类口径，与 ARCH-010 一致）
 * - [archiveBytes] / [archiveCount]：离线归档（Room）总字节与条数
 * - [downloadFilesSize]：已下载文件大小（MediaStore + SAF，用户产物，删除需二次确认）
 * - [totalClearable]：可清理缓存 = 图片 + 其他 + 离线归档（下载文件为独立破坏性项）
 * - [isThresholdExceeded]：图片 + 其他缓存超过自动清理阈值（与 SettingsManager 口径一致）
 * - [cleaningCategory]：正在清理的类别（防重入，非空时禁用其他清理操作）
 * - [toastMessage]：一次性 Toast 文案，Fragment 展示后调用 [CacheManagerViewModel.consumeToast] 清除
 */
data class CacheManagerUiState(
    val isLoading: Boolean = true,
    val imageCacheSize: Long = 0L,
    val otherCacheSize: Long = 0L,
    val archiveBytes: Long = 0L,
    val archiveCount: Int = 0,
    val downloadFilesSize: Long = 0L,
    val lastCleanTime: Long = 0L,
    val thresholdBytes: Long = 0L,
    val cleaningCategory: CacheCategory? = null,
    val toastMessage: String? = null
) {
    val totalClearable: Long
        get() = imageCacheSize + otherCacheSize + archiveBytes

    val isThresholdExceeded: Boolean
        get() = imageCacheSize + otherCacheSize > thresholdBytes
}

/**
 * 缓存管理页 ViewModel（ARCH-FEATURE-004）。
 *
 * - 统计四类占用（图片/其他/离线归档/下载文件），全部在 IO 线程计算；
 * - 按类别清理走 [CacheRepository] / [OfflineArchiveRepository] 统一入口；
 * - 图片/其他清理会更新"上次清理时间"（保持自动清理调度语义）；
 * - 离线归档与下载文件为破坏性操作，由 Fragment 二次确认后调用。
 */
@HiltViewModel
class CacheManagerViewModel @Inject constructor(
    application: Application,
    private val settingsManager: SettingsManager,
    private val cacheRepository: CacheRepository,
    private val offlineArchiveRepository: OfflineArchiveRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        CacheManagerUiState(thresholdBytes = settingsManager.getCacheThresholdBytes())
    )
    val uiState: StateFlow<CacheManagerUiState> = _uiState.asStateFlow()

    init {
        refreshStats()
    }

    /**
     * 重新统计各分类大小（进入页面 / 清理完成后调用）。
     */
    fun refreshStats() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    CacheManagerUiState(
                        isLoading = false,
                        imageCacheSize = cacheRepository.getImageCacheSize(),
                        otherCacheSize = cacheRepository.getOtherCacheSize(),
                        archiveBytes = offlineArchiveRepository.getTotalBytes(),
                        archiveCount = offlineArchiveRepository.getCount(),
                        downloadFilesSize = cacheRepository.getDownloadFilesSize(),
                        lastCleanTime = cacheRepository.getLastCleanTime(),
                        thresholdBytes = settingsManager.getCacheThresholdBytes(),
                        cleaningCategory = _uiState.value.cleaningCategory
                    )
                }.getOrElse { e ->
                    Log.w(TAG, "refreshStats failed", e)
                    _uiState.value.copy(
                        isLoading = false,
                        thresholdBytes = settingsManager.getCacheThresholdBytes(),
                        toastMessage = getApplication<Application>().getString(R.string.cache_clean_failed)
                    )
                }
            }
            _uiState.value = snapshot
        }
    }

    /**
     * 按类别清理。同一时间只允许一个清理任务，防重复触发。
     * 离线归档 / 下载文件的删除应先在 Fragment 层弹确认对话框。
     */
    fun clearCategory(category: CacheCategory) {
        if (_uiState.value.cleaningCategory != null) return
        _uiState.value = _uiState.value.copy(cleaningCategory = category)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    when (category) {
                        CacheCategory.IMAGE -> {
                            cacheRepository.clearImageCache()
                            // 仅缓存类清理更新"上次清理时间"，避免影响自动清理调度语义
                            cacheRepository.recordClean()
                            app.getString(R.string.cache_category_cleared)
                        }
                        CacheCategory.OTHER -> {
                            cacheRepository.clearOtherCache()
                            cacheRepository.recordClean()
                            app.getString(R.string.cache_category_cleared)
                        }
                        CacheCategory.ARCHIVE -> {
                            offlineArchiveRepository.clearAll()
                            app.getString(R.string.cache_category_cleared)
                        }
                        CacheCategory.DOWNLOADS -> {
                            val deleted = cacheRepository.deleteDownloadFiles()
                            app.getString(R.string.cache_downloads_deleted, deleted)
                        }
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "clear $category failed", e)
                    app.getString(R.string.cache_clean_failed)
                }
            }
            _uiState.value = _uiState.value.copy(
                cleaningCategory = null,
                toastMessage = message
            )
            refreshStats()
        }
    }

    /** 清除一次性 Toast 文案（Fragment 展示后调用）。 */
    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private companion object {
        const val TAG = "CacheManagerViewModel"
    }
}
