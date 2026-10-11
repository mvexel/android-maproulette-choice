package lol.osm.streettally.auth

import java.net.URI
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.HttpMethod
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
import org.maproulette.sdk.GuestStatus
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
    private val transport: Transport,
    val pendingSaves: PendingSaveStore = PendingSaveStore(InMemoryCredentialStore()),
    private val guestAccessFailureStore: CredentialStore = InMemoryCredentialStore(),
) {
    private val signIn = MobileSignIn(
        MobileSignInConfiguration(config.environment(), config.oauthClientId, config.redirectUri),
        store, transport,
    )
    private val guestMutex = Mutex()
    private val followUpMutex = Mutex()
    private var guestRestoreFailed = false
    private var guestEstablished = false
    private var durableGuestId: String? = null
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState
    private val mutableFollowUp = MutableStateFlow(GuestFollowUpState())
    val followUp: StateFlow<GuestFollowUpState> = mutableFollowUp

    /** A failed read keeps the last confirmed status; it never proves expiry or deletion. */
    suspend fun refreshGuestStatus() = followUpMutex.withLock {
        val snapshot = state.value
        if (snapshot.guest == null || snapshot.account != null) return@withLock
        val client = client()
        mutableFollowUp.update { it.copy(refreshing = true, error = null) }
        try {
            val status = client.getGuestStatus()
            check(status.guestId == snapshot.guest.guestId)
            if (sameGuest(snapshot)) {
                mutableFollowUp.update { it.copy(status = status, refreshing = false, error = null) }
            }
        } catch (e: CancellationException) {
            if (sameGuest(snapshot)) mutableFollowUp.update { it.copy(refreshing = false) }
            throw e
        } catch (_: Exception) {
            preserveLostGuestAccess(snapshot)
            if (sameGuest(snapshot)) mutableFollowUp.update {
                it.copy(refreshing = false, error = "Couldn't check your saved answers right now. Try again later. This doesn't mean they were removed.")
            }
        }
    }

    suspend fun sendFinishLink(email: String) = followUpMutex.withLock {
        val snapshot = state.value
        check(config.guestWritesAllowed && snapshot.guest != null && snapshot.account == null)
        val status = try { client().setGuestEmail(email) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { preserveLostGuestAccess(snapshot); throw e }
        check(status.guestId == snapshot.guest?.guestId)
        if (sameGuest(snapshot)) {
            mutableFollowUp.update { it.copy(status = status, lastLinkEmail = email.trim(), error = null) }
        } else throw CancellationException("guest changed")
    }

    private suspend fun preserveLostGuestAccess(snapshot: SessionState) {
        // SDK drops a refused token's credential. Never silently start a new guest and imply
        // access to earlier confirmed saves has been recovered.
        if (state.value.generation == snapshot.generation && signIn.guest == null && signIn.account == null) {
            guestRestoreFailed = true
            withContext(NonCancellable) {
                runCatching { guestAccessFailureStore.write("lost") }
                publish(SAVED_ACCESS_UNAVAILABLE)
            }
        }
    }

    private fun sameGuest(snapshot: SessionState) = state.value.generation == snapshot.generation &&
        state.value.guest == snapshot.guest && state.value.account == null


    /** Scopes this build asks for: answering needs `tasks:write` and `osm:tagfix`. */
    val requestedScopes: Set<String> get() = MobileSignInConfiguration.scopes(config.writesAllowed)

    /** Signed in, but the grant can't save answers that this build would send. */
    val needsReconsent: Boolean
        get() = needsReconsent(config.writesAllowed, state.value.account)

    /** Reads the saved sign-in. Call once at startup. */
    suspend fun restore() {
        val result = signIn.restore()
        guestEstablished = runCatching { guestAccessFailureStore.read() != null }.getOrDefault(true)
        guestRestoreFailed = result == RestoreResult.Unreadable || (guestEstablished && signIn.guest == null)
        if (signIn.guest != null) {
            try { withContext(NonCancellable) { rememberEstablishedGuest() } }
            catch (_: Exception) { guestRestoreFailed = true }
        }
        val message = when (result) {
            RestoreResult.Unreadable -> SAVED_ACCESS_UNAVAILABLE
            RestoreResult.RefreshInterrupted -> SIGNED_OUT_AGAIN
            else -> if (guestRestoreFailed && signIn.account == null) SAVED_ACCESS_UNAVAILABLE else null
        }
        publish(message, restored = true)
    }

    /** Serialized because SDK registration deliberately runs outside its own credential lock. */
    suspend fun ensureGuest() = guestMutex.withLock {
        if (!state.value.restored || !config.guestWritesAllowed || signIn.account != null) return@withLock
        if (signIn.guest != null) {
            if (durableGuestId != signIn.guest?.guestId) {
                try {
                    withContext(NonCancellable) { rememberEstablishedGuest(); publish(null) }
                } catch (_: Exception) { publish(SAVED_ACCESS_UNAVAILABLE) }
            }
            return@withLock
        }
        if (guestRestoreFailed || guestEstablished || runCatching { pendingSaves.read() != null }.getOrDefault(true)) {
            publish(SAVED_ACCESS_UNAVAILABLE)
            return@withLock
        }
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            // Registration establishes process-wide credentials. Finish persistence even when
            // the opening screen leaves; SDK assigns guest before its store write completes.
            withContext(NonCancellable) {
                signIn.startGuest()
                rememberEstablishedGuest()
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

    /** Persist before exposing credentials to any guest operation. SDK can erase the credential
     * during pending writes, reads or cancellation; this evidence survives every such path.
     * Existing encrypted slot/key is retained so prior lost-access markers still block replacement. */
    private suspend fun rememberEstablishedGuest() {
        val guest = checkNotNull(signIn.guest)
        guestEstablished = true
        guestAccessFailureStore.write("established:${guest.guestId}")
        durableGuestId = guest.guestId
        guestRestoreFailed = false
    }

    /** Account precedence is explicit; a guest never gets an OSM writer. */
    fun writer(): Writer? {
        val account = state.value.account
        if (account != null) return if (config.writesAllowed && account.canWriteTasks) Writer(account.userId, account.canEditOsm) else null
        val guest = state.value.guest ?: return null
        if (guest.guestId != durableGuestId || signIn.guest != guest) return null
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
    fun client(): MapRouletteClient =
        if (signIn.account == null && signIn.guest?.guestId != durableGuestId) {
            // During registration/marker persistence, even campaign reads must not use the
            // SDK's in-memory guest credential before durable evidence exists.
            MapRouletteClient(config.environment(), transport)
        } else if (signIn.account == null && signIn.guest != null) {
            if (config.guestWritesAllowed) {
                signIn.client(environment = config.guestEnvironment(), transport = guestTransport)
            } else {
                // Do not even exchange the saved guest secret when this build has guests off.
                MapRouletteClient(config.guestEnvironment(), guestTransport)
            }
        } else signIn.client(environment = config.environment())

    // SDK 0.x has one mutation gate for both identities. A guest-enabled environment must
    // never allow this client to call authenticated task lifecycle or OSM mutation routes.
    private val guestTransport = Transport { request ->
        val path = URI(request.url).path
        val pendingRoute = path.matches(Regex("/api/v2/task/[0-9]+/choice/pending"))
        val guestRoute = pendingRoute || path in setOf("/api/v2/mobile-guest", "/api/v2/mobile-guest/me",
            "/api/v2/mobile-guest/pending", "/api/v2/mobile-guest/email")
        if (guestRoute && !config.guestWritesAllowed) throw MapRouletteException(ErrorKind.AUTHENTICATION)
        val guestMutation = (pendingRoute && request.method in setOf(HttpMethod.POST, HttpMethod.DELETE)) ||
            (path == "/api/v2/mobile-guest/email" && request.method == HttpMethod.PUT) ||
            (path == "/api/v2/mobile-guest" && request.method == HttpMethod.DELETE)
        // Legacy task start/refresh/release mutate via GET, while marker reads use PUT.
        val readRoute = request.method == HttpMethod.GET && (
            path == "/api/v2/challenges/extendedFind" ||
            path.matches(Regex("/api/v2/challenge/[0-9]+(/tags|/tasks)?")) ||
            path.matches(Regex("/api/v2/task/[0-9]+(/choice/check)?")) ||
            path.startsWith("/api/v2/tasks/box/") ||
            path in setOf("/api/v2/mobile-guest/me", "/api/v2/mobile-guest/pending"))
        val markerRead = request.method == HttpMethod.PUT && path.startsWith("/api/v2/markers/box/")
        if (!readRoute && !markerRead && !guestMutation) throw MapRouletteException(ErrorKind.AUTHENTICATION)
        transport.execute(request)
    }

    private fun publish(message: String?, restored: Boolean = state.value.restored) {
        val account = signIn.account
        mutableFollowUp.update { it.copy(refreshing = false) }
        if (signIn.guest?.guestId != state.value.guest?.guestId) mutableFollowUp.value = GuestFollowUpState()
        mutableState.value = SessionState(
            restored = restored,
            account = account,
            guest = signIn.guest?.takeIf { it.guestId == durableGuestId },
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

/** Address stays in memory only; server status deliberately does not disclose it. */
data class GuestFollowUpState(
    val status: GuestStatus? = null,
    val lastLinkEmail: String? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)
