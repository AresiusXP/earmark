package xyz.aresius.audiobooks.core.torrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyGateTest {
    private val blocked = PolicyDecision.Blocked(PauseReason.VPN_REQUIRED)

    /** What libtorrent would be doing: the session pause flag and whether the networking switches are on. */
    private class FakeSession(val config: EngineConfig = EngineConfig()) {
        @Volatile var sessionPaused = false
        @Volatile var switches = NetworkSwitches.of(false, config)
        val resumedWhileBlocked = CopyOnWriteArrayList<String>()
        val resumes = CopyOnWriteArrayList<String>()

        fun apply(d: PolicyDecision) {
            val allowed = d == PolicyDecision.Allowed
            if (!allowed) sessionPaused = true
            switches = NetworkSwitches.of(allowed, config)
            if (allowed) sessionPaused = false
        }
    }

    @Test fun `a gate that was never evaluated allows nothing`() {
        val gate = PolicyGate()
        assertNull(gate.ifAllowed { "resumed" })
        assertFalse(gate.allowed)
    }

    @Test fun `blocked and disabled decisions switch every networking feature off`() {
        for (config in listOf(EngineConfig(), EngineConfig(enableDht = false, enableUtp = false))) {
            for (d in listOf(PolicyDecision.Disabled, blocked)) {
                val off = NetworkSwitches.of(d == PolicyDecision.Allowed, config)
                assertFalse(off.anyEnabled, "$d $config")
                assertEquals("", off.listenInterfaces)
            }
        }
        val on = NetworkSwitches.of(true, EngineConfig())
        assertTrue(on.dht && on.lsd && on.incomingTcp && on.incomingUtp && on.upnp && on.natpmp)
        assertEquals(EngineConfig().listenInterfaces, on.listenInterfaces)
        assertFalse(NetworkSwitches.of(true, EngineConfig(enableDht = false, enablePortMaps = false)).let { it.dht || it.upnp || it.natpmp })
    }

    @Test fun `a policy flip waits for a resume in progress and then pauses it`() {
        val gate = PolicyGate()
        val session = FakeSession()
        gate.update(PolicyDecision.Allowed, session::apply)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val resumer = thread {
            gate.ifAllowed {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                order += "resume"
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val flipper = thread { gate.update(blocked) { order += "pause"; session.apply(it) } }
        Thread.sleep(100)
        assertEquals(emptyList(), order.toList(), "the flip must not overtake the resume")
        release.countDown()
        resumer.join(5000); flipper.join(5000)
        assertEquals(listOf("resume", "pause"), order.toList(), "the pause comes after the resume, so the torrent ends up paused")
        assertTrue(session.sessionPaused)
        assertFalse(session.switches.anyEnabled)
    }

    @Test fun `once the policy is blocked no resume happens at all`() {
        val gate = PolicyGate()
        val session = FakeSession()
        gate.update(PolicyDecision.Allowed, session::apply)
        gate.update(blocked, session::apply)
        val ran = AtomicBoolean(false)
        assertNull(gate.ifAllowed { ran.set(true); "x" })
        assertFalse(ran.get())
        assertTrue(session.sessionPaused)
    }

    @Test fun `metadata fetch and download starts never resume while blocked, however the policy flips`() {
        val gate = PolicyGate()
        val session = FakeSession()
        gate.update(blocked, session::apply)
        val stop = AtomicBoolean(false)
        val starters = (1..4).map { id ->
            thread {
                var n = 0
                while (!stop.get()) {
                    // the shape of every start in the engine: check + resume under the policy lock, then use the handle
                    gate.ifAllowed {
                        if (session.sessionPaused || !session.switches.anyEnabled) session.resumedWhileBlocked += "starter $id #$n"
                        session.resumes += "starter $id"
                    }
                    n++
                }
            }
        }
        val decisions = listOf(PolicyDecision.Allowed, blocked, PolicyDecision.Allowed, PolicyDecision.Disabled)
        repeat(3000) { gate.update(decisions[it % decisions.size], session::apply) }
        gate.update(blocked, session::apply)
        stop.set(true)
        starters.forEach { it.join(5000) }
        assertEquals(emptyList(), session.resumedWhileBlocked.toList())
        assertTrue(session.resumes.isNotEmpty(), "the stress test must have started something while allowed")
        assertTrue(session.sessionPaused)
        assertFalse(session.switches.anyEnabled)
        val after = session.resumes.size
        assertNull(gate.ifAllowed { "late" })
        assertEquals(after, session.resumes.size)
    }

    @Test fun `version moves only when the decision changes`() {
        val gate = PolicyGate()
        gate.update(PolicyDecision.Allowed) {}
        val v = gate.version.value
        gate.update(PolicyDecision.Allowed) {}
        assertEquals(v, gate.version.value)
        gate.update(blocked) {}
        assertEquals(v + 1, gate.version.value)
    }
}
