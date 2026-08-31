package com.pawchive.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 规则匹配的文件类型（ARCH-FEATURE-002 下载规则）。
 * 由附件文件扩展名推断（见 DownloadRuleEngine.detectType）。
 */
enum class DownloadRuleFileType {
    IMAGE,
    VIDEO,
    ATTACHMENT,
    ALL
}

/**
 * 下载规则实体（ARCH-FEATURE-002：按创作者/服务/文件类型自动下载）。
 *
 * 规则引擎（DownloadRuleEngine）在帖子详情页触发时，对帖子附件逐一匹配
 * 启用中的规则，命中则自动入队 DownloadCenter。
 *
 * @param id 唯一标识（UUID v4）
 * @param name 规则名称（用户自定义）
 * @param creatorId 限定的创作者 id（null = 全部创作者）
 * @param service 限定的服务类型（null = 全部服务）
 * @param fileType 匹配的文件类型（IMAGE/VIDEO/ATTACHMENT/ALL）
 * @param enabled 是否启用（仅启用规则参与匹配）
 * @param createdAt 创建时间戳
 */
@Entity(tableName = "download_rules")
data class DownloadRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creatorId: String?,
    val service: String?,
    val fileType: DownloadRuleFileType,
    val enabled: Boolean,
    val createdAt: Long
)
