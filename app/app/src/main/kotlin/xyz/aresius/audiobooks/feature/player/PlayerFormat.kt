package xyz.aresius.audiobooks.feature.player

import java.util.Locale

/** 252_000 -> "4:12", 15_128_000 -> "4:12:08" (hours only when there are some). */
fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** Time left, with a minus; a leading "~" while some lengths are still estimates. */
fun formatRemaining(ms: Long, estimated: Boolean = false): String = (if (estimated) "~-" else "-") + formatClock(ms)

/** 1.0 -> "1", 1.25 -> "1.25", 1.5 -> "1.5" (the decimal separator follows [locale]). */
fun formatSpeed(speed: Float, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.2f", speed).let { s ->
        val sep = s.indexOfFirst { it == '.' || it == ',' }
        if (sep < 0) s else s.trimEnd('0').trimEnd('.', ',')
    }

private fun two(n: Long) = if (n < 10) "0$n" else n.toString()
