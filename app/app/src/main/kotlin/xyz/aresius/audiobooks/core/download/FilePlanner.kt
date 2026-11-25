package xyz.aresius.audiobooks.core.download

import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.TorrentFile

/** Which files of a release are downloaded, in which order, under which local names. Pure; no I/O. */
object FilePlanner {
    /**
     * The files of THIS book as the server listed them (`files` for debrid, `torrent_files` for BitTorrent, the other as a fallback),
     * audio only, in the server's order (= chapter order). Empty when the server does not know them.
     */
    fun listed(release: ReleaseView, preferTorrent: Boolean): List<TorrentFile> {
        val lists = if (preferTorrent) listOf(release.torrentFiles, release.files) else listOf(release.files, release.torrentFiles)
        return lists.firstOrNull { l -> l.any { PathSafety.isAudio(it.path) } }.orEmpty().filter { PathSafety.isAudio(it.path) }
    }

    /** Rows for [files]; every server-provided path is sanitized: one unsafe path rejects the whole list ([FailReason.InvalidPath]). */
    fun rows(userId: String, asin: String, releaseHash: String, files: List<TorrentFile>, existing: Map<String, DownloadFileEntity> = emptyMap()): List<DownloadFileEntity> {
        val seen = HashSet<String>()
        return files.mapIndexed { i, f ->
            val local = PathSafety.sanitize(f.path) ?: throw DownloadException(FailReason.InvalidPath, "Unsafe file path from the server")
            if (!seen.add(local)) throw DownloadException(FailReason.InvalidPath, "Duplicate file path from the server")
            val old = existing[f.path]
            DownloadFileEntity(
                userId = userId, asin = asin, releaseHash = releaseHash, path = f.path, localPath = local, ordinal = i,
                providerIndex = old?.providerIndex ?: f.index,
                expectedSize = f.size.takeIf { it > 0 } ?: old?.expectedSize ?: 0L,
                bytesDone = old?.bytesDone ?: 0L, size = old?.size ?: 0L, state = old?.state ?: FileState.PENDING,
            )
        }
    }

    /**
     * Matches the planned files with what the provider reports (by PATH, never by index: the lists use different indexes) and takes the
     * provider's index for the download route. With nothing planned, the provider's audio files are used, except for a pack.
     */
    fun matchProvider(
        userId: String, asin: String, releaseHash: String, collection: Boolean,
        planned: List<DownloadFileEntity>, provider: List<TorrentFile>,
    ): List<DownloadFileEntity> {
        val byKey = HashMap<String, TorrentFile>()
        provider.forEach { byKey.putIfAbsent(PathSafety.matchKey(it.path), it) }
        if (planned.isEmpty()) {
            if (collection) throw DownloadException(FailReason.PackFilesUnknown, "The files of this book in the pack are unknown")
            val audio = provider.filter { PathSafety.isAudio(it.path) }.sortedWith { a, b -> naturalCompare(a.path, b.path) }
            if (audio.isEmpty()) throw DownloadException(FailReason.NotFound, "The provider lists no audio files")
            return rows(userId, asin, releaseHash, audio)
        }
        val missing = planned.count { byKey[PathSafety.matchKey(it.path)] == null }
        if (missing > 0) throw DownloadException(FailReason.NotFound, "$missing of ${planned.size} files are not at the provider")
        return planned.map { row ->
            val match = byKey.getValue(PathSafety.matchKey(row.path))
            row.copy(providerIndex = match.index, expectedSize = if (match.size > 0) match.size else row.expectedSize)
        }
    }

    /** "Track 2" before "Track 10"; case-insensitive. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
