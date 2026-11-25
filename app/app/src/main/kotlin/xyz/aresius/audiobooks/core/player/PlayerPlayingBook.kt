package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.download.PlayingBook

/**
 * Tells the storage rules which book the player holds, so the LRU eviction never deletes the files it is reading. Deletions go through
 * [beginDelete] / [endDelete], decided inside the hub's lock (the one the player changes its state under), not on a snapshot.
 */
@Singleton
class PlayerPlayingBook @Inject constructor(private val hub: PlayerHub) : PlayingBook {
    override fun currentAsin(): String? = hub.state.value.takeIf { it.hasBook }?.asin

    override fun beginDelete(asin: String): Boolean = hub.beginDelete(asin)

    override fun endDelete(asin: String) = hub.endDelete(asin)
}
