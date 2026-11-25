package xyz.aresius.audiobooks.core.torrent

import java.io.File

/**
 * Zip-slip protection for paths that come from a torrent's metainfo (untrusted). libtorrent already strips ".." and absolute
 * components when it parses a torrent; this is the second, independent check on what it reports, applied before anything is
 * selected, and again on the final location before the engine trusts a file.
 */
object TorrentPaths {
    private const val MAX_COMPONENT_BYTES = 255
    private const val MAX_PATH_BYTES = 1024

    /** The normalised relative path (components joined by '/'), or null when [path] could leave its directory or is malformed. */
    fun safeRelative(path: String): String? {
        if (path.isEmpty() || path.length > MAX_PATH_BYTES) return null
        if (path.startsWith("/") || path.startsWith("\\") || path.startsWith("~")) return null
        if (path.any { it.isISOControl() || it == '\\' }) return null
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter() && (path.length == 2 || path[2] == '/')) return null
        val parts = path.split('/')
        for (p in parts) {
            if (p.isEmpty() || p == "." || p == "..") return null
            if (p.toByteArray().size > MAX_COMPONENT_BYTES) return null
            if (p.all { it == '.' }) return null
        }
        return parts.joinToString("/")
    }

    /** The file for [relative] below [destination], or null when it is unsafe or its real location is not inside [destination]. */
    fun resolveUnder(destination: File, relative: String): File? {
        val safe = safeRelative(relative) ?: return null
        val root = destination.canonicalFile
        val target = File(root, safe).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }
}
