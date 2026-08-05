package com.pawchive.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawchive.common.R
import com.pawchive.data.repository.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 备份与迁移页 UI 状态（ARCH-FEATURE-005）。
 */
data class BackupUiState(
    val isWorking: Boolean = false,
    val toastMessage: String? = null
)

/**
 * 备份与迁移页 ViewModel（ARCH-FEATURE-005）。
 *
 * - 导出：经 SAF 目标 URI 写入备份 JSON（BackupManager.export）
 * - 导入：读取备份 JSON → BackupManager.import（覆盖式）→ 结果 Toast
 * - 所有文件读写与数据层操作在 IO 线程；isWorking 控制进度条与防重入
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    application: Application,
    private val backupManager: BackupManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 导出备份到指定 SAF 文件 URI。 */
    fun exportTo(uri: Uri) {
        if (_uiState.value.isWorking) return
        _uiState.value = BackupUiState(isWorking = true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val json = backupManager.export()
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("cannot open output stream")
                }.fold(
                    onSuccess = { app.getString(R.string.backup_exported) },
                    onFailure = { e ->
                        android.util.Log.w("BackupViewModel", "export failed", e)
                        app.getString(R.string.backup_export_failed)
                    }
                )
            }
            _uiState.value = BackupUiState(toastMessage = message)
        }
    }

    /** 从指定 SAF 文件 URI 导入备份（覆盖式）。 */
    fun importFrom(uri: Uri) {
        if (_uiState.value.isWorking) return
        _uiState.value = BackupUiState(isWorking = true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val json = app.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("cannot open input stream")
                    backupManager.import(json)
                }.fold(
                    onSuccess = { result ->
                        app.getString(
                            R.string.backup_import_done,
                            result.bookmarks,
                            result.creators,
                            result.blocked,
                            result.downloads
                        )
                    },
                    onFailure = { e ->
                        android.util.Log.w("BackupViewModel", "import failed", e)
                        app.getString(R.string.backup_import_failed)
                    }
                )
            }
            _uiState.value = BackupUiState(toastMessage = message)
        }
    }

    /** 清除一次性 Toast 文案（Fragment 展示后调用）。 */
    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}
