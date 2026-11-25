package xyz.aresius.audiobooks.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class SignOutReason { UserRequested, RefreshRejected }

/** Emitted whenever the session is cleared (the shell also reacts to the SessionStore flow becoming null). */
@Singleton
class SignedOutEvents @Inject constructor() {
    private val _events = MutableSharedFlow<SignOutReason>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SignOutReason> = _events.asSharedFlow()
    fun emit(reason: SignOutReason) {
        _events.tryEmit(reason)
    }
}
