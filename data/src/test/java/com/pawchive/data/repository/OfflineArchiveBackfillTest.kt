package com.pawchive.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.Post
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
 * OfflineArchiveBackfill 收藏历史回填测试（ARCH-FEATURE-001 遗留项）。
 *
 * 覆盖核心场景：
 * - 首次运行遍历既有收藏补齐离线归档索引，并置位一次性标记
 * - 标记置位后再次运行直接跳过（不重复遍历/不覆盖用户已清空的归档）
 * - 已索引内容重复回填幂等（主键 upsert，不产生重复记录）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineArchiveBackfillTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var bookmarkManager: BookmarkManager
    private val offlineArchiveRepository: OfflineArchiveRepository by lazy {
        val db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        OfflineArchiveRepository(db.offlineArchiveDao(), Gson())
    }

    private val backfillPrefs: android.content.SharedPreferences
        get() = context.getSharedPreferences(
            "pawchive_offline_archive_backfill", Context.MODE_PRIVATE
        )

    @Before
    fun setup() {
        bookmarkManager = BookmarkManager(context, offlineArchiveRepository)
        // 清空收藏与回填标记，保证测试隔离
        bookmarkManager.getBookmarkedPosts().forEach {
            bookmarkManager.unbookmarkPost(it.service, it.user, it.id)
        }
        backfillPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        bookmarkManager.getBookmarkedPosts().forEach {
            bookmarkManager.unbookmarkPost(it.service, it.user, it.id)
        }
        backfillPrefs.edit().clear().commit()
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

    private fun newBackfill() =
        OfflineArchiveBackfill(context, bookmarkManager, offlineArchiveRepository)

    /** 等待 bookmarkPost 的异步离线索引旁路落库，保证断言确定性。 */
    private fun settleAsyncIndex() {
        Thread.sleep(200)
    }

    /** 归档条数（suspend 查询包装）。 */
    private fun archiveCount(): Int = runBlocking { offlineArchiveRepository.getCount() }

    @Test
    fun `backfill indexes existing bookmarks and sets flag`() {
        bookmarkManager.bookmarkPost(makePost(postId = "p1", title = "First"))
        bookmarkManager.bookmarkPost(makePost(postId = "p2", title = "Second"))
        settleAsyncIndex()

        // 模拟"升级前收藏无离线索引"：先清空归档，再触发回填
        runBlocking { offlineArchiveRepository.clearAll() }
        runBlocking { newBackfill().runIfNeeded() }

        assertEquals(2, archiveCount())
        assertTrue(backfillPrefs.getBoolean("backfill_done", false))
    }

    @Test
    fun `backfill is skipped after flag is set`() {
        bookmarkManager.bookmarkPost(makePost(postId = "p1"))
        bookmarkManager.bookmarkPost(makePost(postId = "p2"))
        settleAsyncIndex()

        runBlocking { newBackfill().runIfNeeded() }
        assertTrue(backfillPrefs.getBoolean("backfill_done", false))

        // 用户清空离线归档（仅删副本，收藏仍在）后再启动：标记已置位，不应重新回填
        runBlocking { offlineArchiveRepository.clearAll() }
        runBlocking { newBackfill().runIfNeeded() }

        assertEquals(0, archiveCount())
    }

    @Test
    fun `backfill is idempotent with already indexed bookmarks`() {
        // bookmarkPost 已即时索引，回填再次执行不产生重复记录
        bookmarkManager.bookmarkPost(makePost(postId = "p1"))
        settleAsyncIndex()

        runBlocking { newBackfill().runIfNeeded() }
        runBlocking { newBackfill().runIfNeeded() }

        assertEquals(1, archiveCount())
    }

    @Test
    fun `backfill does nothing without bookmarks`() {
        runBlocking { newBackfill().runIfNeeded() }

        assertEquals(0, archiveCount())
        assertTrue(backfillPrefs.getBoolean("backfill_done", false))
    }
}
