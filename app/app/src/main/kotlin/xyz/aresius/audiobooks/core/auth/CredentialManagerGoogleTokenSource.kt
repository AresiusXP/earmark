package xyz.aresius.audiobooks.core.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Sign in with Google through Android Credential Manager. [serverClientId] is the WEB OAuth client of the server's Google project: the ID
 * token's audience, which the server checks. The Android OAuth clients (package + signing key) only have to exist in the same project.
 *
 * The explicit button uses [GetSignInWithGoogleOption] (always lists the accounts, no pre-authorisation needed). Where a device does not
 * support it, [GetGoogleIdOption] with `filterByAuthorizedAccounts(false)` and no auto-select is the same sheet by another name:
 * the user always picks, nothing signs in silently.
 */
class CredentialManagerGoogleTokenSource(private val serverClientId: String) : GoogleTokenSource {
    override fun isAvailable(context: Context): Boolean =
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)

    override suspend fun request(context: Context, nonce: String): GoogleTokenResult {
        val manager = CredentialManager.create(context)
        return try {
            fetch(manager, context, nonce, explicitButton = true)
        } catch (_: GetCredentialUnsupportedException) {
            fallback(manager, context, nonce)
        } catch (_: GetCredentialProviderConfigurationException) {
            fallback(manager, context, nonce)
        } catch (e: GetCredentialException) {
            map(e)
        } catch (_: GoogleIdTokenParsingException) {
            GoogleTokenResult.Failed("parse")
        }
    }

    private suspend fun fallback(manager: CredentialManager, context: Context, nonce: String): GoogleTokenResult = try {
        fetch(manager, context, nonce, explicitButton = false)
    } catch (e: GetCredentialException) {
        map(e)
    } catch (_: GoogleIdTokenParsingException) {
        GoogleTokenResult.Failed("parse")
    }

    private suspend fun fetch(manager: CredentialManager, context: Context, nonce: String, explicitButton: Boolean): GoogleTokenResult {
        val option = if (explicitButton) {
            GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        } else {
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .setNonce(nonce)
                .build()
        }
        val response = manager.getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleTokenResult.Failed("credential type")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        return GoogleTokenResult.Token(GoogleCredential(google.idToken, google.givenName, google.email))
    }

    private fun map(e: GetCredentialException): GoogleTokenResult = when (e) {
        is GetCredentialCancellationException -> GoogleTokenResult.Cancelled
        is NoCredentialException -> GoogleTokenResult.NoAccount
        is GetCredentialUnsupportedException, is GetCredentialProviderConfigurationException -> GoogleTokenResult.Unavailable
        else -> GoogleTokenResult.Failed(e.javaClass.simpleName)
    }
}
