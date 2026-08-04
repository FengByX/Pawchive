package com.pawchive.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.BuildConfig
import com.pawchive.PawchiveApplication
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.data.api.ApiClient
import com.pawchive.data.repository.BlockedCreatorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 UI 状态（P2 FRONTEND-008）。
 *
 * - [language] / [appearance]：当前语言与外观选择
 * - [blockedCount]：已屏蔽创作者数量（订阅 Flow 自动刷新）
 * - [downloadLocationText]：已格式化的下载位置显示文本
 * - [autoCleanCacheEnabled] / [autoCheckUpdateEnabled]：开关状态
 * - [cacheSizeText]：已格式化的缓存大小文本
 * - [versionName]：语义化版本号
 * - [isCleaningCache]：缓存清理进行中（Fragment 控制 loading Toast）
 * - [toastMessage]：一次性 Toast 文案，Fragment 展示后调用 [SettingsViewModel.clearToast] 清除
 */
data class SettingsUiState(
    val language: SettingsManager.Language = SettingsManager.Language.CHINESE,
    val appearance: SettingsManager.Appearance = SettingsManager.Appearance.FOLLOW_SYSTEM,
    val blockedCount: Int = 0,
    val downloadLocationText: String = "",
    val autoCleanCacheEnabled: Boolean = false,
    val autoCheckUpdateEnabled: Boolean = true,
    val cacheSizeText: String = "",
    val versionName: String = "",
    val isCleaningCache: Boolean = false,
    val toastMessage: String? = null
)

/**
 * 设置页 ViewModel（P2 FRONTEND-008）。
 *
 * 职责划分：
 * - ViewModel：设置项读写、屏蔽列表订阅与操作、缓存统计与清理、下载位置管理
 * - Fragment：UI 绑定、Toggle/Switch 监听、SAF 文件选择器、屏蔽列表 Dialog、Telegram 跳转、Toast 渲染
 *
 * 屏蔽计数通过订阅 [BlockedCreatorManager.blockedCreatorsFlow] 自动刷新，
 * 解决首次进入页面因异步加载导致的空数据竞态（P1）。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager.getInstance(application)
    private val blockedCreatorManager = BlockedCreatorManager.getInstance(application)

    private val _uiState = MutableStateFlow(SettingsUiState(versionName = BuildConfig.VERSION_NAME))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialSettings()
        observeBlockedCreators()
    }

    private fun loadInitialSettings() {
        _uiState.value = _uiState.value.copy(
            language = settingsManager.getLanguage(),
            appearance = settingsManager.getAppearance(),
            autoCleanCacheEnabled = settingsManager.isAutoCleanCacheEnabled(),
            autoCheckUpdateEnabled = settingsManager.isAutoCheckUpdateEnabled(),
            downloadLocationText = buildDownloadLocationText()
        )
    }

    /**
     * 订阅屏蔽列表 Flow，加载完成或变更时自动刷新计数（P1：解决首次进入空数据竞态）。
     */
    private fun observeBlockedCreators() {
        blockedCreatorManager.blockedCreatorsFlow
            .onEach { list ->
                _uiState.value = _uiState.value.copy(blockedCount = list.size)
            }
            .launchIn(viewModelScope)
    }

    // ---------- 设置项 ----------

    fun setLanguage(language: SettingsManager.Language) {
        if (language == _uiState.value.language) return
        settingsManager.setLanguage(language)
        _uiState.value = _uiState.value.copy(language = language)
    }

    fun setAppearance(appearance: SettingsManager.Appearance) {
        if (appearance == _uiState.value.appearance) return
        settingsManager.setAppearance(appearance)
        _uiState.value = _uiState.value.copy(appearance = appearance)
    }

    fun setAutoCleanCacheEnabled(enabled: Boolean) {
        settingsManager.setAutoCleanCacheEnabled(enabled)
        _uiState.value = _uiState.value.copy(autoCleanCacheEnabled = enabled)
    }

    fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        settingsManager.setAutoCheckUpdateEnabled(enabled)
        _uiState.value = _uiState.value.copy(autoCheckUpdateEnabled = enabled)
    }

    // ---------- 下载位置 ----------

    /**
     * 用户通过 SAF 选中目录后调用。
     * 尝试获取持久化权限并保存到设置；成功后更新显示文本并提示。
     */
    fun setDownloadTreeUri(uri: Uri) {
        val app = getApplication<Application>()
        try {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val displayName = queryFolderDisplayName(uri)
            settingsManager.setDownloadTreeUri(uri, displayName)
            _uiState.value = _uiState.value.copy(
                downloadLocationText = buildDownloadLocationText(),
                toastMessage = app.getString(R.string.download_location_set_to, displayName)
            )
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(
                toastMessage = app.getString(R.string.file_picker_not_available)
            )
        }
    }

    private fun queryFolderDisplayName(uri: Uri): String {
        val app = getApplication<Application>()
        try {
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        val lastSegment = uri.lastPathSegment ?: ""
        val name = lastSegment.substringAfterLast('/')
        return name.ifEmpty { app.getString(R.string.unknown_folder) }
    }

    private fun buildDownloadLocationText(): String {
        val app = getApplication<Application>()
        val name = settingsManager.getDownloadLocationName()
        val uri = settingsManager.getDownloadTreeUri()
        val displayName = when {
            uri == null || name.isEmpty() -> null
            name.startsWith("primary:") -> app.getString(R.string.download_location_internal_storage) + name.removePrefix("primary:")
            else -> name
        }
        return if (displayName != null) {
            app.getString(R.string.download_location_label) + displayName
        } else {
            app.getString(R.string.download_location_not_set)
        }
    }

    /**
     * 重新计算下载位置显示文本（onResume 时调用，处理外部修改）。
     */
    fun refreshDownloadLocationText() {
        _uiState.value = _uiState.value.copy(downloadLocationText = buildDownloadLocationText())
    }

    // ---------- 缓存 ----------

    /**
     * 异步统计缓存大小并更新显示文本。
     */
    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                SettingsManager.getCacheSize(getApplication())
            }
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(
                cacheSizeText = if (size > 0) {
                    app.getString(R.string.cache_size, SettingsManager.formatSize(size))
                } else {
                    app.getString(R.string.cache_empty)
                }
            )
        }
    }

    /**
     * 清理缓存。清理期间 [SettingsUiState.isCleaningCache] 为 true，Fragment 控制 loading Toast；
     * 完成后通过 [SettingsUiState.toastMessage] 返回结果，并刷新缓存大小。
     */
    fun cleanCache() {
        if (_uiState.value.isCleaningCache) return
        _uiState.value = _uiState.value.copy(isCleaningCache = true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val sizeBefore = withContext(Dispatchers.IO) { SettingsManager.getCacheSize(app) }
            var success = true
            withContext(Dispatchers.IO) {
                try {
                    (app as? PawchiveApplication)?.clearCache()
                    app.cacheDir.let { dir ->
                        if (dir.exists()) {
                            dir.deleteRecursively()
                            dir.mkdirs()
                        }
                    }
                    // 同步清理 externalCacheDir（P2）
                    app.externalCacheDir?.let { dir ->
                        if (dir.exists()) {
                            dir.deleteRecursively()
                            dir.mkdirs()
                        }
                    }
                    // 清理 API 内存缓存（P2）
                    ApiClient.clearMemoryCache()
                } catch (_: Exception) {
                    success = false
                }
            }
            val message = if (success) {
                val sizeAfter = withContext(Dispatchers.IO) { SettingsManager.getCacheSize(app) }
                val freed = sizeBefore - sizeAfter
                // 记录清理时间戳（FRONTEND-003）
                settingsManager.setLastCacheCleanTime(System.currentTimeMillis())
                if (freed > 0) {
                    app.getString(R.string.cache_cleaned_freed, SettingsManager.formatSize(freed))
                } else {
                    app.getString(R.string.cache_cleaned)
                }
            } else {
                app.getString(R.string.cache_clean_failed)
            }
            // 刷新缓存大小显示
            val newSize = withContext(Dispatchers.IO) { SettingsManager.getCacheSize(app) }
            val newSizeText = if (newSize > 0) {
                app.getString(R.string.cache_size, SettingsManager.formatSize(newSize))
            } else {
                app.getString(R.string.cache_empty)
            }
            _uiState.value = _uiState.value.copy(
                isCleaningCache = false,
                toastMessage = message,
                cacheSizeText = newSizeText
            )
        }
    }

    // ---------- 屏蔽列表 ----------

    /**
     * 获取当前屏蔽列表（供 Dialog 展示）。同步返回，依赖 BlockedCreatorManager 内存缓存。
     */
    fun getBlockedCreators(): List<Pair<String, String>> = blockedCreatorManager.getBlockedCreators()

    /**
     * 批量取消屏蔽。取消后屏蔽计数会通过 Flow 自动更新。
     */
    fun unblockCreators(items: List<Pair<String, String>>) {
        items.forEach { (service, creatorId) ->
            blockedCreatorManager.unblockCreator(service, creatorId)
        }
    }

    // ---------- 一次性事件 ----------

    /**
     * 清除一次性 Toast 文案（Fragment 展示后调用）。
     */
    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}
