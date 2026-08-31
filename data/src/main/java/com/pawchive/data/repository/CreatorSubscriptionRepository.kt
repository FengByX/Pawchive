package com.pawchive.data.repository

import android.util.Log
import com.pawchive.core.api.ApiClient
import com.pawchive.core.db.ContentUpdateDao
import com.pawchive.core.db.CreatorSubscriptionDao
import com.pawchive.core.model.ContentUpdateEntity
import com.pawchive.core.model.CreatorSubscriptionEntity
import com.pawchive.core.model.Post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内容更新条目 + 创作者名称（更新列表展示用，名称来自订阅时缓存）。
 */
data class ContentUpdateWithCreator(
    val update: ContentUpdateEntity,
    val creatorName: String?
)

/**
 * 一次周期同步的结果（ARCH-FEATURE-003 系统通知推送）。
 *
 * - [syncedCount]：成功同步的创作者数（每个创作者独立容错）
 * - [newUpdates]：本次新增的内容更新列表（用于系统通知栏推送）
 */
data class SubscriptionSyncResult(
    val syncedCount: Int,
    val newUpdates: List<ContentUpdateEntity>
)

/**
 * 创作者订阅仓库（ARCH-FEATURE-003：内容更新订阅）。
 *
 * 职责：
 * - 订阅/退订/观察：订阅本地存储，不依赖登录态
 * - 未读数：content_updates 中 read = false 的行数
 * - 增量同步：拉取订阅创作者最新帖，与订阅基线（lastPostId）对比，
 *   基线之后的新帖写入内容更新表（唯一索引防重复通知），并推进基线
 *
 * 同步默认走 [ApiClient.publicApi]；测试可通过 [syncSubscribedCreators] 的
 * [fetchPosts] 参数注入虚实现，避免真实网络与 Cloudflare 过盾。
 */
@Singleton
class CreatorSubscriptionRepository @Inject constructor(
    private val subscriptionDao: CreatorSubscriptionDao,
    private val updateDao: ContentUpdateDao
) {

    companion object {
        private const val TAG = "CreatorSubscription"
        // 并发同步限流：避免大量订阅同时请求导致 429/超时/WorkManager 超时（ARCH-BUG-MAJOR-8）
        private const val MAX_CONCURRENT_SYNC = 4
    }

    /** 观察全部订阅（订阅时间倒序）。 */
    fun observeSubscriptions(): Flow<List<CreatorSubscriptionEntity>> = subscriptionDao.observeAll()

    /** 观察未读数。 */
    fun observeUnreadCount(): Flow<Int> = updateDao.observeUnreadCount()

    /** 观察内容更新列表（含创作者名称，发现时间倒序）。 */
    fun observeUpdates(): Flow<List<ContentUpdateWithCreator>> =
        combine(updateDao.observeAll(), subscriptionDao.observeAll()) { updates, subscriptions ->
            val names = subscriptions.associate { (it.service to it.creatorId) to it.name }
            updates.map { update ->
                ContentUpdateWithCreator(
                    update = update,
                    creatorName = names[update.service to update.creatorId]
                )
            }
        }

    suspend fun isSubscribed(service: String, creatorId: String): Boolean =
        subscriptionDao.getById(service, creatorId) != null

    /** 同步读取全部订阅（周期同步 Worker 用）。 */
    suspend fun getSubscriptions(): List<CreatorSubscriptionEntity> = subscriptionDao.getAll()

    /**
     * 订阅创作者。
     * @param lastPostId 订阅时最新帖 id，作为增量基线；null 表示首次订阅无基线
     *                   （首次同步只初始化基线，不把历史帖当新帖通知）
     */
    suspend fun subscribe(service: String, creatorId: String, name: String?, lastPostId: String?) {
        subscriptionDao.upsert(
            CreatorSubscriptionEntity(
                service = service,
                creatorId = creatorId,
                name = name,
                lastPostId = lastPostId,
                subscribedAt = System.currentTimeMillis()
            )
        )
    }

    /** 退订并清除该创作者的历史更新通知。 */
    suspend fun unsubscribe(service: String, creatorId: String) {
        subscriptionDao.delete(service, creatorId)
        updateDao.deleteForCreator(service, creatorId)
    }

    suspend fun markRead(id: Long) = updateDao.markRead(id)

    suspend fun markAllRead() = updateDao.markAllRead()

    /**
     * 对全部订阅创作者执行增量同步（并发限流）。
     *
     * 每个创作者独立容错：单个失败仅记日志，不影响其他订阅；返回成功同步数。
     * 需要本次新增明细（系统通知推送）请使用 [syncSubscribedCreatorsDetailed]。
     *
     * @param fetchPosts 拉取创作者最新帖列表（默认走公开 API，测试可注入虚实现）
     * @return 成功同步的创作者数
     */
    suspend fun syncSubscribedCreators(
        fetchPosts: suspend (service: String, creatorId: String) -> List<Post> = { service, creatorId ->
            ApiClient.publicApi.getCreatorPosts(service, creatorId, offset = 0)
        }
    ): Int = syncSubscribedCreatorsDetailed(fetchPosts).syncedCount

    /**
     * 对全部订阅创作者执行增量同步（并发限流），并返回本次新增明细（ARCH-FEATURE-003 系统通知推送）。
     *
     * 与 [syncSubscribedCreators] 同一执行语义，额外通过 [SubscriptionSyncResult.newUpdates]
     * 返回本次实际新增（唯一索引冲突忽略的不算）的内容更新，供 Worker 组装系统通知。
     */
    suspend fun syncSubscribedCreatorsDetailed(
        fetchPosts: suspend (service: String, creatorId: String) -> List<Post> = { service, creatorId ->
            ApiClient.publicApi.getCreatorPosts(service, creatorId, offset = 0)
        }
    ): SubscriptionSyncResult {
        val subscriptions = subscriptionDao.getAll()
        if (subscriptions.isEmpty()) return SubscriptionSyncResult(0, emptyList())

        val semaphore = Semaphore(MAX_CONCURRENT_SYNC)
        val results = coroutineScope {
            subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        runCatching { syncCreator(subscription, fetchPosts) }
                            .onSuccess {
                                // success handled by caller
                            }
                            .onFailure {
                                Log.w(TAG, "sync failed for ${subscription.service}/${subscription.creatorId}")
                            }
                    }
                }
            }.awaitAll()
        }

        var synced = 0
        val newUpdates = mutableListOf<ContentUpdateEntity>()
        for (result in results) {
            result.onSuccess {
                synced++
                newUpdates += it
            }
        }
        return SubscriptionSyncResult(synced, newUpdates)
    }

    /** 同步单个创作者，返回本次新增的内容更新列表（无基线或无可推进时为空）。 */
    private suspend fun syncCreator(
        subscription: CreatorSubscriptionEntity,
        fetchPosts: suspend (service: String, creatorId: String) -> List<Post>
    ): List<ContentUpdateEntity> {
        val posts = fetchPosts(subscription.service, subscription.creatorId)
        val newestPostId = posts.firstOrNull()?.id

        // 有基线时：最新在前，基线之后（直到遇到基线帖）均为新帖
        val baseline = subscription.lastPostId
        val newUpdates = mutableListOf<ContentUpdateEntity>()
        if (baseline != null && newestPostId != null) {
            val now = System.currentTimeMillis()
            for (post in posts.takeWhile { it.id != baseline }) {
                val update = ContentUpdateEntity(
                    service = subscription.service,
                    creatorId = subscription.creatorId,
                    postId = post.id,
                    postTitle = post.title,
                    discoveredAt = now
                )
                // 唯一索引 (service, creatorId, postId) 冲突时忽略：仅统计本次真正新增
                if (updateDao.insertIgnore(update) > 0) newUpdates += update
            }
        }

        // 推进基线（即使无新帖，也保证后续同步从最新帖开始）
        if (newestPostId != null && newestPostId != baseline) {
            subscriptionDao.upsert(subscription.copy(lastPostId = newestPostId))
        }
        return newUpdates
    }
}
