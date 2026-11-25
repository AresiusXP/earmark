package xyz.aresius.audiobooks.feature.player

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerFormatTest {
    @Test fun `clock shows hours only when there are some`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:07", formatClock(7_400))
        assertEquals("4:12", formatClock(252_000))
        assertEquals("59:59", formatClock(3_599_999))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("4:12:08", formatClock(15_128_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test fun `remaining has a minus and a tilde while estimated`() {
        assertEquals("-9:31:40", formatRemaining(34_300_000))
        assertEquals("~-9:31:40", formatRemaining(34_300_000, estimated = true))
    }

    @Test fun `speed drops trailing zeros and follows the locale`() {
        assertEquals("1", formatSpeed(1.0f, Locale.US))
        assertEquals("1.25", formatSpeed(1.25f, Locale.US))
        assertEquals("1.5", formatSpeed(1.5f, Locale.US))
        assertEquals("0.55", formatSpeed(0.55f, Locale.US))
        assertEquals("1,25", formatSpeed(1.25f, Locale.forLanguageTag("nl-NL")))
        assertEquals("3", formatSpeed(3.0f, Locale.forLanguageTag("nl-NL")))
    }
}
