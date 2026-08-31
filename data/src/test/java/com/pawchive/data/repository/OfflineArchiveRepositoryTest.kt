package com.pawchive.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.Post
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OfflineArchiveRepository 离线搜索相关性排序测试（ARCH-FEATURE-001 遗留项）。
 *
 * 验证应用层多列加权合并：标题命中 > 创作者名/id 命中 > 正文/附件命中，
 * 同一优先级内保持 FTS 命中顺序；重复命中去重。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineArchiveRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var repository: OfflineArchiveRepository

    @Before
    fun setup() {
        val db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OfflineArchiveRepository(db.offlineArchiveDao(), Gson())
    }

    private fun makePost(
        postId: String,
        title: String? = null,
        content: String? = null,
        userName: String = "c1",
        user: String = "c1"
    ): Post {
        return Post(
            id = postId,
            user = user,
            userName = userName,
            service = "fanbox",
            title = title,
            content = content,
            added = null,
            published = null,
            edited = null,
            file = null,
            attachments = null,
            sharedFile = null
        )
    }

    private fun indexAll(posts: List<Post>) = runBlocking {
        posts.forEach { repository.index(it) }
    }

    private fun searchIds(query: String): List<String> = runBlocking {
        repository.search(query).map { it.postId }
    }

    @Test
    fun `title only hit is found`() {
        indexAll(listOf(makePost("pA", title = "收藏攻略")))
        val entry = runBlocking { repository.getEntry("fanbox", "c1", "pA") }
        assertEquals("收藏攻略", entry?.title)
        assertEquals(listOf("pA"), searchIds("收藏"))
    }

    @Test
    fun `title match ranks above content match`() {
        indexAll(
            listOf(
                makePost("pA", title = "收藏攻略"),
                makePost("pB", title = "无关标题", content = "正文提及收藏笔记")
            )
        )
        assertEquals(listOf("pA", "pB"), searchIds("收藏"))
    }

    @Test
    fun `creator match ranks above content match`() {
        indexAll(
            listOf(
                makePost("pA", userName = "收藏大师"),
                makePost("pB", content = "正文提及收藏笔记")
            )
        )
        assertEquals(listOf("pA", "pB"), searchIds("收藏"))
    }

    @Test
    fun `title match ranks above creator match`() {
        indexAll(
            listOf(
                makePost("pA", title = "收藏攻略"),
                makePost("pB", userName = "收藏大师")
            )
        )
        assertEquals(listOf("pA", "pB"), searchIds("收藏"))
    }

    @Test
    fun `same id matched by multiple columns is deduplicated`() {
        // pA 同时命中标题与正文，结果只出现一次且不因低权重重复
        indexAll(
            listOf(
                makePost("pA", title = "收藏攻略", content = "内容也提到收藏"),
                makePost("pB", content = "正文提及收藏")
            )
        )
        val ids = searchIds("收藏")
        assertEquals(listOf("pA", "pB"), ids)
        assertEquals(2, ids.size)
    }

    @Test
    fun `no match returns empty list`() {
        indexAll(listOf(makePost("pA", title = "天气不错")))
        assertTrue(searchIds("收藏").isEmpty())
    }
}
