package com.pawchive.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.Post
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CreatorSubscriptionRepository 测试（ARCH-FEATURE-003）。
 *
 * 通过注入虚实现 [fetchPosts] 验证增量同步语义：
 * - 首次订阅无基线 → 只初始化基线，不产生通知
 * - 有基线 → 仅基线之后的新帖产生通知
 * - 重复同步不重复通知（唯一索引 + 基线推进）
 * - 退订清除该创作者的历史通知
 * - 未读数 / 全部已读 / 更新列表创作者名称联查
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorSubscriptionRepositoryTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var repository: CreatorSubscriptionRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        repository = CreatorSubscriptionRepository(
            db.creatorSubscriptionDao(),
            db.contentUpdateDao()
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun post(id: String) = Post(
        id = id,
        user = "c1",
        userName = "Creator One",
        service = "fanbox",
        title = "标题-$id",
        content = null,
        added = null,
        published = null,
        edited = null,
        file = null,
        attachments = null,
        sharedFile = null
    )

    private fun fetch(vararg posts: Post): suspend (String, String) -> List<Post> =
        { _, _ -> posts.toList() }

    @Test
    fun `first subscribe without baseline only initializes baseline`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = null)

        val synced = repository.syncSubscribedCreators(fetch(post("p2"), post("p1")))

        assertEquals(1, synced)
        assertTrue(repository.observeUpdates().first().isEmpty())
        val sub = repository.getSubscriptions().first()
        assertEquals("p2", sub.lastPostId)
    }

    @Test
    fun `sync notifies only posts after baseline`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")

        repository.syncSubscribedCreators(fetch(post("p7"), post("p6"), post("p5"), post("p4")))

        val updates = repository.observeUpdates().first()
        assertEquals(setOf("p7", "p6"), updates.map { it.update.postId }.toSet())
        assertEquals("p7", repository.getSubscriptions().first().lastPostId)
        assertEquals(2, repository.observeUnreadCount().first())
    }

    @Test
    fun `repeated sync does not duplicate notifications`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")
        val fetcher = fetch(post("p7"), post("p6"), post("p5"))

        repository.syncSubscribedCreators(fetcher)
        repository.syncSubscribedCreators(fetcher)

        assertEquals(2, repository.observeUpdates().first().size)
    }

    @Test
    fun `unsubscribe removes subscription and its updates`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")
        repository.syncSubscribedCreators(fetch(post("p7"), post("p6"), post("p5")))
        assertEquals(2, repository.observeUpdates().first().size)

        repository.unsubscribe("fanbox", "c1")

        assertFalse(repository.isSubscribed("fanbox", "c1"))
        assertTrue(repository.observeUpdates().first().isEmpty())
    }

    @Test
    fun `markAllRead clears unread count`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")
        repository.syncSubscribedCreators(fetch(post("p7"), post("p6"), post("p5")))

        repository.markAllRead()

        assertEquals(0, repository.observeUnreadCount().first())
    }

    @Test
    fun `observeUpdates joins cached creator name`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator A", lastPostId = "p5")
        repository.syncSubscribedCreators(fetch(post("p6"), post("p5")))

        // 基线 p5，仅 p6 为新帖
        val items = repository.observeUpdates().first()
        assertEquals(listOf("p6"), items.map { it.update.postId })
        assertEquals("Creator A", items.first().creatorName)
        assertEquals("标题-p6", items.first().update.postTitle)
    }

    @Test
    fun `no subscriptions returns zero`() = runBlocking {
        assertEquals(0, repository.syncSubscribedCreators(fetch(post("p1"))))
    }

    // ---------- ARCH-FEATURE-003 系统通知推送：syncSubscribedCreatorsDetailed ----------

    @Test
    fun `detailed sync returns only genuinely new updates`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")

        val result = repository.syncSubscribedCreatorsDetailed(fetch(post("p7"), post("p6"), post("p5")))

        assertEquals(1, result.syncedCount)
        assertEquals(setOf("p7", "p6"), result.newUpdates.map { it.postId }.toSet())
        assertEquals("标题-p7", result.newUpdates.first { it.postId == "p7" }.postTitle)
        // 与站内未读通知表一致（通知推送基于本次新增明细）
        assertEquals(2, repository.observeUnreadCount().first())
    }

    @Test
    fun `first subscribe without baseline yields no new updates`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = null)

        val result = repository.syncSubscribedCreatorsDetailed(fetch(post("p2"), post("p1")))

        assertEquals(1, result.syncedCount)
        assertTrue(result.newUpdates.isEmpty())
        assertTrue(repository.observeUpdates().first().isEmpty())
    }

    @Test
    fun `repeated detailed sync does not report duplicates`() = runBlocking {
        repository.subscribe("fanbox", "c1", name = "Creator One", lastPostId = "p5")
        val fetcher = fetch(post("p7"), post("p6"), post("p5"))

        val first = repository.syncSubscribedCreatorsDetailed(fetcher)
        val second = repository.syncSubscribedCreatorsDetailed(fetcher)

        assertEquals(2, first.newUpdates.size)
        assertTrue(second.newUpdates.isEmpty())
        assertEquals(2, repository.observeUpdates().first().size)
    }

    @Test
    fun `detailed sync without subscriptions returns empty result`() = runBlocking {
        val result = repository.syncSubscribedCreatorsDetailed(fetch(post("p1")))
        assertEquals(0, result.syncedCount)
        assertTrue(result.newUpdates.isEmpty())
    }
}
