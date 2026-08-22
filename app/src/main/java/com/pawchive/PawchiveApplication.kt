package com.pawchive

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pawchive.core.store.SettingsManager
import com.pawchive.core.api.ApiClient
import com.pawchive.core.api.ClearanceCoordinator
import com.pawchive.core.api.CloudflareManager
import com.pawchive.core.util.CrashHandler
import com.pawchive.data.repository.CacheRepository
import com.pawchive.work.CacheCleanWorker
import com.pawchive.work.ContentUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory

/**
 * Pawchive 应用入口（ARCH-003：已迁移至 Hilt 依赖注入）。
 *
 * 关键变更：
 * - `@HiltAndroidApp` 启用 Hilt，自动生成依赖注入容器；
 * - 实现 `Configuration.Provider` 以向 WorkManager 注入 `HiltWorkerFactory`，
 *   使 `@HiltWorker` 标注的 Worker 可通过构造函数注入依赖；
 * - `SettingsManager` 通过 Hilt 注入，并暴露静态访问器供 `MainActivity.attachBaseContext`
 *   在 Hilt 注入 Activity 之前读取语言设置（该路径在 Activity 注入之前执行，
 *   ARCH-007：已改读轻量启动缓存，不再同步读 DataStore、不阻塞主线程）。
 * - 实现 `ComponentCallbacks2` 以在进程低内存/销毁时取消全局协程作用域，
 *   避免 CoroutineScope 泄漏（ARCH-BUG-MINOR-16）。
 */
@HiltAndroidApp
class PawchiveApplication : Application(), ImageLoaderFactory, Configuration.Provider, ComponentCallbacks2 {

    companion object {
        private const val IMAGE_DISK_CACHE_DIR = "image_cache"
        private const val IMAGE_DISK_CACHE_MAX_SIZE = 100L * 1024 * 1024

        /**
         * 应用级协程作用域，替代 GlobalScope。
         * 使用 SupervisorJob 确保一个子协程失败不会影响其他协程。
         * 使用 Dispatchers.IO 适合后台数据操作。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 静态访问器：仅供 `MainActivity.attachBaseContext` 等启动期路径使用，
         * 该路径在 Hilt 完成 Activity 字段注入之前被系统调用。
         * 常规业务代码应通过 @Inject 注入 SettingsManager，不要使用此方法。
         */
        @Volatile
        private var instance: PawchiveApplication? = null

        fun getSettingsManager(): SettingsManager {
            return instance?.settingsManager
                ?: throw IllegalStateException(
                    "PawchiveApplication 尚未完成 onCreate，SettingsManager 不可用"
                )
        }
    }

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var cacheRepository: CacheRepository

    override fun onCreate() {
        super.onCreate() // Hilt 在此完成字段注入

        // 尽早应用深色/浅色模式：必须在任何 Activity 创建之前设置，
        // 否则 MainActivity 及其 ViewPager2 Fragment 可能用旧配置实例化，
        // 导致重启后部分页面仍为浅色（卡片/列表手动检查 uiMode 时读到错误值）。
        settingsManager.applyAppearance()

        // 显式初始化 WorkManager，确保 HiltWorkerFactory 已就绪
        // 解决 Configuration.Provider 延迟初始化与 Hilt 注入的时序竞态
        WorkManager.initialize(this, workManagerConfiguration)
        android.util.Log.i("PawchiveApp", "WorkManager explicitly initialized with HiltWorkerFactory")
        instance = this

        // 初始化全局崩溃捕获（FEATURE-006 崩溃埋点）
        CrashHandler.init(this)
        // 初始化 Cloudflare 过盾管理器（object 单例，保留 init 调用；
        // 完整迁移至 DI 属于 ARCH-009：ApiClient/CloudflareManager 拆分为类）
        CloudflareManager.init(this)
        // ARCH-009：启动异步预热过盾（非阻塞），减少首次请求被 Cloudflare 403 拦截
        ClearanceCoordinator.preheat()
        // 自动清理缓存：改用 WorkManager 受约束任务 + 容量阈值策略（FRONTEND-003）
        scheduleAutoCacheCleanIfNeeded()
        // 内容更新订阅周期同步（ARCH-FEATURE-003）
        scheduleContentUpdateSync()
    }

    /**
     * 向 WorkManager 提供 HiltWorkerFactory，使 @HiltWorker 能通过构造函数注入依赖。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * 基于容量阈值调度自动缓存清理（FRONTEND-003）。
     * - 用户开启"自动清理缓存"开关时才考虑
     * - 缓存大小超过阈值（默认 200MB）或从未清理过时触发
     * - 使用 WorkManager OneTimeWorkRequest，延迟 10s 避免与启动期文件访问竞争
     */
    /**
     * PERF-001：将缓存阈值检测移至后台协程。
     * 原问题：shouldCleanByThreshold() → getCacheSize() 递归遍历 cacheDir，
     * 在主线程上执行导致冷启动延迟（文件数千时可达数百毫秒）。
     */
    private fun scheduleAutoCacheCleanIfNeeded() {
        applicationScope.launch {
            if (!cacheRepository.isAutoCleanEnabled()) return@launch

            if (!CacheCleanWorker.shouldCleanByThreshold(cacheRepository)) return@launch

            val request = OneTimeWorkRequestBuilder<CacheCleanWorker>()
                .setInitialDelay(10L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(this@PawchiveApplication).enqueue(request)
        }
    }

    /**
     * 调度内容更新订阅周期同步（ARCH-FEATURE-003）。
     * - 周期 30 分钟（WorkManager 最小 15 分钟）
     * - 联网约束：无网络时跳过本次运行
     * - 唯一周期任务：多次启动不重复排队（KEEP 策略）
     */
    private fun scheduleContentUpdateSync() {
        val request = PeriodicWorkRequestBuilder<ContentUpdateWorker>(
            ContentUpdateWorker.SYNC_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ContentUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
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

    /**
     * 清理应用缓存（ARCH-010：统一委托 data 层 CacheRepository，
     * 消除 app 作为单点缓存清理入口的职责）。
     * 保留方法供 UI 层（SettingsViewModel）调用。
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        cacheRepository.clearCache()
    }

    /**
     * ComponentCallbacks2：在进程低内存或销毁时取消全局协程作用域，
     * 避免 CoroutineScope 泄漏（ARCH-BUG-MINOR-16）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // UI 隐藏时可选择性清理，但这里保留应用作用域直到进程销毁
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 取消应用级协程作用域，释放资源
        applicationScope.coroutineContext.cancel()
        // 取消 ClearanceCoordinator 内部作用域
        ClearanceCoordinator.shutdown()
        // 取消 CloudflareManager 内部作用域
        CloudflareManager.shutdown()
    }
}


