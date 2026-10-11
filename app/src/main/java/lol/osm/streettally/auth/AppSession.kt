package lol.osm.streettally.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lol.osm.streettally.task.Writer
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import lol.osm.streettally.AppConfig
import lol.osm.streettally.campaign.Wording
import org.maproulette.sdk.CredentialStore
import org.maproulette.sdk.GuestState
import org.maproulette.sdk.GuestStatus
import org.maproulette.sdk.GuestUpgrade
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
    /** Not signed in, answering as a guest: answers are saved, and put on the map later. */
    val guest: Boolean = false,
    val generation: Int = 0,
    val busy: Boolean = false,
    /** A one-off note about the last sign-in or sign-out, or null. */
    val message: String? = null,
)

/** A guest's saved answers as last read ([status]; null when not a guest or not read yet), and the
 * address the finish link went to in this launch ([lastLinkEmail], kept in memory only). */
data class GuestInfo(val status: GuestStatus? = null, val lastLinkEmail: String? = null)

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
    /** The backend refused guests (for example turned off there); this launch falls back to signing in. */
    private var guestsRefused = false
    private val guestStart = Mutex()
    private val mutableGuest = MutableStateFlow(GuestInfo())
    /** The guest's saved answers and the last link address; empty unless [SessionState.guest]. */
    val guest: StateFlow<GuestInfo> = mutableGuest

    /** Signed out in a build that lets participants answer first, unless the backend refused guests. */
    val canAnswerAsGuest: Boolean
        get() = config.guests && config.writesAllowed && state.value.account == null && !guestsRefused

    /** Makes this phone a guest before its first answer (registering once; later launches restore it).
     * False when signed in, when this build or backend has no guests, or when registering failed. */
    suspend fun ensureGuest(): Boolean = guestStart.withLock {
        if (!canAnswerAsGuest) return@withLock false
        if (state.value.guest) return@withLock true
        try {
            signIn.startGuest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MapRouletteException) {
            // Guests are off on this backend, or this client may not use them: sign in instead.
            if (e.kind == ErrorKind.NOT_FOUND || e.kind == ErrorKind.AUTHENTICATION) guestsRefused = true
        } catch (_: Exception) {
            // Storage failed or the request didn't finish; the next place tries again.
        }
        publish(state.value.message)
        state.value.guest
    }

    /** The writer for this session's answers: the signed-in account, or a guest; null when this
     * build can't save answers for it. */
    fun writer(): Writer? {
        val s = state.value
        if (!config.writesAllowed) return null
        val account = s.account
        if (account != null) return if (account.canWriteTasks) Writer(account.userId, account.canEditOsm) else null
        return if (s.guest && config.guests) Writer.GUEST else null
    }

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
            is RestoreResult.Guest -> upgradeIfClaimed()
            else -> null
        }
        publish(message, restored = true)
    }

    /** A guest whose answers were claimed on the web becomes that account (UX §1.5). Checked on
     * launch. Once claimed the guest token is refused, so a failed status read still tries. Returns
     * the note to show, or null when still a guest. */
    private suspend fun upgradeIfClaimed(): String? {
        val status = quietly { signIn.client().getGuestStatus() }
        if (status != null && status.state != GuestState.CLAIMED) {
            mutableGuest.value = GuestInfo(status)
            return null
        }
        if (quietly { signIn.upgradeGuest() } !is GuestUpgrade.SignedIn) return null
        mutableGuest.value = GuestInfo()
        return Wording.claimedMessage(status)
    }

    /** Re-reads the guest's status (saved count, email state, deadline). Failures keep the last one. */
    suspend fun refreshGuestStatus() {
        if (!state.value.guest) {
            mutableGuest.value = GuestInfo()
            return
        }
        val status = quietly { client().getGuestStatus() } ?: return
        if (state.value.guest) mutableGuest.update { it.copy(status = status) }
    }

    /** Sends the "finish on a computer" link to [email] (again, to correct it). Throws for the
     * card to show a plain message ([Wording.linkFailed]). */
    suspend fun sendFinishLink(email: String) {
        val status = client().setGuestEmail(email)
        if (state.value.guest) mutableGuest.value = GuestInfo(status, email.trim())
    }

    /** Deletes the guest's email and saved answers on the server, then forgets the guest here.
     * Answers already on the map stay. Throws when the server call fails. */
    suspend fun deleteGuestData() {
        if (!state.value.guest) return
        client().deleteGuest()
        quietly { signIn.forgetGuest() }
        mutableGuest.value = GuestInfo()
        publish(null)
    }

    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
        val guest = account == null && signIn.guest != null
        if (!guest) mutableGuest.value = GuestInfo()
        mutableState.value = SessionState(
            restored = restored,
            account = account,
            guest = guest,
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
