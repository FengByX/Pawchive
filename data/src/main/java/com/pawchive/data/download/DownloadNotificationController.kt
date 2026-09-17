package com.pawchive.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载进度通知控制器（FEATURE：通知栏实时下载进度）。
 *
 * 职责：
 * - 为每个下载任务维护一条**独立**通知（百分比 / 已下载/总大小 / 速度 / 剩余时间）
 * - 下载中通知带"暂停 / 取消"动作，暂停后带"继续 / 取消"动作
 * - 终态（完成 / 失败 / 暂停 / 取消）自动更新通知
 * - 维护活跃任务计数：>0 时确保持有前台服务（DownloadNotificationService，dataSync），
 *   =0 时停止，保证 App 退到后台后下载与进度更新不中断
 *
 * 权限策略：Android 13+ 未授予 POST_NOTIFICATIONS 时所有 notify 调用静默跳过，
 * 下载本身不受影响（前台服务仍会启动以保活）。
 *
 * 线程模型：taskProgress 由进度消费协程（progressScope/IO）串行调用；
 * 内部全部使用 ConcurrentHashMap，无锁。
 */
@Singleton
class DownloadNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: com.pawchive.core.store.SettingsManager
) {
    companion object {
        private const val TAG = "DownloadNotif"

        /** 与 DownloadWorker 的通知渠道共用同一 ID，避免设置页出现两个重复渠道。 */
        private const val CHANNEL_ID = "pawchive_download"

        /** 前台服务常驻通知（汇总条目）。避开 DownloadWorker 使用的 1001。 */
        private const val SUMMARY_NOTIFICATION_ID = 2001

        /** 单条通知的最小更新间隔（毫秒）。系统对通知更新有频率限制，自律节流。 */
        private const val MIN_UPDATE_INTERVAL_MS = 500L

        /** 速度采样最小间隔（毫秒）：过近的样本算不出稳定瞬时速度。 */
        private const val MIN_SAMPLE_INTERVAL_MS = 250L

        /** EMA 平滑系数：越小越平滑。0.25 约等于对最近 4 个样本加权。 */
        private const val SPEED_EMA_ALPHA = 0.25
    }

    /** 活跃（RUNNING/PENDING 且本会话内已 taskStarted）的任务数。 */
    private val activeCount = AtomicInteger(0)

    /** recordId -> 通知 ID（每任务一条独立通知）。 */
    private val notificationIds = ConcurrentHashMap<String, Int>()

    /** recordId -> 上次实际 notify 的时间戳（节流用，毫秒 elapsedRealtime）。 */
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()

    /** recordId -> 速度采样器。 */
    private val speedTrackers = ConcurrentHashMap<String, SpeedTracker>()

    // ---------------------------------------------------------------- 生命周期钩子

    /** 任务开始：计数 +1、启动前台服务、发出初始通知。 */
    fun taskStarted(record: DownloadRecord) {
        val count = activeCount.incrementAndGet()
        ensureForegroundService()
        if (!notificationsEnabled()) return
        ensureChannel()
        val notifId = notifIdFor(record.id)
        notificationIds[record.id] = notifId
        speedTrackers[record.id] = SpeedTracker()
        lastNotifiedAt.remove(record.id)
        notify(notifId, buildRunningNotification(record.id, record.fileName, 0, 0L, 0L, indeterminate = true))
        updateSummary(count)
    }

    /**
     * 进度更新：每次读块都会调用（64KB 粒度），速度采样每样本都更新，
     * 实际 notify 按 [MIN_UPDATE_INTERVAL_MS] 节流。
     *
     * @param total 未知（chunked 响应）时传 -1，通知转为不确定进度。
     */
    fun taskProgress(recordId: String, fileName: String, current: Long, total: Long) {
        val tracker = speedTrackers[recordId]
        tracker?.sample(current)
        if (!notificationsEnabled()) return
        val notifId = notificationIds[recordId] ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastNotifiedAt[recordId]
        if (last != null && now - last < MIN_UPDATE_INTERVAL_MS) return
        lastNotifiedAt[recordId] = now
        val speed = tracker?.speedBytesPerSecond() ?: 0.0
        val indeterminate = total <= 0
        notify(
            notifId,
            buildRunningNotification(
                recordId, fileName,
                percent = if (indeterminate) 0 else ((current * 100) / total).toInt().coerceIn(0, 100),
                current, total,
                indeterminate = indeterminate,
                speedBytesPerSecond = speed
            )
        )
    }

    /** 任务进入终态（COMPLETED / FAILED / PAUSED / CANCELLED）：更新通知并维护服务生命周期。 */
    fun taskEnded(record: DownloadRecord) {
        // taskStarted 未跑过（如暂停记录被直接取消）时不做计数递减
        val notifId = notificationIds.remove(record.id)
        speedTrackers.remove(record.id)
        lastNotifiedAt.remove(record.id)
        val wasActive = notifId != null
        val count = if (wasActive) activeCount.decrementAndGet() else activeCount.get()

        // 设置页"下载完成通知"开关：关闭后完成不再弹通知，仅撤除该任务的进行中通知。
        // 失败/暂停通知不受此项控制（属错误与操作信号，与"完成提醒"语义不同）。
        val completionMuted = record.status == DownloadStatus.COMPLETED &&
            !settingsManager.isDownloadCompleteNotificationEnabled()

        if (notificationsEnabled() && !completionMuted) {
            ensureChannel()
            when (record.status) {
                DownloadStatus.COMPLETED ->
                    notify(notifIdFor(record.id), buildFinalNotification(record, R.string.notif_completed_detail))
                DownloadStatus.FAILED ->
                    notify(
                        notifIdFor(record.id),
                        buildFinalNotification(
                            record,
                            R.string.download_notification_failed,
                            arg = record.errorMessage ?: context.getString(R.string.notif_error_unknown)
                        )
                    )
                DownloadStatus.PAUSED ->
                    notify(notifIdFor(record.id), buildPausedNotification(record))
                // CANCELLED / PENDING：撤除通知
                else -> {
                    if (notifId != null) {
                        runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
                    }
                }
            }
        } else if (completionMuted && notifId != null) {
            runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
        }

        if (count <= 0) stopForegroundService()
    }

    // ---------------------------------------------------------------- 通知构建

    /** 前台服务常驻通知（汇总）。由 [DownloadNotificationService] 在 onCreate 调用。 */
    fun buildSummaryNotification(): Notification {
        ensureChannel()
        return baseBuilder()
            .setContentTitle(context.getString(R.string.notif_downloading_summary, activeCount.get().coerceAtLeast(1)))
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun buildRunningNotification(
        recordId: String,
        fileName: String,
        percent: Int,
        current: Long,
        total: Long,
        indeterminate: Boolean,
        speedBytesPerSecond: Double = 0.0
    ): Notification {
        val speedStr = context.getString(R.string.notif_speed, formatBytes(speedBytesPerSecond.toLong()))
        val text = if (indeterminate) {
            context.getString(R.string.notif_speed, formatBytes(speedBytesPerSecond.toLong()))
        } else {
            val eta = etaString(total - current, speedBytesPerSecond)
            context.getString(R.string.notif_progress_detail, formatBytes(current), formatBytes(total), speedStr, eta)
        }
        return baseBuilder()
            .setContentTitle(fileName)
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_notif_pause, context.getString(R.string.notif_action_pause), actionIntent(recordId, DownloadNotificationService.ACTION_PAUSE))
            .addAction(R.drawable.ic_notif_cancel, context.getString(R.string.notif_action_cancel), actionIntent(recordId, DownloadNotificationService.ACTION_CANCEL))
            .build()
    }

    private fun buildPausedNotification(record: DownloadRecord): Notification =
        baseBuilder()
            .setContentTitle(record.fileName)
            .setContentText(context.getString(R.string.notif_paused_detail, record.progress))
            .setProgress(100, record.progress, false)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .clearActions()
            .addAction(R.drawable.ic_notif_resume, context.getString(R.string.notif_action_resume), actionIntent(record.id, DownloadNotificationService.ACTION_RESUME))
            .addAction(R.drawable.ic_notif_cancel, context.getString(R.string.notif_action_cancel), actionIntent(record.id, DownloadNotificationService.ACTION_CANCEL))
            .build()

    private fun buildFinalNotification(record: DownloadRecord, detailRes: Int, arg: String? = null): Notification {
        val sizeStr = if (record.fileSize > 0) formatBytes(record.fileSize) else ""
        val text = if (arg != null) context.getString(detailRes, arg) else context.getString(detailRes, sizeStr)
        return baseBuilder()
            .setContentTitle(record.fileName)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    // ---------------------------------------------------------------- 动作与意图

    private fun actionIntent(recordId: String, action: String): PendingIntent {
        val intent = Intent(context, DownloadNotificationService::class.java)
            .setAction(action)
            .putExtra(DownloadNotificationService.EXTRA_RECORD_ID, recordId)
        return PendingIntent.getService(
            context, recordId.hashCode() + action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点击通知体 → 拉起应用（避免 :data 反向依赖 :app 的 Activity 类）。 */
    private fun openAppPendingIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------------------------------------------------------- 前台服务

    private fun ensureForegroundService() {
        val intent = Intent(context, DownloadNotificationService::class.java)
        runCatching {
            context.startForegroundService(intent)
        }.onFailure {
            // Android 12+ 后台启动 FGS 受限。下载由 UI 触发时不会发生；
            // 一旦发生，通知照常显示，仅失去前台保活优先级。
            Log.w(TAG, "startForegroundService failed", it)
        }
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, DownloadNotificationService::class.java)) }
    }

    // ---------------------------------------------------------------- 工具

    /** 供服务判断是否需要 stopSelf。 */
    fun activeTaskCount(): Int = activeCount.get()

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.download_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun notifIdFor(recordId: String): Int {
        val id = recordId.hashCode()
        return if (id == SUMMARY_NOTIFICATION_ID) SUMMARY_NOTIFICATION_ID + 1 else id
    }

    private fun updateSummary(count: Int) {
        if (!notificationsEnabled()) return
        // 走统一的 notify() 通道：它显式捕获 SecurityException（权限被中途撤销的场景），
        // 让 Lint 的 MissingPermission 检查能被满足，而不是依赖 Lint 看不见的 runCatching。
        runCatching { notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification()) }
        if (count <= 0) stopForegroundService()
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Android 13+ 运行时权限被中途撤销：静默放弃通知，下载继续
            Log.w(TAG, "notify skipped: notification permission revoked", e)
        }
    }

    private fun etaString(remainingBytes: Long, speedBytesPerSecond: Double): String {
        if (speedBytesPerSecond <= 0.0 || remainingBytes <= 0) {
            return context.getString(R.string.notif_eta_unknown)
        }
        val seconds = (remainingBytes / speedBytesPerSecond).toLong()
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatBytes(bytes: Long): String {
        val b = if (bytes < 0) 0L else bytes
        val kb = b / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> String.format(Locale.US, "%d B", b)
        }
    }

    /** 速度采样器：EMA 平滑的字节/秒。 */
    private class SpeedTracker {
        private var lastBytes = -1L
        private var lastElapsedMs = -1L
        @Volatile private var emaSpeed = 0.0

        fun sample(currentBytes: Long) {
            val now = SystemClock.elapsedRealtime()
            if (lastBytes < 0) {
                lastBytes = currentBytes
                lastElapsedMs = now
                return
            }
            val dt = now - lastElapsedMs
            if (dt < MIN_SAMPLE_INTERVAL_MS) return
            val dBytes = currentBytes - lastBytes
            if (dBytes < 0) return
            lastBytes = currentBytes
            lastElapsedMs = now
            val instant = dBytes * 1000.0 / dt
            emaSpeed = if (emaSpeed == 0.0) instant else emaSpeed + SPEED_EMA_ALPHA * (instant - emaSpeed)
        }

        fun speedBytesPerSecond(): Double = emaSpeed
    }
}
