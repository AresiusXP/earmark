package xyz.aresius.audiobooks.feature.admin

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
import xyz.aresius.audiobooks.core.data.AdminRepository
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.AdminUser
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.CreatedInvite
import xyz.aresius.audiobooks.core.network.Invite
import xyz.aresius.audiobooks.feature.common.Load

data class UsersUiState(
    val users: Load<List<AdminUser>> = Load.Loading,
    val invites: Load<List<Invite>> = Load.Loading,
    val currentUserId: String? = null,
    val busyUserId: String? = null,
    val busyInviteId: String? = null,
    val creatingInvite: Boolean = false,
    /** The invite just created: its code is shown once and dropped by [UsersViewModel.dismissCreatedInvite]. */
    val createdInvite: CreatedInvite? = null,
    val error: ApiError? = null,
)

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val admin: AdminRepository,
    me: MeRepository,
) : ViewModel() {
    private val local = MutableStateFlow(UsersUiState())

    val state: StateFlow<UsersUiState> = combine(me.me, local) { user, l -> l.copy(currentUserId = user?.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (val r = admin.users()) {
                is ApiResult.Success -> local.update { it.copy(users = Load.Content(r.value)) }
                is ApiResult.Failure -> local.update { if (it.users is Load.Content) it.copy(error = r.error) else it.copy(users = Load.Failed(r.error)) }
            }
        }
        viewModelScope.launch {
            when (val r = admin.invites()) {
                is ApiResult.Success -> local.update { it.copy(invites = Load.Content(r.value)) }
                is ApiResult.Failure -> local.update { if (it.invites is Load.Content) it.copy(error = r.error) else it.copy(invites = Load.Failed(r.error)) }
            }
        }
    }

    /** Disabling revokes every device of the user (server side); yourself and the last admin are refused by the server (409/403). */
    fun setDisabled(user: AdminUser, disabled: Boolean) {
        if (local.value.busyUserId != null || user.id == state.value.currentUserId) return
        local.update { it.copy(busyUserId = user.id, error = null) }
        viewModelScope.launch {
            when (val r = admin.setDisabled(user.id, disabled)) {
                is ApiResult.Success -> local.update { s ->
                    val updated = (s.users as? Load.Content)?.value?.map { if (it.id == r.value.id) r.value else it }
                    s.copy(busyUserId = null, users = updated?.let { Load.Content(it) } ?: s.users)
                }
                is ApiResult.Failure -> local.update { it.copy(busyUserId = null, error = r.error) }
            }
        }
    }

    fun createInvite(note: String, expiresInHours: Int?) {
        if (local.value.creatingInvite) return
        local.update { it.copy(creatingInvite = true, error = null) }
        viewModelScope.launch {
            when (val r = admin.createInvite(note, expiresInHours)) {
                is ApiResult.Success -> local.update { s ->
                    val list = ((s.invites as? Load.Content)?.value.orEmpty()) + r.value.toInvite()
                    s.copy(creatingInvite = false, createdInvite = r.value, invites = Load.Content(list))
                }
                is ApiResult.Failure -> local.update { it.copy(creatingInvite = false, error = r.error) }
            }
        }
    }

    fun dismissCreatedInvite() = local.update { it.copy(createdInvite = null) }

    fun revokeInvite(invite: Invite) {
        if (local.value.busyInviteId != null) return
        local.update { it.copy(busyInviteId = invite.id, error = null) }
        viewModelScope.launch {
            when (val r = admin.revokeInvite(invite.id)) {
                is ApiResult.Success -> local.update { s ->
                    val list = (s.invites as? Load.Content)?.value?.filterNot { it.id == invite.id }
                    s.copy(busyInviteId = null, invites = list?.let { Load.Content(it) } ?: s.invites)
                }
                is ApiResult.Failure -> local.update { it.copy(busyInviteId = null, error = r.error) }
            }
        }
    }

    fun consumeError() = local.update { it.copy(error = null) }
}
