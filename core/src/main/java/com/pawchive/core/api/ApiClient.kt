package com.pawchive.core.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest

/**
 * API 门面（ARCH-009：职责拆分后的装配入口）。
 *
 * 仅负责组装与暴露 Retrofit 实例与 OkHttpClient，网络细节全部委托给独立组件：
 * - [HttpClientFactory]：各类型客户端构建（独立超时与拦截器组合）
 * - [ApiMemoryCache]：GET JSON 内存缓存（含账号命名空间）
 * - [ClearanceInterceptor] / [ClearanceRetryInterceptor] / [ClearanceCoordinator]：Cloudflare 凭据注入与 403 兜底
 * - [SessionInterceptor]：认证会话注入
 *
 * 公开签名保持不变（publicApi / authApi / loginApi / sharedOkHttpClient /
 * imageOkHttpClient / clearMemoryCache），既有调用方无需改动。
 */
object ApiClient {

    private const val API_BASE_URL = "https://pawchive.pw/api/v1/"
    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    private val httpClientFactory = HttpClientFactory()

    // authApi 实例缓存，key 为对应的 sessionCookie
    @Volatile
    private var cachedAuthApi: PawchiveApi? = null
    @Volatile
    private var cachedAuthCookie: String? = null

    /**
     * 暴露给外部（Coil 图片加载、视频、下载逻辑等）使用的 OkHttpClient，
     * 已注入 Cloudflare 凭据与 403 兜底。
     *
     * 注意：img.pawchive.pw 并非完全公开的 CDN，部分缩略图请求也会被
     * Cloudflare 拦截返回 403，因此图片加载必须使用此客户端。
     */
    val sharedOkHttpClient: OkHttpClient by lazy { httpClientFactory.createApiClient() }

    /**
     * 轻量级 OkHttpClient（无 Cloudflare 拦截器），
     * 仅用于确定不需要过盾的场景（如本地文件下载等）。
     */
    val imageOkHttpClient: OkHttpClient by lazy { httpClientFactory.createImageClient() }

    private val okHttpClient: OkHttpClient by lazy { httpClientFactory.createApiClient() }

    val publicApi: PawchiveApi by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveApi::class.java)
    }

    fun authApi(sessionCookie: String): PawchiveApi {
        // 复用同一 sessionCookie 对应的实例，避免每次调用都重建 OkHttp/Retrofit
        cachedAuthApi?.let { cached ->
            if (cachedAuthCookie == sessionCookie) return cached
        }

        // 设定当前会话 hash，使内存缓存归入该账号命名空间（P2：用 hash 替代明文 cookie）
        ApiMemoryCache.currentSessionHash = hashSession(sessionCookie)

        val client = httpClientFactory.createApiClient(
            extraInterceptors = listOf(SessionInterceptor.forCookie(sessionCookie))
        )

        val api = Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveApi::class.java)

        cachedAuthCookie = sessionCookie
        cachedAuthApi = api
        return api
    }

    /**
     * 登录 API：不自动跟随重定向（登录成功由 302 判断，需手动解析）。
     */
    val loginApi: PawchiveLoginApi by lazy {
        Retrofit.Builder()
            .baseUrl(LOGIN_BASE_URL)
            .client(httpClientFactory.createLoginClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveLoginApi::class.java)
    }

    /**
     * 清除内存缓存并重置认证实例与当前会话。
     * 登出或切换账号时调用，避免旧账号数据残留（P0 / P1）。
     */
    fun clearMemoryCache() {
        ApiMemoryCache.clear()
        cachedAuthApi = null
        cachedAuthCookie = null
        ApiMemoryCache.currentSessionHash = null
    }

    /**
     * 将 session cookie 转为 SHA-256 hash 前 16 字符，用于缓存键命名空间（P2）。
     * 不存储原始 cookie，仅存 hash，降低内存泄漏风险。
     */
    private fun hashSession(cookie: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(cookie.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            // 极端情况降级为 hashCode，仍不含明文
            cookie.hashCode().toString(16)
        }
    }
}
