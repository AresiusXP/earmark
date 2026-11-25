package xyz.aresius.audiobooks.core.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Book-level custom commands of the media session (the standard play/pause/seek/next/previous/speed come with the session itself).
 * The app's UI uses them through [PlaybackController]; a car or a watch (Phase 5) can send the same ones.
 */
object PlayerCommands {
    private const val P = "xyz.aresius.audiobooks.player."
    const val LOAD = P + "LOAD"
    const val SEEK_BOOK = P + "SEEK_BOOK"
    const val SET_SPEED = P + "SET_SPEED"
    const val SKIP_BACK = P + "SKIP_BACK"
    const val SKIP_FORWARD = P + "SKIP_FORWARD"
    const val SLEEP_START = P + "SLEEP_START"
    const val SLEEP_CANCEL = P + "SLEEP_CANCEL"
    const val SLEEP_EXTEND = P + "SLEEP_EXTEND"
    const val RETRY = P + "RETRY"
    const val CLOSE = P + "CLOSE"

    const val KEY_ASIN = "asin"
    const val KEY_RELEASE = "release_hash"
    const val KEY_BOOK_JSON = "book_json"
    const val KEY_AUTOPLAY = "autoplay"
    const val KEY_POSITION_MS = "position_ms"
    const val KEY_SPEED = "speed"

    /** `minutes` of a sleep timer; [END_OF_CHAPTER] waits for the end of the current chapter. */
    const val KEY_MINUTES = "minutes"
    const val END_OF_CHAPTER = 0

    val ALL: List<SessionCommand> = listOf(LOAD, SEEK_BOOK, SET_SPEED, SKIP_BACK, SKIP_FORWARD, SLEEP_START, SLEEP_CANCEL, SLEEP_EXTEND, RETRY, CLOSE)
        .map { SessionCommand(it, Bundle.EMPTY) }
}
