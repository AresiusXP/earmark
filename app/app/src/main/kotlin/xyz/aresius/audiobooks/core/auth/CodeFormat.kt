package xyz.aresius.audiobooks.core.auth

import java.util.Locale

/**
 * Invite codes and link codes share one format: 26 Crockford base32 characters (no I, L, O, U), 130 bits. People type, paste, read
 * aloud and scan them, so input is forgiven the way the server forgives it (case, spaces, dashes, I/L -> 1, O -> 0) and
 * displayed in blocks. Plain string code on purpose (no Regex: every app regex would need an ICU test).
 */
object CodeFormat {
    const val LENGTH = 26
    const val GROUP = 5
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private fun isSeparator(c: Char) = c.isWhitespace() || c == '-' || c == '_' || c == '\u00A0' || c == '\u2010' || c == '\u2011' || c == '\u2012' || c == '\u2013' || c == '\u2014' || c == '\u200B'

    /** Upper case, separators removed, look-alikes folded. The result may still be invalid: see [isValid]. */
    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (c in input.uppercase(Locale.ROOT)) {
            when {
                isSeparator(c) -> Unit
                c == 'I' || c == 'L' -> out.append('1')
                c == 'O' -> out.append('0')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** [normalized] (output of [normalize]) is exactly [LENGTH] base32 characters. */
    fun isValid(normalized: String): Boolean = normalized.length == LENGTH && normalized.all { it in ALPHABET }

    /** The normalised code of [input], or null when it is not a valid code (a scanned QR of something else, a half-typed code). */
    fun parse(input: String): String? = normalize(input).takeIf(::isValid)

    /** "ABCDEFGHJKMNPQRSTVWXYZ0123" -> "ABCDE FGHJK MNPQR STVWX YZ012 3" (blocks of [GROUP]); [separator] joins them. */
    fun grouped(code: String, separator: String = " "): String = code.chunked(GROUP).joinToString(separator)
}
