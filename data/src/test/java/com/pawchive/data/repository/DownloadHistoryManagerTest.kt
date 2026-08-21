package com.pawchive.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.DownloadHistoryDao
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DownloadHistoryManager Room 存储测试（ARCH-004）。
 *
 * 覆盖核心场景：
 * - upsert 后记录可查询（StateFlow 订阅生效）
 * - updateStatus 局部更新：不覆盖已有 filePath / fileSize，COMPLETED 时写入 completedAt
 * - remove / clearAll
 * - 应用重启初始化：PENDING/RUNNING 任务批量重置为 FAILED（保留已完成记录）
 * - 旧 DataStore JSON 数据迁移至 Room（通过 migrateLegacyRecords 注入 JSON，
 *   避免测试中创建第二个 DataStore 实例与生产实例冲突）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadHistoryManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var dao: DownloadHistoryDao
    private lateinit var db: PawchiveDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeRecord(
        id: String,
        status: DownloadStatus = DownloadStatus.PENDING,
        progress: Int = 0,
        fileSize: Long = 0L,
        filePath: String? = null,
        errorMessage: String? = null,
        dedupKey: String? = null
    ): DownloadRecord {
        return DownloadRecord(
            id = id,
            url = "https://file.pawchive.pw/data/$id",
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            type = DownloadType.IMAGE,
            dedupKey = dedupKey,
            status = status,
            progress = progress,
            fileSize = fileSize,
            createdAt = System.currentTimeMillis(),
            completedAt = 0L,
            filePath = filePath,
            errorMessage = errorMessage
        )
    }

    /** 轮询等待 manager.records 满足条件（init 为异步）。 */
    private fun awaitRecords(
        manager: DownloadHistoryManager,
        predicate: (List<DownloadRecord>) -> Boolean
    ): List<DownloadRecord> = runBlocking {
        withTimeout(5_000) {
            while (!predicate(manager.records.value)) {
                delay(50)
            }
            manager.records.value
        }
    }

    @Test
    fun `upsert then records contains the record`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking { manager.upsert(makeRecord("a")) }
        val records = awaitRecords(manager) { it.any { r -> r.id == "a" } }
        assertEquals("https://file.pawchive.pw/data/a", records.first { it.id == "a" }.url)
    }

    @Test
    fun `upsert same id overwrites previous record`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a", status = DownloadStatus.PENDING))
            manager.upsert(makeRecord("a", status = DownloadStatus.COMPLETED, progress = 100))
        }
        val records = awaitRecords(manager) { it.any { r -> r.id == "a" && r.status == DownloadStatus.COMPLETED } }
        assertEquals(1, records.count { it.id == "a" })
    }

    @Test
    fun `updateStatus preserves existing filePath and fileSize when not provided`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a", filePath = "content://media/1", fileSize = 2048L))
            manager.updateStatus("a", DownloadStatus.RUNNING, progress = 50)
        }
        val records = awaitRecords(manager) { it.any { r -> r.id == "a" && r.progress == 50 } }
        val record = records.first { it.id == "a" }
        assertEquals("content://media/1", record.filePath)
        assertEquals(2048L, record.fileSize)
        assertEquals(DownloadStatus.RUNNING, record.status)
    }

    @Test
    fun `updateStatus COMPLETED sets completedAt and filePath`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a"))
            manager.updateStatus(
                "a", DownloadStatus.COMPLETED, progress = 100, filePath = "content://media/2", fileSize = 4096L
            )
        }
        val records = awaitRecords(manager) { it.any { r -> r.id == "a" && r.status == DownloadStatus.COMPLETED } }
        val record = records.first { it.id == "a" }
        assertTrue(record.completedAt > 0)
        assertEquals("content://media/2", record.filePath)
        assertEquals(4096L, record.fileSize)
    }

    @Test
    fun `updateStatus FAILED sets errorMessage`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a"))
            manager.updateStatus("a", DownloadStatus.FAILED, errorMessage = "Network error")
        }
        val records = awaitRecords(manager) { it.any { r -> r.id == "a" && r.status == DownloadStatus.FAILED } }
        assertEquals("Network error", records.first { it.id == "a" }.errorMessage)
    }

    @Test
    fun `remove deletes the record`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a"))
            manager.upsert(makeRecord("b"))
        }
        awaitRecords(manager) { it.size == 2 }
        runBlocking { manager.remove("a") }
        val records = awaitRecords(manager) { it.size == 1 }
        assertEquals("b", records.single().id)
    }

    @Test
    fun `clearAll empties records`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking {
            manager.upsert(makeRecord("a"))
            manager.upsert(makeRecord("b"))
        }
        awaitRecords(manager) { it.size == 2 }
        runBlocking { manager.clearAll() }
        val records = awaitRecords(manager) { it.isEmpty() }
        assertTrue(records.isEmpty())
    }

    @Test
    fun `init marks pending and running records as failed on startup`() {
        // 先直接写入模拟"上次未完成"的任务
        runBlocking {
            dao.upsert(makeRecord("running", status = DownloadStatus.RUNNING, progress = 30))
            dao.upsert(makeRecord("pending", status = DownloadStatus.PENDING))
            dao.upsert(makeRecord("done", status = DownloadStatus.COMPLETED, progress = 100))
        }
        val manager = DownloadHistoryManager(context, dao)
        // init 会将 PENDING/RUNNING 标记为 FAILED（应用重启后下载任务已中断），
        // COMPLETED 记录保持不变。
        val records = awaitRecords(manager) { records ->
            val byId = records.associateBy { it.id }
            byId["running"]?.status == DownloadStatus.FAILED &&
                byId["pending"]?.status == DownloadStatus.FAILED &&
                byId["done"]?.status == DownloadStatus.COMPLETED
        }
        assertEquals(DownloadStatus.FAILED, records.first { it.id == "running" }.status)
        assertEquals(DownloadStatus.FAILED, records.first { it.id == "pending" }.status)
        assertEquals(DownloadStatus.COMPLETED, records.first { it.id == "done" }.status)
    }

    @Test
    fun `migrateLegacyRecords imports old DataStore json into Room`() = runBlocking {
        val manager = DownloadHistoryManager(context, dao)
        val legacyJson = Gson().toJson(
            listOf(
                makeRecord("old-1", status = DownloadStatus.COMPLETED, progress = 100),
                makeRecord("old-2", status = DownloadStatus.FAILED, errorMessage = "Old error")
            )
        )
        manager.migrateLegacyRecords(legacyJson)

        val records = awaitRecords(manager) { it.size == 2 }
        val byId = records.associateBy { it.id }
        assertEquals(DownloadStatus.COMPLETED, byId["old-1"]?.status)
        assertEquals("Old error", byId["old-2"]?.errorMessage)
    }

    @Test
    fun `migrateLegacyRecords with empty or null json is no-op`() = runBlocking {
        val manager = DownloadHistoryManager(context, dao)
        manager.migrateLegacyRecords(null)
        manager.migrateLegacyRecords("")
        manager.migrateLegacyRecords("not-json{{{")
        assertTrue(manager.getAllRecords().isEmpty())
    }

    @Test
    fun `findActiveByDedupKey returns only PENDING or RUNNING record`() = runBlocking {
        val manager = DownloadHistoryManager(context, dao)
        manager.upsert(makeRecord("running", status = DownloadStatus.RUNNING, dedupKey = "key-a"))
        manager.upsert(makeRecord("pending", status = DownloadStatus.PENDING, dedupKey = "key-a"))
        manager.upsert(makeRecord("done", status = DownloadStatus.COMPLETED, dedupKey = "key-b"))

        // 相同指纹存在活跃任务 → 命中
        assertNotNull(manager.findActiveByDedupKey("key-a"))
        // 已完成任务不参与去重
        assertNull(manager.findActiveByDedupKey("key-b"))
        // 未知指纹
        assertNull(manager.findActiveByDedupKey("missing"))
    }

    @Test
    fun `getAllRecords reflects latest in-memory snapshot`() {
        val manager = DownloadHistoryManager(context, dao)
        runBlocking { manager.upsert(makeRecord("a")) }
        awaitRecords(manager) { it.isNotEmpty() }
        assertEquals("a", manager.getAllRecords().first().id)
        assertNotNull(manager.getRecord("a"))
    }
}