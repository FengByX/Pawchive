package com.pawchive.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * 下载历史 DAO（ARCH-004：下载历史存储迁移至 Room）。
 *
 * 相比原 DataStore JSON 大列表：
 * - 单条记录更新只执行一条 UPDATE，不再整表序列化/写回；
 * - 提供按状态 / 时间范围查询，支持 UI 分类与统计；
 * - 批量状态重置（应用重启中断任务）由单条 UPDATE 完成。
 */
@Dao
interface DownloadHistoryDao {

    /** 观察全部记录，按创建时间倒序（最新的在前）。 */
    @Query("SELECT * FROM download_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    /** 观察指定 id 的记录。 */
    @Query("SELECT * FROM download_records WHERE id = :id")
    fun observeById(id: String): Flow<DownloadRecord?>

    /** 按状态查询记录（含 PENDING/RUNNING 归并为进行中的过滤在 UI 层处理）。 */
    @Query("SELECT * FROM download_records WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: DownloadStatus): Flow<List<DownloadRecord>>

    /** 按创建时间范围查询记录（支持按时间范围统计/归档）。 */
    @Query("SELECT * FROM download_records WHERE createdAt BETWEEN :startTime AND :endTime ORDER BY createdAt DESC")
    fun observeBetween(startTime: Long, endTime: Long): Flow<List<DownloadRecord>>

    /** 同步读取指定记录（无则 null）。 */
    @Query("SELECT * FROM download_records WHERE id = :id")
    suspend fun getById(id: String): DownloadRecord?

    /** 一次性读取全部记录（ARCH-FEATURE-005 备份导出用，避免依赖内存快照刷新时序）。 */
    @Query("SELECT * FROM download_records")
    suspend fun getAll(): List<DownloadRecord>

    /**
     * 按去重指纹查询进行中（PENDING/RUNNING）的任务。
     * 用于 ARCH-005 去重：相同"账号|url|文件名|类型"且未完成的任务不重复入队。
     */
    @Query("SELECT * FROM download_records WHERE dedupKey = :dedupKey AND status IN ('PENDING', 'RUNNING') LIMIT 1")
    suspend fun findActiveByDedupKey(dedupKey: String): DownloadRecord?

    /** 插入或覆盖指定记录（主键冲突时替换）。 */
    @Upsert
    suspend fun upsert(record: DownloadRecord)

    /** 批量插入或覆盖记录（主键冲突时替换；ARCH-FEATURE-005 备份导入用）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DownloadRecord>)

    /**
     * 局部更新状态/进度（单条 UPDATE，不触发整表写入）。
     * - filePath 传 null 保留原值；fileSize <= 0 保留原值；
     * - completedAt > 0 才更新（由调用方在完成时传入时间戳）。
     */
    @Query(
        """
        UPDATE download_records SET
            status = :status,
            progress = :progress,
            filePath = CASE WHEN :filePath IS NOT NULL THEN :filePath ELSE filePath END,
            fileSize = CASE WHEN :fileSize > 0 THEN :fileSize ELSE fileSize END,
            errorMessage = :errorMessage,
            completedAt = CASE WHEN :completedAt > 0 THEN :completedAt ELSE completedAt END
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        progress: Int,
        filePath: String?,
        fileSize: Long,
        errorMessage: String?,
        completedAt: Long
    )

    /** 移除指定记录（不影响已保存的文件）。 */
    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun delete(id: String)

    /** 清空全部下载历史（不影响已保存的文件）。 */
    @Query("DELETE FROM download_records")
    suspend fun clearAll()

    /** 应用重启后将所有未完成（PENDING/RUNNING）任务标记为失败（单条批量 UPDATE）。 */
    @Query("UPDATE download_records SET status = :failed, errorMessage = :message WHERE status IN ('PENDING', 'RUNNING')")
    suspend fun markInterruptedAsFailed(failed: DownloadStatus, message: String)
}
