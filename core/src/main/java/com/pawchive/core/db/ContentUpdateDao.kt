package com.pawchive.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pawchive.core.model.ContentUpdateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 内容更新通知 DAO（ARCH-FEATURE-003）。
 */
@Dao
interface ContentUpdateDao {

    /** 观察全部更新（发现时间倒序）。 */
    @Query("SELECT * FROM content_updates ORDER BY discoveredAt DESC")
    fun observeAll(): Flow<List<ContentUpdateEntity>>

    /** 观察指定创作者的更新（创作者主页"更新"展示用，倒序）。 */
    @Query("SELECT * FROM content_updates WHERE service = :service AND creatorId = :creatorId ORDER BY discoveredAt DESC")
    fun observeForCreator(service: String, creatorId: String): Flow<List<ContentUpdateEntity>>

    /** 观察未读数。 */
    @Query("SELECT COUNT(*) FROM content_updates WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    /** 插入更新；唯一索引 (service, creatorId, postId) 冲突时忽略（防重复通知）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(update: ContentUpdateEntity): Long

    @Query("UPDATE content_updates SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE content_updates SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM content_updates WHERE service = :service AND creatorId = :creatorId")
    suspend fun deleteForCreator(service: String, creatorId: String)
}
