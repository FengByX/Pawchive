package com.pawchive.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OfflineArchiveIndexer 分词器测试（ARCH-FEATURE-001）。
 *
 * 覆盖核心场景：
 * - 中文 CJK bigram：相邻两字成 token，支持子串命中
 * - 英文原词保留 + 前缀通配查询
 * - 中英混合
 * - 空/纯空白输入
 * - 查询转义（引号）
 */
class OfflineArchiveIndexerTest {

    @Test
    fun `tokenize CJK bigram`() {
        assertEquals("收藏 藏夹", OfflineArchiveIndexer.tokenize("收藏夹"))
        assertEquals("你好 好世 世界", OfflineArchiveIndexer.tokenize("你好世界"))
    }

    @Test
    fun `tokenize single CJK char stays as is`() {
        assertEquals("猫", OfflineArchiveIndexer.tokenize("猫"))
    }

    @Test
    fun `tokenize latin words preserved`() {
        assertEquals("hello world", OfflineArchiveIndexer.tokenize("hello world"))
    }

    @Test
    fun `tokenize mixed CJK and latin`() {
        assertEquals("Pawchive 更新 新日 日志", OfflineArchiveIndexer.tokenize("Pawchive 更新日志"))
    }

    @Test
    fun `tokenize handles punctuation and whitespace`() {
        assertEquals("测试 用例", OfflineArchiveIndexer.tokenize("测试，用例！"))
    }

    @Test
    fun `tokenize null and blank returns null`() {
        assertNull(OfflineArchiveIndexer.tokenize(null))
        assertNull(OfflineArchiveIndexer.tokenize(""))
        assertNull(OfflineArchiveIndexer.tokenize("   "))
    }

    @Test
    fun `toQuery single token with prefix wildcard`() {
        assertEquals("收藏*", OfflineArchiveIndexer.toQuery("收藏"))
    }

    @Test
    fun `toQuery multiple tokens joined by OR`() {
        assertEquals("收藏* OR 藏夹*", OfflineArchiveIndexer.toQuery("收藏夹"))
    }

    @Test
    fun `toQuery strips quotes from input`() {
        assertEquals("\"\"", OfflineArchiveIndexer.toQuery("\""))
        // 引号被视为非词字符分隔符：收"藏 → 收 / 藏 两个 token
        assertEquals("收* OR 藏*", OfflineArchiveIndexer.toQuery("收\"藏"))
    }

    @Test
    fun `toQuery blank input returns empty match`() {
        assertEquals("\"\"", OfflineArchiveIndexer.toQuery("   "))
    }

    @Test
    fun `toColumnQuery prefixes every token`() {
        assertEquals("title:收藏*", OfflineArchiveIndexer.toColumnQuery("收藏", "title"))
        assertEquals(
            "title:收藏* OR title:藏夹*",
            OfflineArchiveIndexer.toColumnQuery("收藏夹", "title")
        )
    }

    @Test
    fun `toColumnQuery blank input returns empty match`() {
        assertEquals("\"\"", OfflineArchiveIndexer.toColumnQuery("   ", "title"))
    }

    @Test
    fun `toMultiColumnQuery expands tokens across columns`() {
        assertEquals(
            "userName:收藏* OR user:收藏*",
            OfflineArchiveIndexer.toMultiColumnQuery("收藏", "userName", "user")
        )
        assertEquals(
            "content:收藏* OR attachments:收藏* OR content:藏夹* OR attachments:藏夹*",
            OfflineArchiveIndexer.toMultiColumnQuery("收藏夹", "content", "attachments")
        )
    }

    @Test
    fun `toMultiColumnQuery blank input returns empty match`() {
        assertEquals("\"\"", OfflineArchiveIndexer.toMultiColumnQuery("", "content", "attachments"))
    }
}
