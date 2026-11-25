package xyz.aresius.audiobooks.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.player.FailureKind
import xyz.aresius.audiobooks.core.player.PlayerPhase

/** What the user can do about a failure; the screen shows the matching button. */
enum class FailureAction { Retry, AddKey, SignIn, None }

fun FailureKind.action(): FailureAction = when (this) {
    FailureKind.NeedsSource, FailureKind.NoDebridKey, FailureKind.BadDebridKey -> FailureAction.AddKey
    FailureKind.NotSignedIn -> FailureAction.SignIn
    FailureKind.NoReleases -> FailureAction.None
    FailureKind.Network, FailureKind.Provider, FailureKind.RateLimited, FailureKind.Stream, FailureKind.Unknown -> FailureAction.Retry
}

@Composable
fun PlayerPhase.Failed.text(): String = when (kind) {
    FailureKind.NotSignedIn -> stringResource(R.string.player_fail_signed_out)
    FailureKind.Network -> stringResource(R.string.player_fail_network)
    FailureKind.NoReleases -> stringResource(R.string.player_fail_no_releases)
    FailureKind.NeedsSource -> stringResource(R.string.player_fail_needs_source)
    FailureKind.NoDebridKey -> stringResource(R.string.player_fail_no_key)
    FailureKind.BadDebridKey -> stringResource(R.string.player_fail_bad_key)
    FailureKind.RateLimited -> stringResource(R.string.player_fail_rate_limited)
    FailureKind.Stream -> stringResource(R.string.player_fail_stream)
    FailureKind.Unknown -> stringResource(R.string.player_fail_unknown)
    FailureKind.Provider -> when {
        message == "no_seeders" -> stringResource(R.string.player_fail_provider_no_seeders)
        message.isNullOrBlank() || message in PROVIDER_CODES -> stringResource(R.string.player_fail_provider_plain)
        else -> stringResource(R.string.player_fail_provider, message)
    }
}

/** The `reason` codes of `Prepare` (the contract); anything else in [PlayerPhase.Failed.message] is the server's own sentence. */
private val PROVIDER_CODES = setOf("no_seeders", "debrid_cooldown", "debrid_busy", "debrid_failed", "not_found")

@Composable
fun PlayerPhase.Preparing.reasonText(): String? = when (reason) {
    "no_seeders" -> stringResource(R.string.player_preparing_no_seeders)
    "debrid_cooldown" -> stringResource(R.string.player_preparing_cooldown)
    "debrid_busy" -> stringResource(R.string.player_preparing_busy)
    else -> null
}
