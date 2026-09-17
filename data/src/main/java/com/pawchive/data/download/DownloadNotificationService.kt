package com.pawchive.data.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pawchive.data.repository.DownloadCenter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 下载前台服务（FEATURE：通知栏实时下载进度）。
 *
 * 职责边界刻意收窄——**只做两件事**：
 * 1. 持有 dataSync 前台服务状态：只要还有活跃下载任务，App 退到后台后
 *    进程即拥有前台优先级，下载与进度更新不因后台化而中断；
 * 2. 接收通知栏按钮动作（暂停 / 继续 / 取消）并转发给 [DownloadCenter]。
 *
 * 下载执行仍在 DownloadCenter 的协程中；本服务不持有任何下载状态。
 * 启动/停止由 DownloadNotificationController 依据活跃任务数驱动
 * （>0 启动，=0 stopService），本服务在 onStartCommand 里兜底自停。
 *
 * 生命周期说明：
 * - onCreate 必须尽快 startForeground（系统要求 startForegroundService 后 5s 内）；
 * - START_NOT_STICKY：服务被杀后不自动重建；残余的 RUNNING 记录由
 *   DownloadHistoryManager 启动时的 markInterruptedAsFailed 兜底收敛，
 *   用户可手动继续；
 * - Android 15+ 对 dataSync 类型有 24 小时内 6 小时的系统级上限，
 *   超时后系统会停掉服务（长挂机场景下载会中断，需手动继续）。
 */
@AndroidEntryPoint
class DownloadNotificationService : Service() {

    companion object {
        private const val TAG = "DownloadNotifSvc"
        const val ACTION_PAUSE = "com.pawchive.download.action.PAUSE"
        const val ACTION_RESUME = "com.pawchive.download.action.RESUME"
        const val ACTION_CANCEL = "com.pawchive.download.action.CANCEL"
        const val EXTRA_RECORD_ID = "record_id"

        /** 供外部检测服务是否已在运行（简化判断，不做精确生命周期追踪）。 */
        fun startIntent(context: Context): Intent = Intent(context, DownloadNotificationService::class.java)
    }

    @Inject lateinit var downloadCenter: DownloadCenter
    @Inject lateinit var notificationController: DownloadNotificationController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 系统要求：startForegroundService 启动后必须立刻进入前台状态
        startForeground(
            2001, // 与 DownloadNotificationController.SUMMARY_NOTIFICATION_ID 保持一致
            notificationController.buildSummaryNotification()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordId = intent?.getStringExtra(EXTRA_RECORD_ID)
        when (intent?.action) {
            ACTION_PAUSE -> recordId?.let {
                Log.d(TAG, "notification action: pause $it")
                downloadCenter.pause(it)
            }
            ACTION_RESUME -> recordId?.let {
                Log.d(TAG, "notification action: resume $it")
                downloadCenter.requestResume(it)
            }
            ACTION_CANCEL -> recordId?.let {
                Log.d(TAG, "notification action: cancel $it")
                downloadCenter.cancel(it)
            }
            else -> Unit
        }

        // 没有活跃任务（或仅剩本次动作已终结任务）时自停，
        // 兜底 Controller 的 stopService：双保险避免常驻空通知。
        if (notificationController.activeTaskCount() <= 0) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
