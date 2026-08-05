package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.CreatorSubscriptionEntity
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
 * CreatorSubscriptionDao 集成测试（ARCH-FEATURE-003）。
 *
 * 覆盖：复合主键 upsert/getById、observeAll 按订阅时间倒序、删除、空表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorSubscriptionDaoTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: CreatorSubscriptionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.creatorSubscriptionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun subscription(
        service: String = "fanbox",
        creatorId: String = "c1",
        name: String? = null,
        lastPostId: String? = null,
        subscribedAt: Long = 0L
    ) = CreatorSubscriptionEntity(
        service = service,
        creatorId = creatorId,
        name = name,
        lastPostId = lastPostId,
        subscribedAt = subscribedAt
    )

    @Test
    fun `upsert then getById returns the subscription`() = runBlocking {
        val sub = subscription(name = "Creator One", lastPostId = "p10")
        dao.upsert(sub)
        assertEquals(sub, dao.getById("fanbox", "c1"))
    }

    @Test
    fun `same creator on different services do not collide`() = runBlocking {
        dao.upsert(subscription(service = "fanbox", creatorId = "c1"))
        dao.upsert(subscription(service = "patreon", creatorId = "c1"))
        assertEquals("fanbox", dao.getById("fanbox", "c1")?.service)
        assertEquals("patreon", dao.getById("patreon", "c1")?.service)
    }

    @Test
    fun `observeAll orders by subscribedAt descending`() = runBlocking {
        dao.upsert(subscription(creatorId = "a", subscribedAt = 1))
        dao.upsert(subscription(creatorId = "b", subscribedAt = 3))
        dao.upsert(subscription(creatorId = "c", subscribedAt = 2))

        val ids = dao.observeAll().first().map { it.creatorId }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun `delete removes the subscription`() = runBlocking {
        dao.upsert(subscription())
        dao.delete("fanbox", "c1")
        assertNull(dao.getById("fanbox", "c1"))
    }

    @Test
    fun `empty table reports empty list`() = runBlocking {
        assertTrue(dao.getAll().isEmpty())
    }
}
