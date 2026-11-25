package xyz.aresius.audiobooks.core.player

import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView

/** What to play: the release and, for a stream, the debrid provider. [provider] is null when only local files can be played. */
data class ReleaseChoice(val release: ReleaseView, val provider: Provider?)

/**
 * Which release a book plays. The user's remembered choice (per book) wins while it still exists; then the best ranked release that the
 * user can stream with a debrid key, cached ones first (a cached file starts at once, an uncached one has to be fetched by the provider);
 * then, for downloaded or BitTorrent files, the best ranked release that has local files; else the first release with no provider.
 */
object ReleaseChooser {
    fun choose(
        releases: List<ReleaseView>,
        remembered: String?,
        preferredProvider: Provider?,
        hasLocal: (ReleaseView) -> Boolean = { false },
    ): ReleaseChoice? {
        if (releases.isEmpty()) return null
        remembered?.let { hash -> releases.firstOrNull { it.infoHash == hash }?.let { return ReleaseChoice(it, providerFor(it, preferredProvider)) } }
        val streamable = releases.filter { providerFor(it, preferredProvider) != null }
        val best = streamable.firstOrNull { r -> providerFor(r, preferredProvider)?.let { r.cached[it.wire] == true } == true }
            ?: streamable.firstOrNull()
        if (best != null) return ReleaseChoice(best, providerFor(best, preferredProvider))
        val local = releases.firstOrNull(hasLocal)
        return ReleaseChoice(local ?: releases.first(), null)
    }

    /** The provider to stream [release] with: the preferred one if usable, else a usable one where it is cached, else the first usable. */
    fun providerFor(release: ReleaseView, preferred: Provider?): Provider? {
        val usable = release.usable.debrid.mapNotNull(Provider::fromWire)
        if (usable.isEmpty()) return null
        if (preferred != null && preferred in usable) return preferred
        return usable.firstOrNull { release.cached[it.wire] == true } ?: usable.first()
    }
}
