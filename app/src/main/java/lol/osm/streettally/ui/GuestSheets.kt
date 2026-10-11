package lol.osm.streettally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.maproulette.sdk.GuestEmailState
import org.maproulette.sdk.GuestState
import org.maproulette.sdk.GuestStatus

/** "Your answers are saved": how a guest puts saved answers on the map (deferred sign-up §1.3).
 * Shown once after the first saved answer and from the campaign's saved-answers banner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PutOnMapSheet(session: AppSession, noun: Noun, onSignIn: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        PutOnMapContent(session, noun, onSignIn = { onSignIn(); onClose() }, onClose = onClose)
    }
}

@Composable
private fun PutOnMapContent(session: AppSession, noun: Noun, onSignIn: () -> Unit, onClose: () -> Unit) {
    val info by session.guest.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun send() {
        val address = email.trim()
        sending = true
        error = null
        scope.launch {
            try {
                session.sendFinishLink(address)
                sentTo = address
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Wording.linkFailed(e, noun) to Wording.isInputError(e)
            } finally {
                sending = false
            }
        }
    }

    SheetColumn {
        Text(Wording.PUT_ON_MAP_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(Wording.PUT_ON_MAP_INTRO)
        val sent = sentTo
        if (sent != null) {
            Text(Wording.linkSent(sent, info.status?.expiresAt), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { sentTo = null }) { Text("Change") }
        } else {
            // An email is on file, but this launch doesn't know it (it isn't stored on the phone).
            if (info.status.let { it != null && it.email != GuestEmailState.NONE } && info.lastLinkEmail == null) {
                Text(Wording.ENTER_EMAIL_AGAIN)
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { send() }, enabled = !sending && email.contains("@"),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(Wording.SEND_ME_THE_LINK)
                if (sending) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            error?.let { (text, isInput) ->
                // Only a bad address is the participant's to fix; the rest are calm.
                Text(
                    text,
                    color = if (isInput) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Note(Wording.EMAIL_USE)
        }
        OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(Wording.I_ALREADY_USE_OSM)
        }
        Note(Wording.SIGN_IN_PUTS_ON_MAP)
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (sentTo == null) "Later" else "Done")
        }
    }
}

/** "Nice work today": a guest's end-of-session summary (deferred sign-up §1.4), opened from
 * "I'm done for today". Without an email yet, it shows the "put them on the map" card instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(
    session: AppSession,
    checkedToday: Int,
    done: Int,
    total: Int,
    noun: Noun,
    onSignIn: () -> Unit,
    onClose: () -> Unit,
) {
    val info by session.guest.collectAsState()
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var enterEmail by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose) {
        if (info.status?.email == GuestEmailState.NONE || enterEmail) {
            PutOnMapContent(session, noun, onSignIn = { onSignIn(); onClose() }, onClose = onClose)
            return@ModalBottomSheet
        }
        SheetColumn {
            Text(Wording.DAY_SUMMARY_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(Wording.daySummary(checkedToday, done, total, noun))
            Text(Wording.daySummarySaved(info.status?.expiresAt))
            note?.let { Note(it) }
            Button(
                onClick = {
                    // Resends to the address given in this launch; otherwise asks for it again.
                    val address = info.lastLinkEmail
                    if (address == null) {
                        enterEmail = true
                    } else {
                        sending = true
                        scope.launch {
                            note = try {
                                session.sendFinishLink(address)
                                Wording.linkSent(address, session.guest.value.status?.expiresAt)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Wording.linkFailed(e, noun)
                            } finally {
                                sending = false
                            }
                        }
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(Wording.RESEND_THE_LINK) }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}

/** Under the tally for a guest: their saved answers that aren't on the map yet (§1.4), amber in the
 * last 3 days before they are removed (§1.6), or the calm note once they were removed. */
@Composable
internal fun GuestSavedSection(
    status: GuestStatus,
    noun: Noun,
    onLink: () -> Unit,
    onDoneForToday: () -> Unit,
    now: Instant = Instant.now(),
) {
    if (status.state == GuestState.EXPIRED) {
        Text(Wording.SAVED_EXPIRED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (status.pendingCount <= 0) return
    val closing = Duration.between(now, status.expiresAt) < Duration.ofDays(3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (closing) CLOSING_AMBER else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (closing) Wording.savedClosing(status.pendingCount, noun, status.expiresAt)
            else Wording.savedNotOnMap(status.pendingCount, noun, status.expiresAt),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (closing) Color(0xFF3B2600) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (status.email == GuestEmailState.NONE) Wording.SEND_ME_THE_LINK else Wording.RESEND_THE_LINK)
        }
    }
    TextButton(onClick = onDoneForToday, modifier = Modifier.heightIn(min = 48.dp)) { Text(Wording.DONE_FOR_TODAY) }
}

/** "Delete my data" for a guest, with its confirmation; under the account section. */
@Composable
internal fun DeleteMyData(session: AppSession) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val state by session.state.collectAsState()
    if (state.guest) {
        TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(Wording.DELETE_MY_DATA, color = MaterialTheme.colorScheme.error)
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(Wording.DELETE_MY_DATA) },
            text = { Text(Wording.DELETE_MY_DATA_EXPLAINED) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        result = try {
                            session.deleteGuestData()
                            Wording.DELETE_MY_DATA_DONE
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Wording.DELETE_MY_DATA_FAILED
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The closing banner: amber in both themes, with dark text for contrast. */
private val CLOSING_AMBER = Color(0xFFFFD58A)
