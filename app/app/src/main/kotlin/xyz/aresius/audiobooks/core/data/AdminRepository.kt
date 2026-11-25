package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.network.AdminUpdateUserRequest
import xyz.aresius.audiobooks.core.network.AdminUser
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.CreateInviteRequest
import xyz.aresius.audiobooks.core.network.CreatedInvite
import xyz.aresius.audiobooks.core.network.Invite
import xyz.aresius.audiobooks.core.network.safeApiCall

/** Users and invites (admin only; everybody else gets 403). */
@Singleton
class AdminRepository(private val apiSource: suspend () -> AudiobooksApi) {
    @Inject
    constructor(provider: ApiProvider) : this({ provider.api() })

    suspend fun users(): ApiResult<List<AdminUser>> = safeApiCall { apiSource().adminListUsers().users }

    suspend fun setDisabled(userId: String, disabled: Boolean): ApiResult<AdminUser> =
        safeApiCall { apiSource().adminUpdateUser(userId, AdminUpdateUserRequest(disabled)) }

    suspend fun invites(): ApiResult<List<Invite>> = safeApiCall { apiSource().adminListInvites().invites }

    suspend fun createInvite(note: String?, expiresInHours: Int?): ApiResult<CreatedInvite> =
        safeApiCall { apiSource().adminCreateInvite(CreateInviteRequest(note?.trim()?.takeIf { it.isNotEmpty() }, expiresInHours)) }

    suspend fun revokeInvite(inviteId: String): ApiResult<Unit> = safeApiCall { apiSource().adminRevokeInvite(inviteId) }
}
