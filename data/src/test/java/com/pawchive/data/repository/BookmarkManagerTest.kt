package com.pawchive.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.Post
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
 * BookmarkManager 收藏与回滚测试（BACKEND-009；ARCH-FEATURE-001 构造注入扩展）。
 *
 * 覆盖核心场景：
 * - 帖子收藏/取消收藏，状态查询与列表读取一致
 * - 创作者收藏/取消收藏
 * - 收藏列表保持添加顺序（最早的在前）
 * - 回滚场景：取消收藏后立即重新查询，状态正确回滚无残留
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var bookmarkManager: BookmarkManager

    // ARCH-FEATURE-001：离线归档索引为异步旁路，测试用 in-memory Room 仓库（进程级回收，不显式 close）
    private val offlineArchiveRepository: OfflineArchiveRepository by lazy {
        val db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        OfflineArchiveRepository(db.offlineArchiveDao(), Gson())
    }

    @Before
    fun setup() {
        bookmarkManager = BookmarkManager(context, offlineArchiveRepository)
        // 清空所有收藏，保证测试隔离
        val current = bookmarkManager.getBookmarkedPosts()
        current.forEach { bookmarkManager.unbookmarkPost(it.service, it.user, it.id) }
    }

    @After
    fun tearDown() {
        val current = bookmarkManager.getBookmarkedPosts()
        current.forEach { bookmarkManager.unbookmarkPost(it.service, it.user, it.id) }
    }

    private fun makePost(
        service: String = "fanbox",
        creatorId: String = "creator1",
        postId: String = "post1",
        title: String = "Test Post"
    ): Post {
        return Post(
            id = postId,
            user = creatorId,
            userName = creatorId,
            service = service,
            title = title,
            content = null,
            added = null,
            published = null,
            edited = null,
            file = null,
            attachments = null,
            sharedFile = null
        )
    }

    @Test
    fun `fresh BookmarkManager has no bookmarked posts`() {
        val posts = bookmarkManager.getBookmarkedPosts()
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `bookmarkPost marks post as bookmarked`() {
        val post = makePost()
        bookmarkManager.bookmarkPost(post)

        assertTrue(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))
        val posts = bookmarkManager.getBookmarkedPosts()
        assertEquals(1, posts.size)
        assertEquals(post.id, posts[0].id)
    }

    @Test
    fun `unbookmarkPost removes bookmark`() {
        val post = makePost()
        bookmarkManager.bookmarkPost(post)
        assertTrue(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))

        bookmarkManager.unbookmarkPost(post.service, post.user, post.id)

        assertFalse(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))
        assertTrue(bookmarkManager.getBookmarkedPosts().isEmpty())
    }

    @Test
    fun `bookmark same post twice does not duplicate`() {
        val post = makePost()
        bookmarkManager.bookmarkPost(post)
        bookmarkManager.bookmarkPost(post)

        assertEquals(1, bookmarkManager.getBookmarkedPosts().size)
    }

    @Test
    fun `bookmarked posts preserve insertion order`() {
        val post1 = makePost(postId = "post1", title = "First")
        val post2 = makePost(postId = "post2", title = "Second")
        val post3 = makePost(postId = "post3", title = "Third")

        bookmarkManager.bookmarkPost(post1)
        bookmarkManager.bookmarkPost(post2)
        bookmarkManager.bookmarkPost(post3)

        val posts = bookmarkManager.getBookmarkedPosts()
        assertEquals(3, posts.size)
        assertEquals("post1", posts[0].id)
        assertEquals("post2", posts[1].id)
        assertEquals("post3", posts[2].id)
    }

    @Test
    fun `unbookmark middle post does not break order`() {
        val post1 = makePost(postId = "p1")
        val post2 = makePost(postId = "p2")
        val post3 = makePost(postId = "p3")
        bookmarkManager.bookmarkPost(post1)
        bookmarkManager.bookmarkPost(post2)
        bookmarkManager.bookmarkPost(post3)

        bookmarkManager.unbookmarkPost(post2.service, post2.user, post2.id)

        val posts = bookmarkManager.getBookmarkedPosts()
        assertEquals(2, posts.size)
        assertEquals("p1", posts[0].id)
        assertEquals("p3", posts[1].id)
    }

    @Test
    fun `rollback scenario - bookmark then unbookmark then re-bookmark`() {
        val post = makePost()
        // 回滚场景：先收藏，再取消，再重新收藏，状态应正确
        bookmarkManager.bookmarkPost(post)
        assertTrue(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))

        bookmarkManager.unbookmarkPost(post.service, post.user, post.id)
        assertFalse(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))

        bookmarkManager.bookmarkPost(post)
        assertTrue(bookmarkManager.isPostBookmarked(post.service, post.user, post.id))
        assertEquals(1, bookmarkManager.getBookmarkedPosts().size)
    }

    @Test
    fun `unbookmark non-bookmarked post is no-op`() {
        val post = makePost()
        // 未收藏的帖子取消收藏不应抛异常
        bookmarkManager.unbookmarkPost(post.service, post.user, post.id)
        assertTrue(bookmarkManager.getBookmarkedPosts().isEmpty())
    }

    @Test
    fun `bookmarkCreator marks creator as bookmarked`() {
        bookmarkManager.bookmarkCreator("fanbox", "creator1")
        assertTrue(bookmarkManager.isCreatorBookmarked("fanbox", "creator1"))
    }

    @Test
    fun `unbookmarkCreator removes creator bookmark`() {
        bookmarkManager.bookmarkCreator("fanbox", "creator1")
        bookmarkManager.unbookmarkCreator("fanbox", "creator1")
        assertFalse(bookmarkManager.isCreatorBookmarked("fanbox", "creator1"))
    }

    @Test
    fun `getBookmarkedCreators returns bookmarked creator pairs`() {
        // creatorId 含下划线场景：键 creator_<service>_<creatorId> 按首个分隔符解析
        bookmarkManager.bookmarkCreator("fanbox", "g6_creator1")
        bookmarkManager.bookmarkCreator("patreon", "g6_creator_with_underscore")

        val creators = bookmarkManager.getBookmarkedCreators()

        assertTrue(creators.contains("fanbox" to "g6_creator1"))
        assertTrue(creators.contains("patreon" to "g6_creator_with_underscore"))
        assertFalse(creators.contains("fanbox" to "g6_creator_with_underscore"))

        bookmarkManager.unbookmarkCreator("fanbox", "g6_creator1")
        bookmarkManager.unbookmarkCreator("patreon", "g6_creator_with_underscore")
    }

    @Test
    fun `getBookmarkedCreators excludes unbookmarked creators`() {
        bookmarkManager.bookmarkCreator("fanbox", "g6_exclude")
        bookmarkManager.unbookmarkCreator("fanbox", "g6_exclude")
        assertFalse(bookmarkManager.getBookmarkedCreators().contains("fanbox" to "g6_exclude"))
    }

    @Test
    fun `posts from different creators are independent`() {
        val post1 = makePost(creatorId = "creatorA", postId = "pA")
        val post2 = makePost(creatorId = "creatorB", postId = "pB")

        bookmarkManager.bookmarkPost(post1)
        bookmarkManager.bookmarkPost(post2)

        assertTrue(bookmarkManager.isPostBookmarked("fanbox", "creatorA", "pA"))
        assertTrue(bookmarkManager.isPostBookmarked("fanbox", "creatorB", "pB"))

        bookmarkManager.unbookmarkPost("fanbox", "creatorA", "pA")
        assertFalse(bookmarkManager.isPostBookmarked("fanbox", "creatorA", "pA"))
        assertTrue(bookmarkManager.isPostBookmarked("fanbox", "creatorB", "pB"))
    }

    @Test
    fun `directly constructed instances are independent`() {
        // ARCH-003: 单例由 Hilt @Singleton 管理，直接构造的实例彼此独立
        val a = BookmarkManager(context, offlineArchiveRepository)
        val b = BookmarkManager(context, offlineArchiveRepository)
        assertTrue(a !== b)
    }
}
