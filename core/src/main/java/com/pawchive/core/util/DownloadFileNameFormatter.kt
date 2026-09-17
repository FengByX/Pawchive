package com.pawchive.core.util

import com.pawchive.core.store.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载文件命名格式化器（FEATURE：设置项扩展批次三·文件命名格式）。
 *
 * 纯函数、无 Android 依赖，便于单测覆盖；由 UI/数据层在落盘前调用：
 * - [SettingsManager.FilenameFormat.ORIGINAL]：保留原始文件名（缺失时回退时间戳命名）；
 * - [SettingsManager.FilenameFormat.TIMESTAMP]：旧版默认行为 `Pawchive_时间戳_原名尾`；
 * - [SettingsManager.FilenameFormat.TITLE_TIME]：`标题_日期时间.ext`（标题缺失时回退原名）。
 */
object DownloadFileNameFormatter {

    private const val MAX_NAME_LENGTH = 100
    private const val LEGACY_PREFIX = "Pawchive"
    private const val FALLBACK_BASE_NAME = "file"

    /**
     * 按格式生成下载文件名。
     *
     * @param format       命名格式
     * @param originalName 原始文件名（可为 null/空）
     * @param title        帖子标题（TITLE_TIME 用；可为 null）
     * @param timestampMs  时间戳（毫秒），默认当前时间
     */
    fun format(
        format: SettingsManager.FilenameFormat,
        originalName: String?,
        title: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val safeOriginal = sanitize(originalName)
        return when (format) {
            SettingsManager.FilenameFormat.ORIGINAL ->
                if (safeOriginal.isNotBlank()) {
                    safeOriginal
                } else {
                    legacyName(safeOriginal, timestampMs)
                }

            SettingsManager.FilenameFormat.TIMESTAMP -> legacyName(safeOriginal, timestampMs)

            SettingsManager.FilenameFormat.TITLE_TIME ->
                titleName(safeOriginal, sanitize(title), timestampMs)
        }
    }

    /** 旧版默认行为：`Pawchive_时间戳_原名尾`（与历史 PhotoViewerFragment 逻辑一致）。 */
    private fun legacyName(original: String, timestampMs: Long): String {
        val tail = original.ifBlank { FALLBACK_BASE_NAME }.takeLast(30)
        return "${LEGACY_PREFIX}_${timestampMs}_$tail".take(MAX_NAME_LENGTH)
    }

    /** `标题_日期时间.ext`：扩展名取自原名（超过 5 字符视为无扩展名）；标题缺失回退原名去扩展名。 */
    private fun titleName(original: String, title: String?, timestampMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
        val ext = original.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 5 }
            ?.let { ".$it" }
            .orEmpty()
        val base = title?.takeIf { it.isNotBlank() }
            ?: original.substringBeforeLast('.', "").ifBlank { FALLBACK_BASE_NAME }
        return "${base}_$stamp$ext".take(MAX_NAME_LENGTH)
    }

    /**
     * 去除文件系统非法字符（\/:*?"<>|）与控制字符，替换为下划线；限制最大长度。
     */
    fun sanitize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
        return cleaned.take(MAX_NAME_LENGTH)
    }
}
