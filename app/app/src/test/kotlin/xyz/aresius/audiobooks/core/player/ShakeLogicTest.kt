package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShakeLogicTest {
    private val strong = 40f
    private val rest = 9.8f

    @Test
    fun `a phone lying still never counts`() {
        val logic = ShakeLogic()
        assertFalse((0..500).any { logic.onSample(0f, 0f, rest, it * 20L) })
    }

    @Test
    fun `three jolts within a second are a shake, then it is quiet for two seconds`() {
        val logic = ShakeLogic()
        assertFalse(logic.onSample(strong, 0f, 0f, 0))
        assertFalse(logic.onSample(-strong, 0f, 0f, 200))
        assertTrue(logic.onSample(strong, 0f, 0f, 400))
        assertFalse(logic.onSample(strong, 0f, 0f, 600))
        assertFalse(logic.onSample(-strong, 0f, 0f, 900))
        assertFalse(logic.onSample(strong, 0f, 0f, 1200))
    }

    @Test
    fun `slow jolts spread over more than a second do not count, and one jolt is a single peak`() {
        val logic = ShakeLogic()
        assertFalse(logic.onSample(strong, 0f, 0f, 0))
        assertFalse(logic.onSample(strong, 0f, 0f, 50), "a long jolt is one peak")
        assertFalse(logic.onSample(strong, 0f, 0f, 700))
        assertFalse(logic.onSample(strong, 0f, 0f, 1500))
    }
}
