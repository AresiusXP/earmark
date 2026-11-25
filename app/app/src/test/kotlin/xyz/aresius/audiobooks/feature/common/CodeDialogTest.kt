package xyz.aresius.audiobooks.feature.common

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeDialogTest {
    @Test fun theCountdownReadsMinutesAndSecondsThenHours() {
        assertEquals("15:00", formatRemaining(Duration.ofMinutes(15)))
        assertEquals("14:59", formatRemaining(Duration.ofSeconds(899)))
        assertEquals("0:05", formatRemaining(Duration.ofSeconds(5)))
        assertEquals("0:00", formatRemaining(Duration.ofSeconds(-3)))
        assertEquals("1:05:09", formatRemaining(Duration.ofSeconds(3909)))
        assertEquals("23:59:59", formatRemaining(Duration.ofSeconds(86399)))
    }
}
