package com.pawchive.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.work.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载中心统一入口（FEATURE-001；ARCH-003：Hilt 注入；ARCH-004：Room 存储；ARCH-005：UUID + 去重指纹）。
 *
 * 职责：
 * - 统一图片/视频/附件下载队列管理
 * - 通过 WorkManager 调度下载任务，支持后台持续、取消、重试
 * - 下载记录通过 DownloadHistoryManager（Room）持久化
 * - 提供 WorkManager 任务状态 Flow 供 UI 订阅
 *
 * ARCH-005 关键变更：
 * - 下载记录主键从 url 改为 UUID v4，消除 url.hashCode() 碰撞与误判；
 * - 去重指纹 = SHA-256("账号|url|文件名|类型")：相同"账号+URL+文件名+类型"且未完成的
 *   任务不重复入队；同 URL 但文件名/类型/账号不同的下载互不干扰；
 * - WorkManager 唯一键改为基于去重指纹，与业务记录通过记录 id 关联。
 *
 * 使用方式（注入后）：
 * ```
 * @Inject lateinit var downloadCenter: DownloadCenter
 * downloadCenter.enqueueImageDownload(url, fileName, mimeType)
 * downloadCenter.cancel(recordId)
 * downloadCenter.retry(recordId)
 * downloadCenter.observeWorkInfo(recordId).collect { workInfo -> ... }
 * ```
 */
@Singleton
class DownloadCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyManager: DownloadHistoryManager
) : DownloadEnqueuer {

    companion object {
        const val WORK_TAG = "pawchive_download"
        private const val WORK_NAME_PREFIX = "download:"

        /**
         * 计算去重指纹（ARCH-005）：SHA-256("账号|url|文件名|类型")。
         * 账号维度暂为空字符串，账号体系接入后（FEATURE-003）由调用方传入。
         */
        fun dedupFingerprint(
            url: String,
            fileName: String,
            mimeType: String,
            type: DownloadType,
            account: String = ""
        ): String {
            val raw = "$account|$url|$fileName|$mimeType|${type.name}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private fun workName(dedupKey: String) = "$WORK_NAME_PREFIX$dedupKey"

    /**
     * 入队图片下载任务。
     * 相同指纹且未完成的下载不重复入队（返回已有记录 id）。
     */
    override suspend fun enqueueImageDownload(
        url: String,
        fileName: String,
        mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.IMAGE)

    /**
     * 入队视频下载任务。
     */
    override suspend fun enqueueVideoDownload(
        url: String,
        fileName: String,
        mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.VIDEO)

    /**
     * 入队附件下载任务。
     */
    override suspend fun enqueueAttachmentDownload(
        url: String,
        fileName: String,
        mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.ATTACHMENT)

    private suspend fun enqueueDownload(
        url: String,
        fileName: String,
        mimeType: String,
        type: DownloadType
    ): String {
        val key = dedupFingerprint(url, fileName, mimeType, type)

        // 去重：相同指纹且存在活跃 Work 的下载不重复入队（复用已有记录）。
        // 若记录仍为 PENDING/RUNNING 但对应 Work 已消失（被系统丢弃/唯一任务被吞），
        // 直接复用记录并重新入队（REPLACE），避免任务永远停留在"等待中"（PENDING）。
        historyManager.findActiveByDedupKey(key)?.let { record ->
            if (hasActiveWork(key)) return record.id
            enqueueWork(record, ExistingWorkPolicy.REPLACE)
            return record.id
        }

        // 创建下载记录（UUID 主键，ARCH-005）
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            type = type,
            dedupKey = key,
            status = DownloadStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        historyManager.upsert(record)

        enqueueWork(record, ExistingWorkPolicy.REPLACE)
        return record.id
    }

    /**
     * 校验指定去重指纹下是否存在活跃（ENQUEUED/RUNNING）的 Work。
     * 用于去重短路判断：仅有数据库记录但任务已消失时，需重新入队而非复用旧 id。
     */
    private suspend fun hasActiveWork(dedupKey: String): Boolean {
        return try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(workName(dedupKey))
                .first()
                .any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 自愈：修复"永远等待中"（PENDING 但 Work 已消失）的卡死记录。
     * 下载中心页面打开/下拉刷新时调用；对每个 PENDING 且无活跃 Work 的记录重新入队。
     */
    suspend fun selfHealPending() {
        val pending = historyManager.getAllRecords().filter { it.status == DownloadStatus.PENDING }
        for (record in pending) {
            val key = record.dedupKey ?: continue
            if (!hasActiveWork(key)) {
                enqueueWork(record, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    private fun enqueueWork(record: DownloadRecord, policy: ExistingWorkPolicy) {
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, record.url)
            .putString(DownloadWorker.KEY_FILE_NAME, record.fileName)
            .putString(DownloadWorker.KEY_MIME_TYPE, record.mimeType)
            .putString(DownloadWorker.KEY_DOWNLOAD_TYPE, record.type.name)
            .putString(DownloadWorker.KEY_RECORD_ID, record.id)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .addTag(record.id)
            .build()

        val key = record.dedupKey ?: dedupFingerprint(record.url, record.fileName, record.mimeType, record.type)
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), policy, request)
    }

    /**
     * 取消指定下载任务（按记录 id）。
     * 旧版本记录（dedupKey 为 null）无对应活跃 Work，取消为空操作。
     */
    fun cancel(id: String) {
        val record = historyManager.getRecord(id) ?: return
        val key = record.dedupKey ?: return
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    /**
     * 重试下载任务（按记录 id）：重置记录为 PENDING 并重新入队。
     */
    suspend fun retry(id: String): String? {
        val record = historyManager.getRecord(id) ?: return null
        // 重置状态与错误信息
        historyManager.updateStatus(id, DownloadStatus.PENDING, progress = 0, errorMessage = null)
        // 取消旧任务并重新入队（REPLACE 确保新任务生效）
        WorkManager.getInstance(context).cancelUniqueWork(workName(record.dedupKey ?: return null))
        enqueueWork(record, ExistingWorkPolicy.REPLACE)
        return id
    }

    /**
     * 观察指定下载任务的工作状态（按记录 id）。
     */
    fun observeWorkInfo(id: String): Flow<List<WorkInfo>> {
        val record = historyManager.getRecord(id) ?: return emptyFlow()
        val key = record.dedupKey ?: return emptyFlow()
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(key))
    }

    /**
     * 观察所有下载任务的工作状态。
     */
    fun observeAllWorkInfo(): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(WORK_TAG)
    }

    /**
     * 获取所有下载历史记录的 StateFlow。
     */
    fun observeHistory() = historyManager.records

    /**
     * 移除下载历史记录（不影响已保存的文件）；若任务仍在进行则先取消。
     */
    suspend fun removeHistory(id: String) {
        cancel(id)
        historyManager.remove(id)
    }

    /**
     * 清空所有下载历史（不影响已保存的文件）。
     */
    suspend fun clearAllHistory() {
        historyManager.clearAll()
    }
}
