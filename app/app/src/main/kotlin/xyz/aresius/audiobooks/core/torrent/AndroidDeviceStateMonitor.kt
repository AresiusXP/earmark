package xyz.aresius.audiobooks.core.torrent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * [DeviceState] from the system. VPN and network kind come from the DEFAULT network callback, which on Android is per app: it
 * describes the network this app's sockets use, so a split-tunnel VPN that excludes the app reads as "no VPN". When the VPN
 * network disappears the callback fires at once and the engine pauses the whole session from that event.
 * Network kind: Wi-Fi / Ethernet = [NetworkKind.WIFI]; cellular, or anything metered = [NetworkKind.MOBILE]. A VPN network that does
 * not report its underlying transport is classified by the NOT_METERED capability it inherits.
 */
@Singleton
class AndroidDeviceStateMonitor @Inject constructor(@ApplicationContext private val context: Context) : DeviceStateMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val state: StateFlow<DeviceState> = callbackFlow {
        var caps: NetworkCapabilities? = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        var battery = readBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

        fun push() {
            trySend(DeviceState(vpnActive = caps.isVpn(), network = caps.kind(), batteryPercent = battery.first, charging = battery.second))
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                caps = networkCapabilities
                push()
            }

            override fun onLost(network: Network) {
                caps = null
                push()
            }
        }
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                battery = readBattery(intent)
                push()
            }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        push()
        awaitClose {
            connectivity.unregisterNetworkCallback(networkCallback)
            context.unregisterReceiver(batteryReceiver)
        }
    }.distinctUntilChanged().stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SharingStarted.Eagerly,
        DeviceState(vpnActive = false, network = NetworkKind.NONE, batteryPercent = -1, charging = false),
    )

    private fun NetworkCapabilities?.isVpn() = this?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun NetworkCapabilities?.kind(): NetworkKind = when {
        this == null || !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.WIFI
        else -> NetworkKind.MOBILE
    }

    private fun readBattery(intent: Intent?): Pair<Int, Boolean> {
        if (intent == null) return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to charging
    }
}
