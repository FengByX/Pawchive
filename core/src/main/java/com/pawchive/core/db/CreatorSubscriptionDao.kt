package com.pawchive.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pawchive.core.model.CreatorSubscriptionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 创作者订阅 DAO（ARCH-FEATURE-003）。
 */
@Dao
interface CreatorSubscriptionDao {

    /** 观察全部订阅（订阅时间倒序）。 */
    @Query("SELECT * FROM creator_subscriptions ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<CreatorSubscriptionEntity>>

    /** 同步读取全部订阅（周期同步 Worker 用）。 */
    @Query("SELECT * FROM creator_subscriptions")
    suspend fun getAll(): List<CreatorSubscriptionEntity>

    @Query("SELECT * FROM creator_subscriptions WHERE service = :service AND creatorId = :creatorId")
    suspend fun getById(service: String, creatorId: String): CreatorSubscriptionEntity?

    @Upsert
    suspend fun upsert(subscription: CreatorSubscriptionEntity)

    @Query("DELETE FROM creator_subscriptions WHERE service = :service AND creatorId = :creatorId")
    suspend fun delete(service: String, creatorId: String)
}
