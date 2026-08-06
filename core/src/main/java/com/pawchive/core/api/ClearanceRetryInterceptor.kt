package com.pawchive.core.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Cloudflare 403 兜底拦截器（ARCH-009 / BACKEND-001）。
 *
 * 回退 v1.4.9 方式：请求直接发出（调用层不再预等待过盾），
 * 遇到 403 时在此阻塞等待过盾并重试一次：
 * - 403 → `runBlocking` 强制刷新 cf_clearance（过盾 5-15s，最坏 30s 超时）
 * - 过盾成功 → 重建请求重试一次（重试会重新经过 ClearanceInterceptor 注入新凭据）
 * - 过盾失败 → 返回 403 占位响应（不抛异常，上层按加载失败处理）
 *
 * 注意：runBlocking 会占用当前 OkHttp 网络线程，并发 403 较多时可能占满
 * 连接池（maxRequestsPerHost=5）。这是 v1.4.9 的既有行为，按用户要求回退。
 * 首次启动由 PawchiveApplication/MainActivity 的 preheat 提前过盾，
 * 因此正常使用时 403 极少出现。
 */
object ClearanceRetryInterceptor {

    // 用于构造"过盾未完成"占位响应（避免依赖已关闭的响应体）
    private val EMPTY_RESPONSE_BODY = ByteArray(0).toResponseBody("text/plain".toMediaType())

    fun intercept(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403) {
            return@Interceptor response
        }

        // 先关闭原始 403 响应释放连接，再尝试强制刷新过盾
        response.close()
        val success = runBlocking {
            CloudflareManager.ensureClearance(forceRefresh = true)
        }
        if (success) {
            // 重建请求；重试会重新经过 ClearanceInterceptor，自动注入新的 cf_clearance
            val rebuiltRequest = request.newBuilder().build()
            chain.proceed(rebuiltRequest)
        } else {
            // 过盾失败，返回 403 占位响应（而非抛异常），
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
