package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.OfflineArchiveEntity
import com.pawchive.core.util.OfflineArchiveIndexer
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
 * OfflineArchiveDao 集成测试（ARCH-FEATURE-001）。
 *
 * 覆盖核心场景：
 * - 实体表读写与计数
 * - observeAll 按收藏时间倒序
 * - FTS4 中文 bigram 全文搜索（命中/未命中）
 * - FTS4 英文前缀搜索
 * - 删除时实体行 + FTS 行同步清理
 * - 清空时实体表 + FTS 表同步清空
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineArchiveDaoTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: OfflineArchiveDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.offlineArchiveDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun index(
        id: String,
        title: String?,
        content: String? = null,
        userName: String? = null,
        user: String? = null,
        attachments: String? = null,
        favedAt: Long = 0L
    ) = runBlocking {
        val entry = OfflineArchiveEntity(
            id = id,
            service = "fanbox",
            creatorId = "creator1",
            postId = id.substringAfterLast('|'),
            title = title,
            contentText = content,
            userName = userName,
            attachmentsText = attachments,
            postJson = """{"id":"$id"}""",
            favedAt = favedAt,
            updatedAt = favedAt
        )
        dao.upsert(entry)
        dao.deleteFts(id)
        dao.insertFts(
            entryId = id,
            title = OfflineArchiveIndexer.tokenize(title),
            content = OfflineArchiveIndexer.tokenize(content),
            userName = OfflineArchiveIndexer.tokenize(userName),
            user = OfflineArchiveIndexer.tokenize(user),
            attachments = OfflineArchiveIndexer.tokenize(attachments)
        )
    }

    @Test
    fun `upsert then getById and count`() = runBlocking {
        index("fanbox|c1|p1", "标题一")
        assertEquals(1, dao.count())
        assertEquals("标题一", dao.getById("fanbox|c1|p1")?.title)
    }

    @Test
    fun `upsert overwrites same id`() = runBlocking {
        index("fanbox|c1|p1", "标题一")
        index("fanbox|c1|p1", "标题二")
        assertEquals(1, dao.count())
        assertEquals("标题二", dao.getById("fanbox|c1|p1")?.title)
    }

    @Test
    fun `observeAll orders by favedAt descending`() = runBlocking {
        index("fanbox|c1|p1", "旧帖", favedAt = 100L)
        index("fanbox|c1|p2", "新帖", favedAt = 200L)
        val list = dao.observeAll().first()
        assertEquals(listOf("fanbox|c1|p2", "fanbox|c1|p1"), list.map { it.id })
    }

    @Test
    fun `search chinese by bigram matches and misses`() = runBlocking {
        index("fanbox|c1|p1", "收藏夹内容")
        index("fanbox|c1|p2", "无关话题")
        // "收藏" 是 "收藏夹内容" 的首个 bigram
        val hit1 = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("收藏"))
        assertEquals(listOf("fanbox|c1|p1"), hit1)
        // "夹内容" bigram 化后（夹内/内容）命中
        val hit2 = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("夹内容"))
        assertEquals(listOf("fanbox|c1|p1"), hit2)
        // 无关词不命中
        val miss = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("天气"))
        assertTrue(miss.isEmpty())
    }

    @Test
    fun `search latin with prefix wildcard and case folding`() = runBlocking {
        index("fanbox|c1|p1", "Pawchive Update")
        // simple tokenizer 小写化：大写查询也可命中，前缀 "paw" 命中 "pawchive"
        val hit = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("PAW"))
        assertEquals(listOf("fanbox|c1|p1"), hit)
    }

    @Test
    fun `search scopes by multiple columns`() = runBlocking {
        index("fanbox|c1|p1", "标题", userName = "创作者名字")
        index("fanbox|c1|p2", "标题", content = "正文包含关键词")
        // 标题相同，靠正文/用户名区分命中
        val byName = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("创作者"))
        assertEquals(listOf("fanbox|c1|p1"), byName)
        val byContent = dao.searchEntryIds(OfflineArchiveIndexer.toQuery("关键词"))
        assertEquals(listOf("fanbox|c1|p2"), byContent)
    }

    @Test
    fun `column filtered search returns title matches only`() = runBlocking {
        // A 命中标题，B 仅命中正文：标题列过滤查询只返回 A，正文/附件列过滤查询只返回 B
        index("fanbox|c1|pA", "收藏攻略", content = "无关正文")
        index("fanbox|c1|pB", "无关标题", content = "正文提及收藏笔记")
        assertEquals(
            listOf("fanbox|c1|pA"),
            dao.searchEntryIds(OfflineArchiveIndexer.toColumnQuery("收藏", "title"))
        )
        assertEquals(
            listOf("fanbox|c1|pB"),
            dao.searchEntryIds(OfflineArchiveIndexer.toMultiColumnQuery("收藏", "content", "attachments"))
        )
    }

    @Test
    fun `column filtered search returns creator matches only`() = runBlocking {
        // A 命中创作者名，B 命中创作者 id（user 列）：创作者列过滤查询包含二者，正文列不包含
        index("fanbox|c1|pA", "无关标题", userName = "收藏大师")
        index("fanbox|c1|pB", "无关标题", user = "collector_收藏_2024")
        val creatorHits = dao.searchEntryIds(
            OfflineArchiveIndexer.toMultiColumnQuery("收藏", "userName", "user")
        )
        assertEquals(setOf("fanbox|c1|pA", "fanbox|c1|pB"), creatorHits.toSet())
        assertTrue(
            dao.searchEntryIds(
                OfflineArchiveIndexer.toMultiColumnQuery("收藏", "content", "attachments")
            ).isEmpty()
        )
    }

    @Test
    fun `column filtered search matches attachments column`() = runBlocking {
        // 附件名命中：正文/附件列过滤查询应返回该帖
        index("fanbox|c1|pA", "无关标题", attachments = "sample_收藏_archive.zip")
        assertEquals(
            listOf("fanbox|c1|pA"),
            dao.searchEntryIds(OfflineArchiveIndexer.toMultiColumnQuery("收藏", "content", "attachments"))
        )
    }

    @Test
    fun `delete removes entry and fts row`() = runBlocking {
        index("fanbox|c1|p1", "待删除")
        dao.delete("fanbox|c1|p1")
        dao.deleteFts("fanbox|c1|p1")
        assertNull(dao.getById("fanbox|c1|p1"))
        assertTrue(dao.searchEntryIds(OfflineArchiveIndexer.toQuery("待删除")).isEmpty())
        assertEquals(0, dao.count())
    }

    @Test
    fun `clearAll clears both tables`() = runBlocking {
        index("fanbox|c1|p1", "一")
        index("fanbox|c1|p2", "二")
        dao.clearAll()
        dao.clearFts()
        assertEquals(0, dao.count())
        assertTrue(dao.searchEntryIds(OfflineArchiveIndexer.toQuery("一")).isEmpty())
    }

    @Test
    fun `getByIds returns matching entries`() = runBlocking {
        index("fanbox|c1|p1", "一")
        index("fanbox|c1|p2", "二")
        index("fanbox|c1|p3", "三")
        val list = dao.getByIds(listOf("fanbox|c1|p3", "fanbox|c1|p1"))
        // SQLite IN 子句不保证返回顺序，这里只断言命中集合（相关性顺序由 Repository 层维护）
        assertEquals(setOf("fanbox|c1|p3", "fanbox|c1|p1"), list.map { it.id }.toSet())
    }

    @Test
    fun `getTotalBytes sums postJson length`() = runBlocking {
        val e1 = OfflineArchiveEntity(
            id = "fanbox|c1|p1",
            service = "fanbox",
            creatorId = "c1",
            postId = "p1",
            title = "一",
            contentText = null,
            userName = null,
            attachmentsText = null,
            postJson = """{"id":"p1"}""",
            favedAt = 1L,
            updatedAt = 1L
        )
        val e2 = OfflineArchiveEntity(
            id = "fanbox|c1|p2",
            service = "fanbox",
            creatorId = "c1",
            postId = "p2",
            title = "二",
            contentText = null,
            userName = null,
            attachmentsText = null,
            postJson = """{"id":"p2","extra":"abc"}""",
            favedAt = 2L,
            updatedAt = 2L
        )
        dao.upsert(e1)
        dao.upsert(e2)
        // SQLite LENGTH 对文本按字符计数；ASCII postJson 字符数即字节数
        assertEquals(
            e1.postJson.length.toLong() + e2.postJson.length.toLong(),
            dao.getTotalBytes()
        )
        dao.clearAll()
        assertEquals(0L, dao.getTotalBytes())
    }
}
