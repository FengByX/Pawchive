package com.pawchive.core.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Cloudflare 403 兜底拦截器（ARCH-009 / BACKEND-001）。
 *
 * 遇到 403 时使用专用线程池阻塞等待过盾并重试一次，避免占用 OkHttp 网络线程池。
 * - 403 → 在单线程 executor 上强制刷新 cf_clearance（过盾 5-15s，最坏 30s 超时）
 * - 过盾成功 → 重建请求重试一次（重试会重新经过 ClearanceInterceptor 注入新凭据）
 * - 过盾失败/超时 → 返回 403 占位响应（不抛异常，上层按加载失败处理）
 *
 * 使用单线程 executor 串行化过盾请求，配合 CloudflareManager 的单飞机制，
 * 并发 403 时复用同一过盾任务，避免多 WebView 竞争。
 */
object ClearanceRetryInterceptor {

    // 用于构造"过盾未完成"占位响应（避免依赖已关闭的响应体）
    private val EMPTY_RESPONSE_BODY = ByteArray(0).toResponseBody("text/plain".toMediaType())

    // 专用单线程 executor：仅用于 Cloudflare 过盾阻塞等待，隔离 OkHttp 线程池
    private val cfExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Pawchive-CF-Clearance").apply { isDaemon = true }
    }

    // 过盾最大等待时间（与 CloudflareManager.CHALLENGE_TIMEOUT_MS 对齐，略大避免竞态）
    private const val CLEARANCE_WAIT_TIMEOUT_MS = 35_000L

    fun intercept(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403) {
            return@Interceptor response
        }

        // 先关闭原始 403 响应释放连接，再尝试强制刷新过盾
        response.close()

        // 在专用 executor 上阻塞等待过盾，不占用 OkHttp dispatcher 线程。
        // runBlocking 运行在独立单线程 executor 上（非 OkHttp 网络线程），
        // 与 CloudflareManager 的单飞机制配合，并发 403 复用同一过盾任务。
        val future: Future<Boolean> = cfExecutor.submit<Boolean> {
            runBlocking {
                CloudflareManager.ensureClearance(forceRefresh = true)
            }
        }

        val success = try {
            future.get(CLEARANCE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            false
        } catch (e: Exception) {
            false
        }

        if (success) {
            // 重建请求；重试会重新经过 ClearanceInterceptor，自动注入新的 cf_clearance
            val rebuiltRequest = request.newBuilder().build()
            chain.proceed(rebuiltRequest)
        } else {
            // 过盾失败/超时，返回 403 占位响应（而非抛异常），
            // 让 Coil/Retrofit 能正常将其作为加载失败处理
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Cloudflare clearance failed")
                .body(EMPTY_RESPONSE_BODY)
                .build()
        }
    }
}
