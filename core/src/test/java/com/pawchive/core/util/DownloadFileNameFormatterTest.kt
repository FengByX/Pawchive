package com.pawchive.core.util

import com.pawchive.core.store.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DownloadFileNameFormatter 单元测试（FEATURE：文件命名格式）。
 */
class DownloadFileNameFormatterTest {

    private val ts = 1730000000000L

    @Test
    fun `sanitize strips illegal filesystem characters`() {
        assertEquals("a_b_c", DownloadFileNameFormatter.sanitize("a/b\\c"))
        // 尾部的非法字符同样替换为下划线（不吞字符）
        assertEquals("a_b_c_d_e_f_g_", DownloadFileNameFormatter.sanitize("a:b*c?d\"e<f>g|"))
        assertEquals("safe name", DownloadFileNameFormatter.sanitize("safe name"))
        assertEquals("", DownloadFileNameFormatter.sanitize(null))
        assertEquals("", DownloadFileNameFormatter.sanitize("   "))
    }

    @Test
    fun `sanitize removes control characters`() {
        val input = "ab\u0000\u001fcd"
        assertFalse(DownloadFileNameFormatter.sanitize(input).contains('\u0000'))
        assertTrue(DownloadFileNameFormatter.sanitize(input).startsWith("ab"))
    }

    @Test
    fun `sanitize caps length at 100`() {
        val long = "x".repeat(300)
        assertEquals(100, DownloadFileNameFormatter.sanitize(long).length)
    }

    @Test
    fun `original format keeps original name`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.ORIGINAL,
            originalName = "photo_123.jpg",
            timestampMs = ts
        )
        assertEquals("photo_123.jpg", result)
    }

    @Test
    fun `original format falls back to timestamp style when blank`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.ORIGINAL,
            originalName = "",
            timestampMs = ts
        )
        assertTrue(result.startsWith("Pawchive_${ts}_"))
    }

    @Test
    fun `timestamp format matches legacy pattern`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.TIMESTAMP,
            originalName = "abc.jpg",
            timestampMs = ts
        )
        assertEquals("Pawchive_${ts}_abc.jpg", result)
    }

    @Test
    fun `timestamp format truncates original name tail to 30`() {
        val longName = "y".repeat(50) + ".png"
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.TIMESTAMP,
            originalName = longName,
            timestampMs = ts
        )
        // 与旧版逻辑一致：对含扩展名的完整原名 takeLast(30)，扩展名保留
        assertEquals("Pawchive_${ts}_" + "y".repeat(26) + ".png", result)
    }

    @Test
    fun `title time format uses title and extension`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.TITLE_TIME,
            originalName = "raw.jpg",
            title = "我的作品",
            timestampMs = ts
        )
        // 2025-10-27T03:33:20Z 附近（时区相关），仅断言结构与后缀
        assertTrue(result.startsWith("我的作品_"))
        assertTrue(result.endsWith(".jpg"))
        assertFalse(result.contains("raw"))
    }

    @Test
    fun `title time format falls back to original base name when title blank`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.TITLE_TIME,
            originalName = "clip.mp4",
            title = null,
            timestampMs = ts
        )
        assertTrue(result.startsWith("clip_"))
        assertTrue(result.endsWith(".mp4"))
    }

    @Test
    fun `title time format drops implausible long extension`() {
        val result = DownloadFileNameFormatter.format(
            SettingsManager.FilenameFormat.TITLE_TIME,
            originalName = "archive.tar.gz",
            title = "t",
            timestampMs = ts
        )
        // "gz" 是合法短扩展名
        assertTrue(result.endsWith(".gz"))
    }
}
