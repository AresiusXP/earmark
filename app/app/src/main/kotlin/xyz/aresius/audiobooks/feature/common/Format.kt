package xyz.aresius.audiobooks.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import xyz.aresius.audiobooks.R

/** 1536 -> "1.5 KB", 3.2e9 -> "3.0 GB" (decimal-free below 1 KB). Units are the same in English, Dutch and Spanish. */
fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(locale, "%.1f %s", value, units[unit])
}

/** Hours and minutes of a runtime; the caller picks the localized pattern. */
data class RuntimeParts(val hours: Int, val minutes: Int)

fun runtimeParts(totalMinutes: Int): RuntimeParts = RuntimeParts(totalMinutes.coerceAtLeast(0) / 60, totalMinutes.coerceAtLeast(0) % 60)

@Composable
fun formatRuntime(totalMinutes: Int): String {
    val parts = runtimeParts(totalMinutes)
    return when {
        totalMinutes <= 0 -> stringResource(R.string.runtime_unknown)
        parts.hours == 0 -> stringResource(R.string.runtime_minutes, parts.minutes)
        parts.minutes == 0 -> stringResource(R.string.runtime_hours, parts.hours)
        else -> stringResource(R.string.runtime_hours_minutes, parts.hours, parts.minutes)
    }
}

/** Progress of a library entry in [0, 1]; 0 when the runtime is unknown. */
fun progressFraction(positionMs: Long, runtimeMin: Int): Float {
    if (runtimeMin <= 0) return 0f
    return (positionMs.toFloat() / (runtimeMin * 60_000f)).coerceIn(0f, 1f)
}

/** ISO-8601 instant ("2026-11-25T10:00:00Z") as a short local date-time; the raw text when it does not parse. */
fun formatInstant(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.parse(iso))
    }.getOrDefault(iso)
}
