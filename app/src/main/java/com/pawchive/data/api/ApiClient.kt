package com.pawchive.data.api

import com.pawchive.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val API_BASE_URL = "https://pawchive.st/api/v1/"
    private const val LOGIN_BASE_URL = "https://pawchive.st/"

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
        val cookieInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Cookie", "session=$sessionCookie")
                .build()
            chain.proceed(request)
        }

        val client = okHttpClient.newBuilder()
            .addInterceptor(cookieInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveApi::class.java)
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
