package com.pawchive.core.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Cloudflare 403 兜底拦截器（ARCH-009 / BACKEND-001）。
 *
 * 原实现会在 OkHttp 网络线程上 `runBlocking` 等待过盾，并发 403 时长期占用
 * 线程池（maxRequestsPerHost=5）放大超时风险。本实现改为完全非阻塞：
 * - 遇到 403 → 触发非阻塞过盾预热（[ClearanceCoordinator.preheat]，不占网络线程）
 * - 返回原始 403 响应，由上层决定重试；重试时过盾大概率已完成，请求即可成功。
 *
 * 请求发出前的主动过盾由调用层 `ClearanceCoordinator.ensureClearance()` /
 * `CloudflareManager.withClearance()` 负责，因此正常情况下 403 极少出现，
 * 本拦截器仅作为极端兜底（如 Coil 图片加载这类无法在调用层预过盾的场景）。
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

        // 关闭原始 403 响应释放连接（其 body 已无用）
        response.close()
        // 非阻塞触发过盾，供后续请求复用
        ClearanceCoordinator.preheat()

        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Cloudflare clearance required")
            .body(EMPTY_RESPONSE_BODY)
            .build()
    }
}
