package lol.osm.streettally.ui

import lol.osm.streettally.auth.SessionState
import lol.osm.streettally.campaign.Wording

// Account wording, kept free of Compose so unit tests can check it against the iOS app.

/** [guestsAvailable]: signed out in a build where guests answer first (AppSession.canAnswerAsGuest). */
internal fun accountLine(state: SessionState, guestsAvailable: Boolean = false): String {
    val account = state.account
    return when {
        !state.restored -> "Checking your sign-in…"
        state.busy -> if (account == null) "Signing in…" else "Signing out…"
        account == null && guestsAvailable -> Wording.noAccountNeeded()
        account == null -> "To save answers you'll sign in with a free OpenStreetMap account."
        else -> Wording.signedInAs(account.displayName)
    }
}

/** [guestsAvailable]: as for [accountLine]; the guest copy points at "I already use OpenStreetMap". */
internal fun signInLabel(signedIn: Boolean, needsReconsent: Boolean, guestsAvailable: Boolean = false): String = when {
    !signedIn && guestsAvailable -> Wording.I_ALREADY_USE_OSM
    !signedIn -> "Sign in with OpenStreetMap"
    needsReconsent -> "Sign in again to save answers"
    else -> "Sign out"
}

internal const val ACCOUNT_HINT = "You'll sign in on openstreetmap.org. No account yet? It's free to create one there."
