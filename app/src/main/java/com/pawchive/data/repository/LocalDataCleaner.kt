package com.pawchive.data.repository

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 清除本地用户数据（FEATURE-003 账号与数据边界）。
 *
 * 在账号切换或登出时调用，清除当前账号的本地数据以实现数据隔离：
 * - 本地收藏（BookmarkManager）
 * - 搜索历史（SearchHistoryManager）
 * - 下载历史（DownloadHistoryManager）
 * - 下载任务（WorkManager）
 * - 图片缓存（Coil）
 */
object LocalDataCleaner {

    suspend fun clearAllLocalData(context: Context) = withContext(Dispatchers.IO) {
        // 清除本地收藏
        runCatching {
            BookmarkManager.getInstance(context).clearAllForAccountSwitch()
        }

        // 清除搜索历史
        runCatching {
            SearchHistoryManager.getInstance(context).clearAllForAccountSwitch()
        }

        // 清除下载历史
        runCatching {
            DownloadHistoryManager.getInstance(context).clearAllForAccountSwitch()
        }

        // 取消所有下载任务
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(DownloadCenter.WORK_TAG)
        }

        // 清除图片缓存
        runCatching {
            (context.applicationContext as? com.pawchive.PawchiveApplication)?.clearCache()
        }
    }
}
