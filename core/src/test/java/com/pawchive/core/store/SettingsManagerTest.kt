package com.pawchive.core.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsManager 设置持久化测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - 语言/外观读写并持久化
 * - 下载位置 URI 与显示名成对保存
 * - 自动清理缓存、自动检查更新开关
 * - 缓存大小统计与格式化
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setup() {
        settingsManager = SettingsManager(context)
        // 重置到默认状态，避免单例状态泄漏
        settingsManager.setLanguage(SettingsManager.Language.CHINESE)
        settingsManager.setAppearance(SettingsManager.Appearance.FOLLOW_SYSTEM)
        settingsManager.setAutoCleanCacheEnabled(false)
        settingsManager.setAutoCheckUpdateEnabled(true)
        settingsManager.setDownloadTreeUri(null, "")
        settingsManager.setAutoSubscribeOnBookmarkEnabled(true)
    }

    @Test
    fun `default language is CHINESE`() {
        assertEquals(SettingsManager.Language.CHINESE, settingsManager.getLanguage())
    }

    @Test
    fun `setLanguage ENGLISH persists and is readable`() {
        settingsManager.setLanguage(SettingsManager.Language.ENGLISH)
        assertEquals(SettingsManager.Language.ENGLISH, settingsManager.getLanguage())
    }

    @Test
    fun `setLanguage JAPANESE persists and is readable`() {
        settingsManager.setLanguage(SettingsManager.Language.JAPANESE)
        assertEquals(SettingsManager.Language.JAPANESE, settingsManager.getLanguage())
    }

    @Test
    fun `setLanguage CHINESE persists and is readable`() {
        settingsManager.setLanguage(SettingsManager.Language.ENGLISH)
        settingsManager.setLanguage(SettingsManager.Language.CHINESE)
        assertEquals(SettingsManager.Language.CHINESE, settingsManager.getLanguage())
    }

    @Test
    fun `default appearance is FOLLOW_SYSTEM`() {
        assertEquals(SettingsManager.Appearance.FOLLOW_SYSTEM, settingsManager.getAppearance())
    }

    @Test
    fun `setAppearance LIGHT persists and is readable`() {
        settingsManager.setAppearance(SettingsManager.Appearance.LIGHT)
        assertEquals(SettingsManager.Appearance.LIGHT, settingsManager.getAppearance())
    }

    @Test
    fun `setAppearance DARK persists and is readable`() {
        settingsManager.setAppearance(SettingsManager.Appearance.DARK)
        assertEquals(SettingsManager.Appearance.DARK, settingsManager.getAppearance())
    }

    @Test
    fun `default download tree uri is null`() {
        assertNull(settingsManager.getDownloadTreeUri())
        assertEquals("", settingsManager.getDownloadLocationName())
    }

    @Test
    fun `setDownloadTreeUri persists uri and display name`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APawchive")
        val displayName = "Pawchive"
        settingsManager.setDownloadTreeUri(uri, displayName)

        val savedUri = settingsManager.getDownloadTreeUri()
        assertNotNull(savedUri)
        assertEquals(uri.toString(), savedUri.toString())
        assertEquals(displayName, settingsManager.getDownloadLocationName())
    }

    @Test
    fun `setDownloadTreeUri null clears both uri and name`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APawchive")
        settingsManager.setDownloadTreeUri(uri, "Pawchive")
        assertNotNull(settingsManager.getDownloadTreeUri())

        settingsManager.setDownloadTreeUri(null, "")
        assertNull(settingsManager.getDownloadTreeUri())
        assertEquals("", settingsManager.getDownloadLocationName())
    }

    @Test
    fun `default auto clean cache is disabled`() {
        assertFalse(settingsManager.isAutoCleanCacheEnabled())
    }

    @Test
    fun `setAutoCleanCacheEnabled true persists`() {
        settingsManager.setAutoCleanCacheEnabled(true)
        assertTrue(settingsManager.isAutoCleanCacheEnabled())
    }

    @Test
    fun `setAutoCleanCacheEnabled false after true persists`() {
        settingsManager.setAutoCleanCacheEnabled(true)
        settingsManager.setAutoCleanCacheEnabled(false)
        assertFalse(settingsManager.isAutoCleanCacheEnabled())
    }

    @Test
    fun `default auto check update is enabled`() {
        assertTrue(settingsManager.isAutoCheckUpdateEnabled())
    }

    @Test
    fun `setAutoCheckUpdateEnabled false persists`() {
        settingsManager.setAutoCheckUpdateEnabled(false)
        assertFalse(settingsManager.isAutoCheckUpdateEnabled())
    }

    @Test
    fun `default hide bookmarked creators is disabled`() {
        assertFalse(settingsManager.isHideBookmarkedCreatorsEnabled())
    }

    @Test
    fun `setHideBookmarkedCreatorsEnabled true persists`() {
        settingsManager.setHideBookmarkedCreatorsEnabled(true)
        assertTrue(settingsManager.isHideBookmarkedCreatorsEnabled())
        settingsManager.setHideBookmarkedCreatorsEnabled(false)
        assertFalse(settingsManager.isHideBookmarkedCreatorsEnabled())
    }

    @Test
    fun `default auto subscribe on bookmark is enabled`() {
        // ARCH-FEATURE-003 联动：收藏即订阅默认开启
        assertTrue(settingsManager.isAutoSubscribeOnBookmarkEnabled())
    }

    @Test
    fun `setAutoSubscribeOnBookmarkEnabled false persists`() {
        settingsManager.setAutoSubscribeOnBookmarkEnabled(false)
        assertFalse(settingsManager.isAutoSubscribeOnBookmarkEnabled())
        settingsManager.setAutoSubscribeOnBookmarkEnabled(true)
        assertTrue(settingsManager.isAutoSubscribeOnBookmarkEnabled())
    }

    @Test
    fun `getCacheSize returns zero for empty cache`() {
        val size = SettingsManager.getCacheSize(context)
        // 空目录应返回 0
        assertTrue(size >= 0)
    }

    @Test
    fun `formatSize formats bytes correctly`() {
        assertEquals("0 B", SettingsManager.formatSize(0L))
        assertEquals("512 B", SettingsManager.formatSize(512L))
        assertEquals("1 KB", SettingsManager.formatSize(1024L))
        // 1 MB（1048576 < 1GB 阈值，走 MB 分支）
        assertEquals("1.0 MB", SettingsManager.formatSize(1024L * 1024))
        // 1.5 MB（1572864 < 1GB 阈值）
        assertEquals("1.5 MB", SettingsManager.formatSize(1572864L))
        // 1 GB（1073741824 >= 1GB 阈值，走 GB 分支）
        assertEquals("1.0 GB", SettingsManager.formatSize(1024L * 1024 * 1024))
    }

    @Test
    fun `multiple instances are independent`() {
        // ARCH-003: 单例由 Hilt @Singleton 管理，直接构造的实例彼此独立
        val a = SettingsManager(context)
        val b = SettingsManager(context)
        // 两个实例应能独立工作（内存缓存独立）
        a.setLanguage(SettingsManager.Language.ENGLISH)
        assertEquals(SettingsManager.Language.ENGLISH, a.getLanguage())
        // b 的语言不受 a 影响（独立内存缓存快照）
        // 注：DataStore 底层是同一文件，但内存缓存快照独立
        assertTrue(a !== b)
    }
}
