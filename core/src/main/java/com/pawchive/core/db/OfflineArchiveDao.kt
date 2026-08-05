package com.pawchive.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pawchive.core.model.OfflineArchiveEntity
import kotlinx.coroutines.flow.Flow

/**
 * 离线归档 DAO（ARCH-FEATURE-001）。
 *
 * 实体表（offline_archives）与 FTS 影子表（offline_archives_fts）显式同步：
 * 写入/删除时由调用方先操作实体表，再同步 FTS 表，避免依赖 SQLite 触发器。
 */
@Dao
interface OfflineArchiveDao {

    /** 观察全部归档，按收藏时间倒序（最新收藏在前）。 */
    @Query("SELECT * FROM offline_archives ORDER BY favedAt DESC")
    fun observeAll(): Flow<List<OfflineArchiveEntity>>

    /** 同步读取单个归档（无则 null）。 */
    @Query("SELECT * FROM offline_archives WHERE id = :id")
    suspend fun getById(id: String): OfflineArchiveEntity?

    /** 插入或覆盖归档记录（主键冲突时替换）。 */
    @Upsert
    suspend fun upsert(entry: OfflineArchiveEntity)

    /** 按 entryId 删除 FTS 索引行（写入前调用保证幂等）。 */
    @Query("DELETE FROM offline_archives_fts WHERE entryId = :entryId")
    suspend fun deleteFts(entryId: String)

    /** 插入 FTS 索引行（列内容需已做 CJK bigram 预处理）。 */
    @Query(
        """
        INSERT INTO offline_archives_fts(entryId, title, content, userName, user, attachments)
        VALUES (:entryId, :title, :content, :userName, :user, :attachments)
        """
    )
    suspend fun insertFts(
        entryId: String,
        title: String?,
        content: String?,
        userName: String?,
        user: String?,
        attachments: String?
    )

    /** 删除归档记录（不删除 FTS 行，调用方需同步 [deleteFts]）。 */
    @Query("DELETE FROM offline_archives WHERE id = :id")
    suspend fun delete(id: String)

    /** 清空全部归档记录（账号切换/登出数据隔离，调用方需同步 [clearFts]）。 */
    @Query("DELETE FROM offline_archives")
    suspend fun clearAll()

    /** 清空全部 FTS 索引行。 */
    @Query("DELETE FROM offline_archives_fts")
    suspend fun clearFts()

    /** 全文搜索：返回匹配的 entryId 列表（query 需已做转义与 CJK bigram 预处理）。 */
    @Query("SELECT entryId FROM offline_archives_fts WHERE offline_archives_fts MATCH :query")
    suspend fun searchEntryIds(query: String): List<String>

    /** 按 id 列表批量读取归档（搜索结果第二段查询）。 */
    @Query("SELECT * FROM offline_archives WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<OfflineArchiveEntity>

    /** 归档总数。 */
    @Query("SELECT COUNT(*) FROM offline_archives")
    suspend fun count(): Int

    /** 归档内容总字节数（postJson 长度之和，ARCH-FEATURE-004 缓存管理页用）。 */
    @Query("SELECT COALESCE(SUM(LENGTH(postJson)), 0) FROM offline_archives")
    suspend fun getTotalBytes(): Long
}
