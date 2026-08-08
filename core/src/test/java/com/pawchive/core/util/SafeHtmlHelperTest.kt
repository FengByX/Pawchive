package com.pawchive.core.util

import android.text.Spannable
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SafeHtmlHelper 安全 HTML 渲染测试（BACKEND-009）。
 *
 * 覆盖核心安全场景：
 * - https 链接保留可点击（替换为 SafeLinkSpan）
 * - http 链接被移除可点击行为（防降级攻击）
 * - javascript: / intent: 等危险 scheme 被剥离
 * - 空输入与纯文本安全返回
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeHtmlHelperTest {

    private fun clickSpanCount(text: CharSequence): Int {
        if (text !is Spannable) return 0
        return text.getSpans(0, text.length, ClickableSpan::class.java).size
    }

    private fun urlSpanCount(text: CharSequence): Int {
        if (text !is Spannable) return 0
        return text.getSpans(0, text.length, URLSpan::class.java).size
    }

    @Test
    fun `render empty string returns empty`() {
        val result = SafeHtmlHelper.render("")
        assertEquals("", result)
    }

    @Test
    fun `render blank string returns empty`() {
        val result = SafeHtmlHelper.render("   ")
        assertEquals("", result)
    }

    @Test
    fun `render plain text returns as-is`() {
        val result = SafeHtmlHelper.render("Hello World")
        assertEquals("Hello World", result.toString())
    }

    @Test
    fun `render https link preserves clickable span`() {
        val html = "<a href=\"https://example.com\">safe link</a>"
        val result = SafeHtmlHelper.render(html)
        // https 链接应被替换为 SafeLinkSpan（URLSpan 子类，仍可被 ClickableSpan 检索到）
        assertEquals(1, clickSpanCount(result))
        // SafeLinkSpan 继承 URLSpan，所以 urlSpanCount 也为 1
        assertEquals(1, urlSpanCount(result))
    }

    @Test
    fun `render http link removes clickable span`() {
        val html = "<a href=\"http://example.com\">unsafe link</a>"
        val result = SafeHtmlHelper.render(html)
        // http 链接应被移除，不可点击
        assertEquals(0, clickSpanCount(result))
        assertEquals(0, urlSpanCount(result))
        // 文本应保留
        assertTrue(result.toString().contains("unsafe link"))
    }

    @Test
    fun `render javascript scheme link removes clickable span`() {
        val html = "<a href=\"javascript:alert(1)\">xss</a>"
        val result = SafeHtmlHelper.render(html)
        assertEquals(0, clickSpanCount(result))
        assertEquals(0, urlSpanCount(result))
    }

    @Test
    fun `render intent scheme link removes clickable span`() {
        val html = "<a href=\"intent://example.com#Intent;scheme=https;end\">intent</a>"
        val result = SafeHtmlHelper.render(html)
        assertEquals(0, clickSpanCount(result))
        assertEquals(0, urlSpanCount(result))
    }

    @Test
    fun `render file scheme link removes clickable span`() {
        val html = "<a href=\"file:///etc/passwd\">file link</a>"
        val result = SafeHtmlHelper.render(html)
        assertEquals(0, clickSpanCount(result))
        assertEquals(0, urlSpanCount(result))
    }

    @Test
    fun `render multiple https links all clickable`() {
        val html = "<a href=\"https://a.com\">A</a> <a href=\"https://b.com\">B</a>"
        val result = SafeHtmlHelper.render(html)
        assertEquals(2, clickSpanCount(result))
    }

    @Test
    fun `render mixed http and https links only https clickable`() {
        val html = "<a href=\"https://safe.com\">safe</a> <a href=\"http://unsafe.com\">unsafe</a>"
        val result = SafeHtmlHelper.render(html)
        // 只有 https 链接可点击
        assertEquals(1, clickSpanCount(result))
    }

    @Test
    fun `render preserves text content of links`() {
        val html = "<a href=\"http://unsafe.com\">click me</a>"
        val result = SafeHtmlHelper.render(html)
        assertTrue(result.toString().contains("click me"))
    }

    @Test
    fun `render whitelist tags are preserved`() {
        val html = "<b>bold</b> <i>italic</i>"
        val result = SafeHtmlHelper.render(html)
        // 文本内容应保留
        assertTrue(result.toString().contains("bold"))
        assertTrue(result.toString().contains("italic"))
    }

    @Test
    fun `render block-level link is flattened to clickable link`() {
        // Patreon/Fanbox 常见模式：<a> 包裹 <div>/<h3> 等块级元素
        val html = """<a href="https://drive.google.com/file/d/abc/view" target="_blank">
            <div class="embed-view">
              <h3 class="">ALMOST FINISHED ANIMATION.mp4</h3>
            </div>
          </a>"""
        val result = SafeHtmlHelper.render(html)
        // 链接文本应保留
        assertTrue(result.toString().contains("ALMOST FINISHED ANIMATION.mp4"))
        // 应有一个可点击的链接 span
        assertEquals(1, clickSpanCount(result))
    }

    @Test
    fun `render block-level link with https preserves url`() {
        val html = """<a href="https://example.com/download"><div><h3>File.zip</h3></div></a>"""
        val result = SafeHtmlHelper.render(html)
        assertTrue(result.toString().contains("File.zip"))
        assertEquals(1, clickSpanCount(result))
    }

    @Test
    fun `render simple link without block elements is not affected`() {
        val html = "<a href=\"https://example.com\">simple link</a>"
        val result = SafeHtmlHelper.render(html)
        assertEquals(1, clickSpanCount(result))
        assertTrue(result.toString().contains("simple link"))
    }

    @Test
    fun `render real world patreon embed link`() {
        // 用户实际遇到的 HTML：Patreon 帖子中嵌入的 Google Drive 链接
        val html = """
            <a href="https://drive.google.com/file/d/1c0gZMmzT46YFXPg0zHVmLZVHP3lnLIGv/view?usp=sharing" target="_blank">
            <div class="embed-view">
              <h3 class="">
                ALMOST FINISHED ANIMATION.mp4
              </h3>
              
            </div>
          </a>
        """.trimIndent()
        val result = SafeHtmlHelper.render(html)
        val resultStr = result.toString()
        // 文本必须保留
        if (!resultStr.contains("ALMOST FINISHED ANIMATION.mp4")) {
            fail("Text 'ALMOST FINISHED ANIMATION.mp4' not found in result: $resultStr")
        }
        // 必须有且仅有一个可点击的 span
        val clickCount = clickSpanCount(result)
        if (clickCount != 1) {
            fail("Expected 1 clickable span, got $clickCount. Result: $resultStr")
        }
    }
}
