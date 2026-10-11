package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.auth.SessionState

/** Placeholder home until the campaign picker lands: the account section, with the same labels
 * as the iOS app. */
@Composable
fun StartScreen(
    session: AppSession,
    scope: CoroutineScope,
    privateSignIn: Boolean,
    authenticate: suspend (String) -> String,
) {
    val state by session.state.collectAsState()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Street Tally", style = MaterialTheme.typography.headlineMedium)
            AccountSection(
                state = state,
                needsReconsent = AppSession.needsReconsent(session.config.writesAllowed, state.account),
                privateSignIn = privateSignIn,
                onSignIn = { scope.launch { session.signIn(authenticate) } },
                onSignOut = { scope.launch { session.signOut() } },
            )
            Text(
                session.config.baseUrl + if (session.config.writesAllowed) "" else " (read only)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountSection(
    state: SessionState,
    needsReconsent: Boolean,
    privateSignIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val account = state.account
    Text("Account", style = MaterialTheme.typography.titleMedium)
    Text(
        accountLine(state),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    val enabled = state.restored && !state.busy
    when {
        account == null || needsReconsent -> Button(onClick = onSignIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(signInLabel(account != null, needsReconsent))
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
