package com.pawchive.data.repository

import android.content.Context
import android.util.Log
import com.pawchive.core.api.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * HTTP 流式下载管理器（BUG-005：替换 okdownload 1.0.7）。
 *
 * 为什么移除 okdownload（源码逐行取证，见 .tmp-okdownload-src 与 2026-09-12 工作日志）：
 * 1. 断点续传从未生效——旧实现每次尝试前都清断点 + 删临时文件，okdownload 的核心价值
 *    被自己的调用方禁用，留下的是全套风险：多块下载（块数由服务器 Accept-Ranges 决定，
 *    1–5 块）、sync 线程、breakpoint store、offset 校验。
 * 2. [com.liulishuo.okdownload.core.file.MultiPointOutputStream.ensureSync] 在等待落盘
 *    同步时调用无超时的 `LockSupport.park()`；sync 线程一旦因异常退出（异常还被吞掉），
 *    fetch 线程永久阻塞，okdownload 永不派发 taskEnd → 上层协程永不 resume →
 *    记录永久停在最后一个进度值（表现为"卡在 99%"），并泄漏 Semaphore 许可与并发槽。
 * 3. [com.liulishuo.okdownload.core.file.MultiPointOutputStream.inspectComplete] 的
 *    "The current offset on block-info isn't update correct" 校验在落盘进度与总长度
 *    不一致（响应被截断/增量未提交）时直接抛 IOException。
 * 4. 多块下载会对同一文件并发发起多个 Range 请求，与文件服务器运维方
 *    "不要每秒对同一文件发起超过 1 次请求"的要求直接冲突。
 *
 * 替代实现的行为约定：
 * - 单连接单请求，直接流式写临时文件；成功后由 [download] 分块拷贝到目标输出流
 *   （保留"下载阶段不碰目标流"的既有正确性设计，重试不会污染已写出的字节）。
 * - 进度取实际已读字节数（旧实现用 file.length()，而 okdownload 预分配会让文件长度
 *   在下载开始前就等于总长度，导致进度一开局就被 coerce 成 99%）。
 * - 读循环每 64KB 检查一次协程活跃性：取消可即时打断下载与写出两个阶段。
 * - OkHttp readTimeout(60s) 保证连接停滞会超时失败，而不是无限等待。
 * - 校验 Content-Length 与实际读取字节数：响应被截断时快速失败并进入重试，
 *   不再产出"半截文件 + 隐晦报错"。
 */
@Singleton
class HttpDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HttpDownloadManager"
        private const val MAX_RETRY = 3
        private const val TEMP_DIR_NAME = "download_tmp"

        /**
         * 网络读缓冲区（64KB）。分块读取的目的：
         * 1. 每块之间插入协程活跃性检查，取消可即时打断；
         * 2. 进度按实际读取字节数推进，弱网下也能看到平滑进度。
         */
        private const val READ_BUFFER_SIZE = 64 * 1024

        /**
         * 同一文件两次请求的最小间隔（毫秒）。
         * 文件服务器运维方要求：不要每秒对同一文件发起超过 1 次请求。
         * 重试时至少等待 1 秒再发起下一次请求。
         */
        private const val MIN_RETRY_INTERVAL_MS = 1000L

        /**
         * 临时文件 → 目标输出流的拷贝缓冲区大小（64KB）。
         * 分块拷贝的目的是在每块之间插入协程活跃性检查：
         * 整段 copyTo() 是不可中断的阻塞 IO，下载完成后若用户取消
         * （或调用方协程被取消），旧实现无法打断写出阶段。
         */
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }

    /** 正在执行的请求（url -> Call），用于外部取消。 */
    private val runningCalls = ConcurrentHashMap<String, okhttp3.Call>()

    /**
     * 内部协程作用域，专用于进度 Channel 消费。
     * @Singleton + SupervisorJob 保证整个 App 生命周期内存活，不会因 Activity/Fragment 销毁被取消。
     */
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 旧 okdownload 引擎遗留临时目录的一次性清理标记。 */
    @Volatile private var legacyCleaned = false    /**
     * 临时文件目录。
     *
     * 优先外部私有目录（空间随设备存储，通常数 GB），回退内部 cache。
     * 此前固定使用 context.cacheDir（内部缓存，配额通常仅数十 MB 且与 Coil 图片
     * 缓存争用），下载大附件时极易 ENOSPC。
     */
    private fun tempDir(): File {
        if (!legacyCleaned) {
            legacyCleaned = true
            // 一次性清理旧 okdownload 引擎遗留的临时目录（均为本应用私有缓存，可安全删除）
            runCatching {
                context.getExternalFilesDir(null)?.let { File(it, "okdownload").deleteRecursively() }
                File(context.cacheDir, "okdownload").deleteRecursively()
            }
        }
        val dir = context.getExternalFilesDir(TEMP_DIR_NAME)
            ?: File(context.cacheDir, TEMP_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create temp dir: ${dir.absolutePath}")
        }
        return dir
    }

    /** 稳定且无碰撞的临时文件名（SHA-256 截断，此前用 url.hashCode() 会撞名）。 */
    private fun tempFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return "dl_" + digest.joinToString("") { "%02x".format(it) }.take(32) + ".tmp"
    }

    /**
     * 下载并写入 [outputStream]，返回写入字节数。
     * 输出流由调用方负责关闭；本方法保证临时文件被清理。
     *
     * 进度回调解耦：
     * - 下载协程只做 channel.trySend，不阻塞下载读循环
     * - 独立协程消费 Channel，串行回调 onProgress
     * - download() 在返回前 await 最后一个进度处理完毕
     *   → 调用方继续执行时，所有进度 UPDATE 已落盘，不会再覆盖后续的 COMPLETED
     */
    suspend fun download(
        url: String,
        outputStream: java.io.OutputStream,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Long {
        // Channel.CONFLATED：只保留最新进度，读循环密集回调时不会堆队列阻塞
        val progressChannel = Channel<Pair<Long, Long>>(capacity = Channel.CONFLATED)

        // 启动独立协程消费进度 Channel，串行回调 onProgress
        val progressJob = progressScope.launch {
            for ((current, total) in progressChannel) {
                onProgress(current, total)
            }
        }

        var tempFileRef: File? = null
        try {
            val file = downloadToFile(url) { current, total ->
                progressChannel.trySend(current to total)
            }
            tempFileRef = file

            // 下载完成 → 关闭 Channel → progressJob 消费完积压的最后一个进度后退出
            progressChannel.close()
            progressJob.join()  // 关键：等所有进度写 Room 完成再继续

            // 分块拷贝：每块之间检查协程活跃性。整段 copyTo() 是不可中断的阻塞 IO，
            // 写出阶段（大文件可达分钟级）无法被取消，记录会永远停在"下载中 99%"。
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                }
            }
            outputStream.flush()
            return file.length()
        } finally {
            // 异常路径也要收尾
            if (!progressChannel.isClosedForSend) progressChannel.close()
            if (!progressJob.isCompleted) { progressJob.cancel(); progressJob.join() }
            tempFileRef?.let { if (!it.delete()) Log.w(TAG, "Failed to delete temp file: ${it.absolutePath}") }
        }
    }

    /**
     * 下载到临时文件，带重试。
     *
     * 设计要点：**下载阶段不碰目标输出流**。若直接写目标流，某次尝试在写出中途失败后，
     * 已写入的字节仍留在流里，重试会从头再写一遍并追加，最终产出体积翻倍、内容损坏的
     * 文件。先落到临时文件、成功后一次性写出，重试只影响临时文件。
     *
     * @return 下载完成的临时文件；调用方负责在使用后删除。
     */
    suspend fun downloadToFile(
        url: String,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRY) {
            // 每次尝试前取消可能残留的旧请求并删除残片
            runningCalls.remove(url)?.cancel()
            deleteTempFile(url)

            if (attempt > 1) {
                Log.w(TAG, "Retry attempt $attempt for $url (last error: ${lastError?.message})")
                // 同一文件两次请求至少间隔 1 秒（文件服务器运维方要求），
                // 并随重试次数线性退避，避免瞬时密集重试。
                delay(MIN_RETRY_INTERVAL_MS * attempt)
            }

            try {
                return doDownloadToFile(url, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (!isRetryable(e)) throw e
                // 可恢复错误（网络 IO / 5xx / 响应截断），继续循环重试
            }
        }
        throw lastError ?: IOException("Download failed after $MAX_RETRY attempts")
    }

    /** 网络层 IO 错误与服务端 5xx 可重试；4xx（鉴权/不存在/CF 拦截）重试无意义，直接失败。 */
    private fun isRetryable(e: Exception): Boolean {
        if (e is HttpDownloadException) return e.code >= 500
        return e is IOException
    }

    /** 单次下载执行（不含重试逻辑）。 */
    private suspend fun doDownloadToFile(
        url: String,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val tempFile = File(tempDir(), tempFileName(url))
        // 清掉上一次失败留下的残片，避免与本次写入混叠
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w(TAG, "Failed to delete stale temp file: ${tempFile.absolutePath}")
        }

        val call = ApiClient.downloadOkHttpClient
            .newCall(Request.Builder().url(url).get().build())
        runningCalls[url] = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpDownloadException(response.code, url)
                }
                val body = response.body ?: throw IOException("Empty response body: $url")
                val total = body.contentLength()
                var read = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { out ->
                        val buffer = ByteArray(READ_BUFFER_SIZE)
                        while (true) {
                            // 每块之间检查协程活跃性：取消可即时打断
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                        out.flush()
                    }
                }

                // 截断检测：响应声明的 Content-Length 与实际读取字节数不一致时快速失败。
                // 旧 okdownload 实现对这种情况只能在最后抛出
                // "block-info isn't update correct" 这类隐晦错误。
                if (total > 0 && read != total) {
                    throw IOException("Connection closed prematurely: $read != $total")
                }
            }
            tempFile
        } catch (e: IOException) {
            runCatching { tempFile.delete() }
            // 外部 cancel(url) 或协程取消导致的中断 → 统一走 CancellationException，
            // 让 DownloadCenter 标 CANCELLED 而不是 FAILED。
            if (call.isCanceled() || !currentCoroutineContext().isActive) {
                throw CancellationException("Download cancelled")
            }
            throw e
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw e
        } finally {
            runningCalls.remove(url)
        }
    }

    private fun deleteTempFile(url: String) {
        val tempFile = File(tempDir(), tempFileName(url))
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w(TAG, "Failed to delete temp file: ${tempFile.absolutePath}")
        }
    }

    /**
     * 取消指定 URL 的下载。
     * Call.cancel() 会让阻塞中的 socket 读立即抛 IOException，
     * doDownloadToFile 捕获后映射为 CancellationException。
     */
    fun cancel(url: String) {
        runningCalls.remove(url)?.cancel()
        deleteTempFile(url)
    }

    /** 服务端返回非 2xx 时抛出；用于区分"可重试的 5xx"与"重试无意义的 4xx"。 */
    private class HttpDownloadException(val code: Int, url: String) :
        IOException("HTTP $code for $url")
}
