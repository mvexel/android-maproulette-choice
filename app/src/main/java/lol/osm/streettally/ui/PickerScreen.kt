package lol.osm.streettally.ui

import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.osm.streettally.AppConfig
import lol.osm.streettally.BuildConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.auth.guestDiagnostic
import lol.osm.streettally.campaign.CampaignList
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.CampaignCache
import lol.osm.streettally.campaign.errorMessage
import lol.osm.streettally.task.GuestOutbox
import lol.osm.streettally.task.SLOW_MS
import org.maproulette.sdk.Challenge

/** Account and the backend's published mapping campaigns, like the iOS picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    session: AppSession,
    appScope: CoroutineScope,
    /** A guest's answers still on this phone: sent with a sign-in, removed by "Delete my data". */
    outbox: GuestOutbox?,
    /** The campaign kept on this phone (BACKLOG O2): listed when there's no signal; null in tests. */
    cache: CampaignCache? = null,
    signIn: suspend () -> Unit,
    /** After "Delete my data": the places this phone saved are open again. */
    onDeletedMyData: () -> Unit = {},
    onCampaign: (Long) -> Unit,
) {
    val state by session.state.collectAsState()
    var list by remember { mutableStateOf<CampaignList?>(null) }
    var status by remember { mutableStateOf("Loading campaigns…") }
    var failed by remember { mutableStateOf(false) }
    // No signal: the campaign kept on this phone, which opens from its copy (as iOS #53).
    var kept by remember { mutableStateOf<Challenge?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }

    // A hanging load lists the kept campaign SLOW_MS after the picker opens, as iOS #55, even while
    // the session's own restore is still waiting on the network (Tess's AE6b: ~12 s). The server's
    // list replaces it if it still arrives. The copy is read off the main thread.
    val opened = remember { SystemClock.elapsedRealtime() }
    fun trace(step: String) = Log.i(TRACE, "picker +${SystemClock.elapsedRealtime() - opened} ms: $step")
    LaunchedEffect(Unit) {
        delay(SLOW_MS)
        trace("kept-campaign timer (list ${list != null}, kept ${kept != null})")
        if (list != null || kept != null) return@LaunchedEffect
        val copy = withContext(Dispatchers.IO) { cache?.keptCampaign() } ?: return@LaunchedEffect
        trace("kept campaign read")
        if (list == null) {
            kept = copy
            status = Wording.PICKER_KEPT
        }
    }

    LaunchedEffect(state.restored, state.generation, reload) {
        if (!state.restored) return@LaunchedEffect
        trace("load starts (generation ${state.generation}, reload $reload)")
        loading = true
        failed = false
        // A retry keeps the kept campaign listed until the live list replaces it.
        status = if (kept != null) Wording.PICKER_KEPT else "Loading campaigns…"
        try {
            val loaded = Campaigns.load(session.client())
            list = loaded
            kept = null
            status = if (loaded.campaigns.isEmpty()) Wording.NO_CAMPAIGNS else ""
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace("load failed: ${e.javaClass.simpleName}")
            list = null
            kept = if (CampaignCache.isOffline(e)) cache?.keptCampaign() else null
            status = if (kept != null) Wording.PICKER_KEPT else errorMessage(e)
            failed = true
        } finally {
            loading = false
        }
    }

    // In a dead spot with only the kept campaign showing, try again by itself: 10 s, doubling to a
    // minute, while the picker is on screen (as iOS #58, Tess's AB11). The live list replaces it.
    var retries by remember { mutableStateOf(0) }
    LaunchedEffect(failed, kept) {
        if (!failed || kept == null) {
            if (list != null) retries = 0
            return@LaunchedEffect
        }
        delay(retryDelayMs(retries++))
        reload++
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
                        if (failed) TextButton(
                            onClick = { reload++ },
                            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = Wording.RETRY_CAMPAIGNS },
                        ) { Text("Retry") }
                    }
                    kept?.let { challenge ->
                        item(key = "kept") {
                            CampaignRow(challenge) { onCampaign(challenge.id.value) }
                            HorizontalDivider()
                        }
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
                            guestsAvailable = session.canAnswerAsGuest,
                            onSignIn = { appScope.launch { signIn() } },
                            onSignOut = { appScope.launch { session.signOut() } },
                        )
                        DeleteMyData(session, outbox, onDeletedMyData)
                        // Test builds name their backend; participants on prod don't need to see it.
                        if (session.config.baseUrl != AppConfig.PROD_ORIGIN) Text(
                            session.config.baseUrl + if (session.config.writesAllowed) "" else " (read only)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Debug builds only: why this phone can or can't answer as a guest.
                        if (BuildConfig.DEBUG) Text(
                            guestDiagnostic(session.config.guests, session.config.writesAllowed, state.guestRefusal),
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
/** The picker's own retry with no signal: 10 s, then doubling, at most a minute. */
internal fun retryDelayMs(attempt: Int): Long = (10_000L shl attempt.coerceAtMost(3)).coerceAtMost(60_000L)

internal const val CAMPAIGN_LIST_TAG = "campaign-list"
