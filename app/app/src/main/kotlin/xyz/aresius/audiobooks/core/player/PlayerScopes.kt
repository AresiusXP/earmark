package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-lifetime scope for writes (progress, durations) that must finish even when the service is destroyed right after. */
@Singleton
class PlayerScopes @Inject constructor() {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
