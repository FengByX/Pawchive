package com.pawchive.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pawchive.core.model.ContentUpdateEntity
import com.pawchive.core.util.ContentUpdateConstants
import com.pawchive.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内容更新系统通知推送（ARCH-FEATURE-003 遗留项）。
 *
 * 由 [ContentUpdateWorker] 在周期同步发现新增更新后调用：
 * - Android 13+ 未授予 POST_NOTIFICATIONS 时静默跳过（订阅时已引导授权）；
 * - 单条通知展示帖子标题，多条聚合展示数量 + 首条标题；
 * - 点击通知经 [ContentUpdateConstants.ACTIVITY_CLASS_NAME]（MainActivity）
 *   打开内容更新页，App 在后台/已启动均正确跳转。
 */
@Singleton
class ContentUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 推送订阅更新系统通知。
     * @param updates 本次同步新增的内容更新（非空才调用）
     */
    fun notifyNewUpdates(updates: List<ContentUpdateEntity>) {
        if (updates.isEmpty()) return
        if (!hasNotificationPermission()) return
        ensureChannel()

        val notification = buildNotification(updates)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 权限在发送瞬间被撤销：记录日志，不中断同步
            Log.w(TAG, "content update notification failed", e)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        // Android 13+ 需要运行时权限；以下版本通知默认可用
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.content_update_channel_name),
            NotificationManager.IMPORTANCE_HIGH // 订阅新帖提醒：响铃 + 横幅
        ).apply {
            description = context.getString(R.string.content_update_channel_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(updates: List<ContentUpdateEntity>): android.app.Notification {
        val contentIntent = Intent()
            .setClassName(context.packageName, ContentUpdateConstants.ACTIVITY_CLASS_NAME)
            .putExtra(ContentUpdateConstants.EXTRA_OPEN_CONTENT_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val first = updates.first()
        val title = if (updates.size == 1) {
            context.getString(R.string.content_update_notification_title)
        } else {
            context.getString(R.string.content_update_notification_multi, updates.size)
        }
        val content = first.postTitle?.takeIf { it.isNotBlank() } ?: first.postId

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
    }

    companion object {
        private const val TAG = "ContentUpdateNotifier"

        const val CHANNEL_ID = "pawchive_content_update"
        private const val NOTIFICATION_ID = 2001
    }
}
