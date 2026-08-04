package com.pawchive.data.repository

import android.content.Context
import com.pawchive.R
import com.pawchive.data.AppError
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
                    return@withContext Result.failure(
                        AppError.Server(statusCode, "登录失败，HTTP $statusCode")
                    )
                }

                // 检查重定向目标：重定向到登录页表示失败，其他目标通常表示成功
                val locationHeader = response.headers()["Location"]?.lowercase().orEmpty()
                if (locationHeader.contains("/account/login") || locationHeader.endsWith("/login")) {
                    return@withContext Result.failure(
                        AppError.Auth(AppError.Auth.Reason.INVALID_CREDENTIALS)
                    )
                }

                // 提取 session cookie
                val cookies = response.headers().values("Set-Cookie")
                val sessionCookie = cookies.find { it.startsWith("session=") }
                    ?.substringAfter("session=")
                    ?.substringBefore(";")

                if (!sessionCookie.isNullOrEmpty()) {
                    sessionManager.saveSession(sessionCookie)
                    sessionManager.saveUsername(username)
                    // 保存到账号列表供后续切换（FEATURE-003）
                    sessionManager.saveAccountToList(username, sessionCookie)
                    Result.success(username)
                } else {
                    Result.failure(AppError.Business("登录失败，服务器未返回 session cookie"))
                }
            } catch (e: Exception) {
                Result.failure(AppError.from(e))
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
                    return@withContext Result.failure(
                        AppError.Business(context.getString(R.string.error_passwords_not_match))
                    )
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
                        Result.failure(AppError.Business(extractRegisterError(bodyString, context)))
                    }

                    statusCode == 200 -> {
                        Result.failure(AppError.Business(extractRegisterError(bodyString, context)))
                    }

                    else -> {
                        Result.failure(AppError.Server(statusCode, context.getString(R.string.register_failed, statusCode)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(AppError.from(e))
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
     * 清除会话并清理该账号的本地数据（FEATURE-003 数据边界）。
     */
    suspend fun logout(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            // 清除当前账号的本地数据（收藏/历史/下载）以实现数据隔离
            LocalDataCleaner.clearAllLocalData(context)
            sessionManager.clearSession()
            ApiClient.clearMemoryCache()
            Result.success(Unit)
        }
    }

    /**
     * 切换到指定账号（FEATURE-003）。
     * 清除当前账号本地数据后切换到目标账号。
     */
    suspend fun switchAccount(username: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            // 清除当前账号的本地数据
            LocalDataCleaner.clearAllLocalData(context)
            val success = sessionManager.switchToAccount(username)
            if (success) {
                ApiClient.clearMemoryCache()
                Result.success(Unit)
            } else {
                Result.failure(AppError.Business("账号不存在"))
            }
        }
    }

    /**
     * 获取所有已保存的账号列表（FEATURE-003）。
     */
    fun getSavedAccounts(): Map<String, String> = sessionManager.getSavedAccounts()

    /**
     * 从账号列表中移除指定账号（FEATURE-003）。
     */
    fun removeSavedAccount(username: String) {
        sessionManager.removeAccount(username)
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
            Result.failure(AppError.Auth(AppError.Auth.Reason.NOT_LOGGED_IN))
        } else {
            try {
                val api = getAuthenticatedApi()
                block(api)
            } catch (e: Exception) {
                Result.failure(AppError.from(e))
            }
        }
    }

    private fun <T> apiResultToResult(apiResult: ApiResult<T>): Result<T> {
        // 统一使用 AppError 包装错误，UI 层可直接通过 toMessage() 获取友好文案（P2 BACKEND-007）
        return when (apiResult) {
            is ApiResult.Success -> Result.success(apiResult.data)
            is ApiResult.Error.NetworkError -> Result.failure(
                AppError.from(apiResult.cause ?: Exception(apiResult.message))
            )
            is ApiResult.Error.AuthError -> {
                sessionManager.clearSession()
                Result.failure(AppError.Auth(AppError.Auth.Reason.SESSION_EXPIRED))
            }
            is ApiResult.Error.ServerError -> {
                if (apiResult.code == 401) {
                    sessionManager.clearSession()
                    Result.failure(AppError.Auth(AppError.Auth.Reason.SESSION_EXPIRED))
                } else {
                    Result.failure(AppError.Server(apiResult.code, apiResult.message))
                }
            }
            is ApiResult.Error.UnknownError -> Result.failure(
                AppError.from(apiResult.cause)
            )
        }
    }
}
