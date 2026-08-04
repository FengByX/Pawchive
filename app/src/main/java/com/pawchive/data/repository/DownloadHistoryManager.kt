package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pawchive.data.model.DownloadRecord
import com.pawchive.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.downloadHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_history")

private val downloadIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 下载历史管理器（FEATURE-001 下载中心）。
 *
 * 使用 DataStore + JSON 列表持久化下载记录，避免引入 Room 依赖。
 * 提供 Flow<StateFlow> 供 UI 订阅，记录增删改自动刷新。
 *
 * 记录策略：
 * - 同一 URL 的下载只保留一条记录（id = url）
 * - 新下载覆盖旧记录（状态重置为 PENDING）
 * - 完成后更新状态为 COMPLETED 并记录 filePath
 * - 失败时记录 errorMessage
 * - 取消时标记为 CANCELLED
 */
class DownloadHistoryManager private constructor(private val context: Context) {

    private val dataStore = context.downloadHistoryDataStore
    private val gson = Gson()
    private val writeMutex = Mutex()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    init {
        // 异步加载历史记录到内存
        downloadIoScope.launch {
            runCatching { loadFromDisk() }.onFailure { it.printStackTrace() }
        }
    }

    private suspend fun loadFromDisk() {
        val json = dataStore.data.first()[KEY_RECORDS] ?: ""
        if (json.isEmpty()) {
            _records.value = emptyList()
            return
        }
        val type = object : TypeToken<List<DownloadRecord>>() {}.type
        val list: List<DownloadRecord> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // 启动时将所有 RUNNING 状态重置为 FAILED（上次未完成的任务）
        _records.value = list.map { record ->
            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) {
                record.copy(status = DownloadStatus.FAILED, errorMessage = "Interrupted by app restart")
            } else {
                record
            }
        }
        persistToDisk()
    }

    private suspend fun persistToDisk() {
        writeMutex.withLock {
            runCatching {
                val json = gson.toJson(_records.value)
                dataStore.edit { it[KEY_RECORDS] = json }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * 添加或更新下载记录。同一 URL 的记录会被覆盖。
     */
    suspend fun upsert(record: DownloadRecord) {
        val current = _records.value.toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(0, record) // 新记录插入头部
        }
        _records.value = current
        persistToDisk()
    }

    /**
     * 更新指定下载记录的状态和进度。
     */
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        progress: Int = 0,
        filePath: String? = null,
        fileSize: Long = 0L,
        errorMessage: String? = null
    ) {
        val current = _records.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val record = current[index]
            current[index] = record.copy(
                status = status,
                progress = progress,
                filePath = filePath ?: record.filePath,
                fileSize = if (fileSize > 0) fileSize else record.fileSize,
                completedAt = if (status == DownloadStatus.COMPLETED) System.currentTimeMillis() else record.completedAt,
                errorMessage = errorMessage
            )
            _records.value = current
            persistToDisk()
        }
    }

    /**
     * 移除指定下载记录（不影响已保存的文件）。
     */
    suspend fun remove(id: String) {
        _records.value = _records.value.filter { it.id != id }
        persistToDisk()
    }

    /**
     * 清空所有下载历史（不影响已保存的文件）。
     */
    suspend fun clearAll() {
        _records.value = emptyList()
        persistToDisk()
    }

    /**
     * 清除所有下载历史（FEATURE-003 账号切换/登出时数据隔离）。
     */
    suspend fun clearAllForAccountSwitch() {
        clearAll()
    }

    /**
     * 获取指定 URL 的下载记录（同步，基于内存缓存）。
     */
    fun getRecord(id: String): DownloadRecord? {
        return _records.value.find { it.id == id }
    }

    /**
     * 同步获取所有记录（基于内存缓存）。
     */
    fun getAllRecords(): List<DownloadRecord> = _records.value

    companion object {
        private val KEY_RECORDS = stringPreferencesKey("download_records")

        @Volatile
        private var instance: DownloadHistoryManager? = null

        fun getInstance(context: Context): DownloadHistoryManager =
            instance ?: synchronized(this) {
                instance ?: DownloadHistoryManager(context.applicationContext).also { instance = it }
            }
    }
}
