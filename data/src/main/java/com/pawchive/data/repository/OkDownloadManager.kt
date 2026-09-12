package com.pawchive.data.repository

import android.content.Context
import android.util.Log
import com.liulishuo.okdownload.DownloadListener
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.dispatcher.DownloadDispatcher
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.connection.DownloadOkHttp3Connection
import com.pawchive.core.api.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class OkDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OkDownloadManager"
        @Volatile private var initialized = false
        private const val MAX_RETRY = 3
        private const val TEMP_DIR_NAME = "okdownload"

        /**
         * okdownload 内部调度器的最大并行下载数。
         * 与 DownloadCenter 的 Semaphore(5) 形成双重约束：协程层排队后，
         * okdownload 自身也不会同时启动超过 5 个连接。
         */
        private const val MAX_PARALLEL_COUNT = 5

        /**
         * 同一文件两次请求的最小间隔（毫秒）。
         * 文件服务器运维方要求：不要每秒对同一文件发起超过 1 次请求。
         * 重试时至少等待 1 秒再发起下一次请求。
         */
        private const val MIN_RETRY_INTERVAL_MS = 1000L
    }

    // 正在运行的下载任务（url -> task），用于外部取消
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()

    @Synchronized
    fun init() {
        if (initialized) return
        // 必须使用下载专用客户端：sharedOkHttpClient 带 60s callTimeout，
        // 会在流式读取大文件时中断下载（详见 HttpClientFactory.createDownloadClient）。
        val factory = DownloadOkHttp3Connection.Factory()
            .setBuilder(ApiClient.downloadOkHttpClient.newBuilder())
        // okdownload 默认 maxParallelRunningCount=5，此处显式设置以与运维方要求一致，
        // 同时与 DownloadCenter 的 Semaphore(5) 形成双重约束。
        DownloadDispatcher.setMaxParallelRunningCount(MAX_PARALLEL_COUNT)
        OkDownload.setSingletonInstance(
            OkDownload.Builder(context)
                .connectionFactory(factory)
                .build()
        )
        initialized = true
        Log.i(TAG, "okdownload initialized")
    }

    /**
     * 临时文件目录。
     *
     * 优先外部私有目录（空间随设备存储，通常数 GB），回退内部 cache。
     * 此前固定使用 context.cacheDir（内部缓存，配额通常仅数十 MB 且与 Coil 图片
     * 缓存争用），下载大附件时极易 ENOSPC，被 okdownload 包装成含糊的 IO 错误。
     */
    private fun tempDir(): File {
        val dir = context.getExternalFilesDir(TEMP_DIR_NAME)
            ?: File(context.cacheDir, TEMP_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create temp dir: ${dir.absolutePath}")
        }
        return dir
    }

    /** 稳定且无碰撞的临时文件名（此前用 url.hashCode()，不同 URL 可能撞名）。 */
    private fun tempFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return "dl_" + digest.joinToString("") { "%02x".format(it) }.take(32) + ".tmp"
    }

    /**
     * 清除指定任务对应的 okdownload 断点记录。
     * 下载失败/取消后必须调用，否则重试时旧断点与已删除的临时文件不匹配，
     * 会抛出 "The current offset on block-info isn't update correct" 错误。
     */
    private fun clearBreakpoint(task: DownloadTask) {
        if (!initialized) return
        try {
            OkDownload.with().breakpointStore().remove(task.id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear breakpoint for task ${task.id}", e)
        }
    }

    /** 按 URL 清除断点（供外部重试前调用）。 */
    fun clearBreakpoint(url: String) {
        init()
        val tempDir = tempDir()
        val task = buildTask(url, tempDir)
        clearBreakpoint(task)
    }

    private fun buildTask(url: String, tempDir: File): DownloadTask {
        return DownloadTask.Builder(url, tempDir)
            .setFilename(tempFileName(url))
            // 关键：禁止从响应头推断文件名。否则 okdownload 会用
            // Content-Disposition 覆盖我们指定的临时文件名，导致
            // providedPathFile 变化 → 任务 id 变化 → clearBreakpoint() 清不掉
            // 真正的断点记录，重试时必然撞上 offset 不匹配错误。
            .setFilenameFromResponse(false)
            .setMinIntervalMillisCallbackProcess(500)
            .setPassIfAlreadyCompleted(false)
            .setAutoCallbackToUIThread(false)
            .build()
    }

    /**
     * 下载到临时文件。
     *
     * 设计要点（与旧实现的根本差异）：**下载阶段不碰 OutputStream**。
     * 旧实现在 okdownload 的 taskEnd 回调里直接 `copyTo(outputStream)`，
     * 而 download() 自带重试——若某次尝试在拷贝中途失败，已写入的字节仍留在流里，
     * 重试会从头再写一遍并追加，最终产出体积翻倍、内容损坏的文件。
     * 改为"先落到临时文件，成功后一次性写出"，重试只影响临时文件。
     *
     * @return 下载完成的临时文件；调用方负责在使用后删除。
     */
    suspend fun downloadToFile(
        url: String,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        init()

        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRY) {
            // 每次尝试前取消可能残留的旧任务并清断点
            runningTasks.remove(url)?.cancel()
            clearBreakpoint(url)

            if (attempt > 1) {
                Log.w(TAG, "Retry attempt $attempt for $url (last error: ${lastError?.message})")
                // 同一文件两次请求至少间隔 1 秒（文件服务器运维方要求），
                // 并随重试次数线性退避，避免瞬时密集重试。
                delay(MIN_RETRY_INTERVAL_MS * attempt)
            }

            try {
                return doDownloadToFile(url, onProgress)
            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                val recoverable = msg.contains("SAME_TASK_BUSY", true) ||
                    msg.contains("Update store failed", true) ||
                    msg.contains("block-info", true)
                if (!recoverable || attempt == MAX_RETRY) {
                    throw e
                }
                // 可恢复错误，继续循环重试
            }
        }
        throw lastError ?: Exception("Download failed after $MAX_RETRY attempts")
    }

    /**
     * 下载并写入 [outputStream]，返回写入字节数。
     * 输出流由调用方负责关闭；本方法保证临时文件被清理。
     */
    suspend fun download(
        url: String,
        outputStream: OutputStream,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Long {
        val file = downloadToFile(url, onProgress)
        return try {
            file.inputStream().use { input -> input.copyTo(outputStream) }
            outputStream.flush()
            file.length()
        } finally {
            if (!file.delete()) Log.w(TAG, "Failed to delete temp file: ${file.absolutePath}")
        }
    }

    /** 单次下载执行（不含重试逻辑）。 */
    private suspend fun doDownloadToFile(
        url: String,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val tempDir = tempDir()
        val tempFile = File(tempDir, tempFileName(url))
        // 清掉上一次失败留下的残片：断点已被 clearBreakpoint() 删除，
        // 残留文件与新断点不匹配会触发 offset 校验失败。
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w(TAG, "Failed to delete stale temp file: ${tempFile.absolutePath}")
        }

        return suspendCancellableCoroutine { cont ->
            val task = buildTask(url, tempDir)
            var resumed = false

            runningTasks[url] = task

            cont.invokeOnCancellation {
                runningTasks.remove(url)
                runCatching { task.cancel() }
                runCatching { tempFile.delete() }
                clearBreakpoint(task)
            }

            /**
             * 统一出口：保证只 resume 一次，且协程已取消时不再 resume
             * （对已取消的 continuation 调用 resume 会抛 IllegalStateException("Already resumed")）。
             */
            fun finish(result: Result<File>) {
                if (resumed) return
                resumed = true
                runningTasks.remove(url)
                if (!cont.isActive) {
                    runCatching { tempFile.delete() }
                    return
                }
                if (result.isSuccess) {
                    cont.resume(result.getOrThrow())
                } else {
                    cont.resumeWithException(result.exceptionOrNull()!!)
                }
            }

            try {
                task.enqueue(object : DownloadListener {
                    override fun taskStart(task: DownloadTask) {}
                    override fun connectTrialStart(task: DownloadTask, h: MutableMap<String, MutableList<String>>) {}
                    override fun connectTrialEnd(task: DownloadTask, code: Int, h: MutableMap<String, MutableList<String>>) {}
                    override fun downloadFromBeginning(task: DownloadTask, info: BreakpointInfo, cause: ResumeFailedCause) {}
                    override fun downloadFromBreakpoint(task: DownloadTask, info: BreakpointInfo) {}
                    override fun connectStart(task: DownloadTask, blockIndex: Int, h: MutableMap<String, MutableList<String>>) {}
                    override fun connectEnd(task: DownloadTask, blockIndex: Int, code: Int, h: MutableMap<String, MutableList<String>>) {}
                    override fun fetchStart(task: DownloadTask, blockIndex: Int, contentLength: Long) {}
                    override fun fetchProgress(task: DownloadTask, blockIndex: Int, increaseBytes: Long) {
                        val current = task.file?.length() ?: 0L
                        val total = task.info?.totalLength ?: 0L
                        onProgress(current, total)
                    }
                    override fun fetchEnd(task: DownloadTask, blockIndex: Int, contentLength: Long) {}
                    override fun taskEnd(task: DownloadTask, cause: EndCause, realCause: Exception?) {
                        if (cause == EndCause.COMPLETED) {
                            val f = task.file ?: tempFile
                            if (f.exists() && f.length() > 0L) {
                                clearBreakpoint(task)
                                finish(Result.success(f))
                            } else {
                                runCatching { f.delete() }
                                clearBreakpoint(task)
                                finish(Result.failure(Exception("File not found or empty after download")))
                            }
                        } else {
                            runCatching { tempFile.delete() }
                            clearBreakpoint(task)
                            // BUG-003 fix: 区分"用户/框架主动取消"与"真失败"。
                            // 主动取消走 CancellationException，让协程走结构化取消语义；
                            // DownloadCenter 的 catch (CancellationException) 会标 CANCELLED 而非 FAILED。
                            if (cause == EndCause.CANCELED) {
                                finish(Result.failure(CancellationException("Download cancelled")))
                            } else {
                                finish(Result.failure(realCause ?: Exception("Download failed: $cause")))
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                // enqueue 同步抛出异常（如 SAME_TASK_BUSY）
                clearBreakpoint(task)
                finish(Result.failure(e))
            }
        }
    }

    fun cancel(url: String) {
        runningTasks.remove(url)?.cancel()
        val tempFile = File(tempDir(), tempFileName(url))
        runCatching { tempFile.delete() }
        clearBreakpoint(url)
    }
}
