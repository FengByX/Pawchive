package com.pawchive.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 收藏历史一次性回填（ARCH-FEATURE-001 遗留项）。
 *
 * 背景：离线归档索引（Room + FTS4）上线前就存在的收藏没有索引记录，
 * 升级后无法离线搜索/阅读。本服务在启动后异步遍历既有收藏，逐条补齐索引。
 *
 * 设计要点：
 * - **仅执行一次**：SharedPreferences 布尔标记，回填完成后置位，避免每次启动重复遍历；
 *   新收藏/取消收藏/账号切换/备份导入均走即时索引（BookmarkManager → OfflineArchiveRepository），
 *   因此全局标记在账号切换后依然安全，无需按账号重置。
 * - **幂等**：`OfflineArchiveRepository.index` 为主键 upsert，重复执行不产生脏数据。
 * - **单条失败不中断**：某条收藏 JSON 损坏时仅记日志，其余历史数据尽量完成索引。
 * - **调用方必须在 IO 线程执行**：`BookmarkManager.getBookmarkedPosts` 内部可能
 *   `runBlocking` 等待 DataStore 加载，严禁在启动/主线程同步路径调用。
 */
@Singleton
class OfflineArchiveBackfill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkManager: BookmarkManager,
    private val offlineArchiveRepository: OfflineArchiveRepository
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否已完成历史回填（置位后不再重复遍历）。 */
    private val backfillDone: Boolean
        get() = prefs.getBoolean(KEY_BACKFILL_DONE, false)

    /**
     * 补齐既有收藏的离线归档索引（幂等，单条失败不中断）。
     *
     * 必须在 IO 线程调用；应用启动后异步触发，不阻塞启动路径。
     */
    suspend fun runIfNeeded() {
        if (backfillDone) return
        for (post in bookmarkManager.getBookmarkedPosts()) {
            runCatching { offlineArchiveRepository.index(post) }
                .onFailure { it.printStackTrace() }
        }
        prefs.edit().putBoolean(KEY_BACKFILL_DONE, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "pawchive_offline_archive_backfill"
        private const val KEY_BACKFILL_DONE = "backfill_done"
    }
}
