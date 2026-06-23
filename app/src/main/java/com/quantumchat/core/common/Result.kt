package com.quantumchat.core.common

sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>
    data class Error(val exception: Throwable) : Result<Nothing>
    object Loading : Result<Nothing>
}
