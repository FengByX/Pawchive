package com.pawchive.data.repository

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 清除本地用户数据（FEATURE-003 账号与数据边界；ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * 在账号切换或登出时调用，清除当前账号的本地数据以实现数据隔离：
 * - 本地收藏（BookmarkManager）
 * - 搜索历史（SearchHistoryManager）
 * - 下载历史（DownloadHistoryManager）
 * - 下载任务（WorkManager）
 * - 图片缓存（Coil）
 */
@Singleton
class LocalDataCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkManager: BookmarkManager,
    private val searchHistoryManager: SearchHistoryManager,
    private val downloadHistoryManager: DownloadHistoryManager,
    private val cacheRepository: CacheRepository
) {

    suspend fun clearAllLocalData() = withContext(Dispatchers.IO) {
        // 清除本地收藏
        runCatching {
            bookmarkManager.clearAllForAccountSwitch()
        }

        // 清除搜索历史
        runCatching {
            searchHistoryManager.clearAllForAccountSwitch()
        }

        // 清除下载历史
        runCatching {
            downloadHistoryManager.clearAllForAccountSwitch()
        }

        // 取消所有下载任务
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(DownloadCenter.WORK_TAG)
        }

        // 清除缓存（ARCH-010：统一走 CacheRepository 入口，覆盖 Coil/API 内存缓存等）
        runCatching {
            cacheRepository.clearCache()
        }
    }
}
