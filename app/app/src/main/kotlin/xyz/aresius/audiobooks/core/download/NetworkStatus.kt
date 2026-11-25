package xyz.aresius.audiobooks.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone's default network is: nothing usable, an unmetered one (Wi-Fi) or a metered one (mobile data, a metered hotspot). */
enum class NetKind { None, Unmetered, Metered }

interface NetworkStatus {
    val kind: StateFlow<NetKind>
}

/** Whether a download may run right now. */
sealed interface Gate {
    data object Go : Gate
    data class Wait(val reason: WaitReason) : Gate
}

object DownloadGate {
    /** Wi-Fi only unless the user also allows mobile data; [unmeteredOnly] (auto-download) ignores that setting. */
    fun check(net: NetKind, allowMobileData: Boolean, unmeteredOnly: Boolean = false): Gate = when {
        net == NetKind.None -> Gate.Wait(WaitReason.Network)
        net == NetKind.Metered && (unmeteredOnly || !allowMobileData) -> Gate.Wait(WaitReason.WiFi)
        else -> Gate.Go
    }
}

@Singleton
class AndroidNetworkStatus @Inject constructor(@ApplicationContext context: Context) : NetworkStatus {
    private val manager: ConnectivityManager? = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
    private val state = MutableStateFlow(compute())
    override val kind: StateFlow<NetKind> = state

    init {
        try {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null

                override fun onAvailable(network: Network) {
                    current = network
                    state.value = compute()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    current = network
                    state.value = classify(capabilities)
                }

                override fun onLost(network: Network) {
                    if (network == current) {
                        current = null
                        state.value = NetKind.None
                    }
                }
            })
        } catch (_: Exception) {
            // Cannot watch: keep the value computed at start.
        }
    }

    private fun compute(): NetKind = try {
        val m = manager
        val caps = m?.activeNetwork?.let { m.getNetworkCapabilities(it) }
        if (caps == null) NetKind.None else classify(caps)
    } catch (_: Exception) {
        NetKind.Metered // unknown: stay conservative, never use mobile data by accident
    }

    private fun classify(caps: NetworkCapabilities): NetKind = when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetKind.None
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetKind.Unmetered
        else -> NetKind.Metered
    }
}

/** Free bytes on the volume of the downloads directory. */
fun interface FreeSpace {
    fun freeBytes(): Long
}

class StatFsFreeSpace(private val dir: File) : FreeSpace {
    override fun freeBytes(): Long = try {
        dir.mkdirs()
        StatFs(dir.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}
