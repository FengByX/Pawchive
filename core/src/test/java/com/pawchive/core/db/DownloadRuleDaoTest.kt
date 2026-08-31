package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.DownloadRuleFileType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DownloadRuleDao 集成测试（ARCH-FEATURE-002）。
 *
 * 覆盖核心场景：
 * - upsert 后 getById 可读
 * - observeAll 按创建时间倒序
 * - getEnabled 仅返回启用规则
 * - 同 id upsert 覆盖更新
 * - delete 后记录不可读
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRuleDaoTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: DownloadRuleDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.downloadRuleDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun rule(
        id: String,
        name: String = "rule-$id",
        creatorId: String? = null,
        service: String? = null,
        fileType: DownloadRuleFileType = DownloadRuleFileType.ALL,
        enabled: Boolean = true,
        createdAt: Long = 0L
    ) = DownloadRuleEntity(
        id = id,
        name = name,
        creatorId = creatorId,
        service = service,
        fileType = fileType,
        enabled = enabled,
        createdAt = createdAt
    )

    @Test
    fun `upsert then getById returns the rule`() = runBlocking {
        val r = rule(id = "r1", name = "fanbox 图片")
        dao.upsert(r)
        val loaded = dao.getById("r1")
        assertEquals(r, loaded)
    }

    @Test
    fun `observeAll orders by createdAt descending`() = runBlocking {
        dao.upsert(rule(id = "a", createdAt = 1))
        dao.upsert(rule(id = "b", createdAt = 3))
        dao.upsert(rule(id = "c", createdAt = 2))

        val ids = dao.observeAll().first().map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun `getEnabled returns only enabled rules`() = runBlocking {
        dao.upsert(rule(id = "on1", enabled = true))
        dao.upsert(rule(id = "off1", enabled = false))
        dao.upsert(rule(id = "on2", enabled = true))

        val enabledIds = dao.getEnabled().map { it.id }.toSet()
        assertEquals(setOf("on1", "on2"), enabledIds)
    }

    @Test
    fun `upsert with same id overwrites the rule`() = runBlocking {
        dao.upsert(rule(id = "r1", name = "旧名称", fileType = DownloadRuleFileType.IMAGE))
        dao.upsert(rule(id = "r1", name = "新名称", fileType = DownloadRuleFileType.VIDEO))

        val loaded = dao.getById("r1")
        assertEquals("新名称", loaded?.name)
        assertEquals(DownloadRuleFileType.VIDEO, loaded?.fileType)
    }

    @Test
    fun `delete removes the rule`() = runBlocking {
        dao.upsert(rule(id = "r1"))
        dao.delete("r1")
        assertNull(dao.getById("r1"))
    }

    @Test
    fun `empty table reports no enabled rules`() = runBlocking {
        assertTrue(dao.getEnabled().isEmpty())
    }
}
