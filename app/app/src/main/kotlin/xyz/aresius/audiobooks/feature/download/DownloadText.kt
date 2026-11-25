package xyz.aresius.audiobooks.feature.download

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.download.BookDownloadState
import xyz.aresius.audiobooks.core.download.EnqueueResult
import xyz.aresius.audiobooks.core.download.FailReason
import xyz.aresius.audiobooks.core.download.WaitReason
import xyz.aresius.audiobooks.feature.common.formatSize

@StringRes
fun FailReason.messageRes(): Int = when (this) {
    FailReason.NoSeeders -> R.string.download_fail_no_seeders
    FailReason.DebridCooldown -> R.string.download_fail_debrid_cooldown
    FailReason.DebridBusy -> R.string.download_fail_debrid_busy
    FailReason.DebridFailed -> R.string.download_fail_debrid_failed
    FailReason.NoDebridKey -> R.string.download_fail_no_debrid_key
    FailReason.BadDebridKey -> R.string.download_fail_bad_debrid_key
    FailReason.RateLimited -> R.string.download_fail_rate_limited
    FailReason.NotFound -> R.string.download_fail_not_found
    FailReason.NoSpace -> R.string.download_fail_no_space
    FailReason.StorageLimit -> R.string.download_fail_storage_limit
    FailReason.Integrity -> R.string.download_fail_integrity
    FailReason.InvalidContent -> R.string.download_fail_invalid_content
    FailReason.InvalidPath -> R.string.download_fail_invalid_path
    FailReason.PackFilesUnknown -> R.string.download_fail_pack_unknown
    FailReason.TorrentUnavailable -> R.string.download_fail_torrent_unavailable
    FailReason.TorrentStalled -> R.string.download_fail_torrent_stalled
    FailReason.Network -> R.string.download_fail_network
    FailReason.Timeout -> R.string.download_fail_timeout
    FailReason.Unknown -> R.string.download_fail_unknown
}

/** "4 min", "1 h 5 min": the time left. */
@Composable
fun formatEta(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.download_eta_seconds, seconds.coerceAtLeast(1))
    seconds < 3600 -> stringResource(R.string.download_eta_minutes, (seconds / 60).toInt())
    else -> stringResource(R.string.download_eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}

/** Why a download failed, in words; a 429 says when to try again. */
@Composable
fun BookDownloadState.Failed.text(): String {
    if (reason == FailReason.RateLimited && retryAfterSec != null && retryAfterSec > 0) {
        val wait = if (retryAfterSec >= 60) formatEta(retryAfterSec.toLong()) else stringResource(R.string.download_eta_seconds, retryAfterSec)
        return stringResource(R.string.download_fail_rate_limited_in, wait)
    }
    return stringResource(reason.messageRes())
}

/** The one-line status under the download button (null when there is nothing to say). */
@Composable
fun BookDownloadState.statusText(): String? = when (this) {
    BookDownloadState.None -> null
    BookDownloadState.Queued -> stringResource(R.string.download_status_queued)
    is BookDownloadState.Preparing -> when (slowReason) {
        FailReason.DebridCooldown -> stringResource(R.string.download_status_preparing_cooldown)
        FailReason.DebridBusy -> stringResource(R.string.download_status_preparing_busy)
        else -> if (percent != null && percent > 0) stringResource(R.string.download_status_preparing_pct, percent) else stringResource(R.string.download_status_preparing)
    }
    is BookDownloadState.Downloading -> {
        val done = formatSize(bytesDone)
        val total = formatSize(bytesTotal)
        if (speedBytesPerSec > 0 && etaSeconds != null) {
            stringResource(R.string.download_status_downloading, done, total, formatSize(speedBytesPerSec), formatEta(etaSeconds))
        } else {
            stringResource(R.string.download_status_downloading_no_speed, done, total)
        }
    }
    is BookDownloadState.Paused -> stringResource(
        when (waitingFor) {
            WaitReason.WiFi -> R.string.download_status_paused_wifi
            WaitReason.Network -> R.string.download_status_paused_network
            WaitReason.Vpn -> R.string.download_status_paused_vpn
            WaitReason.Battery -> R.string.download_status_paused_battery
            WaitReason.Background -> R.string.download_status_paused_background
        },
    )
    is BookDownloadState.Failed -> text()
    is BookDownloadState.Completed -> stringResource(R.string.download_status_completed, formatSize(bytes))
}

@Composable
fun EnqueueResult.messageOrNull(): String? = when (this) {
    EnqueueResult.Started -> null
    EnqueueResult.AlreadyDownloaded -> stringResource(R.string.download_already_done)
    EnqueueResult.AlreadyActive -> stringResource(R.string.download_already_active)
    EnqueueResult.NotSignedIn -> stringResource(R.string.download_not_signed_in)
    EnqueueResult.NeedsKey -> stringResource(R.string.download_needs_key)
    is EnqueueResult.Rejected -> stringResource(reason.messageRes())
}
