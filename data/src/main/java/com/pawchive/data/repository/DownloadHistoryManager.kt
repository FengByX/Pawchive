package com.pawchive.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pawchive.core.db.DownloadHistoryDao
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// 旧 DataStore（ARCH-004 前存储方式）：仅用于一次性迁移历史数据，迁移完成后清空
private val Context.downloadHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_history")

private val downloadIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 下载历史管理器（FEATURE-001 下载中心；ARCH-003：Hilt 注入；ARCH-004：存储迁移至 Room）。
 *
 * 存储层由 DataStore JSON 大列表迁移至 Room：
 * - 单条状态/进度更新只执行一条 UPDATE，不再整表序列化/写回；
 * - 通过 DAO 提供按状态 / 时间范围查询能力；
 * - 保留 [records] StateFlow 供 UI 订阅，对外 API 与迁移前保持一致。
 *
 * 记录策略：
 * - 同一 URL 的下载只保留一条记录（id = url，主键）
 * - 新下载覆盖旧记录（状态重置为 PENDING）
 * - 完成后更新状态为 COMPLETED 并记录 filePath
 * - 失败时记录 errorMessage；取消时标记为 CANCELLED
 * - 应用重启后自动将未完成任务标记为 FAILED
 *
 * 数据迁移：首次启动（含升级）时读取旧 DataStore JSON 并导入 Room，随后清空 DataStore。
 */
@Singleton
class DownloadHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadHistoryDao
) {

    private val gson = Gson()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    init {
        // 异步初始化：先迁移旧数据，再标记中断任务，最后订阅 Room Flow
        downloadIoScope.launch {
            runCatching {
                migrateFromDataStoreIfNeeded()
                // ARCH-004：应用被系统杀死/崩溃/升级后重新启动，遗留的 PENDING/RUNNING
                // 记录对应的 WorkManager 任务已不复存在（WorkManager 会在某些场景下
                // 丢弃内部 DB 中的条目，或者 Force Stop 会清空其作业），必须批量标失败
                // 以避免下载中心永远显示"等待中"。selfHealPending 可在之后由 UI 或
                // DownloadCenter.schedule 触发重新入队（若用户仍需要继续下载）。
                dao.markInterruptedAsFailed(
                    DownloadStatus.FAILED,
                    "应用重启，下载任务中断"
                )
                dao.observeAll().collect { _records.value = it }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * 一次性迁移旧 DataStore 数据到 Room（ARCH-004）。
     * 迁移成功后清空 DataStore，避免重复导入。
     */
    private suspend fun migrateFromDataStoreIfNeeded() {
        val json = context.downloadHistoryDataStore.data.first()[KEY_RECORDS] ?: return
        migrateLegacyRecords(json)
        // 清空旧存储（无论是否解析成功，避免重复迁移）
        context.downloadHistoryDataStore.edit { it.clear() }
    }

    /**
     * 解析旧版本 DataStore 中的 JSON 记录列表并导入 Room。
     * 独立为 internal 方法以便测试直接注入 JSON（避免测试中创建第二个
     * DataStore 实例与生产实例冲突）。
     */
    internal suspend fun migrateLegacyRecords(json: String?) {
        if (json.isNullOrEmpty()) return
        val type = object : TypeToken<List<DownloadRecord>>() {}.type
        val list: List<DownloadRecord> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            // ARCH-008：旧版本历史 JSON 损坏时跳过迁移，记录日志便于排查
            Log.w("DownloadHistoryManager", "migrate legacy records failed", e)
            emptyList()
        }
        if (list.isNotEmpty()) {
            list.forEach { dao.upsert(it) }
        }
    }

    /**
     * 添加或更新下载记录。同一 URL 的记录会被覆盖（@Upsert）。
     */
    suspend fun upsert(record: DownloadRecord) {
        dao.upsert(record)
    }

    /**
     * 更新指定下载记录的状态和进度（单条 UPDATE）。
     * filePath 传 null 保留原值；fileSize 传 0 保留原值。
     */
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        progress: Int = 0,
        filePath: String? = null,
        fileSize: Long = 0L,
        errorMessage: String? = null
    ) {
        val completedAt = if (status == DownloadStatus.COMPLETED) System.currentTimeMillis() else 0L
        dao.updateStatus(id, status, progress, filePath, fileSize, errorMessage, completedAt)
    }

    /**
     * 移除指定下载记录（不影响已保存的文件）。
     */
    suspend fun remove(id: String) {
        dao.delete(id)
    }

    /**
     * 清空所有下载历史（不影响已保存的文件）。
     */
    suspend fun clearAll() {
        dao.clearAll()
    }

    /**
     * 批量导入下载历史（ARCH-FEATURE-005 备份恢复）。
     * 先清空现有记录再批量写入（主键冲突覆盖），保持与备份一致。
     */
    suspend fun importAll(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        dao.clearAll()
        dao.insertAll(records)
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
     * 按去重指纹查询进行中任务（ARCH-005 去重）。
     */
    suspend fun findActiveByDedupKey(dedupKey: String): DownloadRecord? {
        return dao.findActiveByDedupKey(dedupKey)
    }

    /**
     * 同步获取所有记录（基于内存缓存）。
     */
    fun getAllRecords(): List<DownloadRecord> = _records.value

    /**
     * 从数据库读取全部记录（ARCH-FEATURE-005 备份导出用）。
     * 不依赖内存快照的异步刷新时序，保证导出拿到最新数据。
     */
    suspend fun getAllRecordsFromDb(): List<DownloadRecord> = dao.getAll()

    /**
     * 从数据库读取所有 PENDING 状态记录（DownloadCenter 自愈用，避免加载全量历史）。
     */
    suspend fun getPendingRecords(): List<DownloadRecord> = dao.getPending()

    companion object {
        private val KEY_RECORDS = stringPreferencesKey("download_records")
    }
}
