package com.pawchive.data.repository

import com.google.gson.Gson
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.Post
import com.pawchive.core.store.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/** 创作者引用（备份 JSON 用；导出原始字段，避免字符串拼接解析错位）。 */
data class CreatorRef(val service: String, val creatorId: String)

/** 可备份的设置项（设备绑定项与运行态策略值不导出：下载目录 Uri、缓存阈值、上次清理时间）。 */
data class BackupSettings(
    val language: String? = null,
    val appearance: String? = null,
    val autoCleanCache: Boolean? = null,
    val autoCheckUpdate: Boolean? = null,
    val hideBookmarkedCreators: Boolean? = null
)

/**
 * 备份文件主体（ARCH-FEATURE-005）。
 *
 * 不包含：会话 Cookie、搜索历史（隐私 + 易重建）、下载目录 Uri（设备绑定需重新授权）、
 * 下载记录的瞬时状态字段（progress / filePath / errorMessage）。
 */
data class BackupBundle(
    val schemaVersion: Int = BackupManager.SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val bookmarkedPosts: List<Post> = emptyList(),
    val bookmarkedCreators: List<CreatorRef> = emptyList(),
    val blockedCreators: List<CreatorRef> = emptyList(),
    val readingProgress: ReadingProgressSnapshot = ReadingProgressSnapshot(),
    val downloadHistory: List<DownloadRecord> = emptyList(),
    val settings: BackupSettings = BackupSettings()
)

/** 导入结果计数（UI 展示）。 */
data class BackupImportResult(
    val bookmarks: Int = 0,
    val creators: Int = 0,
    val blocked: Int = 0,
    val downloads: Int = 0
)

/**
 * 备份与迁移管理器（ARCH-FEATURE-005）。
 *
 * - 导出：收集本地收藏（帖子 + 创作者）、屏蔽名单、阅读进度、下载历史与设置，
 *   序列化为单文件 JSON（Gson），由 UI 层经 SAF 写出；
 * - 导入：解析并校验版本后，按"覆盖"语义写回各数据源（收藏/屏蔽/阅读进度/下载历史），
 *   设置逐项恢复；会话 Cookie 与搜索历史永不导出/导入。
 */
@Singleton
class BackupManager @Inject constructor(
    private val gson: Gson,
    private val bookmarkManager: BookmarkManager,
    private val blockedCreatorManager: BlockedCreatorManager,
    private val readingProgressManager: ReadingProgressManager,
    private val downloadHistoryManager: DownloadHistoryManager,
    private val settingsManager: SettingsManager
) {

    /** 导出为备份 JSON 字符串。 */
    suspend fun export(): String {
        val bundle = BackupBundle(
            exportedAt = System.currentTimeMillis(),
            bookmarkedPosts = bookmarkManager.getBookmarkedPosts(),
            bookmarkedCreators = bookmarkManager.getBookmarkedCreators()
                .map { CreatorRef(it.first, it.second) },
            blockedCreators = blockedCreatorManager.getBlockedCreators()
                .map { CreatorRef(it.first, it.second) },
            readingProgress = readingProgressManager.exportAll(),
            downloadHistory = downloadHistoryManager.getAllRecordsFromDb().map { sanitizeDownload(it) },
            settings = BackupSettings(
                language = settingsManager.getLanguage().code,
                appearance = settingsManager.getAppearance().name,
                autoCleanCache = settingsManager.isAutoCleanCacheEnabled(),
                autoCheckUpdate = settingsManager.isAutoCheckUpdateEnabled(),
                hideBookmarkedCreators = settingsManager.isHideBookmarkedCreatorsEnabled()
            )
        )
        return gson.toJson(bundle)
    }

    /**
     * 解析并导入备份 JSON（覆盖式）。
     * @throws IllegalArgumentException 文件无效或 schemaVersion 不兼容
     */
    suspend fun import(json: String): BackupImportResult {
        val bundle = runCatching { gson.fromJson(json, BackupBundle::class.java) }
            .getOrNull()
            ?: throw IllegalArgumentException("invalid backup file")
        if (bundle.schemaVersion != SCHEMA_VERSION) {
            throw IllegalArgumentException("unsupported backup schema: ${bundle.schemaVersion}")
        }

        bookmarkManager.importAll(
            bundle.bookmarkedPosts,
            bundle.bookmarkedCreators.map { it.service to it.creatorId }
        )
        blockedCreatorManager.importAll(bundle.blockedCreators.map { it.service to it.creatorId })
        readingProgressManager.importAll(bundle.readingProgress)
        downloadHistoryManager.importAll(bundle.downloadHistory)
        applySettings(bundle.settings)

        return BackupImportResult(
            bookmarks = bundle.bookmarkedPosts.size,
            creators = bundle.bookmarkedCreators.size,
            blocked = bundle.blockedCreators.size,
            downloads = bundle.downloadHistory.size
        )
    }

    private fun applySettings(settings: BackupSettings) {
        settings.language?.let { code ->
            SettingsManager.Language.entries.find { it.code == code }
                ?.let { settingsManager.setLanguage(it) }
        }
        settings.appearance?.let { name ->
            runCatching { SettingsManager.Appearance.valueOf(name) }
                .getOrNull()
                ?.let { settingsManager.setAppearance(it) }
        }
        settings.autoCleanCache?.let { settingsManager.setAutoCleanCacheEnabled(it) }
        settings.autoCheckUpdate?.let { settingsManager.setAutoCheckUpdateEnabled(it) }
        settings.hideBookmarkedCreators?.let { settingsManager.setHideBookmarkedCreatorsEnabled(it) }
    }

    /** 剔除设备绑定 / 瞬时状态字段：进度、文件路径（SAF/MediaStore uri）、错误信息；进行中任务归一为已完成。 */
    private fun sanitizeDownload(record: DownloadRecord): DownloadRecord = record.copy(
        progress = 0,
        filePath = null,
        errorMessage = null,
        status = if (record.status == DownloadStatus.PENDING || record.status == DownloadStatus.RUNNING) {
            DownloadStatus.COMPLETED
        } else {
            record.status
        }
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
