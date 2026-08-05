package com.pawchive.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pawchive.data.repository.CreatorSubscriptionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 内容更新订阅周期同步任务（ARCH-FEATURE-003）。
 *
 * 通过 WorkManager 周期执行（默认 30 分钟，需联网约束）：
 * 拉取所有订阅创作者的最新帖，与订阅基线对比，新帖写入内容更新表
 * （站内未读通知，见 CreatorSubscriptionRepository.syncSubscribedCreatorsDetailed）；
 * 本次有新增时经 [ContentUpdateNotifier] 推送系统通知栏提醒。
 *
 * 单个创作者失败不影响整体；周期任务下次运行自然重试。
 */
@HiltWorker
class ContentUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val subscriptionRepository: CreatorSubscriptionRepository,
    private val contentUpdateNotifier: ContentUpdateNotifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = subscriptionRepository.syncSubscribedCreatorsDetailed()
            if (result.newUpdates.isNotEmpty()) {
                contentUpdateNotifier.notifyNewUpdates(result.newUpdates)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "content update sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ContentUpdateWorker"

        const val WORK_NAME = "content_update_sync"

        /** 周期同步间隔（分钟）。 */
        const val SYNC_INTERVAL_MINUTES = 30L
    }
}
