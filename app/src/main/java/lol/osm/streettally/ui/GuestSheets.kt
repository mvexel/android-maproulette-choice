package lol.osm.streettally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.maproulette.sdk.ChallengeAnswerCounts

/** "Your answers are saved": what happens to a guest's answers (design/handback-copy.md §1).
 * Opened from the campaign's saved-answers banner, never by itself after a save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PutOnMapSheet(session: AppSession, noun: Noun, onSignIn: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        SheetColumn {
            Text(Wording.PUT_ON_MAP_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(Wording.GUEST_ANSWERS_ARE_PUBLIC)
            AlreadyMapping(onSignIn = { onSignIn(); onClose() })
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}

/** For people who already map: signing in puts the saved answers straight on the map (option D). */
@Composable
private fun AlreadyMapping(onSignIn: () -> Unit) {
    OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(Wording.I_ALREADY_USE_OSM)
    }
    Note(Wording.SIGN_IN_PUTS_ON_MAP)
}

/** "Nice work today": a guest's end-of-session summary, opened from "I'm done for today". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(
    session: AppSession,
    checkedToday: Int,
    done: Int,
    total: Int,
    noun: Noun,
    answers: ChallengeAnswerCounts,
    onSignIn: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        SheetColumn {
            Text(Wording.daySummaryTitle(checkedToday), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(Wording.daySummary(checkedToday, done, total, noun))
            // "Local mappers will check them" only while some are still waiting (Iris, on the map).
            if (answers.pending > 0) Text(Wording.DAY_SUMMARY_SAVED)
            if (answers.onMap > 0) OnTheMap(answers.onMap)
            // Done leads; signing in is a secondary link with no footer (Iris, parity picks).
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
            TextButton(onClick = { onSignIn(); onClose() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(Wording.I_ALREADY_USE_OSM)
            }
        }
    }
}

/** Under the tally for a guest, "Your answers" in this campaign: saved and counted, then "✓ k on the
 * map" once local mappers have added some. Skipped or rejected answers show nothing, and nothing
 * expires (handback-copy.md §1). The first line counts waiting and on-the-map answers together, so it
 * never drops when one goes on the map. */
@Composable
internal fun GuestSavedSection(
    answers: ChallengeAnswerCounts,
    noun: Noun,
    onLink: () -> Unit,
    onDoneForToday: () -> Unit,
) {
    val counted = answers.pending + answers.onMap
    if (counted <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
            .clickable(onClick = onLink)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(Wording.savedNotOnMap(counted, noun), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (answers.onMap > 0) OnTheMap(answers.onMap)
        }
    }
    TextButton(onClick = onDoneForToday, modifier = Modifier.heightIn(min = 48.dp)) { Text(Wording.DONE_FOR_TODAY) }
}

/** "✓ k on the map", read aloud as "k on the map". */
@Composable
private fun OnTheMap(count: Int) {
    Text(
        Wording.onTheMap(count),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clearAndSetSemantics { contentDescription = Wording.onTheMapSpoken(count) },
    )
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

