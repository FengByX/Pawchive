package com.pawchive.data.repository

import android.util.Log
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.data.download.DownloadNotificationController
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    private val httpDownloadManager: HttpDownloadManager,
    private val notificationController: DownloadNotificationController,
    private val settingsManager: com.pawchive.core.store.SettingsManager
) : DownloadEnqueuer {

    companion object {
        private const val TAG = "DownloadCenter"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_STEP = 5

        /**
         * 同时进行的下载任务上限（默认值）。
         * 文件服务器运维方要求：不要同时进行超过 5 个下载任务，避免占用过多带宽。
         * 超过上限的任务会挂起等待，直至有下载完成释放许可。
         * 实际上限以设置页配置为准（SettingsManager 钳制 1–5），见 [withDownloadPermit]。
         */
        private const val MAX_CONCURRENT_DOWNLOADS = 5

        /** 仅 Wi-Fi 下载时轮询网络状态的间隔（毫秒）。 */
        private const val WIFI_WAIT_POLL_MS = 30_000L

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

    /**
     * 并发下载信号量（可动态调整，FEATURE：设置页"最大并发下载数"）。
     * kotlinx.coroutines.Semaphore 不支持运行中扩容，故在队列完全空闲
     * （[permitHoldCount] == 0）时按新配置重建，进行中的任务不受影响。
     * 任意时刻并发数都不超过设置值，且设置值被钳制在 5 以内（服务器合规）。
     */
    private val semaphoreLock = Any()
    private var configuredPermits = MAX_CONCURRENT_DOWNLOADS
    private var downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private var permitHoldCount = 0

    init {
        // 初始许可数按用户设置（钳制 1–5），而非固定默认值
        configuredPermits = settingsManager.getMaxConcurrentDownloads()
        downloadSemaphore = Semaphore(configuredPermits)
    }

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

    /**
     * 正在执行的下载协程（recordId -> Job）。
     *
     * 协程级取消（Job.cancel）覆盖任务全生命周期（下载阶段 + 临时文件→目标流写出阶段）；
     * 协程退出时在 finally 中按 Job 身份条件移除，与 activeDownloads 一样
     * 以 finally 为唯一清理点。
     */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * 已请求暂停的记录 ID（pause() 写入、协程 CancellationException 处理路径消费）。
     * 用于把"暂停导致的协程取消"与"用户取消"区分开：前者标 PAUSED，后者标 CANCELLED。
     */
    private val pausedRecordIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * 各记录最近一次上报的进度百分比（recordId -> percent）。
     * 暂停时 Room 内存缓存可能尚未反映最后一次进度 UPDATE，用该表取准确值落 PAUSED 状态。
     */
    private val lastProgress = ConcurrentHashMap<String, Int>()

    // 取消语义：取消协程 + 取消在飞 HTTP Call，HttpDownloadManager 将 Call 中断映射为
    // CancellationException 走协程结构化并发，catch 块据此标 CANCELLED 而非 FAILED。

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

        val job = scope.launch {
            // 仅 Wi-Fi 下载（FEATURE 设置项扩展批次二）：
            // 开启后无 Wi-Fi 时任务保持 PENDING 并挂起等待，不占用并发许可；
            // 等待期间可被取消/暂停（cancel/pause 已把记录写入对应状态）。
            try {
                waitUntilWifiIfConfigured()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (pausedRecordIds.remove(record.id)) {
                        val progress = lastProgress.remove(record.id) ?: 0
                        historyManager.updateStatus(record.id, DownloadStatus.PAUSED, progress = progress)
                        notificationController.taskEnded(
                            record.copy(status = DownloadStatus.PAUSED, progress = progress)
                        )
                    } else {
                        lastProgress.remove(record.id)
                        historyManager.updateStatus(record.id, DownloadStatus.CANCELLED)
                        notificationController.taskEnded(record.copy(status = DownloadStatus.CANCELLED))
                    }
                }
                activeDownloads.remove(key)
                activeJobs.remove(record.id, currentCoroutineContext().job)
                throw e
            }
            // 并发上限：可配置（1–5，默认 5）。5 为文件服务器运维方要求的硬上限。
            // 超过上限的任务在此挂起等待，有下载完成释放许可后才继续。
            withDownloadPermit {
                try {
                    executeDownload(record)
                } catch (e: CancellationException) {
                    // 协程取消路径分两支：
                    // - 暂停（pause 写入 pausedRecordIds）→ 标 PAUSED，临时文件保留待续传
                    // - 取消 → 标 CANCELLED，临时文件已由 HttpDownloadManager.cancel 删除
                    // 状态写入用 NonCancellable 包裹：协程已处于取消态，
                    // 不包裹的话 Room suspend UPDATE 会在第一个挂起点被取消吞掉。
                    withContext(NonCancellable) {
                        if (pausedRecordIds.remove(record.id)) {
                            val progress = lastProgress.remove(record.id) ?: 0
                            Log.d(TAG, "Download paused: record=${record.id} progress=$progress")
                            historyManager.updateStatus(record.id, DownloadStatus.PAUSED, progress = progress)
                            notificationController.taskEnded(
                                record.copy(status = DownloadStatus.PAUSED, progress = progress)
                            )
                        } else {
                            lastProgress.remove(record.id)
                            Log.d(TAG, "Download cancelled: record=${record.id}")
                            historyManager.updateStatus(record.id, DownloadStatus.CANCELLED)
                            notificationController.taskEnded(
                                record.copy(status = DownloadStatus.CANCELLED)
                            )
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    val progress = lastProgress.remove(record.id) ?: 0
                    Log.e(TAG, "Download failed: record=${record.id}", e)
                    historyManager.updateStatus(
                        record.id,
                        DownloadStatus.FAILED,
                        progress = progress,
                        errorMessage = e.message ?: "Download failed"
                    )
                    notificationController.taskEnded(
                        record.copy(
                            status = DownloadStatus.FAILED,
                            progress = progress,
                            errorMessage = e.message ?: "Download failed"
                        )
                    )
                } finally {
                    activeDownloads.remove(key)
                    // BUG-004 fix: 按 Job 身份条件移除——若协程退出后同记录已重新入队
                    // （retry/再点下载），新 Job 已占位，这里不能误删。
                    activeJobs.remove(record.id, currentCoroutineContext().job)
                }
            }
        }
        activeJobs[record.id] = job
        // 防御：协程可能在首次挂起前就同步失败退出，且 finally 先于上一行执行，
        // 此时上面写入的是已完成的 Job，补一次条件清理。
        if (job.isCompleted) activeJobs.remove(record.id, job)
    }

    /**
     * 以可动态调整的并发许可执行下载（FEATURE：设置页"最大并发下载数"）。
     * kotlinx.coroutines.Semaphore 不支持运行中扩容/缩容，故仅在队列完全空闲
     * （[permitHoldCount] == 0）时按新配置重建信号量；进行中的任务全部排空后才生效。
     * 设置值由 SettingsManager 钳制在 1–5，保证不突破文件服务器运维方要求的并发上限。
     */
    private suspend fun <T> withDownloadPermit(block: suspend () -> T): T {
        val semaphore = synchronized(semaphoreLock) {
            val desired = settingsManager.getMaxConcurrentDownloads()
            if (permitHoldCount == 0 && desired != configuredPermits) {
                configuredPermits = desired
                downloadSemaphore = Semaphore(desired)
            }
            downloadSemaphore
        }
        synchronized(semaphoreLock) { permitHoldCount++ }
        try {
            return semaphore.withPermit { block() }
        } finally {
            synchronized(semaphoreLock) { permitHoldCount-- }
        }
    }

    /**
     * 仅 Wi-Fi 下载（FEATURE）：开启"仅 Wi-Fi 下载"且当前不在 Wi-Fi 时挂起等待，
     * 每 30 秒轮询一次网络状态，网络就绪后继续。未开启该设置时立即返回。
     */
    private suspend fun waitUntilWifiIfConfigured() {
        if (!settingsManager.isWifiOnlyDownloadEnabled()) return
        while (!com.pawchive.core.util.NetworkUtils.isOnWifi(context)) {
            Log.d(TAG, "Wifi-only download: waiting for Wi-Fi, record pending")
            kotlinx.coroutines.delay(WIFI_WAIT_POLL_MS)
        }
    }

    private suspend fun executeDownload(record: DownloadRecord) {
        val recordId = record.id
        val url = record.url
        val fileName = record.fileName

        Log.i(TAG, "executeDownload START: record=$recordId url=$url")

        // 标记为运行中
        historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = 0)
        // 通知栏：发出初始通知并确保持有前台服务（dataSync）保活
        notificationController.taskStarted(record)

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
            // 使用 HttpDownloadManager 流式下载（BUG-005：已替换 okdownload）
            var lastReported = -1
            val totalRead = httpDownloadManager.download(url, opened.outputStream) { currentBytes, totalBytes ->
                // 通知栏进度（控制器内部自带 500ms 节流与 EMA 测速）：
                // 每次 64KB 读块都上报，不受下方 5% Room 写入步长限制，
                // 保证通知栏速度/进度平滑。
                notificationController.taskProgress(recordId, fileName, currentBytes, totalBytes)
                if (totalBytes > 0) {
                    val percent = (currentBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
                    if (percent - lastReported >= PROGRESS_STEP) {
                        lastReported = percent
                        lastProgress[recordId] = percent
                        Log.d(TAG, "Download progress: $recordId $percent% / $totalBytes")
                        // 用 runBlocking 直接写 Room。
                        // 此 lambda 在 HttpDownloadManager.progressScope（Dispatchers.IO）执行，
                        // 不会阻塞 UI。HttpDownloadManager.download() 在返回前会先 join
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
            lastProgress.remove(recordId)
            notificationController.taskEnded(
                record.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    filePath = opened.uri.toString(),
                    fileSize = totalRead
                )
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
     * 取消语义：协程级取消（Job.cancel）覆盖包括"临时文件→目标流写出阶段"在内的
     * 所有执行阶段；同时取消在飞的 HTTP Call，解除阻塞中的 socket 读，
     * HttpDownloadManager 会将其映射为 CancellationException → 记录标 CANCELLED。
     * 不在这里提前清 activeDownloads：提前清掉去重标记会在"点了取消但协程尚未退出"
     * 的窗口期内放行同记录二次入队，第二路 RUNNING 进度会把已落盘的 COMPLETED/100
     * 覆盖回 RUNNING/99。标记的清理统一交给协程 finally。
     *
     * 若记录没有活跃协程（暂停中 / 待自愈的 PENDING），直接落 CANCELLED 终态，
     * 避免状态滞留；同时清除暂停标志（取消 = 放弃续传）并通知栏落终态。
     */
    fun cancel(id: String) {
        val record = historyManager.getRecord(id) ?: return
        val job = activeJobs.remove(id)
        // 先清暂停标志再 cancel 协程：catch 路径读取不到标志才会正确标 CANCELLED
        pausedRecordIds.remove(id)
        httpDownloadManager.cancel(record.url)
        if (job == null) {
            lastProgress.remove(id)
            scope.launch {
                historyManager.updateStatus(id, DownloadStatus.CANCELLED)
                notificationController.taskEnded(record.copy(status = DownloadStatus.CANCELLED))
            }
        } else {
            job.cancel()
        }
        Log.i(TAG, "cancel: record=$id")
    }

    /**
     * 暂停指定下载任务。
     *
     * 语义：协程取消 + HTTP Call 取消，但**保留临时文件**——pause 先写入
     * pausedRecordIds（区分暂停/取消）与 HttpDownloadManager 的暂停标志，
     * doDownloadToFile 的异常路径据此跳过临时文件删除；
     * [requestResume] 时以临时文件长度为起点走 HTTP Range 断点续传。
     * 协程 catch 路径负责落 PAUSED 状态并更新通知栏。
     */
    fun pause(id: String) {
        val job = activeJobs[id] ?: run {
            Log.w(TAG, "pause: no active job for record=$id")
            return
        }
        val record = historyManager.getRecord(id) ?: return
        pausedRecordIds.add(id)
        job.cancel()
        httpDownloadManager.pause(record.url)
        Log.i(TAG, "pause: record=$id")
    }

    /**
     * 继续（恢复）暂停中的下载任务。
     * 若 HttpDownloadManager 仍持有该 URL 的暂停标志（本次会话内暂停），
     * downloadToFile 会消费标志并从临时文件长度处 Range 续传；
     * 跨应用重启的暂停记录（内存标志丢失、无 ETag 校验）安全降级为从头下载。
     *
     * 非挂起实现（fire-and-forget）：调用方是通知栏服务 onStartCommand（非协程上下文），
     * 状态写入在 DownloadCenter 自有 scope 上执行。
     */
    fun requestResume(id: String) {
        scope.launch {
            val record = historyManager.getRecord(id) ?: return@launch
            if (record.status != DownloadStatus.PAUSED) {
                retry(id)
                return@launch
            }
            historyManager.updateStatus(
                id,
                DownloadStatus.PENDING,
                progress = record.progress,
                errorMessage = null
            )
            launchDownload(record)
            Log.i(TAG, "requestResume: record=$id")
        }
    }

    /** 重试下载任务。 */
    suspend fun retry(id: String): String? {
        val record = historyManager.getRecord(id) ?: return null
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
