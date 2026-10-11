package lol.osm.streettally.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignList
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.errorMessage
import org.maproulette.sdk.Challenge

/** Account and the backend's published mapping campaigns, like the iOS picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    session: AppSession,
    appScope: CoroutineScope,
    privateSignIn: Boolean,
    authenticate: suspend (String) -> String,
    onCampaign: (Long) -> Unit,
) {
    val state by session.state.collectAsState()
    var list by remember { mutableStateOf<CampaignList?>(null) }
    var status by remember { mutableStateOf("Loading campaigns…") }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.restored, state.generation, reload) {
        if (!state.restored) return@LaunchedEffect
        loading = true
        failed = false
        status = "Loading campaigns…"
        try {
            val loaded = Campaigns.load(session.client())
            list = loaded
            status = if (loaded.campaigns.isEmpty()) Wording.NO_CAMPAIGNS else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            list = null
            status = errorMessage(e)
            failed = true
        } finally {
            loading = false
        }
    }

    val shown = list?.campaigns.orEmpty().filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
    Scaffold(topBar = { TopAppBar(title = { Text("Street Tally") }) }) { padding ->
        // Pull down to reload, e.g. after a campaign was published. The first load shows its own spinner.
        PullToRefreshBox(
            isRefreshing = loading && list != null,
            onRefresh = { reload++ },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(CAMPAIGN_LIST_TAG),
            ) {
                item {
                    Text(
                        "Mapping campaigns",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                if ((list?.campaigns?.size ?: 0) > 5) {
                    item {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it },
                            label = { Text("Find a campaign") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(status.ifEmpty { "No campaign matches." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (failed) TextButton(onClick = { reload++ }, modifier = Modifier.padding(start = 8.dp)) { Text("Retry") }
                    }
                }
                items(shown, key = { it.id.value }) { challenge ->
                    CampaignRow(challenge) { onCampaign(challenge.id.value) }
                    HorizontalDivider()
                }
                item {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountSection(
                            state = state,
                            needsReconsent = AppSession.needsReconsent(session.config.writesAllowed, state.account),
                            privateSignIn = privateSignIn,
                            guestsAvailable = session.canAnswerAsGuest,
                            onSignIn = { appScope.launch { session.signIn(authenticate) } },
                            onSignOut = { appScope.launch { session.signOut() } },
                        )
                        DeleteMyData(session)
                        Text(
                            session.config.baseUrl + if (session.config.writesAllowed) "" else " (read only)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignRow(challenge: Challenge, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(challenge.name, style = MaterialTheme.typography.bodyLarge)
        challenge.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The campaign list, for tests. */
internal const val CAMPAIGN_LIST_TAG = "campaign-list"
