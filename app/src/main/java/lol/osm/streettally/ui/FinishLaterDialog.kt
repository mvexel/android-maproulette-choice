package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.maproulette.sdk.GuestEmailState

/** Optional, receipt-first follow-up. Closing it never changes saved answers. */
@Composable
internal fun FinishLaterDialog(session: AppSession, noun: Noun, onClose: () -> Unit) {
    val followUp by session.followUp.collectAsState()
    var email by remember { mutableStateOf(followUp.lastLinkEmail.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var accountHelp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Your answers are saved") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Wording.PUT_ON_MAP_INTRO)
                val sent = sentTo
                if (sent != null) {
                    Text(Wording.linkSent(sent, followUp.status?.expiresAt), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { sentTo = null }) { Text("Change") }
                } else {
                    if (followUp.status?.email?.let { it != GuestEmailState.NONE } == true && followUp.lastLinkEmail == null) {
                        Text("Enter your email again and we'll send a new link.")
                    }
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email address") },
                        singleLine = true, enabled = !sending,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Button(enabled = !sending && email.isNotBlank(), onClick = {
                        val address = email.trim()
                        sending = true
                        error = null
                        scope.launch {
                            try {
                                session.sendFinishLink(address)
                                sentTo = address
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = Wording.linkFailed(e, noun) }
                            finally { sending = false }
                        }
                    }) { Text(if (sending) "Sending…" else "Send me the link") }
                    error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    Text(Wording.EMAIL_USE, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { accountHelp = true }) { Text("I already use OpenStreetMap") }
                if (accountHelp) Text(Wording.EXISTING_ACCOUNT_HANDOFF)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (sentTo == null) "Later" else "Done") } },
    )
}
