package com.pawchive.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.db.DownloadRuleDao
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.Attachment
import com.pawchive.core.model.DownloadRuleFileType
import com.pawchive.core.model.DownloadType
import com.pawchive.core.model.Post
import com.pawchive.core.model.PostFile
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
 * DownloadRuleEngine 测试（ARCH-FEATURE-002）。
 *
 * 覆盖：
 * - detectType / ruleMatchesFileType / guessMimeType 纯逻辑
 * - enqueueMatches 批量入队（创作者/服务/文件类型匹配、启停、去重跳过、主文件+附件归一化）
 *   通过 [FakeDownloadEnqueuer] 虚实现注入，避免真实 WorkManager。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRuleEngineTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: DownloadRuleDao
    private lateinit var repository: DownloadRuleRepository
    private lateinit var enqueuer: FakeDownloadEnqueuer
    private lateinit var engine: DownloadRuleEngine

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.downloadRuleDao()
        repository = DownloadRuleRepository(dao)
        enqueuer = FakeDownloadEnqueuer()
        engine = DownloadRuleEngine(repository, enqueuer)
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---------- 纯逻辑 ----------

    @Test
    fun `detectType classifies known extensions`() {
        assertEquals(DownloadType.IMAGE, DownloadRuleEngine.detectType("photo.jpg"))
        assertEquals(DownloadType.IMAGE, DownloadRuleEngine.detectType("PHOTO.PNG"))
        assertEquals(DownloadType.VIDEO, DownloadRuleEngine.detectType("clip.mp4"))
        assertEquals(DownloadType.ATTACHMENT, DownloadRuleEngine.detectType("archive.zip"))
        assertNull(DownloadRuleEngine.detectType("noext"))
    }

    @Test
    fun `ruleMatchesFileType respects ALL wildcard`() {
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.ALL, DownloadType.IMAGE))
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.ALL, DownloadType.VIDEO))
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.ALL, DownloadType.ATTACHMENT))
    }

    @Test
    fun `ruleMatchesFileType requires exact type for non-ALL`() {
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.IMAGE, DownloadType.IMAGE))
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.VIDEO, DownloadType.VIDEO))
        assertTrue(DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.ATTACHMENT, DownloadType.ATTACHMENT))
        assertEquals(false, DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.IMAGE, DownloadType.VIDEO))
        assertEquals(false, DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.VIDEO, DownloadType.ATTACHMENT))
        assertEquals(false, DownloadRuleEngine.ruleMatchesFileType(DownloadRuleFileType.ATTACHMENT, DownloadType.IMAGE))
    }

    @Test
    fun `guessMimeType falls back for unknown extension`() {
        assertEquals("application/octet-stream", DownloadRuleEngine.guessMimeType("data.bin"))
    }

    // ---------- 批量入队 ----------

    private fun insertRule(
        creatorId: String? = null,
        service: String? = null,
        fileType: DownloadRuleFileType = DownloadRuleFileType.ALL,
        enabled: Boolean = true
    ) = runBlocking {
        repository.addRule(name = "test", creatorId = creatorId, service = service, fileType = fileType, enabled = enabled)
    }

    private fun postWith(file: PostFile?, attachments: List<Attachment>?) = Post(
        id = "p1",
        user = "creator1",
        userName = "Creator One",
        service = "fanbox",
        title = "标题",
        content = "内容",
        added = null,
        published = null,
        edited = null,
        file = file,
        attachments = attachments,
        sharedFile = null
    )

    @Test
    fun `enqueueMatches returns 0 when no rules exist`() = runBlocking {
        val post = postWith(PostFile("a.jpg", "/1/a.jpg"), null)
        assertEquals(0, engine.enqueueMatches(post))
        assertTrue(enqueuer.enqueued.isEmpty())
    }

    @Test
    fun `enqueueMatches filters by file type and enqueues only matches`() = runBlocking {
        insertRule(creatorId = "creator1", service = "fanbox", fileType = DownloadRuleFileType.IMAGE)
        val post = postWith(
            file = PostFile("a.jpg", "/1/a.jpg"),
            attachments = listOf(
                Attachment("b.png", "/1/b.png"),
                Attachment("c.mp4", "/1/c.mp4")
            )
        )

        val count = engine.enqueueMatches(post)

        assertEquals(2, count)
        assertEquals(
            listOf("IMAGE:https://file.pawchive.pw/data/1/a.jpg", "IMAGE:https://file.pawchive.pw/data/1/b.png"),
            enqueuer.enqueued.map { "${it.type}:${it.url}" }
        )
    }

    @Test
    fun `enqueueMatches ALL rule enqueues every file including videos`() = runBlocking {
        insertRule(fileType = DownloadRuleFileType.ALL)
        val post = postWith(
            file = PostFile("clip.mp4", "/1/clip.mp4"),
            attachments = listOf(Attachment("a.jpg", "/1/a.jpg"))
        )

        val count = engine.enqueueMatches(post)

        assertEquals(2, count)
        assertTrue(enqueuer.enqueued.any { it.type == "VIDEO" })
        assertTrue(enqueuer.enqueued.any { it.type == "IMAGE" })
    }

    @Test
    fun `enqueueMatches ignores disabled rules`() = runBlocking {
        insertRule(creatorId = "creator1", service = "fanbox", fileType = DownloadRuleFileType.ALL, enabled = false)
        val post = postWith(PostFile("a.jpg", "/1/a.jpg"), null)

        assertEquals(0, engine.enqueueMatches(post))
        assertTrue(enqueuer.enqueued.isEmpty())
    }

    @Test
    fun `enqueueMatches does not match other creators or services`() = runBlocking {
        insertRule(creatorId = "otherCreator", service = "patreon", fileType = DownloadRuleFileType.ALL)
        val post = postWith(PostFile("a.jpg", "/1/a.jpg"), null)

        assertEquals(0, engine.enqueueMatches(post))
        assertTrue(enqueuer.enqueued.isEmpty())
    }

    @Test
    fun `enqueueMatches skips files without recognizable extension`() = runBlocking {
        insertRule(fileType = DownloadRuleFileType.ALL)
        val post = postWith(PostFile("noext", "/1/noext"), listOf(Attachment("a.jpg", "/1/a.jpg")))

        assertEquals(1, engine.enqueueMatches(post))
        assertEquals(listOf("IMAGE:https://file.pawchive.pw/data/1/a.jpg"), enqueuer.enqueued.map { "${it.type}:${it.url}" })
    }

    @Test
    fun `enqueueMatches matches by creator only when service is null`() = runBlocking {
        insertRule(creatorId = "creator1", fileType = DownloadRuleFileType.IMAGE)
        val post = postWith(PostFile("a.jpg", "/1/a.jpg"), null)

        assertEquals(1, engine.enqueueMatches(post))
    }

    private class FakeDownloadEnqueuer : DownloadEnqueuer {
        data class EnqueuedCall(val type: String, val url: String, val fileName: String)

        val enqueued = mutableListOf<EnqueuedCall>()

        override suspend fun enqueueImageDownload(url: String, fileName: String, mimeType: String): String {
            enqueued.add(EnqueuedCall("IMAGE", url, fileName))
            return "img-${enqueued.size}"
        }

        override suspend fun enqueueVideoDownload(url: String, fileName: String, mimeType: String): String {
            enqueued.add(EnqueuedCall("VIDEO", url, fileName))
            return "vid-${enqueued.size}"
        }

        override suspend fun enqueueAttachmentDownload(url: String, fileName: String, mimeType: String): String {
            enqueued.add(EnqueuedCall("ATTACHMENT", url, fileName))
            return "att-${enqueued.size}"
        }
    }
}
