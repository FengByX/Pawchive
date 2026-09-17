package com.pawchive.data.repository

import android.content.Context
import com.pawchive.core.store.SessionManager
import com.pawchive.core.store.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 清除本地用户数据（FEATURE-003 账号与数据边界；ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * 在账号切换或登出时调用 [clearAllLocalData]，清除当前账号的本地数据以实现数据隔离：
 * - 本地收藏（BookmarkManager）
 * - 搜索历史（SearchHistoryManager）
 * - 下载历史（DownloadHistoryManager）
 * - 下载任务（WorkManager）
 * - 图片缓存（Coil）
 *
 * [wipeAllLocalData] 供设置页"清除本地全部数据"使用（FEATURE：设置项扩展批次三）：
 * 在隔离清理之上追加全量清除——屏蔽名单、阅读进度/播放位置、离线归档索引、
 * 会话 Cookie 与全部应用设置；调用方负责在完成后重启应用使 UI 复位。
 */
@Singleton
class LocalDataCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkManager: BookmarkManager,
    private val searchHistoryManager: SearchHistoryManager,
    private val downloadHistoryManager: DownloadHistoryManager,
    private val cacheRepository: CacheRepository,
    private val blockedCreatorManager: BlockedCreatorManager,
    private val readingProgressManager: ReadingProgressManager,
    private val offlineArchiveRepository: OfflineArchiveRepository,
    private val sessionManager: SessionManager,
    private val settingsManager: SettingsManager
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

        // 清除缓存（ARCH-010：统一走 CacheRepository 入口，覆盖 Coil/API 内存缓存等）
        runCatching {
            cacheRepository.clearCache()
        }
    }

    /**
     * 清除全部本地数据（不可恢复）：
     * 收藏、搜索历史、下载历史、屏蔽名单、阅读进度、离线归档、缓存、
     * 会话 Cookie 与全部应用设置（含下载目录/备份目录等设备绑定项）。
     * 各步独立容错：单项失败不阻断其余清理。
     */
    suspend fun wipeAllLocalData() = withContext(Dispatchers.IO) {
        runCatching { bookmarkManager.clearAllForAccountSwitch() }
        runCatching { searchHistoryManager.clearAll() }
        runCatching { downloadHistoryManager.clearAll() }
        runCatching { blockedCreatorManager.clearAll() }
        runCatching { readingProgressManager.clearAll() }
        runCatching { offlineArchiveRepository.clearAll() }
        runCatching { cacheRepository.clearCache() }
        runCatching { sessionManager.clearSession() }
        runCatching { settingsManager.resetAllSettings() }
    }
}
