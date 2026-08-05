package com.pawchive.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.store.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CacheRepository 分类统计与清理测试（ARCH-FEATURE-004）。
 *
 * 覆盖核心场景：
 * - 图片缓存 / 其他缓存分类口径与总量一致（cacheDir + externalCacheDir）
 * - 清理"其他缓存"删除非 image_cache 内容但保留 image_cache 目录本身
 * - 清空 image_cache 目录内的文件（Coil 磁盘缓存目录）
 * - MediaStore / SAF 下载统计与删除在空库下安全返回（Robolectric 无真实媒体库）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var repository: CacheRepository
    private lateinit var imageCacheDir: File
    private lateinit var otherDir: File

    @Before
    fun setup() {
        repository = CacheRepository(context, SettingsManager(context))
        // 清理历史残留，保证用例间独立
        repository.clearOtherCache()
        imageCacheDir = File(context.cacheDir, "image_cache")
        imageCacheDir.mkdirs()
        imageCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        otherDir = File(context.cacheDir, "other")
        otherDir.mkdirs()
    }

    private fun writeFile(dir: File, name: String, bytes: Long) {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes.toInt()))
    }

    @Test
    fun `image and other cache classification sums to total`() {
        writeFile(imageCacheDir, "img1.webp", 100)
        writeFile(imageCacheDir, "img2.webp", 200)
        writeFile(otherDir, "tmp.bin", 500)
        assertEquals(300L, repository.getImageCacheSize())
        assertEquals(500L, repository.getOtherCacheSize())
        assertEquals(800L, repository.getCacheSize())
    }

    @Test
    fun `clearOtherCache removes non-image contents but keeps image cache dir`() {
        val imageFile = File(imageCacheDir, "img1.webp")
        writeFile(imageCacheDir, "img1.webp", 100)
        writeFile(otherDir, "tmp.bin", 500)
        writeFile(context.cacheDir, "root.tmp", 50)

        repository.clearOtherCache()

        // image_cache 目录及其内容由 Coil 磁盘缓存单独管理，其他缓存清理不触碰
        assertTrue(imageCacheDir.exists())
        assertTrue(imageFile.exists())
        assertTrue(!otherDir.exists())
        assertTrue(!File(context.cacheDir, "root.tmp").exists())
    }

    @Test
    fun `clearImageCache is safe when coil not initialized and preserves dir`() {
        writeFile(imageCacheDir, "img1.webp", 100)
        repository.clearImageCache()
        // Coil 未初始化时静默跳过，目录本身始终保留（后续重新浏览会再下载）
        assertTrue(imageCacheDir.exists())
    }

    @Test
    fun `download files size and delete are safe with empty media store`() {
        assertEquals(0L, repository.getDownloadFilesSize())
        assertEquals(0, repository.deleteDownloadFiles())
    }

    @Test
    fun `other cache size never negative`() {
        // image_cache 不存在的边界：other = total - 0
        val emptyImageDir = File(context.cacheDir, "image_cache")
        emptyImageDir.deleteRecursively()
        writeFile(otherDir, "tmp.bin", 100)
        assertTrue(repository.getOtherCacheSize() >= 0L)
    }
}
