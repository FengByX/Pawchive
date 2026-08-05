package com.pawchive.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 离线归档索引实体（ARCH-FEATURE-001：收藏内容离线索引）。
 *
 * 收藏帖子时由 BookmarkManager 同步写入，供离线浏览与全文搜索：
 * - 收藏主数据仍由 [BookmarkManager]（DataStore）负责，本表是派生索引，两者通过 id 关联；
 * - [contentText] / [attachmentsText] 为预处理的纯文本（去 HTML、附件名拼接），
 *   全文搜索使用 [OfflineArchiveFts]（FTS4 影子表）；
 * - [postJson] 保存完整 Post JSON，离线详情页无需网络即可渲染。
 *
 * @param id 唯一标识：`service|creatorId|postId`
 * @param favedAt 收藏时间戳（毫秒）
 * @param updatedAt 最后同步时间戳（毫秒）
 */
@Entity(tableName = "offline_archives")
data class OfflineArchiveEntity(
    @PrimaryKey val id: String,
    val service: String,
    val creatorId: String,
    val postId: String,
    val title: String?,
    @ColumnInfo(name = "contentText") val contentText: String?,
    val userName: String?,
    @ColumnInfo(name = "attachmentsText") val attachmentsText: String?,
    @ColumnInfo(name = "postJson") val postJson: String,
    @ColumnInfo(name = "favedAt") val favedAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long
)
