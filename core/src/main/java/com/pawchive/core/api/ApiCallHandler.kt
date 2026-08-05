package com.pawchive.core.api

import com.pawchive.core.error.AppError
import retrofit2.Response
import java.io.IOException

object ApiCallHandler {

    suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
        return try {
            // ARCH-009：请求前确保已过盾（已有凭据时秒回），避免首次请求 403
            ClearanceCoordinator.ensureClearance()
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error.UnknownError(IllegalStateException("Response body is null"))
                }
            } else {
                val code = response.code()
                val errorMessage = parseErrorBody(response)
                when (code) {
                    401 -> ApiResult.Error.AuthError(errorMessage)
                    else -> ApiResult.Error.ServerError(code, errorMessage)
                }
            }
        } catch (e: IOException) {
            ApiResult.Error.NetworkError(
                message = e.message ?: "Network error",
                cause = e
            )
        } catch (e: Exception) {
            ApiResult.Error.UnknownError(e)
        }
    }

    suspend fun <T> safeApiCallDirect(call: suspend () -> T): ApiResult<T> {
        return try {
            // ARCH-009：直接调用（Retrofit suspend 接口）用 withClearance 包裹，
            // 请求前过盾 + 遇 403 强制刷新重试一次
            val result = CloudflareManager.withClearance { call() }
            ApiResult.Success(result)
        } catch (e: IOException) {
            ApiResult.Error.NetworkError(
                message = e.message ?: "Network error",
                cause = e
            )
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val message = e.message() ?: "HTTP Error"
            when (code) {
                401 -> ApiResult.Error.AuthError(message)
                else -> ApiResult.Error.ServerError(code, message)
            }
        } catch (e: Exception) {
            ApiResult.Error.UnknownError(e)
        }
    }

    suspend fun safeApiCallUnit(call: suspend () -> Response<Void>): ApiResult<Unit> {
        return try {
            // ARCH-009：请求前确保已过盾
            ClearanceCoordinator.ensureClearance()
            val response = call()
            if (response.isSuccessful) {
                ApiResult.Success(Unit)
            } else {
                val code = response.code()
                val errorMessage = parseErrorBody(response)
                when (code) {
                    401 -> ApiResult.Error.AuthError(errorMessage)
                    else -> ApiResult.Error.ServerError(code, errorMessage)
                }
            }
        } catch (e: IOException) {
            ApiResult.Error.NetworkError(
                message = e.message ?: "Network error",
                cause = e
            )
        } catch (e: Exception) {
            ApiResult.Error.UnknownError(e)
        }
    }

    // ==================== 统一错误类型 API（P2 BACKEND-007）====================
    // 新增直接返回 Result<T>、错误类型为 AppError 的便捷方法。
    // 旧 safeApiCall* 保留兼容现有调用，新代码应优先使用以下方法。

    /**
     * 执行 Retrofit Response 调用，成功返回 body，失败返回 [AppError]。
     * 自动识别 401/403/404/5xx 与网络异常。
     */
    suspend fun <T> runCatchingApi(call: suspend () -> Response<T>): Result<T> {
        return try {
            // ARCH-009：请求前确保已过盾
            ClearanceCoordinator.ensureClearance()
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(AppError.Unknown(IllegalStateException("Response body is null")))
                }
            } else {
                val code = response.code()
                val serverMessage = parseErrorBody(response)
                val error: AppError = when (code) {
                    401 -> AppError.Auth(AppError.Auth.Reason.SESSION_EXPIRED)
                    403 -> AppError.Server(403, serverMessage)
                    else -> AppError.Server(code, serverMessage)
                }
                Result.failure(error)
            }
        } catch (e: IOException) {
            Result.failure(AppError.from(e))
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * 执行直接返回数据的调用（非 Response 包装），失败返回 [AppError]。
     * 适用于 Retrofit suspend 接口直接返回数据类型的场景。
     */
    suspend fun <T> runCatchingDirect(call: suspend () -> T): Result<T> {
        return try {
            // ARCH-009：直接调用用 withClearance 包裹（请求前过盾 + 403 自动重试一次）
            Result.success(CloudflareManager.withClearance { call() })
        } catch (e: AppError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    private fun <T> parseErrorBody(response: Response<T>): String {
        return try {
            response.errorBody()?.string()?.take(200) ?: response.message()
        } catch (e: Exception) {
            response.message()
        }
    }
}
