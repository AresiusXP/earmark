package xyz.aresius.audiobooks.core.player

import xyz.aresius.audiobooks.core.network.LibraryStatus

/** Small pure rules of the player, kept apart so they are tested without any Android class. */
object PlaybackRules {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    /** Speeds are kept on a 0.05 grid inside 0.5..3.0 (what the contract accepts), so repeated nudges never drift. */
    fun clampSpeed(speed: Float): Float = (Math.round(speed.coerceIn(MIN_SPEED, MAX_SPEED) * 20f) / 20f).coerceIn(MIN_SPEED, MAX_SPEED)

    val SKIP_OPTIONS_S = listOf(10, 15, 30, 45, 60)
    const val DEFAULT_SKIP_S = 30

    fun validSkip(seconds: Int): Int = if (seconds in SKIP_OPTIONS_S) seconds else DEFAULT_SKIP_S

    /**
     * After a pause of more than a minute, a few seconds of the book are repeated so the listener finds their place again; the longer the
     * pause, the more. Returns the rewind in ms (0 for a short pause).
     */
    fun smartRewindMs(pausedMs: Long): Long = when {
        pausedMs <= 60_000L -> 0
        pausedMs <= 10 * 60_000L -> 5_000
        pausedMs <= 60 * 60_000L -> 10_000
        else -> 15_000
    }

    /** Finished when the last 30 s (or 5 % of a short book) or 99 % has been played. */
    fun isFinished(positionMs: Long, totalMs: Long): Boolean {
        if (totalMs <= 0) return false
        val remaining = totalMs - positionMs
        return remaining <= minOf(FINISH_TAIL_MS, totalMs / 20) || positionMs >= totalMs * 0.99
    }

    const val FINISH_TAIL_MS = 30_000L

    fun statusFor(positionMs: Long, totalMs: Long): String = if (isFinished(positionMs, totalMs)) LibraryStatus.FINISHED else LibraryStatus.LISTENING
}
