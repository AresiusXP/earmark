package xyz.aresius.audiobooks.core.player

/** What the user asked for. */
sealed interface SleepSetting {
    data class Minutes(val minutes: Int) : SleepSetting
    data object EndOfChapter : SleepSetting
}

/** What the UI shows. [remainingMs] is wall-clock time left; [fading] = the volume is going down. */
sealed interface SleepState {
    data object Off : SleepState
    data class Running(val setting: SleepSetting, val remainingMs: Long, val fading: Boolean) : SleepState
}

/** The answer of one tick: the volume to set (1 = full) and whether playback must stop now. */
data class SleepTick(val volume: Float, val fire: Boolean)

/**
 * Sleep timer without a player or an Android clock: the service feeds it the position of the book and the chapter ends, it answers with a
 * volume and "stop now". The last [FADE_MS] fade out. A minutes timer runs on the (monotonic) wall clock; "end of chapter" waits for a
 * book position and counts down the wall time that is left at the current [speed].
 */
class SleepTimerLogic(private val clock: () -> Long) {
    private var setting: SleepSetting? = null
    private var endsAtMs = 0L
    private var targetMs = 0L

    val isRunning: Boolean get() = setting != null

    fun start(setting: SleepSetting, bookMs: Long, chapterEndMs: Long?) {
        this.setting = setting
        when (setting) {
            is SleepSetting.Minutes -> endsAtMs = clock() + setting.minutes * 60_000L
            SleepSetting.EndOfChapter -> targetMs = chapterEndMs ?: Long.MAX_VALUE
        }
    }

    fun cancel() {
        setting = null
    }

    /** More time: a minutes timer starts over with its full length; "end of chapter" moves to the end of the next chapter ([nextChapterEndMs]). */
    fun extend(nextChapterEndMs: Long?) {
        when (val s = setting) {
            is SleepSetting.Minutes -> endsAtMs = clock() + s.minutes * 60_000L
            SleepSetting.EndOfChapter -> nextChapterEndMs?.let { targetMs = it }
            null -> Unit
        }
    }

    /**
     * [chapterEndAt] gives the end of the chapter that contains a position. When the book position is already past the target by more than a
     * tick's worth (the user skipped ahead), "end of chapter" re-arms for the chapter the user is in now instead of firing.
     */
    fun tick(bookMs: Long, speed: Float, chapterEndAt: (Long) -> Long?): SleepTick {
        val s = setting ?: return SleepTick(1f, false)
        val remaining = when (s) {
            is SleepSetting.Minutes -> endsAtMs - clock()
            SleepSetting.EndOfChapter -> {
                if (bookMs > targetMs + overshootMs(speed)) targetMs = chapterEndAt(bookMs) ?: Long.MAX_VALUE
                ((targetMs - bookMs) / speed.coerceAtLeast(0.1f)).toLong()
            }
        }
        if (remaining <= 0) {
            setting = null
            return SleepTick(1f, true)
        }
        return SleepTick(volumeFor(remaining), false)
    }

    fun state(bookMs: Long, speed: Float): SleepState {
        val s = setting ?: return SleepState.Off
        val remaining = when (s) {
            is SleepSetting.Minutes -> endsAtMs - clock()
            SleepSetting.EndOfChapter -> ((targetMs - bookMs) / speed.coerceAtLeast(0.1f)).toLong()
        }.coerceAtLeast(0)
        return SleepState.Running(s, remaining, fading = remaining <= FADE_MS)
    }

    private fun overshootMs(speed: Float) = (3_000 * speed).toLong()

    companion object {
        const val FADE_MS = 10_000L
        val MINUTE_OPTIONS = listOf(5, 10, 15, 30, 45, 60)

        /** Linear fade over the last [FADE_MS]. */
        fun volumeFor(remainingMs: Long): Float = if (remainingMs >= FADE_MS) 1f else (remainingMs.coerceAtLeast(0) / FADE_MS.toFloat())
    }
}
