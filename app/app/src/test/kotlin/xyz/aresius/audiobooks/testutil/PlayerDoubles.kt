package xyz.aresius.audiobooks.testutil

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.player.PlayerControls
import xyz.aresius.audiobooks.core.player.PlayerState

/** Records what the screens ask of the player. */
class FakePlayerControls(var remembered: String? = null) : PlayerControls {
    val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState
    val plays = ArrayList<Triple<String, String?, Book?>>()
    val calls = ArrayList<String>()

    override fun play(asin: String, releaseHash: String?, book: Book?, autoplay: Boolean) { plays.add(Triple(asin, releaseHash, book)) }
    override suspend fun rememberedRelease(asin: String) = remembered
    override fun togglePlay() { calls.add("toggle") }
    override fun pause() { calls.add("pause") }
    override fun skipBack() { calls.add("skipBack") }
    override fun skipForward() { calls.add("skipForward") }
    override fun nextChapter() { calls.add("next") }
    override fun previousChapter() { calls.add("previous") }
    override fun seekTo(positionMs: Long) { calls.add("seek:$positionMs") }
    override fun setSpeed(speed: Float) { calls.add("speed:$speed") }
    override fun startSleepTimer(minutes: Int) { calls.add("sleep:$minutes") }
    override fun cancelSleepTimer() { calls.add("sleepCancel") }
    override fun extendSleepTimer() { calls.add("sleepExtend") }
    override fun retry() { calls.add("retry") }
    override fun close() { calls.add("close") }
}

class FakeLocalFiles(downloads: Boolean = false) : LocalFiles {
    val has = MutableStateFlow(downloads)
    override fun localFile(asin: String, releaseHash: String, path: String): File? = null
    override fun hasDownloads(asin: String): Flow<Boolean> = has
}
