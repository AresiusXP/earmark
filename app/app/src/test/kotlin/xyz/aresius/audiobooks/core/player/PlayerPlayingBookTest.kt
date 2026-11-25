package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.aresius.audiobooks.testutil.FakePlayerControls

class PlayerPlayingBookTest {
    @Test fun reportsTheBookThePlayerHoldsAndNothingWhenIdle() {
        val controls = FakePlayerControls()
        val playing = PlayerPlayingBook(controls)
        assertNull(playing.currentAsin())
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Ready, asin = "B0TEST")
        assertEquals("B0TEST", playing.currentAsin())
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Idle, asin = "B0TEST")
        assertNull(playing.currentAsin())
    }
}
