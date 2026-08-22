package com.pawchive.data.repository

import android.util.Log
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载中心统一入口（协程版，彻底移除 WorkManager 依赖）。
 *
 * 职责：
 * - 统一图片/视频/附件下载队列管理
 * - 通过协程直接执行下载任务，无 WorkManager 初始化/调度问题
 * - 下载记录通过 DownloadHistoryManager（Room）持久化
 * - 去重指纹防止重复下载
 */
@Singleton
class DownloadCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyManager: DownloadHistoryManager,
    private val downloadRepository: DownloadRepository,
    private val okDownloadManager: OkDownloadManager
) : DownloadEnqueuer {

    companion object {
        private const val TAG = "DownloadCenter"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_STEP = 5

        fun dedupFingerprint(
            url: String,
            fileName: String,
            mimeType: String,
            type: DownloadType,
            account: String = ""
        ): String {
            val raw = "$account|$url|$fileName|$mimeType|${type.name}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    // 下载协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 正在执行的下载任务（dedupKey -> true）
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    // 取消信号（recordId -> true 表示请求取消）
    private val cancelRequests = ConcurrentHashMap<String, Boolean>()

    override suspend fun enqueueImageDownload(
        url: String, fileName: String, mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.IMAGE)

    override suspend fun enqueueVideoDownload(
        url: String, fileName: String, mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.VIDEO)

    override suspend fun enqueueAttachmentDownload(
        url: String, fileName: String, mimeType: String
    ): String = enqueueDownload(url, fileName, mimeType, DownloadType.ATTACHMENT)

    private suspend fun enqueueDownload(
        url: String,
        fileName: String,
        mimeType: String,
        type: DownloadType
    ): String {
        val key = dedupFingerprint(url, fileName, mimeType, type)

        // 去重：相同指纹且正在下载的任务不重复入队
        historyManager.findActiveByDedupKey(key)?.let { record ->
            if (activeDownloads.containsKey(key)) {
                Log.d(TAG, "enqueueDownload: dedup hit, returning existing record ${record.id}")
                return record.id
            }
            // 记录存在但没有活跃下载 → 重新启动下载
            Log.i(TAG, "enqueueDownload: restarting stale record ${record.id}")
            launchDownload(record)
            return record.id
        }

        // 创建新下载记录
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
        Log.i(TAG, "enqueueDownload: new record=${record.id} url=$url file=$fileName")

        launchDownload(record)
        return record.id
    }

    private fun launchDownload(record: DownloadRecord) {
        val key = record.dedupKey ?: return
        activeDownloads[key] = true
        cancelRequests.remove(record.id)

        scope.launch {
            try {
                executeDownload(record)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: record=${record.id}", e)
                if (cancelRequests.remove(record.id) != null) {
                    historyManager.updateStatus(record.id, DownloadStatus.CANCELLED)
                } else {
                    historyManager.updateStatus(
                        record.id,
                        DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Download failed"
                    )
                }
            } finally {
                activeDownloads.remove(key)
            }
        }
    }

    private suspend fun executeDownload(record: DownloadRecord) {
        val recordId = record.id
        val url = record.url
        val fileName = record.fileName

        Log.i(TAG, "executeDownload START: record=$recordId url=$url")

        // 标记为运行中
        historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = 0)

        // 准备输出流
        val mimeType = record.mimeType.ifEmpty { guessMimeType(fileName) }
        val repoType = when (record.type) {
            DownloadType.IMAGE -> DownloadRepository.DownloadType.IMAGE
            DownloadType.VIDEO -> DownloadRepository.DownloadType.VIDEO
            else -> DownloadRepository.DownloadType.ATTACHMENT
        }
        val target = DownloadRepository.DownloadTarget(repoType, fileName, mimeType)
        val (os, fileUri, requiresFinalize) = downloadRepository.openDownloadStream(target)

        try {
            // 使用 okdownload 下载
            var lastReported = -1
            val totalRead = okDownloadManager.download(url, os) { currentBytes, totalBytes ->
                if (totalBytes > 0) {
                    val percent = (currentBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                    if (percent - lastReported >= PROGRESS_STEP || percent == 100) {
                        lastReported = percent
                        Log.d(TAG, "Download progress: $recordId $percent%")
                    }
                }
            }

            // 完成
            if (requiresFinalize) downloadRepository.finalizeDownload(fileUri)
            historyManager.updateStatus(
                recordId,
                DownloadStatus.COMPLETED,
                progress = 100,
                filePath = fileUri.toString(),
                fileSize = totalRead
            )
            Log.i(TAG, "executeDownload SUCCESS: record=$recordId size=$totalRead")
        } finally {
            os.close()
        }
    }

    private fun guessMimeType(fileName: String): String = when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        fileName.endsWith(".mp4", true) -> "video/mp4"
        fileName.endsWith(".webm", true) -> "video/webm"
        else -> "application/octet-stream"
    }

    /** 取消指定下载任务。 */
    fun cancel(id: String) {
        cancelRequests[id] = true
        val record = historyManager.getRecord(id) ?: return
        val key = record.dedupKey ?: return
        activeDownloads.remove(key)
        okDownloadManager.cancel(record.url)
        Log.i(TAG, "cancel: record=$id")
    }

    /** 重试下载任务。 */
    suspend fun retry(id: String): String? {
        val record = historyManager.getRecord(id) ?: return null
        historyManager.updateStatus(id, DownloadStatus.PENDING, progress = 0, errorMessage = null)
        cancelRequests.remove(id)
        launchDownload(record)
        return id
    }

    /** 获取所有下载历史记录的 StateFlow。 */
    fun observeHistory() = historyManager.records

    /** 移除下载历史记录。 */
    suspend fun removeHistory(id: String) {
        cancel(id)
        historyManager.remove(id)
    }

    /** 清空所有下载历史。 */
    suspend fun clearAllHistory() {
        historyManager.clearAll()
    }

    /** 自愈：重启卡在 PENDING/RUNNING 的记录。 */
    suspend fun selfHealPending() {
        val pending = historyManager.getPendingRecords()
        for (record in pending) {
            if (!activeDownloads.containsKey(record.dedupKey)) {
                Log.i(TAG, "selfHeal: restarting record=${record.id}")
                launchDownload(record)
            }
        }
    }
}
