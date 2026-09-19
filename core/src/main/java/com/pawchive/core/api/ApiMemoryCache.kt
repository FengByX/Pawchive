package com.pawchive.core.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


/**
 * API 响应内存缓存（ARCH-009：从 ApiClient 拆出的 ResponseCache 职责）。
 *
 * - 仅缓存 GET 的 JSON 响应，有效期 5 分钟，进程退出随内存自动清除；
 * - 下拉刷新通过 Header "Cache-Control: no-cache" 跳过缓存；
 * - 缓存键包含账号命名空间（session hash），杜绝跨用户缓存复用（P0）；
 * - 容量上限防止无限增长。
 * - 缓存键规范化：URL 参数按字典序排序，去除追踪参数（ARCH-BUG-MINOR-19）。
 */
object ApiMemoryCache {

    private const val CACHE_MAX_AGE_MILLIS = 5 * 60 * 1000L // 5 分钟
    private const val CACHE_MAX_ENTRIES = 200 // 容量上限

    // 追踪/分析参数前缀，缓存键中去除以提高命中率
    private val TRACKING_PARAM_PREFIXES = setOf("utm_", "fbclid", "gclid", "ref", "source")

    private data class CacheEntry(val timestamp: Long, val body: ByteArray, val contentType: String?)

    // PERF-008：使用 LRU LinkedHashMap + 同步锁替代 ConcurrentHashMap + O(N) 遍历
    private val cache = object : LinkedHashMap<String, CacheEntry>(
        CACHE_MAX_ENTRIES, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > CACHE_MAX_ENTRIES
        }
    }
    private val cacheLock = Any()

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
        val key = "$namespace|${normalizeUrl(request.url.toString())}"
        val now = System.currentTimeMillis()

        // 命中缓存
        val cached = synchronized(cacheLock) { cache[key] }
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
     * 规范化 URL 用于缓存键：参数按字典序排序、去除追踪参数（ARCH-BUG-MINOR-19）。
     */
    private fun normalizeUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            val path = url.path
            val query = url.query ?: return urlString
            if (query.isEmpty()) return urlString

            // 解析参数，去除追踪参数，按 key 排序
            val params = query.split("&")
                .mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    val key = parts[0].trim()
                    if (key.isEmpty()) return@mapNotNull null
                    if (TRACKING_PARAM_PREFIXES.any { key.startsWith(it) }) return@mapNotNull null
                    val value = if (parts.size > 1) parts[1] else ""
                    key to value
                }
                .sortedBy { it.first }
                .map { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}" }
                .joinToString("&")

            "$path?$params"
        } catch (e: Exception) {
            // 解析失败时回退原 URL
            urlString
        }
    }

    /**
     * 写入缓存，并回收过期条目、淘汰最旧条目以维持容量上限。
     */
    private fun put(key: String, entry: CacheEntry) {
        synchronized(cacheLock) {
            cache[key] = entry
        }
    }

    /**
     * 按路径前缀精准失效缓存条目，返回被移除的条数。
     *
     * 使用场景：写操作（POST / DELETE）成功后，必须清掉对应读接口的陈旧缓存。
     * 收藏列表就是典型例子——它走 GET + 5 分钟 TTL 被缓存，而新增/移除收藏走
     * POST/DELETE（不经过本拦截器），若不显式失效，收藏页会滞后最长 5 分钟
     * 才能看到刚写入的收藏。
     *
     * 相比 [clear] 只清目标资源，不会误伤首页 feed 等其它接口的缓存。
     *
     * 缓存键格式为 `"<namespace>|<normalizedUrl>"`（见 [intercept]），
     * 因此只需匹配首个 `|` 之后的部分是否包含 [pathPrefix]。
     *
     * @param pathPrefix 目标接口路径前缀，如 `"/api/v1/account/favorites"`
     */
    fun invalidateByPathPrefix(pathPrefix: String): Int {
        return synchronized(cacheLock) {
            val matched = cache.keys.filter { key ->
                key.substringAfter('|', missingDelimiterValue = "").contains(pathPrefix)
            }
            matched.forEach { cache.remove(it) }
            matched.size
        }
    }

    /**
     * 清空缓存（登出 / 切换账号 / 手动清理时调用）。
     */
    fun clear() {
        synchronized(cacheLock) { cache.clear() }
    }
}
