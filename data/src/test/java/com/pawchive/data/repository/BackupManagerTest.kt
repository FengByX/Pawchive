package com.pawchive.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import com.pawchive.core.model.Post
import com.pawchive.core.store.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BackupManager 备份往返测试（ARCH-FEATURE-005）。
 *
 * 覆盖核心场景：
 * - 导出 → 清空 → 导入，收藏/屏蔽/阅读进度/下载历史/设置完整还原
 * - 下载记录导出剔除设备绑定字段（filePath 置空）
 * - 无效 JSON / 版本不兼容导入抛异常
 * - 空数据往返安全
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var blockedCreatorManager: BlockedCreatorManager
    private lateinit var readingProgressManager: ReadingProgressManager
    private lateinit var downloadHistoryManager: DownloadHistoryManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var backupManager: BackupManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookmarkManager = BookmarkManager(
            context,
            OfflineArchiveRepository(db.offlineArchiveDao(), Gson())
        )
        blockedCreatorManager = BlockedCreatorManager(context)
        readingProgressManager = ReadingProgressManager(context)
        downloadHistoryManager = DownloadHistoryManager(context, db.downloadHistoryDao())
        settingsManager = SettingsManager(context)
        backupManager = BackupManager(
            Gson(),
            bookmarkManager,
            blockedCreatorManager,
            readingProgressManager,
            downloadHistoryManager,
            settingsManager
        )
        // 测试方法间共享同一 Application 的 DataStore/Room，先清空保证用例独立
        runBlocking {
            bookmarkManager.importAll(emptyList(), emptyList())
            blockedCreatorManager.importAll(emptyList())
            readingProgressManager.importAll(ReadingProgressSnapshot())
            downloadHistoryManager.clearAll()
        }
        settingsManager.setAutoCleanCacheEnabled(false)
        settingsManager.setAutoCheckUpdateEnabled(true)
        settingsManager.setHideBookmarkedCreatorsEnabled(false)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun post(id: String, title: String? = null) = Post(
        id = id,
        user = "c1",
        userName = "Creator One",
        service = "fanbox",
        title = title ?: "标题-$id",
        content = null,
        added = null,
        published = null,
        edited = null,
        file = null,
        attachments = null,
        sharedFile = null
    )

    private fun downloadRecord(id: String) = DownloadRecord(
        id = id,
        url = "https://example.com/$id.mp4",
        fileName = "$id.mp4",
        mimeType = "video/mp4",
        type = DownloadType.VIDEO,
        dedupKey = null,
        status = DownloadStatus.COMPLETED,
        progress = 100,
        fileSize = 1024L,
        createdAt = 1L,
        completedAt = 2L,
        filePath = "content://media/external/video/media/1",
        errorMessage = null
    )

    @Test
    fun `export then import restores all data sources`() = runBlocking {
        // 准备数据
        bookmarkManager.bookmarkPost(post("p1", "一"))
        bookmarkManager.bookmarkPost(post("p2", "二"))
        bookmarkManager.bookmarkCreator("patreon", "creator-a")
        blockedCreatorManager.blockCreator("fanbox", "bad-creator")
        readingProgressManager.saveReadingScroll("p1", 123)
        readingProgressManager.saveVideoPosition("https://example.com/v1.mp4", 5000L)
        downloadHistoryManager.upsert(downloadRecord("d1"))
        settingsManager.setAutoCleanCacheEnabled(true)

        val json = backupManager.export()
        // 导出剔除设备绑定字段
        val exported = Gson().fromJson(json, BackupBundle::class.java)
        assertTrue(exported.downloadHistory.all { it.filePath == null && it.progress == 0 })

        // 清空全部数据源（收藏用挂起批量接口，避免异步落盘与导入竞态）
        bookmarkManager.importAll(emptyList(), emptyList())
        blockedCreatorManager.importAll(emptyList())
        readingProgressManager.importAll(ReadingProgressSnapshot())
        downloadHistoryManager.clearAll()
        settingsManager.setAutoCleanCacheEnabled(false)

        // 导入还原
        val result = backupManager.import(json)

        assertEquals(2, result.bookmarks)
        assertEquals(1, result.creators)
        assertEquals(1, result.blocked)
        assertEquals(1, result.downloads)

        assertEquals(2, bookmarkManager.getBookmarkedPosts().size)
        assertEquals(listOf("p1", "p2"), bookmarkManager.getBookmarkedPosts().map { it.id })
        assertTrue(bookmarkManager.isCreatorBookmarked("patreon", "creator-a"))
        assertTrue(blockedCreatorManager.isCreatorBlocked("fanbox", "bad-creator"))
        assertEquals(123, readingProgressManager.getReadingScroll("p1"))
        assertEquals(5000L, readingProgressManager.getVideoPosition("https://example.com/v1.mp4"))
        val restored = downloadHistoryManager.getAllRecordsFromDb()
        assertEquals(1, restored.size)
        assertEquals("d1", restored.first().id)
        assertTrue(settingsManager.isAutoCleanCacheEnabled())
    }

    @Test
    fun `import rejects invalid json`() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { backupManager.import("this is not json") }
            }
        }
    }

    @Test
    fun `import rejects unsupported schema version`() {
        runBlocking {
            val bundle = BackupBundle(schemaVersion = 999)
            val json = Gson().toJson(bundle)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { backupManager.import(json) }
            }
        }
    }

    @Test
    fun `empty backup round trip is safe`() = runBlocking {
        val json = backupManager.export()
        val result = backupManager.import(json)
        assertEquals(0, result.bookmarks)
        assertEquals(0, result.downloads)
        assertFalse(settingsManager.isAutoCleanCacheEnabled())
    }
}
