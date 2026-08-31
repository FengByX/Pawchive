package com.pawchive.data.repository

import com.pawchive.core.db.DownloadRuleDao
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.DownloadRuleFileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载规则仓库（ARCH-FEATURE-002）。
 *
 * 规则 CRUD 与观察入口；规则引擎（[DownloadRuleEngine]）通过 [getEnabledRules]
 * 读取启用中的规则进行匹配。
 */
@Singleton
class DownloadRuleRepository @Inject constructor(
    private val dao: DownloadRuleDao
) {

    /** 观察全部规则（创建时间倒序）。 */
    fun observeRules(): Flow<List<DownloadRuleEntity>> = dao.observeAll()

    /** 同步读取全部规则。 */
    suspend fun getRules(): List<DownloadRuleEntity> = dao.observeAll().first()

    /** 读取全部启用中的规则（引擎匹配用）。 */
    suspend fun getEnabledRules(): List<DownloadRuleEntity> = dao.getEnabled()

    /** 新增规则。 */
    suspend fun addRule(
        name: String,
        creatorId: String?,
        service: String?,
        fileType: DownloadRuleFileType,
        enabled: Boolean = true
    ): DownloadRuleEntity {
        val rule = DownloadRuleEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            creatorId = creatorId?.takeIf { it.isNotBlank() },
            service = service?.takeIf { it.isNotBlank() },
            fileType = fileType,
            enabled = enabled,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(rule)
        return rule
    }

    /** 更新规则（编辑时整体覆盖）。 */
    suspend fun updateRule(rule: DownloadRuleEntity) {
        dao.upsert(rule)
    }

    /** 切换规则启用状态。 */
    suspend fun toggleEnabled(id: String, enabled: Boolean) {
        val rule = dao.getById(id) ?: return
        dao.upsert(rule.copy(enabled = enabled))
    }

    /** 删除规则。 */
    suspend fun deleteRule(id: String) {
        dao.delete(id)
    }
}
