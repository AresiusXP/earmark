package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerLogicTest {
    private var now = 0L
    private val timer = SleepTimerLogic { now }
    private val chapterEnd: (Long) -> Long? = { p -> if (p < 600_000) 600_000 else 1_200_000 }

    private fun tick(position: Long = 0, speed: Float = 1f) = timer.tick(position, speed, chapterEnd)

    @Test
    fun `off by default`() {
        assertEquals(SleepTick(1f, false), tick())
        assertEquals(SleepState.Off, timer.state(0, 1f))
    }

    @Test
    fun `minutes run on the clock, fade over the last ten seconds and fire at zero`() {
        timer.start(SleepSetting.Minutes(5), 0, 600_000)
        now = 289_000
        assertEquals(SleepTick(1f, false), tick())
        now = 290_000
        assertEquals(1f, tick().volume, 0.001f)
        now = 295_000
        assertEquals(0.5f, tick().volume, 0.001f)
        assertTrue((timer.state(0, 1f) as SleepState.Running).fading)
        now = 299_000
        assertEquals(0.1f, tick().volume, 0.001f)
        now = 300_000
        assertEquals(SleepTick(1f, true), tick())
        assertFalse(timer.isRunning)
    }

    @Test
    fun `extending a minutes timer starts it over with its full length`() {
        timer.start(SleepSetting.Minutes(10), 0, null)
        now = 595_000
        assertTrue(tick().volume < 1f)
        timer.extend(null)
        assertEquals(1f, tick().volume, 0.001f)
        now = 595_000 + 599_000
        assertFalse(tick().fire)
        now += 1_001
        assertTrue(tick().fire)
    }

    @Test
    fun `end of chapter counts the wall time left at the current speed`() {
        timer.start(SleepSetting.EndOfChapter, 0, 600_000)
        assertEquals(1f, tick(position = 500_000).volume, 0.001f)
        // 2 s of content left at 2x = 1 s of wall time: deep in the fade.
        val t = tick(position = 598_000, speed = 2f)
        assertEquals(0.1f, t.volume, 0.001f)
        assertFalse(t.fire)
        assertTrue(tick(position = 600_000).fire)
    }

    @Test
    fun `end of chapter fires when playback reaches the end, also a little past it`() {
        timer.start(SleepSetting.EndOfChapter, 590_000, 600_000)
        assertTrue(tick(position = 601_000).fire)
    }

    @Test
    fun `skipping far past the chapter end re-arms for the chapter the listener is in now`() {
        timer.start(SleepSetting.EndOfChapter, 100_000, 600_000)
        val t = tick(position = 800_000)
        assertFalse(t.fire)
        assertTrue(timer.isRunning)
        assertEquals(1f, t.volume, 0.001f)
        assertTrue(tick(position = 1_200_000).fire)
    }

    @Test
    fun `extending end of chapter moves to the end of the next chapter`() {
        timer.start(SleepSetting.EndOfChapter, 0, 600_000)
        timer.extend(1_200_000)
        assertFalse(tick(position = 601_000).fire)
        assertTrue(tick(position = 1_200_000).fire)
    }

    @Test
    fun `cancel stops everything and the state reports the remaining time`() {
        timer.start(SleepSetting.Minutes(15), 0, null)
        now = 60_000
        val running = timer.state(0, 1f) as SleepState.Running
        assertEquals(14 * 60_000L, running.remainingMs)
        assertEquals(SleepSetting.Minutes(15), running.setting)
        timer.cancel()
        assertEquals(SleepState.Off, timer.state(0, 1f))
        assertFalse(tick().fire)
    }

    @Test
    fun `fade volume is linear and clamped`() {
        assertEquals(1f, SleepTimerLogic.volumeFor(60_000))
        assertEquals(0f, SleepTimerLogic.volumeFor(-5))
        assertEquals(0.3f, SleepTimerLogic.volumeFor(3_000), 0.0001f)
    }
}
