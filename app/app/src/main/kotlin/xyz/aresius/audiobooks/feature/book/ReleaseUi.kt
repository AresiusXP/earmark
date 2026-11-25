package xyz.aresius.audiobooks.feature.book

import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView

/** How the signed-in user could get a release onto the phone ("usable via" chips). */
sealed interface UsableVia {
    data class Debrid(val provider: Provider) : UsableVia
    data object BitTorrent : UsableVia

    /** Neither a working debrid key nor BitTorrent turned on: the user has to add one in Settings. */
    data object NeedsKey : UsableVia
}

fun ReleaseView.usableVia(): List<UsableVia> {
    val ways = buildList {
        usable.debrid.mapNotNull(Provider::fromWire).distinct().forEach { add(UsableVia.Debrid(it)) }
        if (usable.p2p) add(UsableVia.BitTorrent)
    }
    return ways.ifEmpty { listOf(UsableVia.NeedsKey) }
}

/** Cache state per provider the server knows about; an absent provider is "unknown" and not listed. */
data class CacheBadge(val provider: Provider, val cached: Boolean)

fun ReleaseView.cacheBadges(): List<CacheBadge> =
    Provider.entries.mapNotNull { p -> cached[p.wire]?.let { CacheBadge(p, it) } }

fun Provider.label(): String = when (this) {
    Provider.TorBox -> "TorBox"
    Provider.RealDebrid -> "Real-Debrid"
}
