package lol.osm.streettally.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lol.osm.streettally.task.Writer
import lol.osm.streettally.task.PendingSaveStore
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.MobileGuest
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
    val guest: MobileGuest? = null,
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
    val pendingSaves: PendingSaveStore = PendingSaveStore(InMemoryCredentialStore()),
) {
    private val signIn = MobileSignIn(
        MobileSignInConfiguration(config.environment(), config.oauthClientId, config.redirectUri),
        store, transport,
    )
    private val guestMutex = Mutex()
    private var guestRestoreFailed = false
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
        guestRestoreFailed = result == RestoreResult.Unreadable
        val message = when (result) {
            RestoreResult.Unreadable -> SAVED_ACCESS_UNAVAILABLE
            RestoreResult.RefreshInterrupted -> SIGNED_OUT_AGAIN
            else -> null
        }
        publish(message, restored = true)
    }

    /** Serialized because SDK registration deliberately runs outside its own credential lock. */
    suspend fun ensureGuest() = guestMutex.withLock {
        if (!state.value.restored || !config.guestWritesAllowed || signIn.account != null || signIn.guest != null) return@withLock
        if (guestRestoreFailed || runCatching { pendingSaves.read() != null }.getOrDefault(true)) {
            publish(SAVED_ACCESS_UNAVAILABLE)
            return@withLock
        }
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            // Registration establishes process-wide credentials. Finish persistence even when
            // the opening screen leaves; SDK assigns guest before its store write completes.
            withContext(NonCancellable) {
                signIn.startGuest()
                publish(null)
            }
        } catch (e: CancellationException) {
            // Do not publish a possibly non-durable SDK identity after a cancelled store.
            mutableState.update { it.copy(busy = false) }
            throw e
        } catch (_: Exception) {
            publish("Couldn't start without an account. Check your connection and try again.")
        }
    }

    /** Account precedence is explicit; a guest never gets an OSM writer. */
    fun writer(): Writer? {
        val account = state.value.account
        if (account != null) return if (config.writesAllowed && account.canWriteTasks) Writer(account.userId, account.canEditOsm) else null
        val guest = state.value.guest ?: return null
        return if (config.guestWritesAllowed) Writer(0, false, "${config.baseUrl}|${config.oauthClientId}|${guest.guestId}") else null
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
            guest = signIn.guest,
            generation = signIn.generation,
            busy = false,
            message = message,
        )
    }

    companion object {
        const val SAVED_ACCESS_UNAVAILABLE = "We couldn't restore this phone's saved-answer access. If you received a finish link, open it on a computer. Don't assume your earlier answers were removed."
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
