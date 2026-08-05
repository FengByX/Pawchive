package com.pawchive.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 创作者订阅实体（ARCH-FEATURE-003：内容更新订阅）。
 *
 * 复合主键 (service, creatorId)：同一创作者 id 在不同服务下互不冲突。
 * [lastPostId] 记录最近一次同步到的最新帖 id；为 null 表示首次订阅
 * （以订阅时最新帖为基线，不把历史帖当新帖通知）。
 */
@Entity(
    tableName = "creator_subscriptions",
    primaryKeys = ["service", "creatorId"],
    indices = [Index("service")]
)
data class CreatorSubscriptionEntity(
    val service: String,
    val creatorId: String,
    /** 订阅时缓存的创作者名称（更新列表展示用，不实时刷新）。 */
    val name: String?,
    /** 最近一次同步到的最新帖 id；null = 首次订阅，仅初始化基线不产生通知。 */
    val lastPostId: String?,
    val subscribedAt: Long
)

/**
 * 内容更新通知实体（ARCH-FEATURE-003）。
 *
 * 每次周期同步发现"订阅基线之后的新帖"时插入一行；(service, creatorId, postId)
 * 唯一索引 + [androidx.room.OnConflictStrategy.IGNORE] 保证同一帖子不会重复通知。
 * [read] 标记未读状态，未读数即 read = false 的行数。
 */
@Entity(
    tableName = "content_updates",
    indices = [
        Index("service"),
        Index(value = ["service", "creatorId", "postId"], unique = true),
        Index("read")
    ]
)
data class ContentUpdateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val service: String,
    val creatorId: String,
    val postId: String,
    val postTitle: String?,
    val discoveredAt: Long,
    val read: Boolean = false
)
