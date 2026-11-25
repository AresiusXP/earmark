package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.media3.common.Player
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class TrustedControllersTest {
    private val own = 10123
    private val dir = mapOf(
        10200 to listOf("com.google.android.projection.gearhead"),
        10201 to listOf("com.evil.app"),
        10202 to listOf("com.evil.shared", "com.google.android.gms"),
        1002 to listOf("com.google.android.bluetooth"),
        1000 to listOf("android"),
        10300 to listOf("com.android.systemui"),
    )
    private val directory = CallerDirectory { uid -> dir[uid].orEmpty() }

    private fun access(pkg: String, uid: Int, trusted: Boolean = false, verified: Boolean = false, legacy: Boolean = false, d: CallerDirectory = directory) =
        TrustedControllers.decide(CallerClaim(pkg, uid, trusted, verified, legacy), own, d).access

    @Test fun `this app has full access, whatever package it claims`() {
        assertEquals(ControllerAccess.FULL, access("xyz.aresius.audiobooks", own))
        assertEquals(ControllerAccess.FULL, access("whatever", own))
    }

    @Test fun `an ordinary third party app gets nothing`() {
        assertEquals(ControllerAccess.NONE, access("com.evil.app", 10201))
        assertEquals(ControllerAccess.NONE, access("com.evil.app", 10201, legacy = true), "a legacy MediaController of a third party is no key press")
        assertEquals(ControllerAccess.NONE, access("com.evil.app", 10201, verified = true))
    }

    @Test fun `claiming an allowed package name from another uid does not work`() {
        assertEquals(ControllerAccess.NONE, access("com.google.android.projection.gearhead", 10201))
        assertEquals(ControllerAccess.NONE, access("com.android.systemui", 10201, legacy = true))
    }

    @Test fun `an allowed package whose name matches its uid has full access`() {
        assertEquals(ControllerAccess.FULL, access("com.google.android.projection.gearhead", 10200))
        assertEquals(ControllerAccess.FULL, access("com.android.systemui", 10300))
        assertEquals(ControllerAccess.FULL, access("com.google.android.bluetooth", 1002))
    }

    @Test fun `a verified package name is enough when the package list is invisible`() {
        assertEquals(ControllerAccess.FULL, access("com.google.android.projection.gearhead", 10999, verified = true, d = { emptyList() }))
        assertEquals(ControllerAccess.NONE, access("com.google.android.projection.gearhead", 10999, d = { emptyList() }))
    }

    @Test fun `a shared uid with an allowed package counts only when the claimed package is verified for that uid`() {
        assertEquals(ControllerAccess.FULL, access("com.evil.shared", 10202))
        assertEquals(ControllerAccess.NONE, access("com.other", 10202))
    }

    @Test fun `platform trust and the system uid have full access`() {
        assertEquals(ControllerAccess.FULL, access("anything", 10201, trusted = true))
        assertEquals(ControllerAccess.FULL, access("android", 1000))
    }

    @Test fun `a legacy media key caller under a platform uid gets the keys only`() {
        assertEquals(ControllerAccess.KEYS, access("com.unknown.bt", 1002, legacy = true, d = { emptyList() }))
        assertEquals(ControllerAccess.KEYS, access("x", 1002 + 200000, legacy = true, d = { emptyList() }), "per-user variant of a platform uid")
        assertEquals(ControllerAccess.NONE, access("com.unknown.bt", 1002, legacy = false, d = { emptyList() }), "a platform uid that is not a key press is not admitted")
    }

    @Test fun `an unknown caller (media button intent without a uid) gets nothing`() {
        assertEquals(ControllerAccess.NONE, access("", -1, legacy = true))
    }

    @Test fun `the allowlist holds the system UI, the Bluetooth stack and Android Auto`() {
        for (p in listOf("com.android.systemui", "com.android.bluetooth", "com.google.android.bluetooth", "com.google.android.projection.gearhead")) {
            assertTrue(p in TrustedControllers.ALLOWED_PACKAGES, p)
        }
    }
}

/** Player.Commands needs the Android framework (SparseBooleanArray). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KeyCommandsTest {
    @Test fun `the keys come with the playback commands and nothing else`() {
        val keys = KeyCommands.player
        assertTrue(keys.contains(Player.COMMAND_PLAY_PAUSE) && keys.contains(Player.COMMAND_SEEK_TO_NEXT) && keys.contains(Player.COMMAND_SEEK_BACK))
        assertFalse(keys.contains(Player.COMMAND_SET_MEDIA_ITEM) || keys.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) || keys.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
    }
}
