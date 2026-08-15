package com.pawchive.core.api

import okhttp3.Interceptor

/**
 * 会话注入拦截器（ARCH-009：SessionInterceptor 职责）。
 *
 * 为认证 API 请求附加 `session=<cookie>`，与 Cloudflare 注入的 cf_clearance
 * 等 Cookie 头合并，避免出现重复的 Cookie 头或重复的 session 段。
 */
object SessionInterceptor {

    fun forCookie(sessionCookie: String): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val existing = original.header("Cookie") ?: ""
        // 解析现有 Cookie，移除已有的 session= 段，防止重复（服务端取值行为未定义）
        val cleanedExisting = existing.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("session=", ignoreCase = true) }
            .joinToString("; ")
        val merged = if (cleanedExisting.isEmpty()) {
            "session=$sessionCookie"
        } else {
            "$cleanedExisting; session=$sessionCookie"
        }
        val request = original.newBuilder()
            .header("Cookie", merged)
            .build()
        chain.proceed(request)
    }
}
