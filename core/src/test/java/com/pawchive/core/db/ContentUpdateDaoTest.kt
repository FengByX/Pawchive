package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.ContentUpdateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ContentUpdateDao 集成测试（ARCH-FEATURE-003）。
 *
 * 覆盖：唯一索引防重复插入、未读数统计、单条/全部已读、按创作者删除、倒序观察。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentUpdateDaoTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: ContentUpdateDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.contentUpdateDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun update(
        postId: String,
        service: String = "fanbox",
        creatorId: String = "c1",
        discoveredAt: Long = 0L,
        read: Boolean = false
    ) = ContentUpdateEntity(
        service = service,
        creatorId = creatorId,
        postId = postId,
        postTitle = "title-$postId",
        discoveredAt = discoveredAt,
        read = read
    )

    @Test
    fun `insertIgnore deduplicates identical post`() = runBlocking {
        dao.insertIgnore(update(postId = "p1"))
        dao.insertIgnore(update(postId = "p1"))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `same postId on different creators are distinct`() = runBlocking {
        dao.insertIgnore(update(postId = "p1", creatorId = "c1"))
        dao.insertIgnore(update(postId = "p1", creatorId = "c2"))
        assertEquals(2, dao.observeAll().first().size)
    }

    @Test
    fun `observeAll orders by discoveredAt descending`() = runBlocking {
        dao.insertIgnore(update(postId = "a", discoveredAt = 1))
        dao.insertIgnore(update(postId = "b", discoveredAt = 3))
        dao.insertIgnore(update(postId = "c", discoveredAt = 2))

        val ids = dao.observeAll().first().map { it.postId }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun `unread count only counts unread rows`() = runBlocking {
        dao.insertIgnore(update(postId = "p1"))
        dao.insertIgnore(update(postId = "p2"))
        val readId = dao.observeAll().first().first().id
        dao.markRead(readId)

        assertEquals(1, dao.observeUnreadCount().first())
    }

    @Test
    fun `markAllRead clears unread count`() = runBlocking {
        dao.insertIgnore(update(postId = "p1"))
        dao.insertIgnore(update(postId = "p2"))
        dao.markAllRead()

        assertEquals(0, dao.observeUnreadCount().first())
        assertTrue(dao.observeAll().first().all { it.read })
    }

    @Test
    fun `deleteForCreator removes only that creator updates`() = runBlocking {
        dao.insertIgnore(update(postId = "p1", creatorId = "c1"))
        dao.insertIgnore(update(postId = "p2", creatorId = "c2"))
        dao.deleteForCreator("fanbox", "c1")

        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals("c2", remaining.first().creatorId)
    }
}
