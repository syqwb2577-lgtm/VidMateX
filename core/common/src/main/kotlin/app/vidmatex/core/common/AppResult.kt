package app.vidmatex.core.common

/** Result type used across source adapters, media processing and repositories. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Error(val message: String, val recoverable: Boolean = false) : AppResult<Nothing>

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Error -> this
    }

    fun onSuccess(block: (T) -> Unit): AppResult<T> { if (this is Success) block(value); return this }
    fun onError(block: (Error) -> Unit): AppResult<T> { if (this is Error) block(this); return this }

    fun getOrNull(): T? = (this as? Success)?.value
}

inline fun <T> safeRun(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: Exception) {
    AppResult.Error(e.message ?: e.javaClass.simpleName, recoverable = false)
}

suspend inline fun <T> safeSuspend(crossinline block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: Exception) {
    AppResult.Error(e.message ?: e.javaClass.simpleName, recoverable = false)
}
