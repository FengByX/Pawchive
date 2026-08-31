package com.pawchive.core.error

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * AppError 统一错误类型测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - [AppError.from]：各类 Throwable 到 AppError 的分类映射
 * - [AppError.toMessage]：各错误类型到用户友好文案的映射
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppErrorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // ---------- from() 错误分类 ----------

    @Test
    fun `from UnknownHostException maps to Network UNREACHABLE`() {
        val error = AppError.from(UnknownHostException("Unable to resolve host"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.UNREACHABLE, (error as AppError.Network).kind)
    }

    @Test
    fun `from ConnectException maps to Network SERVER_UNREACHABLE`() {
        val error = AppError.from(ConnectException("Connection refused"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.SERVER_UNREACHABLE, (error as AppError.Network).kind)
    }

    @Test
    fun `from SocketTimeoutException maps to Network TIMEOUT`() {
        val error = AppError.from(SocketTimeoutException("timeout"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.TIMEOUT, (error as AppError.Network).kind)
    }

    @Test
    fun `from SSLPeerUnverifiedException maps to Network SSL`() {
        val error = AppError.from(SSLPeerUnverifiedException("peer not verified"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.SSL, (error as AppError.Network).kind)
    }

    @Test
    fun `from SSLException maps to Network SSL`() {
        val error = AppError.from(SSLException("SSL handshake failed"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.SSL, (error as AppError.Network).kind)
    }

    @Test
    fun `from IOException with Connection reset maps to Network RESET`() {
        val error = AppError.from(IOException("Connection reset by peer"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.RESET, (error as AppError.Network).kind)
    }

    @Test
    fun `from IOException with Broken pipe maps to Network RESET`() {
        val error = AppError.from(IOException("Broken pipe"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.RESET, (error as AppError.Network).kind)
    }

    @Test
    fun `from generic IOException maps to Network UNREACHABLE`() {
        val error = AppError.from(IOException("unexpected EOF"))
        assertTrue(error is AppError.Network)
        assertEquals(AppError.Network.Kind.UNREACHABLE, (error as AppError.Network).kind)
    }

    @Test
    fun `from AppError returns same instance`() {
        val original = AppError.Business("custom message")
        val result = AppError.from(original)
        assertTrue(result === original)
    }

    @Test
    fun `from unknown Throwable maps to Unknown`() {
        val error = AppError.from(RuntimeException("something went wrong"))
        assertTrue(error is AppError.Unknown)
    }

    @Test
    fun `from Throwable with Cloudflare in message maps to CloudflareChallenge`() {
        val error = AppError.from(RuntimeException("Cloudflare challenge required"))
        assertTrue(error is AppError.CloudflareChallenge)
    }

    @Test
    fun `from preserves cause`() {
        val cause = UnknownHostException("test")
        val error = AppError.from(cause)
        assertNotNull(error.cause)
        assertTrue(error.cause === cause)
    }

    // ---------- toMessage() 消息映射 ----------

    @Test
    fun `toMessage Network UNREACHABLE returns network unreachable string`() {
        val message = AppError.Network(AppError.Network.Kind.UNREACHABLE).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_network_unreachable), message)
    }

    @Test
    fun `toMessage Network TIMEOUT returns timeout string`() {
        val message = AppError.Network(AppError.Network.Kind.TIMEOUT).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_timeout), message)
    }

    @Test
    fun `toMessage Server 403 returns forbidden string`() {
        val message = AppError.Server(403).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_forbidden), message)
    }

    @Test
    fun `toMessage Server 404 returns not found string`() {
        val message = AppError.Server(404).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_not_found), message)
    }

    @Test
    fun `toMessage Server 500 returns server error string`() {
        val message = AppError.Server(500).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_server_error), message)
    }

    @Test
    fun `toMessage Server 503 returns server error string`() {
        val message = AppError.Server(503).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_server_error), message)
    }

    @Test
    fun `toMessage Auth SESSION_EXPIRED returns auth expired string`() {
        val message = AppError.Auth(AppError.Auth.Reason.SESSION_EXPIRED).toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_auth_expired), message)
    }

    @Test
    fun `toMessage CloudflareChallenge returns cloudflare string`() {
        val message = AppError.CloudflareChallenge().toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_cloudflare), message)
    }

    @Test
    fun `toMessage Business returns user message directly`() {
        val customMessage = "自定义业务错误文案"
        val message = AppError.Business(customMessage).toMessage(context)
        assertEquals(customMessage, message)
    }

    @Test
    fun `toMessage Unknown returns unknown string`() {
        val message = AppError.Unknown().toMessage(context)
        assertEquals(context.getString(com.pawchive.core.R.string.error_unknown), message)
    }
}
