package xyz.aresius.audiobooks.core.download

import java.io.File

/**
 * File paths come from the server (and, for torrents, from the swarm): they are untrusted. A path is accepted only when it is
 * a relative path of plain segments, so `File(root, path)` can never leave [root] (zip-slip). No regular expressions.
 */
object PathSafety {
    const val MAX_PATH_BYTES = 512
    const val MAX_SEGMENT_BYTES = 200

    /** The cleaned relative path, or null when [raw] is unsafe: empty, absolute, drive letter, backslash, NUL/control chars, `.`/`..` segments, empty segments, too long. */
    fun sanitize(raw: String): String? {
        if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) return null
        if (raw.startsWith('/') || raw.contains('\\')) return null
        if (raw.any { it.isISOControl() }) return null
        if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) return null
        val segments = raw.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
            if (segment.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES) return null
            if (segment.endsWith(".part")) return null
        }
        return segments.joinToString("/")
    }

    /** A key to match the same file in two lists (the server's `files` and a `Prepare` answer): leading slashes and case-neutral. */
    fun matchKey(path: String): String = path.trimStart('/')

    /** ASINs and info hashes become directory names: letters and digits only. */
    fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /** `root/<sanitized path>`, verified to stay below [root] even after resolving; null when unsafe. */
    fun resolve(root: File, raw: String): File? {
        val clean = sanitize(raw) ?: return null
        val file = File(root, clean)
        val base = root.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        return if (target.startsWith(base) && target != base) file else null
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "mka")

    fun isAudio(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}
