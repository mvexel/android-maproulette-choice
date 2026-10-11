package lol.osm.streettally.ui

import lol.osm.streettally.auth.SessionState

// Account wording, kept free of Compose so unit tests can check it against the iOS app.

internal fun accountLine(state: SessionState): String {
    val account = state.account
    return when {
        !state.restored -> "Checking your sign-in…"
        state.busy -> if (account == null) "Signing in…" else "Signing out…"
        account == null -> "To save answers you'll sign in with a free OpenStreetMap account."
        account.displayName != null -> "Signed in as ${account.displayName}."
        else -> "Signed in."
    }
}

internal fun signInLabel(signedIn: Boolean, needsReconsent: Boolean): String = when {
    !signedIn -> "Sign in with OpenStreetMap"
    needsReconsent -> "Sign in again to save answers"
    else -> "Sign out"
}

internal fun accountHint(privateSignIn: Boolean): String = if (privateSignIn) {
    "Sign-in opens a private browser tab, so you can choose any OpenStreetMap account."
} else {
    "Your browser keeps you signed in to OpenStreetMap. To use another account, sign out on openstreetmap.org in your browser first."
}
