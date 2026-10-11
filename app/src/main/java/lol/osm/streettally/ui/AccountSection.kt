package lol.osm.streettally.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import lol.osm.streettally.auth.SessionState

/** The account part of the picker, with the iOS app's labels (see AccountText). */
@Composable
internal fun AccountSection(
    state: SessionState,
    needsReconsent: Boolean,
    privateSignIn: Boolean,
    /** Signed out in a build where guests answer first. */
    guestsAvailable: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val account = state.account
    Text("Account", style = MaterialTheme.typography.titleMedium)
    Text(
        accountLine(state, guestsAvailable),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    val enabled = state.restored && !state.busy
    when {
        account == null || needsReconsent -> Button(onClick = onSignIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(signInLabel(account != null, needsReconsent, guestsAvailable))
        }
        else -> OutlinedButton(onClick = onSignOut, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
    if (account == null) {
        Text(
            accountHint(privateSignIn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
