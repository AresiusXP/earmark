package xyz.aresius.audiobooks.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.AuthError
import xyz.aresius.audiobooks.core.auth.GoogleAuthRepository
import xyz.aresius.audiobooks.core.auth.GoogleOutcome
import xyz.aresius.audiobooks.core.auth.GoogleTokenRequester
import xyz.aresius.audiobooks.core.auth.GoogleTokenSource
import xyz.aresius.audiobooks.core.auth.requester
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.GoogleLink

data class GoogleAccountUiState(
    val google: GoogleLink = GoogleLink(),
    /** The server has Sign in with Google switched on (`GET /v1/version`); null until known. */
    val offered: Boolean? = null,
    val busy: Boolean = false,
    val error: AuthError? = null,
    /** Unlinking failed on the network or the server (the contract's error, not an [AuthError]). */
    val apiError: ApiError? = null,
) {
    /** Nothing to show: Google is off on the server and nothing is linked. */
    val hidden: Boolean get() = offered != true && !google.linked
}

/** Settings > Account: link a Google account to this user, show which one, unlink it. */
@HiltViewModel
class GoogleAccountViewModel @Inject constructor(
    private val googleAuth: GoogleAuthRepository,
    private val me: MeRepository,
    private val source: GoogleTokenSource,
) : ViewModel() {
    private val local = MutableStateFlow(GoogleAccountUiState())

    val state: StateFlow<GoogleAccountUiState> = combine(me.me, local) { user, l -> l.copy(google = user?.google ?: GoogleLink()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        viewModelScope.launch { local.update { it.copy(offered = googleAuth.serverOffersGoogle()) } }
    }

    fun available(context: Context): Boolean = source.isAvailable(context)

    fun link(context: Context) = link(source.requester(context))

    internal fun link(requester: GoogleTokenRequester) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null, apiError = null) }
        viewModelScope.launch {
            val outcome = googleAuth.link(requester)
            local.update {
                when (outcome) {
                    is GoogleOutcome.Failed -> it.copy(busy = false, error = outcome.error)
                    is GoogleOutcome.NotLinked, GoogleOutcome.Cancelled, is GoogleOutcome.Done -> it.copy(busy = false)
                }
            }
        }
    }

    fun unlink() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null, apiError = null) }
        viewModelScope.launch {
            val r = googleAuth.unlink()
            local.update { it.copy(busy = false, apiError = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun consumeError() = local.update { it.copy(error = null, apiError = null) }
}
