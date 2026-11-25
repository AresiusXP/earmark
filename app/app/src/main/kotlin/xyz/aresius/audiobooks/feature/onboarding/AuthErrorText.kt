package xyz.aresius.audiobooks.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.auth.AuthError
import xyz.aresius.audiobooks.core.auth.InputProblem

@StringRes
fun InputProblem.messageRes(): Int = when (this) {
    InputProblem.UrlEmpty -> R.string.input_url_empty
    InputProblem.UrlInvalid -> R.string.input_url_invalid
    InputProblem.UrlNeedsHttps -> R.string.input_url_needs_https
    InputProblem.SecretEmpty -> R.string.input_secret_empty
    InputProblem.NameEmpty -> R.string.input_name_empty
    InputProblem.NameTooLong -> R.string.input_name_too_long
    InputProblem.DeviceNameEmpty -> R.string.input_device_empty
    InputProblem.DeviceNameTooLong -> R.string.input_device_too_long
}

@Composable
fun AuthError.message(method: OnboardingMethod? = null): String = when (this) {
    is AuthError.InvalidInput -> stringResource(problem.messageRes())
    AuthError.BadCredentials -> stringResource(
        when (method) {
            OnboardingMethod.FirstSetup -> R.string.auth_wrong_password
            OnboardingMethod.Link -> R.string.auth_bad_link_code
            else -> R.string.auth_bad_invite
        },
    )
    AuthError.AlreadySetUp -> stringResource(R.string.auth_already_set_up)
    is AuthError.TooManyAttempts ->
        if (retryAfterSec != null) stringResource(R.string.auth_too_many_in, retryAfterSec) else stringResource(R.string.auth_too_many)
    AuthError.Network -> stringResource(R.string.auth_network)
    is AuthError.Server -> message ?: stringResource(R.string.error_server, status)
    is AuthError.Unknown -> stringResource(R.string.auth_unknown)
}
