package com.pawchive.data.repository

import android.content.Context
import android.util.Log
import com.liulishuo.okdownload.DownloadListener
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.connection.DownloadOkHttp3Connection
import com.pawchive.core.api.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
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
    }

    // 正在运行的下载任务（url -> task），用于外部取消
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val factory = DownloadOkHttp3Connection.Factory()
                .setBuilder(ApiClient.sharedOkHttpClient.newBuilder())
            OkDownload.setSingletonInstance(
                OkDownload.Builder(context).connectionFactory(factory).build()
            )
            initialized = true
            Log.i(TAG, "okdownload initialized")
        }
    }

    /**
     * 清除指定 URL 对应的 okdownload 断点记录。
     * 下载失败/取消后必须调用，否则重试时旧断点与已删除的临时文件不匹配，
     * 会抛出 "The current offset on block-info isn't update correct" 错误。
     */
    private fun clearBreakpoint(task: DownloadTask) {
        try {
            OkDownload.with().breakpointStore().remove(task.id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear breakpoint for task ${task.id}", e)
        }
    }

    /** 按 URL 清除断点（供外部重试前调用）。 */
    fun clearBreakpoint(url: String) {
        init()
        val tempDir = File(context.cacheDir, "okdownload")
        val fileName = "dl_${url.hashCode()}.tmp"
        val task = DownloadTask.Builder(url, tempDir)
            .setFilename(fileName)
            .build()
        clearBreakpoint(task)
    }

    /**
     * 下载入口，带自动重试。
     * 处理 okdownload 的两类可恢复错误：
     * - SAME_TASK_BUSY：上一个任务未从内部调度器清理，快速重试时冲突
     * - Update store failed：断点数据库写入失败，需清断点后重试
     */
    suspend fun download(
        url: String,
        outputStream: OutputStream,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Long {
        init()

        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRY) {
            // 每次尝试前取消可能残留的旧任务并清断点
            runningTasks.remove(url)?.cancel()
            clearBreakpoint(url)

            if (attempt > 1) {
                Log.w(TAG, "Retry attempt $attempt for $url (last error: ${lastError?.message})")
                delay(300L * attempt)
            }

            try {
                return doDownload(url, outputStream, onProgress)
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

    /** 单次下载执行（不含重试逻辑）。 */
    private suspend fun doDownload(
        url: String,
        outputStream: OutputStream,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit
    ): Long {
        val tempDir = File(context.cacheDir, "okdownload")
        if (!tempDir.exists()) tempDir.mkdirs()
        val fileName = "dl_${url.hashCode()}.tmp"

        return suspendCancellableCoroutine { cont ->
            var resumed = false
            val task = DownloadTask.Builder(url, tempDir)
                .setFilename(fileName)
                .setMinIntervalMillisCallbackProcess(500)
                .setPassIfAlreadyCompleted(false)
                .setAutoCallbackToUIThread(false)
                .build()

            runningTasks[url] = task

            cont.invokeOnCancellation {
                runningTasks.remove(url)
                task.cancel()
                File(tempDir, fileName).delete()
                clearBreakpoint(task)
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
                        runningTasks.remove(url)
                        if (resumed) return
                        resumed = true
                        if (cause == EndCause.COMPLETED) {
                            try {
                                val f = task.file
                                if (f != null && f.exists()) {
                                    f.inputStream().use { it.copyTo(outputStream) }
                                    val bytes = f.length()
                                    f.delete()
                                    clearBreakpoint(task)
                                    cont.resume(bytes)
                                } else {
                                    cont.resumeWithException(Exception("File not found after download"))
                                }
                            } catch (e: Exception) {
                                task.file?.delete()
                                clearBreakpoint(task)
                                cont.resumeWithException(e)
                            }
                        } else {
                            task.file?.delete()
                            clearBreakpoint(task)
                            cont.resumeWithException(realCause ?: Exception("Download failed: $cause"))
                        }
                    }
                })
            } catch (e: Exception) {
                // enqueue 同步抛出异常（如 SAME_TASK_BUSY）
                runningTasks.remove(url)
                if (!resumed) {
                    resumed = true
                    cont.resumeWithException(e)
                }
            }
        }
    }

    fun cancel(url: String) {
        runningTasks.remove(url)?.cancel()
        val tempDir = File(context.cacheDir, "okdownload")
        val fileName = "dl_${url.hashCode()}.tmp"
        File(tempDir, fileName).delete()
        clearBreakpoint(url)
    }
}
