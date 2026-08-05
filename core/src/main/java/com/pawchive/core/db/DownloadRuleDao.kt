package com.pawchive.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pawchive.core.model.DownloadRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * 下载规则 DAO（ARCH-FEATURE-002）。
 */
@Dao
interface DownloadRuleDao {

    /** 观察全部规则，按创建时间倒序（最新在前）。 */
    @Query("SELECT * FROM download_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRuleEntity>>

    /** 读取全部启用中的规则（规则引擎匹配用）。 */
    @Query("SELECT * FROM download_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<DownloadRuleEntity>

    /** 同步读取单条规则（无则 null）。 */
    @Query("SELECT * FROM download_rules WHERE id = :id")
    suspend fun getById(id: String): DownloadRuleEntity?

    /** 插入或覆盖规则（主键冲突时替换）。 */
    @Upsert
    suspend fun upsert(rule: DownloadRuleEntity)

    /** 删除规则。 */
    @Query("DELETE FROM download_rules WHERE id = :id")
    suspend fun delete(id: String)
}
