package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.download.PlayingBook

/** Tells the storage rules which book the player holds, so the LRU eviction never deletes the files it is reading. */
@Singleton
class PlayerPlayingBook @Inject constructor(private val player: PlayerControls) : PlayingBook {
    override fun currentAsin(): String? = player.state.value.takeIf { it.hasBook }?.asin
}
