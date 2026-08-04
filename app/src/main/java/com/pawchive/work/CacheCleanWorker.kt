package com.pawchive.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pawchive.PawchiveApplication
import com.pawchive.data.SettingsManager
import com.pawchive.data.api.ApiClient

/**
 * 缓存清理 WorkManager 任务（FRONTEND-003）。
 *
 * 解决问题：
 * - 原 onCreate 中直接清理缓存与 Coil 初始化、图片加载、恢复中下载竞争文件锁
 * - 每次启动无条件清空，无任务协调、无结果记录
 *
 * 策略：
 * - 通过 WorkManager 受约束任务执行，避免与启动期文件访问竞争
 * - 清理完成后记录时间戳，供设置页展示"上次清理时间"
 * - 同时清理 cacheDir、externalCacheDir、Coil 磁盘缓存、API 内存缓存
 */
class CacheCleanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val app = context as? PawchiveApplication

            // 1. 清理 Coil 内存+磁盘缓存
            app?.clearCache()

            // 2. 清理 cacheDir（保留 image_cache 目录，由 Coil 自身管理）
            //    实际上 clearCache() 已处理，这里补充清理 externalCacheDir
            try {
                context.externalCacheDir?.let { dir ->
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        dir.mkdirs()
                    }
                }
            } catch (_: Exception) {}

            // 3. 清理 API 内存缓存，避免旧账号数据残留
            ApiClient.clearMemoryCache()

            // 4. 记录清理时间戳
            SettingsManager.getInstance(context).setLastCacheCleanTime(System.currentTimeMillis())

            Result.success()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "cache_clean_work"

        /**
         * 检查是否应基于容量阈值触发自动清理（FRONTEND-003）。
         * - 缓存大小超过阈值时返回 true
         * - 从未清理过时返回 true（首次启动场景）
         */
        fun shouldCleanByThreshold(context: Context): Boolean {
            return SettingsManager.getInstance(context).shouldAutoCleanByThreshold(context)
        }
    }
}
