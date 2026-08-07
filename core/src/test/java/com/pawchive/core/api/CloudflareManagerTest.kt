package com.pawchive.core.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.IOException

/**
 * CloudflareManager 失败处理与异常分类测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - hasClearance：未过盾时返回 false
 * - clear：清除后所有缓存凭据为空
 * - isForbidden：403 异常被识别为 Cloudflare 拦截
 * - isForbidden：非 403 异常不被识别
 *
 * 注意：真实 WebView 挑战流程与 withClearance 重试依赖 ensureClearance 实际执行，
 * 在 Robolectric 下会触发 30 秒超时，因此不在此测试覆盖范围内。
 * 单飞与重试策略通过代码审查 + 集成测试验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudflareManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setup() {
        CloudflareManager.clear()
    }

    @Test
    fun `hasClearance returns false before any challenge`() {
        assertFalse(CloudflareManager.hasClearance())
    }

    @Test
    fun `currentCookie is null before challenge`() {
        // 未过盾时 cookie 为 null
        assertFalse(CloudflareManager.currentCookie() != null)
    }

    @Test
    fun `currentUserAgent is null before challenge`() {
        assertFalse(CloudflareManager.currentUserAgent() != null)
    }

    @Test
    fun `clear resets all cached credentials`() {
        CloudflareManager.clear()
        assertFalse(CloudflareManager.hasClearance())
        assertFalse(CloudflareManager.currentCookie() != null)
        assertFalse(CloudflareManager.currentUserAgent() != null)
    }

    @Test
    fun `isForbidden identifies HttpException 403`() {
        val exception = HttpException(
            retrofit2.Response.error<Any>(403, okhttp3.ResponseBody.create(null, ""))
        )
        assertTrue(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden identifies message containing 403`() {
        val exception = IOException("HTTP 403 Forbidden")
        assertTrue(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden does not identify 404`() {
        val exception = HttpException(
            retrofit2.Response.error<Any>(404, okhttp3.ResponseBody.create(null, ""))
        )
        assertFalse(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden does not identify generic IOException`() {
        val exception = IOException("Connection reset")
        assertFalse(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden does not identify 500`() {
        val exception = HttpException(
            retrofit2.Response.error<Any>(500, okhttp3.ResponseBody.create(null, ""))
        )
        assertFalse(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden does not identify 401`() {
        val exception = HttpException(
            retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))
        )
        assertFalse(CloudflareManager.isForbidden(exception))
    }

    @Test
    fun `isForbidden handles null message safely`() {
        val exception = IOException()
        // message 为 null 时不应崩溃，且不应被识别为 403
        assertFalse(CloudflareManager.isForbidden(exception))
    }

    // ===== stripSessionTokens：登录失效循环防护 =====

    @Test
    fun `stripSessionTokens removes session token`() {
        val cookie = "cf_clearance=abc; __cf_bm=xyz; session=.eJwAAA; HttpOnly"
        val result = CloudflareManager.stripSessionTokens(cookie)
        assertFalse(result.contains("session=", ignoreCase = true))
        assertTrue(result.contains("cf_clearance=abc"))
        assertTrue(result.contains("__cf_bm=xyz"))
    }

    @Test
    fun `stripSessionTokens removes session token case-insensitively`() {
        val cookie = "cf_clearance=abc; Session=ANON; __cf_bm=xyz"
        val result = CloudflareManager.stripSessionTokens(cookie)
        assertFalse(result.contains("session=", ignoreCase = true))
        assertTrue(result.contains("cf_clearance=abc"))
    }

    @Test
    fun `stripSessionTokens keeps pure cf cookie unchanged`() {
        val cookie = "cf_clearance=abc; __cf_bm=xyz"
        assertEquals(cookie, CloudflareManager.stripSessionTokens(cookie))
    }

    @Test
    fun `stripSessionTokens returns empty for session-only cookie`() {
        val result = CloudflareManager.stripSessionTokens("session=ANON")
        assertTrue(result.isBlank())
    }

    @Test
    fun `stripSessionTokens handles blank input`() {
        assertEquals("", CloudflareManager.stripSessionTokens(""))
    }
}
