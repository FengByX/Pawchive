package com.pawchive.data.repository

import android.content.Context
import com.pawchive.R
import com.pawchive.data.api.PawchiveApi
import com.pawchive.data.api.PawchiveLoginApi
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
     */
    suspend fun login(username: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val loginApi = PawchiveLoginApi.create()
                val response = loginApi.login(username, password).execute()
                
                // 登录成功后通常会重定向（302），我们从响应头中提取 cookie
                val cookies = response.headers().values("Set-Cookie")
                val sessionCookie = cookies.find { it.startsWith("session=") }
                    ?.substringAfter("session=")
                    ?.substringBefore(";")
                
                if (sessionCookie != null) {
                    sessionManager.saveSession(sessionCookie)
                    sessionManager.saveUsername(username)
                    Result.success(username)
                } else {
                    // 如果没有从 Set-Cookie 中获取到，可能是登录失败
                    // 检查响应码，302 通常表示登录成功并重定向
                    val isRedirect = response.code() in 300..399
                    if (isRedirect) {
                        Result.failure(Exception("登录成功但未找到 session cookie"))
                    } else {
                        Result.failure(Exception("登录失败，请检查用户名和密码"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 登出
     */
    suspend fun logout(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                sessionManager.clearSession()
                Result.success(Unit)
            } catch (e: Exception) {
                sessionManager.clearSession()
                Result.failure(e)
            }
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
            PawchiveApi.createWithSession(cookie)
        } else {
            PawchiveApi.create()
        }
    }

    /**
     * 同步账号收藏的帖子
     */
    suspend fun syncFavoritePosts(offset: Int? = null): Result<List<FavoritePost>> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val favorites = api.getFavoritePosts(offset = offset)
                Result.success(favorites)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 同步账号收藏的创作者
     */
    suspend fun syncFavoriteCreators(): Result<List<FavoriteCreator>> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val favorites = api.getFavoriteCreators()
                Result.success(favorites)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 添加帖子到账号收藏
     */
    suspend fun addPostToFavorites(service: String, creatorId: String, postId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val response = api.addPostToFavorites(service, creatorId, postId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()?.take(100) ?: ""
                    Result.failure(Exception("HTTP ${response.code()}: ${response.message()} $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 从账号收藏移除帖子
     */
    suspend fun removePostFromFavorites(service: String, creatorId: String, postId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val response = api.removePostFromFavorites(service, creatorId, postId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()?.take(100) ?: ""
                    Result.failure(Exception("HTTP ${response.code()}: ${response.message()} $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 添加创作者到账号收藏
     */
    suspend fun addCreatorToFavorites(service: String, creatorId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val response = api.addCreatorToFavorites(service, creatorId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()?.take(100) ?: ""
                    Result.failure(Exception("HTTP ${response.code()}: ${response.message()} $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 从账号收藏移除创作者
     */
    suspend fun removeCreatorFromFavorites(service: String, creatorId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!sessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.error_not_logged_in)))
                }
                val api = getAuthenticatedApi()
                val response = api.removeCreatorFromFavorites(service, creatorId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()?.take(100) ?: ""
                    Result.failure(Exception("HTTP ${response.code()}: ${response.message()} $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}