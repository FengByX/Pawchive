package com.pawchive.core.api

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * ApiMemoryCache 内存缓存与「写入后精准失效」逻辑测试。
 *
 * 背景：收藏列表走 GET 且被本缓存持有 5 分钟，而增删收藏走 POST/DELETE（不经过本拦截器）。
 * 若写入成功后不调用 [ApiMemoryCache.invalidateByPathPrefix]，收藏页会滞后最长 5 分钟
 * 才能看到刚写入的收藏（"收藏后返回收藏页看不到新条目"）。本测试锁定该失效行为，
 * 防止回归。
 */
class ApiMemoryCacheTest {

    private val favoritesUrl = "https://pawchive.pw/api/v1/account/favorites?type=post&o=0"
    private val postsUrl = "https://pawchive.pw/api/v1/posts?o=0"

    @Before
    fun setup() {
        ApiMemoryCache.clear()
        ApiMemoryCache.currentSessionHash = null
    }

    @After
    fun tearDown() {
        ApiMemoryCache.clear()
        ApiMemoryCache.currentSessionHash = null
    }

    @Test
    fun `second identical get is served from memory cache`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()

        interceptor.intercept(chain)
        val second = interceptor.intercept(chain)

        assertEquals(1, chain.proceedCalls)
        assertEquals("OK (from memory cache)", second.message)
    }

    @Test
    fun `invalidateByPathPrefix makes the next favorites request hit network again`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()

        interceptor.intercept(chain)
        interceptor.intercept(chain)
        assertEquals("首次请求落盘后第二次应命中缓存", 1, chain.proceedCalls)

        ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites")

        interceptor.intercept(chain)
        assertEquals("失效后必须重新打到网络", 2, chain.proceedCalls)
    }

    @Test
    fun `invalidateByPathPrefix leaves other endpoints cached`() {
        val favoritesChain = FakeChain(buildGet(favoritesUrl))
        val postsChain = FakeChain(buildGet(postsUrl))
        val interceptor = ApiMemoryCache.intercept()

        interceptor.intercept(favoritesChain)
        interceptor.intercept(postsChain)

        ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites")

        interceptor.intercept(favoritesChain)
        interceptor.intercept(postsChain)

        assertEquals("收藏接口已被失效，应重新请求", 2, favoritesChain.proceedCalls)
        assertEquals("其它接口不应被误伤，仍命中缓存", 1, postsChain.proceedCalls)
    }

    @Test
    fun `invalidateByPathPrefix returns removed entry count`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()
        interceptor.intercept(chain)

        assertEquals(1, ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites"))
        assertEquals("已失效的条目不应被重复计数", 0, ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites"))
    }

    @Test
    fun `invalidateByPathPrefix returns zero when nothing matches`() {
        val chain = FakeChain(buildGet(postsUrl))
        ApiMemoryCache.intercept().intercept(chain)

        assertEquals(0, ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites"))
    }

    @Test
    fun `invalidateByPathPrefix removes entries of every session namespace`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()

        ApiMemoryCache.currentSessionHash = "hash-a"
        interceptor.intercept(chain)
        ApiMemoryCache.currentSessionHash = "hash-b"
        interceptor.intercept(chain)
        assertEquals(2, chain.proceedCalls)

        assertEquals(2, ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites"))
    }

    @Test
    fun `invalidateByPathPrefix matches url with tracking params stripped`() {
        val chain = FakeChain(buildGet("https://pawchive.pw/api/v1/account/favorites?type=post&utm_source=app"))
        ApiMemoryCache.intercept().intercept(chain)

        assertEquals(1, ApiMemoryCache.invalidateByPathPrefix("/api/v1/account/favorites"))
    }

    @Test
    fun `no-cache header bypasses both cache read and write`() {
        val chain = FakeChain(buildGet(favoritesUrl, cacheControl = "no-cache"))
        val interceptor = ApiMemoryCache.intercept()

        interceptor.intercept(chain)
        interceptor.intercept(chain)

        assertEquals("no-cache 请求不得读缓存，也不得污染缓存", 2, chain.proceedCalls)
    }

    @Test
    fun `non-get request is never cached`() {
        val request = Request.Builder()
            .url(favoritesUrl)
            .post("".toRequestBody("text/plain".toMediaType()))
            .build()
        val chain = FakeChain(request)
        val interceptor = ApiMemoryCache.intercept()

        interceptor.intercept(chain)
        interceptor.intercept(chain)

        assertEquals(2, chain.proceedCalls)
    }

    @Test
    fun `cache is isolated per session namespace`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()

        ApiMemoryCache.currentSessionHash = "hash-a"
        interceptor.intercept(chain)
        ApiMemoryCache.currentSessionHash = "hash-b"
        interceptor.intercept(chain)

        assertEquals("不同账号不得复用同一份缓存", 2, chain.proceedCalls)
    }

    @Test
    fun `clear empties the cache`() {
        val chain = FakeChain(buildGet(favoritesUrl))
        val interceptor = ApiMemoryCache.intercept()
        interceptor.intercept(chain)

        ApiMemoryCache.clear()

        interceptor.intercept(chain)
        assertEquals(2, chain.proceedCalls)
    }

    private fun buildGet(url: String, cacheControl: String? = null): Request {
        val builder = Request.Builder().url(url).get()
        if (cacheControl != null) builder.header("Cache-Control", cacheControl)
        return builder.build()
    }

    /**
     * 最小 [Interceptor.Chain] 实现：返回固定 JSON 响应。
     *
     * [proceedCalls] 用于区分请求是真正打到了"网络"，还是被内存缓存短路——
     * 这是判断缓存是否失效的唯一可靠信号（缓存命中时不会调用 proceed）。
     */
    private class FakeChain(private val request: Request) : Interceptor.Chain {

        var proceedCalls = 0
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceedCalls++
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("[]".toResponseBody("application/json".toMediaType()))
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("ApiMemoryCache 不读取 chain.call()")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
