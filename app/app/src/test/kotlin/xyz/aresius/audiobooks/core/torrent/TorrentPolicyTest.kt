package xyz.aresius.audiobooks.core.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentPolicyTest {
    private val on = TorrentSettings(p2pEnabled = true)
    private val wifiVpn = DeviceState(vpnActive = true, network = NetworkKind.WIFI, batteryPercent = 80, charging = false)
    private val policy = TorrentPolicy()

    private fun blocked(reason: PauseReason) = PolicyDecision.Blocked(reason)

    @Test fun `refuses without the opt-in whatever the device looks like`() {
        assertEquals(PolicyDecision.Disabled, policy.decide(TorrentSettings(p2pEnabled = false), wifiVpn))
        assertEquals(PolicyDecision.Disabled, policy.decide(TorrentSettings(p2pEnabled = false, requireVpn = false), wifiVpn))
    }

    @Test fun `defaults are VPN required, Wi-Fi only and no seeding`() {
        val d = TorrentSettings()
        assertFalse(d.p2pEnabled); assertTrue(d.requireVpn); assertFalse(d.allowMobileData); assertFalse(d.seedToRatio)
        assertEquals(2, d.maxActiveTorrents)
    }

    @Test fun `VPN up on Wi-Fi is allowed`() {
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn))
    }

    @Test fun `VPN drop blocks at once with VPN_REQUIRED, return allows again`() {
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn))
        assertEquals(blocked(PauseReason.VPN_REQUIRED), policy.decide(on, wifiVpn.copy(vpnActive = false)))
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn))
    }

    @Test fun `VPN requirement beats the network reason when both fail`() {
        assertEquals(blocked(PauseReason.VPN_REQUIRED), policy.decide(on, wifiVpn.copy(vpnActive = false, network = NetworkKind.NONE)))
    }

    @Test fun `without the VPN requirement no VPN is fine`() {
        assertEquals(PolicyDecision.Allowed, policy.decide(on.copy(requireVpn = false), wifiVpn.copy(vpnActive = false)))
    }

    @Test fun `mobile data needs the allow-mobile-data setting`() {
        val mobile = wifiVpn.copy(network = NetworkKind.MOBILE)
        assertEquals(blocked(PauseReason.WIFI_REQUIRED), policy.decide(on, mobile))
        assertEquals(PolicyDecision.Allowed, policy.decide(on.copy(allowMobileData = true), mobile))
    }

    @Test fun `offline is WIFI_REQUIRED even with mobile data allowed`() {
        assertEquals(blocked(PauseReason.WIFI_REQUIRED), policy.decide(on.copy(allowMobileData = true), wifiVpn.copy(network = NetworkKind.NONE)))
    }

    @Test fun `low battery pauses below 15 percent unless charging`() {
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn.copy(batteryPercent = 15)))
        assertEquals(blocked(PauseReason.LOW_BATTERY), policy.decide(on, wifiVpn.copy(batteryPercent = 14)))
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn.copy(batteryPercent = 5, charging = true)))
    }

    @Test fun `low battery has hysteresis and resumes at 20 percent or when charging`() {
        assertEquals(blocked(PauseReason.LOW_BATTERY), policy.decide(on, wifiVpn.copy(batteryPercent = 14)))
        assertEquals(blocked(PauseReason.LOW_BATTERY), policy.decide(on, wifiVpn.copy(batteryPercent = 17)))
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn.copy(batteryPercent = 20)))
        assertEquals(blocked(PauseReason.LOW_BATTERY), policy.decide(on, wifiVpn.copy(batteryPercent = 10)))
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn.copy(batteryPercent = 10, charging = true)))
        assertEquals(blocked(PauseReason.LOW_BATTERY), policy.decide(on, wifiVpn.copy(batteryPercent = 10)))
    }

    @Test fun `unknown battery never blocks`() {
        assertEquals(PolicyDecision.Allowed, policy.decide(on, wifiVpn.copy(batteryPercent = -1)))
    }

    @Test fun `seeding needs the setting, Wi-Fi, charging and an allowed session`() {
        val seed = on.copy(seedToRatio = true)
        val charging = wifiVpn.copy(charging = true)
        assertTrue(policy.seedingAllowed(seed, charging))
        assertFalse(policy.seedingAllowed(on, charging))
        assertFalse(policy.seedingAllowed(seed, wifiVpn))
        assertFalse(policy.seedingAllowed(seed, charging.copy(network = NetworkKind.MOBILE)))
        assertFalse(policy.seedingAllowed(seed.copy(allowMobileData = true), charging.copy(network = NetworkKind.MOBILE)))
        assertFalse(policy.seedingAllowed(seed, charging.copy(vpnActive = false)))
        assertFalse(policy.seedingAllowed(seed.copy(p2pEnabled = false), charging))
    }

    @Test fun `state machine walk-through`() {
        var s = on
        var d = wifiVpn
        val seen = mutableListOf<PolicyDecision>()
        fun step() { seen += policy.decide(s, d) }
        step()
        d = d.copy(vpnActive = false); step()
        d = d.copy(vpnActive = true, network = NetworkKind.MOBILE); step()
        s = s.copy(allowMobileData = true); step()
        d = d.copy(batteryPercent = 9); step()
        d = d.copy(charging = true); step()
        s = s.copy(p2pEnabled = false); step()
        assertEquals(
            listOf(
                PolicyDecision.Allowed, blocked(PauseReason.VPN_REQUIRED), blocked(PauseReason.WIFI_REQUIRED), PolicyDecision.Allowed,
                blocked(PauseReason.LOW_BATTERY), PolicyDecision.Allowed, PolicyDecision.Disabled,
            ),
            seen,
        )
    }
}
