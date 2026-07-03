package com.pawchive.data.api

sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()

    sealed class Error : ApiResult<Nothing>() {
        data class NetworkError(
            val message: String,
            val cause: Throwable? = null
        ) : Error()

        data class AuthError(
            val message: String
        ) : Error()

        data class ServerError(
            val code: Int,
            val message: String
        ) : Error()

        data class UnknownError(
            val cause: Throwable
        ) : Error()
    }
}
