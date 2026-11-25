package xyz.aresius.audiobooks.feature.common

import androidx.lifecycle.SavedStateHandle
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult

/** State of one independently loaded piece of data (a screen or a section of a screen). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    /** [stale] = served from the cache while the network failed. */
    data class Content<T>(val value: T, val stale: Boolean = false) : Load<T>

    data class Failed(val error: ApiError) : Load<Nothing>
}

val <T> Load<T>.valueOrNull: T? get() = (this as? Load.Content)?.value

/** A failure keeps the previous content (marked stale) so a refresh never blanks a screen that has data. */
fun <T> ApiResult<T>.toLoad(previous: Load<T>? = null): Load<T> = when (this) {
    is ApiResult.Success -> Load.Content(value, stale)
    is ApiResult.Failure -> if (previous is Load.Content) previous.copy(stale = true) else Load.Failed(error)
}

/**
 * The `asin` argument of Routes.Book. Typed routes store each argument under its property name, so reading it directly equals
 * `toRoute<...>().asin` while staying testable on the JVM.
 */
fun SavedStateHandle.routeAsin(): String = checkNotNull(get<String>("asin")) { "missing route argument 'asin'" }
