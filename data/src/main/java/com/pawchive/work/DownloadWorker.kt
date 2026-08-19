package com.pawchive.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pawchive.data.R
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.data.repository.DownloadHistoryManager
import com.pawchive.data.repository.DownloadRepository
import com.pawchive.data.repository.OkDownloadManager
import com.pawchive.core.error.ErrorMessageHelper
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
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
    private val downloadRepository: DownloadRepository,
    private val okDownloadManager: OkDownloadManager
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "DownloadWorker"
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
        Log.i(TAG, "doWork() CALLED");
        // 先抽参数：任何步骤失败都能把错误写回同一条 DB 记录。
        // 若 recordId 不存在，说明入队时 DownloadCenter 没写 KEY_RECORD_ID，那此 Work 本身无法关联记录，直接失败。
        val recordId = inputData.getString(KEY_RECORD_ID)
        if (recordId == null) {
            Log.e(TAG, "Missing record_id input, Work cannot proceed")
            return@withContext Result.failure()
        }
        val url = inputData.getString(KEY_URL)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "<unknown>"
        val downloadTypeStr = inputData.getString(KEY_DOWNLOAD_TYPE) ?: DownloadType.VIDEO.name
        val context = applicationContext
        val hasNotifyPermission = hasNotificationPermission(context)

        // 统一在 doWork 最外层 try/catch：即使是 setForeground()、ensureClearance()、
        // historyManager 自身初始化、downloadRepository.openDownloadStream() 等任何
        // 早期异常，也能把友好错误信息落回 DownloadHistory，而不是让 WorkManager 只
        // 能留下空 Output Data，然后 selfHealPending 兜底写"Download worker failed to
        // start"这种模糊文案。
        try {
            if (url == null) throw IllegalArgumentException("Missing download URL")
            var outputStream: OutputStream? = null
            try {
                // 标记为运行中（在最外层 try 内：historyManager.updateStatus 任何异常也能进下方 catch）
                historyManager.updateStatus(recordId, DownloadStatus.RUNNING, progress = 0)

                // 1) 前台通知初始化。注意：setForeground() 在少数 ROM 上会因通知权限/前台
                // 服务类型声明不匹配抛 IllegalStateException，这里会捕获并落库，Work 不会平白 FAILED。
                if (hasNotifyPermission) {
                    ensureChannel(context)
                    setForeground(buildForegroundInfo(context, fileName, 0, downloadTypeStr))
                }

                // 使用 okdownload 下载（断点续传 + OkHttp 集成）
                val mimeType = inputData.getString(KEY_MIME_TYPE) ?: inferMimeType(fileName)
                val repoType = parseRepoType(downloadTypeStr, fileName)
                val target = DownloadRepository.DownloadTarget(repoType, fileName, mimeType)
                val (os, fileUri, requiresFinalize) = downloadRepository.openDownloadStream(target)
                outputStream = os
                var lastReported = -1

                val totalRead = okDownloadManager.download(url, os) { currentBytes, totalBytes ->
                    if (totalBytes > 0) {
                        val percent = (currentBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                        if (percent - lastReported >= PROGRESS_STEP || percent == 100) {
                            lastReported = percent
                            if (hasNotifyPermission) notifyProgress(context, fileName, percent, downloadTypeStr)
                            setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, percent).build())
// 进度更新仅通知 UI，不写 DB（回调非 suspend）
                        }
                    }
                }

                // 5) 完成：标记 MediaStore IS_PENDING=0，更新通知和历史
                if (requiresFinalize) downloadRepository.finalizeDownload(fileUri)
                historyManager.updateStatus(
                    recordId,
                    DownloadStatus.COMPLETED,
                    progress = 100,
                    filePath = fileUri.toString(),
                    fileSize = totalRead
                )
                if (hasNotifyPermission) {
                    notifyComplete(context, fileName, downloadTypeStr)
                }
                Result.success()
            } finally {
                runCatching { outputStream?.close() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "doWork EXCEPTION: : ", e)
            when {
                isStopped -> {
                    historyManager.updateStatus(recordId, DownloadStatus.CANCELLED)
                    Result.failure()
                }
                e is CancellationException -> {
                    // 协程取消但 isStopped=false：WorkManager 框架级重排，这里不要误写 FAILED
                    // 让 WorkManager 自己管理 ENQUEUED 状态（重试时会再次调用 doWork）。
                    throw e
                }
                else -> {
                    val friendly = ErrorMessageHelper.getFriendlyMessage(context, e)
                    Log.e(TAG, "Download worker failed for record=$recordId file=$fileName", e)
                    historyManager.updateStatus(recordId, DownloadStatus.FAILED, errorMessage = friendly)
                    if (hasNotifyPermission) {
                        notifyFailed(context, fileName, friendly)
                    }
                    Result.failure(
                        Data.Builder()
                            .putString(KEY_MESSAGE, friendly)
                            .putString(KEY_RECORD_ID, recordId)
                            .build()
                    )
                }
            }
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

    @StringRes
    private fun resolveNotificationTitleRes(typeStr: String): Int = when (typeStr) {
        DownloadType.IMAGE.name -> R.string.download_notification_title_image
        DownloadType.VIDEO.name -> R.string.download_notification_title_video
        DownloadType.ATTACHMENT.name -> R.string.download_notification_title_attachment
        else -> R.string.download_notification_title_video // 回退兼容旧值
    }

    @StringRes
    private fun resolveNotificationCompleteRes(typeStr: String): Int = when (typeStr) {
        DownloadType.IMAGE.name -> R.string.download_notification_complete_image
        DownloadType.VIDEO.name -> R.string.download_notification_complete_video
        DownloadType.ATTACHMENT.name -> R.string.download_notification_complete_attachment
        else -> R.string.download_notification_complete_video // 回退兼容旧值
    }

    private fun buildForegroundInfo(context: Context, fileName: String, percent: Int, downloadTypeStr: String): ForegroundInfo {
        val notification = buildNotification(context, fileName, percent, indeterminate = percent <= 0, downloadTypeStr = downloadTypeStr)
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
        indeterminate: Boolean = false,
        downloadTypeStr: String
    ): android.app.Notification {
        val contentText = if (percent > 0) {
            context.getString(R.string.download_notification_progress, fileName, percent)
        } else {
            fileName
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(resolveNotificationTitleRes(downloadTypeStr)))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyProgress(context: Context, fileName: String, percent: Int, downloadTypeStr: String) {
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID, buildNotification(context, fileName, percent, downloadTypeStr = downloadTypeStr)
            )
        } catch (e: SecurityException) {
            // 极少数情况下权限被撤销：忽略通知，下载继续
            Log.w("DownloadWorker", "progress notification failed", e)
        }
    }

    private fun notifyComplete(context: Context, fileName: String, downloadTypeStr: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(resolveNotificationCompleteRes(downloadTypeStr)))
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



