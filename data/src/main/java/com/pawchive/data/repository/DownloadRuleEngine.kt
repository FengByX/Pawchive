package com.pawchive.data.repository

import com.pawchive.core.model.Attachment
import com.pawchive.core.model.DownloadRuleFileType
import com.pawchive.core.model.DownloadType
import com.pawchive.core.model.Post
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载规则引擎（ARCH-FEATURE-002：按创作者/服务/文件类型自动下载 + 批量任务编排）。
 *
 * 在帖子详情页触发"按规则下载"时：
 * 1. 读取启用中的规则（[DownloadRuleRepository.getEnabledRules]）；
 * 2. 遍历帖子的 file + attachments，按扩展名推断文件类型；
 * 3. 逐条规则匹配（创作者/服务/文件类型），命中则通过 [DownloadCenter] 入队；
 * 4. 单附件最多入队一次（命中即 break），返回入队数量供 UI 反馈。
 *
 * 附件 URL 构造与既有逻辑一致：`https://file.pawchive.pw/data{path}`。
 */
@Singleton
class DownloadRuleEngine @Inject constructor(
    private val ruleRepository: DownloadRuleRepository,
    private val downloadEnqueuer: DownloadEnqueuer
) {

    companion object {
        // 与 PostDetailFragment.isImageFile / isVideoFile 保持一致（ARCH-BUG-MINOR-18）
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp",
            "tif", "tiff", "heic", "heif"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "webm", "mov", "m4v", "mkv",
            "ts", "flv", "wmv", "ogv"
        )

        /** 按文件名扩展名推断下载类型；无法识别返回 null（不参与规则匹配）。 */
        fun detectType(fileName: String): DownloadType? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                ext in IMAGE_EXTENSIONS -> DownloadType.IMAGE
                ext in VIDEO_EXTENSIONS -> DownloadType.VIDEO
                ext.isEmpty() -> null
                else -> DownloadType.ATTACHMENT
            }
        }

        /** 规则是否匹配指定下载类型（ALL 匹配一切）。 */
        fun ruleMatchesFileType(ruleType: DownloadRuleFileType, downloadType: DownloadType): Boolean {
            if (ruleType == DownloadRuleFileType.ALL) return true
            return when (ruleType) {
                DownloadRuleFileType.IMAGE -> downloadType == DownloadType.IMAGE
                DownloadRuleFileType.VIDEO -> downloadType == DownloadType.VIDEO
                DownloadRuleFileType.ATTACHMENT -> downloadType == DownloadType.ATTACHMENT
                DownloadRuleFileType.ALL -> true
            }
        }

        /** 推断 MIME 类型（附件下载入参；未知时回退 octet-stream）。 */
        fun guessMimeType(fileName: String): String {
            return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        }
    }

    /**
     * 对帖子应用启用中的规则，批量入队匹配的附件下载。
     *
     * @return 实际入队数量（去重后）
     */
    suspend fun enqueueMatches(post: Post): Int {
        val rules = ruleRepository.getEnabledRules()
        if (rules.isEmpty()) return 0

        // 归一化主文件（PostFile）与附件（Attachment）为 (文件名, 路径) 列表
        val files = buildList<Pair<String, String>> {
            post.file?.let { file ->
                val name = file.name.orEmpty()
                if (name.isNotBlank()) add(name to file.path.orEmpty())
            }
            post.attachments.orEmpty().forEach { attachment ->
                val name = attachment.name.orEmpty()
                if (name.isNotBlank()) add(name to attachment.path.orEmpty())
            }
        }

        var count = 0
        for ((name, path) in files) {
            val type = detectType(name) ?: continue
            val matched = rules.any { rule ->
                (rule.creatorId == null || rule.creatorId == post.user) &&
                    (rule.service == null || rule.service == post.service) &&
                    ruleMatchesFileType(rule.fileType, type)
            }
            if (!matched) continue

            val url = "https://file.pawchive.pw/data$path"
            when (type) {
                DownloadType.IMAGE -> downloadEnqueuer.enqueueImageDownload(url, name)
                DownloadType.VIDEO -> downloadEnqueuer.enqueueVideoDownload(url, name)
                DownloadType.ATTACHMENT ->
                    downloadEnqueuer.enqueueAttachmentDownload(url, name, guessMimeType(name))
            }
            count++
        }
        return count
    }
}
