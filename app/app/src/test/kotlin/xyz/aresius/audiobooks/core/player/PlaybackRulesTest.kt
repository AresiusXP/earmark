package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRulesTest {
    @Test
    fun `smart rewind only after more than a minute and grows with the pause`() {
        assertEquals(0, PlaybackRules.smartRewindMs(0))
        assertEquals(0, PlaybackRules.smartRewindMs(60_000))
        assertEquals(5_000, PlaybackRules.smartRewindMs(60_001))
        assertEquals(5_000, PlaybackRules.smartRewindMs(10 * 60_000))
        assertEquals(10_000, PlaybackRules.smartRewindMs(30 * 60_000))
        assertEquals(15_000, PlaybackRules.smartRewindMs(3 * 3_600_000L))
    }

    @Test
    fun `speed is clamped to the contract and snapped to a 0,05 grid`() {
        assertEquals(0.5f, PlaybackRules.clampSpeed(0.1f))
        assertEquals(3.0f, PlaybackRules.clampSpeed(9f))
        assertEquals(1.25f, PlaybackRules.clampSpeed(1.2499f))
        assertEquals(1.05f, PlaybackRules.clampSpeed(1.0f + 0.05f))
        var s = 1.0f
        repeat(40) { s = PlaybackRules.clampSpeed(s + 0.05f) }
        assertEquals(3.0f, s)
        repeat(100) { s = PlaybackRules.clampSpeed(s - 0.1f) }
        assertEquals(0.5f, s)
    }

    @Test
    fun `skip lengths are the offered ones`() {
        assertEquals(listOf(10, 15, 30, 45, 60), PlaybackRules.SKIP_OPTIONS_S)
        assertEquals(45, PlaybackRules.validSkip(45))
        assertEquals(30, PlaybackRules.validSkip(7))
    }

    @Test
    fun `finished rules for a long and a short book`() {
        val hour = 3_600_000L
        assertFalse(PlaybackRules.isFinished(hour - 40_000, hour * 100))
        assertTrue(PlaybackRules.isFinished(hour * 100 - 30_000, hour * 100))
        assertTrue(PlaybackRules.isFinished((hour * 100 * 0.995).toLong(), hour * 100))
        assertFalse(PlaybackRules.isFinished((hour * 0.9).toLong(), hour))
        // A 60 s sample is not finished at 30 s: the tail is at most 5 % of a short book.
        assertFalse(PlaybackRules.isFinished(30_000, 60_000))
        assertTrue(PlaybackRules.isFinished(57_500, 60_000))
        assertFalse(PlaybackRules.isFinished(1, 0))
    }
}
