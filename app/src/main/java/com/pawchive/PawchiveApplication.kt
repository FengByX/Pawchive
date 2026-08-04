package com.pawchive

import android.app.Application
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pawchive.data.SettingsManager
import com.pawchive.data.api.ApiClient
import com.pawchive.data.api.CloudflareManager
import com.pawchive.utils.CrashHandler
import com.pawchive.work.CacheCleanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.TimeUnit

class PawchiveApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val IMAGE_DISK_CACHE_DIR = "image_cache"
        private const val IMAGE_DISK_CACHE_MAX_SIZE = 100L * 1024 * 1024

        /**
         * 应用级协程作用域，替代 GlobalScope。
         * 使用 SupervisorJob 确保一个子协程失败不会影响其他协程。
         * 使用 Dispatchers.IO 适合后台数据操作。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化全局崩溃捕获（FEATURE-006 崩溃埋点）
        CrashHandler.init(this)
        // 初始化 Cloudflare 过盾管理器（用于 WebView 通过 pawchive.pw 的 CF 挑战）
        CloudflareManager.init(this)
        // 自动清理缓存：改用 WorkManager 受约束任务 + 容量阈值策略（FRONTEND-003）
        // - 不再每次启动无条件清空，仅在缓存超过阈值或从未清理时触发
        // - 通过 WorkManager 调度，避免与 Coil 初始化、首屏图片加载竞争文件锁
        // - 清理完成后记录时间戳，供设置页展示"上次清理时间"
        scheduleAutoCacheCleanIfNeeded()
    }

    /**
     * 基于容量阈值调度自动缓存清理（FRONTEND-003）。
     * - 用户开启"自动清理缓存"开关时才考虑
     * - 缓存大小超过阈值（默认 200MB）或从未清理过时触发
     * - 使用 WorkManager OneTimeWorkRequest，延迟 10s 避免与启动期文件访问竞争
     */
    private fun scheduleAutoCacheCleanIfNeeded() {
        val settingsManager = SettingsManager.getInstance(this)
        if (!settingsManager.isAutoCleanCacheEnabled()) return

        if (!CacheCleanWorker.shouldCleanByThreshold(this)) return

        val request = OneTimeWorkRequestBuilder<CacheCleanWorker>()
            .setInitialDelay(10L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueue(request)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // 使用 sharedOkHttpClient：img.pawchive.pw 并非完全公开 CDN，
            // 部分缩略图请求也会被 Cloudflare 拦截返回 403。
            // sharedOkHttpClient 内置 cloudflareRetryInterceptor，
            // 会在 403 时自动刷新 cf_clearance 并重试一次。
            .okHttpClient(ApiClient.sharedOkHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_SIZE)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        try {
            val loader = coil.Coil.imageLoader(this)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (_: Exception) {}

        try {
            val diskCacheDir = File(cacheDir, IMAGE_DISK_CACHE_DIR)
            if (diskCacheDir.exists()) {
                diskCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}

        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name != IMAGE_DISK_CACHE_DIR) {
                    file.deleteRecursively()
                }
            }
        } catch (_: Exception) {}

        try {
            externalCacheDir?.deleteRecursively()
        } catch (_: Exception) {}
    }
}