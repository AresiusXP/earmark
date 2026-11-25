package xyz.aresius.audiobooks.core.torrent

import kotlinx.coroutines.flow.StateFlow

/** What the app's default network is: [WIFI] is any unmetered LAN-like link (Wi-Fi, Ethernet), [MOBILE] everything metered. */
enum class NetworkKind { NONE, WIFI, MOBILE }

data class DeviceState(
    /** The network this app's traffic uses by default is a VPN. */
    val vpnActive: Boolean,
    val network: NetworkKind,
    /** 0..100, or -1 when unknown. */
    val batteryPercent: Int,
    val charging: Boolean,
)

/** Source of [DeviceState]; Android implementation in AndroidDeviceStateMonitor, a fake in tests. */
interface DeviceStateMonitor {
    val state: StateFlow<DeviceState>
}

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    /** The user did not opt in: the engine refuses to start. */
    data object Disabled : PolicyDecision

    /** Every torrent and the session itself are paused until the policy allows again. */
    data class Blocked(val reason: PauseReason) : PolicyDecision
}

/**
 * The safety policy, evaluated INSIDE the engine. Order: opt-in, VPN, network (Wi-Fi / mobile data), battery.
 * Battery has hysteresis: pause below [LOW_BATTERY_PERCENT] unless charging, resume at [RESUME_BATTERY_PERCENT] or when charging starts.
 */
class TorrentPolicy {
    private var lowBatteryLatched = false

    @Synchronized
    fun decide(settings: TorrentSettings, device: DeviceState): PolicyDecision {
        if (!settings.p2pEnabled) return PolicyDecision.Disabled
        if (settings.requireVpn && !device.vpnActive) return PolicyDecision.Blocked(PauseReason.VPN_REQUIRED)
        val networkOk = when (device.network) {
            NetworkKind.WIFI -> true
            NetworkKind.MOBILE -> settings.allowMobileData
            NetworkKind.NONE -> false
        }
        if (!networkOk) return PolicyDecision.Blocked(PauseReason.WIFI_REQUIRED)
        if (lowBattery(device)) return PolicyDecision.Blocked(PauseReason.LOW_BATTERY)
        return PolicyDecision.Allowed
    }

    /** Seeding after the download is allowed only when the user asked for it, on an unmetered network, while charging. */
    @Synchronized
    fun seedingAllowed(settings: TorrentSettings, device: DeviceState): Boolean =
        settings.seedToRatio && device.network == NetworkKind.WIFI && device.charging && decideNoLatch(settings, device)

    private fun decideNoLatch(settings: TorrentSettings, device: DeviceState): Boolean =
        settings.p2pEnabled && (!settings.requireVpn || device.vpnActive)

    private fun lowBattery(device: DeviceState): Boolean {
        val pct = device.batteryPercent
        lowBatteryLatched = when {
            pct < 0 || device.charging -> false
            pct < LOW_BATTERY_PERCENT -> true
            pct >= RESUME_BATTERY_PERCENT -> false
            else -> lowBatteryLatched
        }
        return lowBatteryLatched
    }

    companion object {
        const val LOW_BATTERY_PERCENT = 15
        const val RESUME_BATTERY_PERCENT = 20
    }
}
