package com.pawchive.data.api

import com.pawchive.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val API_BASE_URL = "https://pawchive.pw/api/v1/"
    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    // authApi 实例缓存，key 为对应的 sessionCookie
    @Volatile
    private var cachedAuthApi: PawchiveApi? = null
    @Volatile
    private var cachedAuthCookie: String? = null

    /**
     * 注入 Cloudflare 通行凭据的拦截器：
     * 为每个请求附带过盾时的 User-Agent（必须与 cf_clearance 绑定的 UA 一致）
     * 以及包含 cf_clearance 的 Cookie。若请求已带 Cookie（如 session），则与 CF cookie 合并。
     */
    private val cloudflareInterceptor = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        CloudflareManager.currentUserAgent()?.let { ua ->
            builder.header("User-Agent", ua)
        }
        builder.header("Referer", LOGIN_BASE_URL)

        val cfCookie = CloudflareManager.currentCookie()
        if (!cfCookie.isNullOrEmpty()) {
            val existing = original.header("Cookie")
            val merged = if (existing.isNullOrEmpty()) cfCookie else "$existing; $cfCookie"
            builder.header("Cookie", merged)
        }

        chain.proceed(builder.build())
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(cloudflareInterceptor)
            .addInterceptor(logger)
            .build()
    }

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

        val cookieInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            // 与已有 Cookie（如 Cloudflare 注入的 cf_clearance）合并，避免出现重复的 Cookie 头
            val existing = original.header("Cookie")
            val merged = if (existing.isNullOrEmpty()) {
                "session=$sessionCookie"
            } else {
                "$existing; session=$sessionCookie"
            }
            val request = original.newBuilder()
                .header("Cookie", merged)
                .build()
            chain.proceed(request)
        }

        val client = okHttpClient.newBuilder()
            .addInterceptor(cookieInterceptor)
            .build()

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

    val loginApi: PawchiveLoginApi by lazy {
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .build()

        Retrofit.Builder()
            .baseUrl(LOGIN_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveLoginApi::class.java)
    }
}
