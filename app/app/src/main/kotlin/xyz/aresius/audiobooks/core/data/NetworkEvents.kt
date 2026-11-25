package xyz.aresius.audiobooks.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** "A network that can reach the internet just became available": the screens that show a saved copy ask the server again. */
interface NetworkEvents {
    val available: SharedFlow<Unit>
}

object NoNetworkEvents : NetworkEvents {
    override val available: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()
}

@Singleton
class AndroidNetworkEvents @Inject constructor(@ApplicationContext context: Context) : NetworkEvents {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val available: SharedFlow<Unit> = events.asSharedFlow()

    init {
        try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    events.tryEmit(Unit)
                }

                // The first answer of a new network often says "no internet yet" and the capabilities arrive afterwards.
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) events.tryEmit(Unit)
                }
            })
        } catch (_: Exception) {
            // Cannot watch: the screens still refresh when they come back to the foreground.
        }
    }
}
