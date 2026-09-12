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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

        /**
         * 同时进行的下载任务上限。
         * 文件服务器运维方要求：不要同时进行超过 5 个下载任务，避免占用过多带宽。
         * 超过上限的任务会挂起等待，直至有下载完成释放许可。
         */
        private const val MAX_CONCURRENT_DOWNLOADS = 5

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

    /** 并发下载信号量：限制同时运行的下载不超过 5 个。 */
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /**
     * enqueue 串行化锁。防止快速连续点击下载按钮时两个并发的
     * enqueueDownload 因 Room upsert 尚未 commit 而互相漏查，
     * 导致同 URL 生成两条独立记录（id 不同但 dedupKey 相同）。
     */
    private val enqueueMutex = Mutex()

    // 下载协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 正在执行的下载任务（dedupKey -> true）
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    // BUG-003 fix: 取消语义统一由 OkDownloadManager 抛 CancellationException 走协程结构化并发，
    // 不再维护 cancelRequests map——之前的实现无法识别"okdownload 内部主动 cancel"，
    // 导致 taskEnd(CANCELLED) 被 catch 块当成 FAILED，文案"Download failed: CANCELLED"。

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
    ): String = enqueueMutex.withLock {
        // 加锁保证 find → create → upsert 三段为原子操作。
        // 否则快速连续点击时，两个 enqueueDownload 交错执行，
        // 第二个在第一个 upsert 完成前就查 findActiveByDedupKey，
        // 会互相漏查，生成两条 dedupKey 相同但 id 不同的记录。
        val key = dedupFingerprint(url, fileName, mimeType, type)

        // 去重：相同指纹且正在下载的任务不重复入队
        historyManager.findActiveByDedupKey(key)?.let { record ->
            if (activeDownloads.containsKey(key)) {
                Log.d(TAG, "enqueueDownload: dedup hit, returning existing record ${record.id}")
                return@withLock record.id
            }
            // 记录存在但没有活跃下载 → 重新启动下载
            Log.i(TAG, "enqueueDownload: restarting stale record ${record.id}")
            launchDownload(record)
            return@withLock record.id
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
        record.id
    }

    private fun launchDownload(record: DownloadRecord) {
        val key = record.dedupKey ?: return
        activeDownloads[key] = true

        scope.launch {
            // 并发上限：同一时刻最多 5 个下载同时进行（文件服务器运维方要求）。
            // 超过上限的任务在此挂起等待，有下载完成释放许可后才继续。
            downloadSemaphore.withPermit {
                try {
                    executeDownload(record)
                } catch (e: CancellationException) {
                    // BUG-003 fix: 取消路径走协程结构化并发，不再依赖 cancelRequests map。
                    // 这样"用户点取消"和"okdownload 内部主动 cancel"都能正确标 CANCELLED。
                    Log.d(TAG, "Download cancelled: record=${record.id}")
                    historyManager.updateStatus(record.id, DownloadStatus.CANCELLED)
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed: record=${record.id}", e)
                    historyManager.updateStatus(
                        record.id,
                        DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Download failed"
                    )
                } finally {
                    activeDownloads.remove(key)
                }
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
        val opened = downloadRepository.openDownloadStream(target)

        try {
            // 使用 okdownload 下载
            var lastReported = -1
            val totalRead = okDownloadManager.download(url, opened.outputStream) { currentBytes, totalBytes ->
                if (totalBytes > 0) {
                    val percent = (currentBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
                    if (percent - lastReported >= PROGRESS_STEP) {
                        lastReported = percent
                        Log.d(TAG, "Download progress: $recordId $percent% / $totalBytes")
                        // 用 runBlocking 直接写 Room。
                        // 此 lambda 在 OkDownloadManager.progressScope（Dispatchers.IO）执行，
                        // 不会阻塞 UI。OkDownloadManager.download() 在返回前会先 join
                        // progressJob（即等此 lambda 执行完毕），保证 COMPLETED 写入前
                        // 所有进度 UPDATE 已落盘，彻底消除"COMPLETED 被 RUNNING 覆盖"的竞态。
                        runBlocking {
                            historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = percent)
                        }
                    }
                }
            }

            // 完成。顺序不可颠倒：必须先 flush + close 输出流，再把 IS_PENDING 置 0。
            // IS_PENDING 归零的瞬间文件立刻对系统/图库/文件管理器可见，若此时数据
            // 还留在缓冲区未落盘，用户看到的就是 0 字节或损坏文件。
            opened.outputStream.flush()
            opened.outputStream.close()

            if (opened.requiresFinalize) downloadRepository.finalizeDownload(opened.uri)
            historyManager.updateStatus(
                recordId,
                DownloadStatus.COMPLETED,
                progress = 100,
                filePath = opened.uri.toString(),
                fileSize = totalRead
            )
            Log.i(TAG, "executeDownload SUCCESS: record=$recordId size=$totalRead")
        } catch (e: Throwable) {
            // 失败/取消：先关流，再删掉半截文件（MediaStore 的 IS_PENDING=1 孤儿行
            // 或 SAF 目录里的 0 字节空文档），避免反复重试堆积垃圾。
            runCatching { opened.outputStream.close() }
            downloadRepository.abandonDownload(opened.uri, opened.isSaf)
            throw e
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

    /**
     * 取消指定下载任务。
     *
     * BUG-003 fix: 取消语义由 OkDownloadManager.taskEnd 在 cause==CANCELLED 时抛
     * CancellationException，让协程 catch 接住并标 CANCELLED。这里不再维护 cancelRequests map。
     */
    fun cancel(id: String) {
        val record = historyManager.getRecord(id) ?: return
        val key = record.dedupKey ?: return
        activeDownloads.remove(key)
        okDownloadManager.cancel(record.url)
        Log.i(TAG, "cancel: record=$id")
    }

    /** 重试下载任务。 */
    suspend fun retry(id: String): String? {
        val record = historyManager.getRecord(id) ?: return null
        // 重试前清除可能损坏的 okdownload 断点，避免 offset 不匹配错误
        okDownloadManager.clearBreakpoint(record.url)
        historyManager.updateStatus(id, DownloadStatus.PENDING, progress = 0, errorMessage = null)
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
