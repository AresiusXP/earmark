package xyz.aresius.audiobooks.core.torrent

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engine's current [PolicyDecision] and the one lock that serialises it with everything that can start network activity.
 *
 * A policy change ([update]: set the decision AND apply it to the session) and every start of a torrent or of the session
 * ([ifAllowed]: check the decision AND resume) run under the same [lock]. A resume therefore either happens completely before a flip
 * (the flip then pauses it) or sees the new decision and does not happen: there is no window in which a torrent is resumed after the
 * session was paused for a blocked policy.
 */
internal class PolicyGate {
    val lock = ReentrantLock()
    private val current = MutableStateFlow<PolicyDecision?>(null)
    private val changes = MutableStateFlow(0L)

    /** Null until the first evaluation (treated as blocked wherever it matters). */
    val decision: StateFlow<PolicyDecision?> = current.asStateFlow()

    /** Increments on every change of the decision; collectors wait for it to leave the version they saw. */
    val version: StateFlow<Long> = changes.asStateFlow()

    val allowed: Boolean get() = current.value == PolicyDecision.Allowed

    /** Sets [next] and runs [apply] (the session side of it, always, also when the decision did not change) before another resume can run. */
    fun update(next: PolicyDecision, apply: (PolicyDecision) -> Unit) = lock.withLock {
        if (next != current.value) {
            current.value = next
            changes.value++
        }
        apply(next)
    }

    /** Runs [block] under the lock when the policy allows right now; null (and nothing run) when it does not. */
    inline fun <T : Any> ifAllowed(block: () -> T): T? = lock.withLock { if (allowed) block() else null }
}

/** The switches of libtorrent that put traffic on the network, derived from the policy: a blocked policy turns every one of them off. */
internal data class NetworkSwitches(
    val dht: Boolean,
    val lsd: Boolean,
    val incomingTcp: Boolean,
    val incomingUtp: Boolean,
    val upnp: Boolean,
    val natpmp: Boolean,
    /** Empty = no listen socket at all. */
    val listenInterfaces: String,
) {
    val anyEnabled: Boolean get() = dht || lsd || incomingTcp || incomingUtp || upnp || natpmp || listenInterfaces.isNotEmpty()

    companion object {
        fun of(allowed: Boolean, config: EngineConfig) = NetworkSwitches(
            dht = allowed && config.enableDht,
            lsd = allowed && config.enableLsd,
            incomingTcp = allowed,
            incomingUtp = allowed && config.enableUtp,
            upnp = allowed && config.enablePortMaps,
            natpmp = allowed && config.enablePortMaps,
            listenInterfaces = if (allowed) config.listenInterfaces else "",
        )
    }
}
