package com.pawchive.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pawchive.data.R
import com.pawchive.core.api.ApiClient
import com.pawchive.core.api.ClearanceCoordinator
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.data.repository.DownloadHistoryManager
import com.pawchive.data.repository.DownloadRepository
import com.pawchive.core.error.ErrorMessageHelper
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 统一下载 Worker（P2 FRONTEND-006 + FEATURE-001 下载中心）。
 *
 * 支持图片/视频/附件下载：
 * - 使用 WorkManager 前台任务，下载在后台存活，App 切到后台也不中断；
 * - 通过通知栏展示进度条（每 5% 更新一次，避免过度刷新）；
 * - 下载完成/失败/取消均更新通知，给用户明确反馈；
 * - 文件写入走 DownloadRepository，优先 SAF 树 URI，回退 MediaStore；
 * - 下载状态通过 DownloadHistoryManager 持久化，供下载中心 UI 展示。
 *
 * 入参：
 * - KEY_URL：下载直链
 * - KEY_FILE_NAME：保存文件名（带扩展名，用于推断 MIME 与显示名）
 * - KEY_MIME_TYPE：可选 MIME 类型，未提供时按文件名扩展名推断
 * - KEY_DOWNLOAD_TYPE：下载类型（IMAGE/VIDEO/ATTACHMENT），默认 VIDEO
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val historyManager: DownloadHistoryManager,
    private val downloadRepository: DownloadRepository
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_DOWNLOAD_TYPE = "download_type"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"

        const val CHANNEL_ID = "pawchive_download"
        private const val NOTIFICATION_ID = 1001
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_STEP = 5 // 每 5% 更新一次通知，避免频繁刷新
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        // ARCH-005：记录 id（UUID 主键）由 DownloadCenter 入队时写入
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return@withContext Result.failure()
        val context = applicationContext

        var outputStream: OutputStream? = null
        var networkResponse: okhttp3.Response? = null
        // 通知权限在 try 外判断一次：catch 分支同样需要据此决定是否发失败通知
        val hasNotifyPermission = hasNotificationPermission(context)
        try {
            // 标记为运行中（移入 try：前台初始化抛异常时也能正确标记 FAILED，不留"永续下载中"）
            historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = 0)

            // 1) 前台通知初始化（即便无通知权限，下载也会继续，只是无可见通知）
            if (hasNotifyPermission) {
                ensureChannel(context)
                setForeground(buildForegroundInfo(context, fileName, 0))
            }

            // ARCH-009：下载前确保已过盾（403 拦截器已非阻塞化，不再线程内等待过盾）
            ClearanceCoordinator.ensureClearance()
            // 2) 复用 ApiClient.sharedOkHttpClient：自动注入 cf_clearance / User-Agent，403 兜底
            val okHttpClient = ApiClient.sharedOkHttpClient.newBuilder()
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .build()

            val response = okHttpClient.newCall(request).execute()
            networkResponse = response
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: run {
                throw Exception("Empty response body")
            }
            val inputStream = body.byteStream()
            val contentLength = body.contentLength()

            // 3) 统一下载入口：优先 SAF 树 URI，回退 MediaStore（P1）
            val mimeType = inputData.getString(KEY_MIME_TYPE) ?: inferMimeType(fileName)
            val downloadTypeStr = inputData.getString(KEY_DOWNLOAD_TYPE) ?: DownloadType.VIDEO.name
            val repoType = parseRepoType(downloadTypeStr, fileName)
            val target = DownloadRepository.DownloadTarget(
                type = repoType,
                displayName = fileName,
                mimeType = mimeType
            )
            val (os, fileUri, requiresFinalize) = downloadRepository.openDownloadStream(target)
            outputStream = os

            // 4) 边写边更新进度
            var totalRead = 0L
            var lastReported = -1
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                os.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (contentLength > 0) {
                    val percent = (totalRead * 100 / contentLength).toInt().coerceIn(0, 100)
                    if (percent - lastReported >= PROGRESS_STEP || percent == 100) {
                        lastReported = percent
                        if (hasNotifyPermission) {
                            notifyProgress(context, fileName, percent)
                        }
                        setProgressAsync(
                            Data.Builder().putInt(KEY_PROGRESS, percent).build()
                        )
                        historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = percent, fileSize = contentLength)
                    }
                }
            }

            if (contentLength > 0 && totalRead != contentLength) {
                throw Exception("Download incomplete: $totalRead/$contentLength bytes")
            }

            // 5) 完成：标记 MediaStore IS_PENDING=0，更新通知和历史
            if (requiresFinalize) downloadRepository.finalizeDownload(fileUri)
            historyManager.updateStatus(
                recordId,
                DownloadStatus.COMPLETED,
                progress = 100,
                filePath = fileUri.toString(),
                fileSize = if (contentLength > 0) contentLength else totalRead
            )
            if (hasNotifyPermission) {
                notifyComplete(context, fileName)
            }
            Result.success()
        } catch (e: Exception) {
            if (isStopped) {
                // WorkManager 2.9.0 取消任务时协程被取消（onCancelled 已移除，onStopped 在
                // CoroutineWorker 中为 final 无法重写），此处通过 isStopped 检测取消并标记状态
                historyManager.updateStatus(recordId, DownloadStatus.CANCELLED)
                Result.failure()
            } else {
                val friendly = ErrorMessageHelper.getFriendlyMessage(context, e)
                historyManager.updateStatus(recordId, DownloadStatus.FAILED, errorMessage = friendly)
                if (hasNotifyPermission) {
                    notifyFailed(context, fileName, friendly)
                }
                Result.failure(Data.Builder().putString(KEY_MESSAGE, friendly).build())
            }
        } finally {
            runCatching { outputStream?.close() }
            runCatching { networkResponse?.close() }
        }
    }

    private fun parseRepoType(typeStr: String, fileName: String): DownloadRepository.DownloadType {
        return when (typeStr) {
            DownloadType.IMAGE.name -> DownloadRepository.DownloadType.IMAGE
            DownloadType.VIDEO.name -> DownloadRepository.DownloadType.VIDEO
            DownloadType.ATTACHMENT.name -> {
                // 附件根据文件名推断是图片还是视频
                if (fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) ||
                    fileName.endsWith(".jpeg", true) || fileName.endsWith(".gif", true) ||
                    fileName.endsWith(".webp", true)
                ) {
                    DownloadRepository.DownloadType.IMAGE
                } else if (fileName.endsWith(".mp4", true) || fileName.endsWith(".webm", true) ||
                    fileName.endsWith(".mov", true) || fileName.endsWith(".mkv", true) ||
                    fileName.endsWith(".avi", true) || fileName.endsWith(".m4v", true)
                ) {
                    DownloadRepository.DownloadType.VIDEO
                } else {
                    DownloadRepository.DownloadType.ATTACHMENT
                }
            }
            else -> DownloadRepository.DownloadType.VIDEO
        }
    }

    private fun inferMimeType(fileName: String): String = when {
        fileName.endsWith(".mp4", true) -> "video/mp4"
        fileName.endsWith(".webm", true) -> "video/webm"
        fileName.endsWith(".mov", true) -> "video/quicktime"
        fileName.endsWith(".mkv", true) -> "video/x-matroska"
        fileName.endsWith(".avi", true) -> "video/x-msvideo"
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        // Android 13+ 需要运行时权限；以下版本通知默认可用
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW // 低优先级：进度通知不打扰用户
        ).apply {
            description = context.getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundInfo(context: Context, fileName: String, percent: Int): ForegroundInfo {
        val notification = buildNotification(context, fileName, percent, indeterminate = percent <= 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        context: Context,
        fileName: String,
        percent: Int,
        indeterminate: Boolean = false
    ): android.app.Notification {
        val contentText = if (percent > 0) {
            context.getString(R.string.download_notification_progress, fileName, percent)
        } else {
            fileName
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyProgress(context: Context, fileName: String, percent: Int) {
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID, buildNotification(context, fileName, percent)
            )
        } catch (e: SecurityException) {
            // 极少数情况下权限被撤销：忽略通知，下载继续
            Log.w("DownloadWorker", "progress notification failed", e)
        }
    }

    private fun notifyComplete(context: Context, fileName: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.download_notification_complete))
                .setContentText(fileName)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // ARCH-008：通知权限被拒（Android 13+ 未授予 POST_NOTIFICATIONS），记录日志
            Log.w("DownloadWorker", "complete notification failed", e)
        }
    }

    private fun notifyFailed(context: Context, fileName: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.download_notification_failed, fileName))
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // ARCH-008：通知权限被拒（Android 13+ 未授予 POST_NOTIFICATIONS），记录日志
            Log.w("DownloadWorker", "failed notification dismissed", e)
        }
    }
}
