package com.pawchive.core.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * API 响应内存缓存（ARCH-009：从 ApiClient 拆出的 ResponseCache 职责）。
 *
 * - 仅缓存 GET 的 JSON 响应，有效期 5 分钟，进程退出随内存自动清除；
 * - 下拉刷新通过 Header "Cache-Control: no-cache" 跳过缓存；
 * - 缓存键包含账号命名空间（session hash），杜绝跨用户缓存复用（P0）；
 * - 容量上限防止无限增长。
 */
object ApiMemoryCache {

    private const val CACHE_MAX_AGE_MILLIS = 5 * 60 * 1000L // 5 分钟
    private const val CACHE_MAX_ENTRIES = 200 // 容量上限

    private data class CacheEntry(val timestamp: Long, val body: ByteArray, val contentType: String?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // 当前登录会话 hash（缓存命名空间隔离，避免明文 cookie 驻留内存键 / P2）
    @Volatile
    var currentSessionHash: String? = null

    /**
     * 构建缓存拦截器（每次调用返回独立实例，共享同一份缓存数据）。
     */
    fun intercept(): Interceptor = Interceptor { chain ->
        val request = chain.request()

        // 只缓存 GET 请求
        if (request.method != "GET") {
            return@Interceptor chain.proceed(request)
        }

        // 下拉刷新等场景通过 no-cache Header 跳过缓存
        if (request.header("Cache-Control")?.contains("no-cache") == true) {
            return@Interceptor chain.proceed(request)
        }

        // 缓存键加入账号维度：用 session hash 替代明文 cookie，避免凭据驻留内存键（P2）
        val namespace = currentSessionHash?.let { "u:$it" } ?: "public"
        val key = "$namespace|${request.url}"
        val now = System.currentTimeMillis()

        // 命中缓存
        val cached = cache[key]
        if (cached != null && now - cached.timestamp < CACHE_MAX_AGE_MILLIS) {
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK (from memory cache)")
                .body(cached.body.toResponseBody(cached.contentType?.toMediaType()))
                .build()
        }

        // 发起请求并缓存成功的 JSON 响应
        val response = chain.proceed(request)
        if (response.isSuccessful) {
            val contentType = response.header("Content-Type") ?: response.body?.contentType()?.toString()
            if (contentType?.contains("json") == true) {
                val bodyBytes = response.body?.bytes()
                if (bodyBytes != null) {
                    put(key, CacheEntry(now, bodyBytes, contentType))
                    // body 已被消费，需重新构建 Response
                    return@Interceptor response.newBuilder()
                        .body(bodyBytes.toResponseBody(contentType.toMediaType()))
                        .build()
                }
            }
        }
        response
    }

    /**
     * 写入缓存，并回收过期条目、淘汰最旧条目以维持容量上限。
     */
    private fun put(key: String, entry: CacheEntry) {
        val now = System.currentTimeMillis()
        // 回收过期条目
        cache.entries
            .filter { now - it.value.timestamp >= CACHE_MAX_AGE_MILLIS }
            .forEach { cache.remove(it.key) }
        // 超出容量上限则淘汰最旧的一条
        if (cache.size >= CACHE_MAX_ENTRIES) {
            cache.minByOrNull { it.value.timestamp }?.key?.let { cache.remove(it) }
        }
        cache[key] = entry
    }

    /**
     * 清空缓存（登出 / 切换账号 / 手动清理时调用）。
     */
    fun clear() {
        cache.clear()
    }
}
