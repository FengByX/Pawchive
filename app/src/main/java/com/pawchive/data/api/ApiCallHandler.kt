package com.pawchive.data.api

import retrofit2.Response
import java.io.IOException

object ApiCallHandler {

    suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
        return try {
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
            val result = call()
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

    private fun <T> parseErrorBody(response: Response<T>): String {
        return try {
            response.errorBody()?.string()?.take(200) ?: response.message()
        } catch (e: Exception) {
            response.message()
        }
    }
}
