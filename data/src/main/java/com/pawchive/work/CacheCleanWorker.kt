package com.pawchive.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pawchive.data.repository.CacheRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 缓存清理 WorkManager 任务（FRONTEND-003；ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * 解决问题：
 * - 原 onCreate 中直接清理缓存与 Coil 初始化、图片加载、恢复中下载竞争文件锁
 * - 每次启动无条件清空，无任务协调、无结果记录
 *
 * 策略：
 * - 通过 WorkManager 受约束任务执行，避免与启动期文件访问竞争
 * - 手动（设置页）与自动清理统一走 [CacheRepository] 入口（ARCH-010）
 * - 清理完成后记录时间戳，供设置页展示"上次清理时间"
 */
@HiltWorker
class CacheCleanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cacheRepository: CacheRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // ARCH-010：统一入口，覆盖 Coil 缓存 / cacheDir / externalCacheDir / API 内存缓存
            cacheRepository.clearCache()
            cacheRepository.recordClean()
            Result.success()
        } catch (e: Exception) {
            Log.w("CacheCleanWorker", "cache clean failed", e)
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "cache_clean_work"

        /**
         * 检查是否应基于容量阈值触发自动清理（FRONTEND-003）。
         * - 缓存大小超过阈值时返回 true
         * - 从未清理过时返回 true（首次启动场景）
         *
         * 注：由调用方（PawchiveApplication）传入已注入的 CacheRepository，
         * 消除 work → app 的逆向依赖（ARCH-002 阶段 2）。
         */
        fun shouldCleanByThreshold(cacheRepository: CacheRepository): Boolean {
            return cacheRepository.shouldAutoClean()
        }
    }
}
