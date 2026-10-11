package lol.osm.streettally.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import lol.osm.streettally.AppConfig
import org.maproulette.sdk.CredentialStore
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MobileAccount
import org.maproulette.sdk.MobileSignIn
import org.maproulette.sdk.MobileSignInConfiguration
import org.maproulette.sdk.RestoreResult
import org.maproulette.sdk.SignInException
import org.maproulette.sdk.SignInFailure
import org.maproulette.sdk.Transport

/** What the UI shows about the account. [generation] changes on every sign-in and sign-out, so
 * screens can drop clients and data that belong to the previous account. */
data class SessionState(
    val restored: Boolean = false,
    val account: MobileAccount? = null,
    val generation: Int = 0,
    val busy: Boolean = false,
    /** A one-off note about the last sign-in or sign-out, or null. */
    val message: String? = null,
)

/** The app's sign-in, on top of the SDK's [MobileSignIn]. Android-free: the browser step and the
 * credential store are passed in. One per process (see StreetTallyApp). */
class AppSession(
    val config: AppConfig,
    store: CredentialStore,
    transport: Transport,
) {
    private val signIn = MobileSignIn(
        MobileSignInConfiguration(config.environment(), config.oauthClientId, config.redirectUri),
        store, transport,
    )
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState

    /** Scopes this build asks for: answering needs `tasks:write` and `osm:tagfix`. */
    val requestedScopes: Set<String> get() = MobileSignInConfiguration.scopes(config.writesAllowed)

    /** Signed in, but the grant can't save answers that this build would send. */
    val needsReconsent: Boolean
        get() = needsReconsent(config.writesAllowed, state.value.account)

    /** Reads the saved sign-in. Call once at startup. */
    suspend fun restore() {
        val result = signIn.restore()
        val message = when (result) {
            RestoreResult.RefreshInterrupted, RestoreResult.Unreadable -> SIGNED_OUT_AGAIN
            else -> null
        }
        publish(message, restored = true)
    }

    /** Runs browser sign-in. [authenticate] opens the URL and returns the callback URL; throwing
     * from it means the person closed the browser. */
    suspend fun signIn(authenticate: suspend (String) -> String) {
        mutableState.update { it.copy(busy = true, message = null) }
        val message = try {
            signIn.signIn(requestedScopes, authenticate)
            null
        } catch (e: SignInException) {
            failureMessage(e.failure)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TRY_AGAIN
        }
        publish(message)
    }

    suspend fun signOut() {
        mutableState.update { it.copy(busy = true, message = null) }
        val result = signIn.signOut()
        publish(if (result.localCleared) null else SIGNED_OUT_HERE_ONLY)
    }

    /** A client for the current account (or anonymous reads when signed out). Make a new one when
     * [SessionState.generation] changes. */
    fun client(): MapRouletteClient = signIn.client()

    private fun publish(message: String?, restored: Boolean = state.value.restored) {
        val account = signIn.account
        mutableState.value = SessionState(
            restored = restored,
            account = account,
            generation = signIn.generation,
            busy = false,
            message = message,
        )
    }

    companion object {
        const val TRY_AGAIN = "Couldn't sign in. Please try again."
        const val SIGNED_OUT_AGAIN = "You were signed out. Please sign in again."
        const val SIGNED_OUT_HERE_ONLY = "Signed out, but this phone couldn't forget the sign-in. Sign out again to be sure."

        fun needsReconsent(writesAllowed: Boolean, account: MobileAccount?): Boolean =
            writesAllowed && account != null && !account.canEditOsm

        /** Plain words for a failed sign-in; null when nothing needs saying (closed the browser). */
        fun failureMessage(failure: SignInFailure): String? = when (failure) {
            SignInFailure.Canceled, SignInFailure.Superseded -> null
            SignInFailure.Denied -> "Sign-in was declined on OpenStreetMap."
            SignInFailure.Storage -> "Couldn't keep you signed in on this phone. Please try again."
            SignInFailure.CallbackMismatch, SignInFailure.TokenExchange, SignInFailure.AccountLookup,
            is SignInFailure.ProviderError -> TRY_AGAIN
        }
    }
}
