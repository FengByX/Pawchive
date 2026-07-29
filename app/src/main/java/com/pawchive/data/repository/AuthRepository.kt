package com.pawchive.data.repository

import android.content.Context
import com.pawchive.R
import com.pawchive.data.api.ApiCallHandler
import com.pawchive.data.api.ApiClient
import com.pawchive.data.api.ApiResult
import com.pawchive.data.api.PawchiveApi
import com.pawchive.data.model.FavoriteCreator
import com.pawchive.data.model.FavoritePost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 处理认证和收藏同步的仓库类
 */
class AuthRepository(private val context: Context) {
    private val sessionManager = SessionManager(context)

    /**
     * 登录并提取 session cookie
     * Pawchive 使用 Flask session，无论登录成功或失败都会返回 302 + Set-Cookie
     * 区分方式：检查 Location 头，重定向到首页 / 表示成功，重定向回 /account/login 表示失败
     */
    suspend fun login(username: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val loginApi = ApiClient.loginApi
                val response = loginApi.login(username, password)

                // 检查响应码（登录接口通常返回 30x 重定向）
                val statusCode = response.code()
                if (statusCode !in 300..399) {
                    return@withContext Result.failure(Exception("登录失败，HTTP $statusCode"))
                }

                // 检查重定向目标：重定向到登录页表示失败，其他目标通常表示成功
                val locationHeader = response.headers()["Location"]?.lowercase().orEmpty()
                if (locationHeader.contains("/account/login") || locationHeader.endsWith("/login")) {
                    return@withContext Result.failure(Exception("用户名或密码错误"))
                }

                // 提取 session cookie
                val cookies = response.headers().values("Set-Cookie")
                val sessionCookie = cookies.find { it.startsWith("session=") }
                    ?.substringAfter("session=")
                    ?.substringBefore(";")

                if (!sessionCookie.isNullOrEmpty()) {
                    sessionManager.saveSession(sessionCookie)
                    sessionManager.saveUsername(username)
                    Result.success(username)
                } else {
                    Result.failure(Exception("登录失败，服务器未返回 session cookie"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 注册新账户
     * Pawchive 注册接口：
     * - 成功 → 302 重定向到登录页或首页
     * - 用户名已存在 → 302 重定向回注册页
     * - 其他验证失败 → 200 重新渲染表单（HTML 中包含错误信息）
     */
    suspend fun register(username: String, password: String, confirmPassword: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (password != confirmPassword) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_passwords_not_match)))
                }

                val loginApi = ApiClient.loginApi
                val response = loginApi.register(username, password, confirmPassword)

                val statusCode = response.code()
                val locationHeader = response.headers()["Location"]?.lowercase().orEmpty()

                val bodyString = response.body()?.string().orEmpty()

                when {
                    statusCode in 300..399 && !locationHeader.contains("/account/register") -> {
                        Result.success(username)
                    }

                    statusCode in 300..399 && locationHeader.contains("/account/register") -> {
                        Result.failure(Exception(extractRegisterError(bodyString, context)))
                    }

                    statusCode == 200 -> {
                        Result.failure(Exception(extractRegisterError(bodyString, context)))
                    }

                    else -> {
                        Result.failure(Exception(context.getString(R.string.register_failed, statusCode)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun extractRegisterError(htmlBody: String, context: Context): String {
        val errorPatterns = listOf(
            "Username.*already.*exists",
            "already.*taken",
            "already.*registered",
            "Username must be",
            "must be.*3.*15",
            "only.*letters.*numbers",
            "Password must be",
            "must be.*5.*characters",
            "confirm.*password.*must.*match",
            "contains.*invalid",
            "invalid.*character",
        )
        for (pattern in errorPatterns) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(htmlBody)) {
                return context.getString(R.string.register_failed_validation)
            }
        }
        return context.getString(R.string.register_failed_unknown)
    }

    /**
     * 登出
     */
    suspend fun logout(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            // 旧实现用了 try/catch 包裹 clearSession，但 clearSession 内部仅
            // 调用 SharedPreferences.edit().apply()，不会抛异常，catch 块为死代码。
            // 简化为直接调用。
            sessionManager.clearSession()
            Result.success(Unit)
        }
    }

    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

    /**
     * 获取当前用户名
     */
    fun getUsername(): String? = sessionManager.getUsername()

    /**
     * 获取已认证的 API 客户端
     */
    fun getAuthenticatedApi(): PawchiveApi {
        val cookie = sessionManager.getSessionCookie()
        return if (cookie != null) {
            ApiClient.authApi(cookie)
        } else {
            ApiClient.publicApi
        }
    }

    /**
     * 同步账号收藏的帖子
     */
    suspend fun syncFavoritePosts(offset: Int? = null): Result<List<FavoritePost>> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallDirect { api.getFavoritePosts(offset = offset) }
                apiResultToResult(result)
            }
        }
    }

    /**
     * 同步账号收藏的创作者
     */
    suspend fun syncFavoriteCreators(): Result<List<FavoriteCreator>> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallDirect { api.getFavoriteCreators() }
                apiResultToResult(result)
            }
        }
    }

    /**
     * 添加帖子到账号收藏
     */
    suspend fun addPostToFavorites(service: String, creatorId: String, postId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallUnit {
                    api.addPostToFavorites(service, creatorId, postId)
                }
                apiResultToResult(result)
            }
        }
    }

    /**
     * 从账号收藏移除帖子
     */
    suspend fun removePostFromFavorites(service: String, creatorId: String, postId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallUnit {
                    api.removePostFromFavorites(service, creatorId, postId)
                }
                apiResultToResult(result)
            }
        }
    }

    /**
     * 添加创作者到账号收藏
     */
    suspend fun addCreatorToFavorites(service: String, creatorId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallUnit {
                    api.addCreatorToFavorites(service, creatorId)
                }
                apiResultToResult(result)
            }
        }
    }

    /**
     * 从账号收藏移除创作者
     */
    suspend fun removeCreatorFromFavorites(service: String, creatorId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            ensureLoggedIn { api ->
                val result = ApiCallHandler.safeApiCallUnit {
                    api.removeCreatorFromFavorites(service, creatorId)
                }
                apiResultToResult(result)
            }
        }
    }

    private suspend fun <T> ensureLoggedIn(block: suspend (PawchiveApi) -> Result<T>): Result<T> {
        return if (!sessionManager.isLoggedIn()) {
            Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
        } else {
            try {
                val api = getAuthenticatedApi()
                block(api)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun <T> apiResultToResult(apiResult: ApiResult<T>): Result<T> {
        return when (apiResult) {
            is ApiResult.Success -> Result.success(apiResult.data)
            is ApiResult.Error.NetworkError -> Result.failure(
                Exception(apiResult.message, apiResult.cause)
            )
            is ApiResult.Error.AuthError -> {
                sessionManager.clearSession()
                Result.failure(Exception(context.getString(R.string.error_auth_expired)))
            }
            is ApiResult.Error.ServerError -> {
                if (apiResult.code == 401) {
                    sessionManager.clearSession()
                    Result.failure(Exception(context.getString(R.string.error_auth_expired)))
                } else {
                    Result.failure(Exception("HTTP ${apiResult.code}: ${apiResult.message}"))
                }
            }
            is ApiResult.Error.UnknownError -> Result.failure(
                apiResult.cause
            )
        }
    }
}
