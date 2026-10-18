package lol.osm.streettally.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignCache
import lol.osm.streettally.campaign.CampaignLink
import lol.osm.streettally.campaign.CampaignPlaces
import lol.osm.streettally.campaign.CampaignProgress
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.Held
import lol.osm.streettally.campaign.HeldTasks
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.TaskReport
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.answersHere
import lol.osm.streettally.campaign.errorMessage
import lol.osm.streettally.location.LocateResult
import lol.osm.streettally.location.Locator
import lol.osm.streettally.task.GuestOutbox
import lol.osm.streettally.task.SLOW_MS
import org.maproulette.sdk.ChallengeAnswerCounts
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException

/** One campaign: the tally, the nearest place still to check, and all its places on a map or in
 * a list sorted by distance. Checked places stay visible, greyed out. Mirrors the iOS screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignScreen(
    session: AppSession,
    prefs: CampaignPrefs,
    checkedLog: CheckedLog,
    /** A guest's answers still on this phone: shown as waiting to send, not counted until sent. */
    outbox: GuestOutbox? = null,
    /** The last campaign opened, kept for dead spots (BACKLOG O2); null in previews and tests. */
    cache: CampaignCache? = null,
    /** Goes up each time the connection comes back: a campaign shown from [cache] refreshes. */
    networkBack: Int = 0,
    /** The server answered a campaign load: a chance to send answers still waiting on the phone. */
    onReachable: () -> Unit = {},
    campaignId: Long,
    onBack: () -> Unit,
    /** The tapped place, the campaign's places, and where the person is (if known). */
    onPlace: (LocatedTask, CampaignPlaces, LatLon?) -> Unit,
    /** Opened from a preview link (design/preview-mode.md): nothing is remembered on the phone. */
    preview: Boolean = false,
    onSignIn: () -> Unit = {},
    /** Reopens the campaign without preview; [alreadyPublished]: it was published when the link opened. */
    onOpenForReal: (alreadyPublished: Boolean) -> Unit = {},
    /** A one-time note above the tally. */
    notice: String? = null,
    /** A place to open once the places load (its task id, or its node id): the demo build's, or the place open before recreation; then [onOpenTask]. */
    openTask: Long? = null,
    onOpenTask: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val guestInfo by session.guest.collectAsState()
    var places by remember { mutableStateOf<CampaignPlaces?>(null) }
    // Open places a guest's saved answer or report holds: shown, never offered. An answer counts as
    // checked; a report needs another look (T-001, T-002).
    var held by remember { mutableStateOf(Held()) }
    // Other people's not-here and moved reports (parity row 4), where this build reads them.
    var serverReports by remember { mutableStateOf(emptyMap<Long, TaskReport>()) }
    val reportSource = LocalReportSource.current
    var status by remember { mutableStateOf("Loading…") }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    // Shown from the phone's copy (no connection): when the server last sent it.
    var keptFrom by remember { mutableStateOf<Long?>(null) }
    // Places are showing while more pages still load: the tally says so instead (as iOS #57).
    var partial by remember { mutableStateOf(false) }
    /** When the current hang began: a reload during it doesn't restart the wait for the kept copy. */
    var waitingSince by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var showList by rememberSaveable { mutableStateOf(false) }
    // Kept while a place is open: coming back must not lose "Nearest" if the new fix is slow or
    // doesn't come (Tess, #38 emulator pass). A new fix still replaces it.
    var origin by rememberSaveable(saver = OriginSaver) { mutableStateOf<LatLon?>(null) }
    var locationPermitted by remember { mutableStateOf(Locator.permitted(context)) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(if (preview) PreviewPhase.OPENING else null) }
    // The welcome card in preview: shown on each visit, never stored (§4.4).
    var previewWelcomeSeen by remember { mutableStateOf(false) }
    val previewing = phase == PreviewPhase.ACTIVE || phase == PreviewPhase.PUBLISHED_NOW

    // Without a location, the line above Map/List says so (Iris, parity row 12); the list has no notes.
    fun locate() {
        scope.launch {
            (Locator.locate(context) as? LocateResult.Found)?.let { origin = it.position }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationPermitted = Locator.permitted(context)
        locate()
    }

    fun askLocation() {
        if (Locator.permitted(context)) locate() else permission.launch(Locator.PERMISSIONS)
    }
    // First visit: the welcome card explains location before the system asks. It opens once the
    // campaign has loaded, so it never shows (and gets marked seen) without its description.
    val welcomeDue = remember { !preview && !prefs.hasSeenWelcome(campaignId) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // Signed in from a sheet here: the sign-in and claim notes show on this screen too.
    var accountNote by remember { mutableStateOf(false) }
    // The place whose name card shows on the map.
    var selected by remember { mutableStateOf<Long?>(null) }
    // The place opened from the map: TalkBack lands back on its pin (iOS #63), not on Back.
    var backTo by rememberSaveable { mutableStateOf<Long?>(null) }
    val signInHere = { accountNote = true; onSignIn() }
    LaunchedEffect(Unit) {
        if (preview) return@LaunchedEffect // Shown once the draft is open; nothing is stored.
        prefs.lastCampaign = campaignId
        // One campaign kept: opening another lets the old copy go.
        withContext(Dispatchers.IO) { cache?.keepOnly(campaignId) }
        if (!welcomeDue) askLocation()
    }
    LaunchedEffect(phase) {
        if (phase == PreviewPhase.ACTIVE && !previewWelcomeSeen) sheet = Sheet.WELCOME
    }
    fun closeWelcome() {
        val first = if (preview) !previewWelcomeSeen else !prefs.hasSeenWelcome(campaignId)
        if (preview) previewWelcomeSeen = true else prefs.markWelcomeSeen(campaignId)
        sheet = null
        if (first || origin == null) askLocation()
    }
    // Back online while showing the phone's copy (or after failing to load): load the server's,
    // which drops places checked or gone meanwhile.
    LaunchedEffect(networkBack) {
        if (keptFrom != null || failed) reload++
    }
    fun showKept(kept: CampaignCache.Snapshot) {
        // A dead spot: the phone's copy, answered through the outbox until the connection is back.
        places = kept.places
        held = kept.held
        keptFrom = kept.savedAt
        status = ""
        if (welcomeDue && !prefs.hasSeenWelcome(campaignId) && sheet == null) sheet = Sheet.WELCOME
    }
    // The first open of a hang counts from the screen opening, not from the session's restore,
    // which can wait on the network too (Tess's AE6b: ~20 s from a cold launch). Read off the main
    // thread: a 5,000-place copy takes a while to decode.
    val opened = remember { SystemClock.elapsedRealtime() }
    // Open-time steps in logcat (AE7): when each one lands, from the screen opening.
    fun trace(step: String) = Log.i(TRACE, "campaign $campaignId +${SystemClock.elapsedRealtime() - opened} ms: $step")
    LaunchedEffect(Unit) {
        if (preview) return@LaunchedEffect
        delay(SLOW_MS)
        if (places != null) return@LaunchedEffect
        trace("kept-copy timer")
        val copy = withContext(Dispatchers.IO) { cache?.get(campaignId) } ?: return@LaunchedEffect
        trace("kept copy read, ${copy.places.tasks.size} places")
        if (places == null) showKept(copy)
    }
    LaunchedEffect(state.generation, reload) {
        if (!state.restored) return@LaunchedEffect
        if (preview && state.account == null) {
            // Drafts are readable only by their organizers (§3): a guest or signed out can't preview.
            phase = PreviewPhase.SIGN_IN_NEEDED
            return@LaunchedEffect
        }
        trace("load starts (generation ${state.generation}, reload $reload, shown ${places?.tasks?.size})")
        loading = true
        failed = false
        if (!preview) launch { session.refreshGuestStatus() }
        // A hanging connection (weak signal) isn't refused for a long while: the kept copy shows
        // after a few seconds instead, until the load lands (as iOS).
        val since = waitingSince ?: System.currentTimeMillis().also { waitingSince = it }
        val slow = if (preview || places != null) null else launch {
            delay((SLOW_MS - (System.currentTimeMillis() - since)).coerceAtLeast(0))
            if (places == null) withContext(Dispatchers.IO) { cache?.get(campaignId) }?.let { if (places == null) showKept(it) }
        }
        // The held search needs no places, so it runs while their pages load (as iOS #57).
        // Preview hides the tally, so held places aren't looked up.
        val flagged = if (preview) null else async(Dispatchers.Default) { HeldTasks.flagged(session.client(), ChallengeId(campaignId)) }
        try {
            // Places show as their pages land (L1 step 2), unless the phone's copy is already up or
            // this is a preview, which first decides what to show. A reload keeps the full list
            // until the new one is in. A busy screen skips steps.
            val fresh = places == null
            var complete: CampaignPlaces? = null
            // The first page shows at once, later ones at most every STEP_MS: each redraws every
            // pin and the tally (as iOS #57).
            var shownAt = 0L
            // Reading and parsing the pages, and the places' sort, run off the main thread.
            var pages = 0
            Campaigns.placesLoading(session.client(), ChallengeId(campaignId)) { trace("page ${++pages} read, ${it} places") }.flowOn(Dispatchers.Default).conflate().collect { step ->
                if (step.complete) {
                    trace("all ${step.places.tasks.size} places read")
                    complete = step.places
                    partial = false
                } else if (shownAt == 0L && !(fresh && !preview && keptFrom == null)) {
                    trace("first page not shown: fresh $fresh, kept ${keptFrom != null}")
                    shownAt = -1L
                } else if (fresh && !preview && keptFrom == null && step.places.challenge.enabled != false &&
                    System.currentTimeMillis() - shownAt >= STEP_MS
                ) {
                    shownAt = System.currentTimeMillis()
                    places = step.places
                    partial = true
                    trace("${step.places.tasks.size} places so far")
                    status = ""
                    // No welcome yet: its count would be the first page's (Tess, AE13: "100 trees", then 5,000).
                }
            }
            val loaded = checkNotNull(complete)
            waitingSince = null
            if (preview) {
                val published = Campaigns.isPublished(loaded.challenge)
                if (published && !previewing) {
                    // Published already: open it normally, with a one-time note (§3 step 3).
                    onOpenForReal(true)
                    return@LaunchedEffect
                }
                phase = if (published) PreviewPhase.PUBLISHED_NOW else PreviewPhase.ACTIVE
            } else if (loaded.challenge.enabled == false) {
                // A draft without its preview link: nothing to answer, even where the server
                // doesn't refuse writes to drafts yet (as on iOS).
                places = loaded.copy(tasks = emptyList())
                status = Wording.NOT_PUBLISHED
                return@LaunchedEffect
            }
            val heldNow = if (flagged == null) Held() else withContext(Dispatchers.Default) { HeldTasks.load(session.client(), ChallengeId(campaignId), loaded.tasks, flagged.await()) }
            places = loaded
            held = heldNow
            keptFrom = null
            trace("held read; all places shown")
            if (!preview) {
                withContext(Dispatchers.IO) { cache?.save(loaded, heldNow) }
                trace("copy kept")
                onReachable()
            }
            // With the places, not after the reports below: the welcome never pops up over a tally
            // already showing.
            if (welcomeDue && !prefs.hasSeenWelcome(campaignId) && sheet == null) sheet = Sheet.WELCOME
            serverReports = if (preview || !session.config.serverReports) emptyMap() else try {
                reportSource.reports(session.client(), ChallengeId(campaignId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap() // The places still work without them.
            }
            status = when {
                Campaigns.shown(loaded.tasks).isNotEmpty() -> ""
                preview -> Wording.previewNoPlaces(loaded.noun)
                else -> "Nothing to check in this campaign yet."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            waitingSince = null
            if (preview && !previewing && refused(e)) {
                phase = PreviewPhase.NO_ACCESS
                return@LaunchedEffect
            }
            val kept = if (preview || !CampaignCache.isOffline(e)) null else withContext(Dispatchers.IO) { cache?.get(campaignId) }
            if (kept != null) {
                showKept(kept)
                return@LaunchedEffect
            }
            trace("load failed: ${e.javaClass.simpleName}")
            Log.w("StreetTally", "Campaign $campaignId didn't load", e)
            if (places == null) status = if (!preview && cache != null && CampaignCache.isOffline(e)) Wording.NOT_KEPT_NO_SIGNAL else errorMessage(e)
            failed = true
        } finally {
            slow?.cancel()
            flagged?.cancel()
            partial = false
            loading = false
        }
    }

    val current = places
    val noun = current?.noun ?: lol.osm.streettally.campaign.Noun.PLACE
    // Places saved on this phone count as checked straight away; a guest's show as saved, not on the map.
    // A guest's answers still on this phone show as waiting to send, and count once sent (offline-copy.md;
    // a guest's only: once signed in, the guest token can't send them).
    val queued = outbox?.entries?.collectAsState()?.value.orEmpty()
    val limitReached = outbox?.limitReached?.collectAsState()?.value == true
    DisposableEffect(limitReached) { onDispose { if (limitReached) outbox?.limitShown() } }
    val notSent = if (preview) 0 else outbox?.notSent?.collectAsState()?.value?.get(campaignId) ?: 0
    // Keyed once: a count that grows while shown is kept until the screen goes.
    val notSentNow by rememberUpdatedState(notSent)
    DisposableEffect(outbox) { onDispose { if (notSentNow > 0) outbox?.notSentShown(campaignId) } }
    // A phone that answered in a dead spot before it could register is a guest too, as far as its
    // waiting answers go (Tess, #45): they register it when they send.
    val guest = state.guest || (!preview && queued.isNotEmpty() && session.canAnswerAsGuest)
    val waiting = if (guest && !preview) queued.filter { it.campaignId == campaignId }.map { it.taskId }.toSet() else emptySet()
    val justDone = if (preview) emptySet() else checkedLog.done(campaignId).toSet()
    // Some sent: reload, so a place sent after a restart shows as saved (the held set says so).
    var lastWaiting by remember { mutableStateOf(waiting) }
    LaunchedEffect(waiting) {
        if (!waiting.containsAll(lastWaiting)) reload++
        lastWaiting = waiting
    }
    // Reported here or by someone else: either way the place needs another look and isn't offered.
    // A guest's saved report holding a place (T-002) counts the same way.
    val reported = if (preview) emptySet() else checkedLog.reported(campaignId).toSet() + serverReports.keys + held.reports
    // Where a moved place was reported to be: a gray dot on the map, joined to its pin.
    val movedTo = if (preview) emptyMap() else (serverReports + checkedLog.reports(campaignId))
        .mapNotNull { (id, r) -> (r as? TaskReport.Moved)?.let { id to it.spot } }.toMap()
    val tally = remember(current, held, justDone, reported, guest, waiting) {
        CampaignProgress(
            current?.tasks.orEmpty(), held.ids, justDone, guest = guest, reported = reported, waiting = waiting, offered = held.offered,
        )
    }
    val shown = tally.shown
    val done = tally.done
    // Sorted once per tally or fix, not on every recomposition (5,000 places, AE6b).
    val open = remember(tally, origin) { Places.sorted(tally.open, origin) }
    val checked = remember(tally, origin) { Places.sorted(shown.filter { it.id in done }, origin) }
    val look = remember(tally, origin) { Places.sorted(shown.filter { it.id in tally.look }, origin) }
    val unsent = remember(tally, origin) { Places.sorted(shown.filter { it.id in tally.waiting }, origin) }
    // Held and gone places are not offered by "Next nearest" either.
    fun open(task: LocatedTask) {
        current?.let { onPlace(task, it.copy(tasks = it.tasks.filter { t -> t.id !in held.ids && t.id !in tally.gone }, reports = serverReports), origin) }
    }

    LaunchedEffect(current, openTask) {
        val wanted = openTask ?: return@LaunchedEffect
        val task = current?.tasks?.firstOrNull { it.id == wanted || it.task.name.substringAfter('/') == "$wanted" } ?: return@LaunchedEffect
        sheet = null
        onOpenTask()
        open(task)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.challenge?.name ?: "Campaign", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reloads places and progress; in preview, the draft's places and questions after an
                    // edit in the admin (§4.3). The list also reloads when pulled down; the map can't.
                    IconButton(onClick = { reload++ }, enabled = (!preview || previewing) && !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { sheet = Sheet.WELCOME }, enabled = !preview || previewing) {
                        Icon(Icons.Filled.Info, contentDescription = "About this campaign")
                    }
                    // A preview link isn't for participants (§4.2).
                    if (!preview) {
                        IconButton(onClick = { sheet = Sheet.SHARE }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share campaign")
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // At large text sizes the block above the map can outgrow the screen: it scrolls within
            // [headerMax] so the map/list keeps the rest instead of being squeezed out.
            val headerMax = maxHeight * 0.6f
            Column(modifier = Modifier.fillMaxSize()) {
                if (previewing) {
                    PreviewBanner(
                        published = phase == PreviewPhase.PUBLISHED_NOW,
                        onExit = onBack,
                        onOpenForReal = { onOpenForReal(false) },
                    )
                }
                when (phase) {
                    PreviewPhase.SIGN_IN_NEEDED -> {
                        PreviewCard(Wording.PREVIEW_SIGN_IN_TITLE, Wording.PREVIEW_SIGN_IN_TEXT,
                            Wording.PREVIEW_SIGN_IN to onSignIn, Wording.CANCEL to onBack)
                        return@Column
                    }
                    PreviewPhase.NO_ACCESS -> {
                        val account = Wording.account(state.account?.userId, state.account?.displayName)
                        PreviewCard(Wording.PREVIEW_NO_ACCESS_TITLE, Wording.previewNoAccessText(account),
                            Wording.SWITCH_ACCOUNT to onSignIn, Wording.CLOSE to onBack)
                        return@Column
                    }
                    else -> Unit
                }
                Column(
                    modifier = Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notice?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    keptFrom?.let {
                        Text(Wording.keptCopy(keptTime(context, it)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // A waiting answer refused for the phone's answer limit: said once, here (offline-copy.md).
                    if (limitReached && guest) Text(Wording.pendingLimit(noun), style = MaterialTheme.typography.bodyMedium)
                    // Waiting answers whose places changed meanwhile: said once, never dropped silently (row 17).
                    if (notSent > 0) Text(Wording.notSent(notSent), style = MaterialTheme.typography.bodyMedium)
                    if (accountNote) AccountNote(state)
                    // Tally and progress are hidden in preview: nothing changes them (§4.2).
                    if (partial && !preview) {
                        // Not a live region: TalkBack doesn't re-announce it for every page.
                        val soFar = current?.tasks?.size ?: 0
                        Text(
                            Wording.loadingSoFar(noun, soFar),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = Wording.loadingSoFarSpoken(noun, soFar) },
                        )
                    } else if (tally.total > 0 && !preview) {
                        Text(
                            Wording.progress(tally.checked, tally.total, noun),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { tally.checked.toFloat() / tally.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Cut off at the app's read limit: the tally covers only the places read (L1).
                        if (current?.truncated == true) Text(Wording.truncatedPlaces(current.tasks.size, noun), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val today = checkedLog.today(campaignId)
                        if (today > 0) Text("You've checked $today today", style = MaterialTheme.typography.bodyMedium)
                        val saved = guestInfo.status
                        if (guest && (saved != null || tally.waiting.isNotEmpty())) {
                            GuestSavedSection(
                                saved?.answersHere(campaignId) ?: ChallengeAnswerCounts.ZERO, noun,
                                onLink = { sheet = Sheet.PUT_ON_MAP }, onDoneForToday = { sheet = Sheet.DAY_SUMMARY },
                                waiting = tally.waiting.size,
                            )
                        }
                    } else if (tally.total == 0 || loading || failed) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (loading) CircularProgressIndicator()
                            // Weighted, so a long line wraps and leaves room for Retry (Tess, #45).
                            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                            if (failed) TextButton(onClick = { reload++ }) { Text("Retry") }
                        }
                    }
                    val nearest = open.firstOrNull()
                    if (nearest != null && origin == null) {
                        // Without a location the list is in id order: nothing is "nearest" (Iris fix 4).
                        Text(Wording.noLocation(noun, locationPermitted), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { showList = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(Wording.choose(noun))
                        }
                        // A denied or timed-out location can be retried here (iOS: More > Update my location).
                        TextButton(onClick = { askLocation() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(Wording.UPDATE_LOCATION)
                        }
                    } else if (nearest != null) {
                        Button(onClick = { open(nearest) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(Wording.nearest(noun, Places.distanceText(origin, nearest.position)))
                        }
                    } else if (tally.total > 0 && !partial) {
                        // Not while pages still load: the first ones can all be checked (Tess, AE9).
                        Text(Wording.allChecked(noun), style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Below the scrolling block so it stays reachable at the largest text sizes.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    SegmentedButton(
                        selected = !showList, onClick = { showList = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Map") }
                    SegmentedButton(
                        selected = showList, onClick = { showList = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("List") }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (showList) {
                        PullToRefreshBox(
                            isRefreshing = loading && places != null,
                            onRefresh = { reload++ },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            PlaceList(open, look, unsent, checked, tally.pending, origin, noun) { open(it) }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            PlacesMap(
                                styleUrl = session.config.mapStyleUrl,
                                places = shown,
                                done = done,
                                pending = tally.pending,
                                look = tally.look,
                                unsent = tally.waiting,
                                movedTo = movedTo,
                                locationPermitted = locationPermitted,
                                // A tap shows the place's name first, as iOS's callout; the card opens it.
                                onPlace = { id -> selected = id },
                                onBackground = { selected = null },
                                modifier = Modifier.fillMaxSize(),
                                // TalkBack reads each pin as its row in the list does; a double tap opens it
                                // where the card would, else shows the card (A11y1).
                                origin = origin,
                                describe = { place ->
                                    placeDescription(
                                        place, origin, noun, done = place.id in done, pending = place.id in tally.pending,
                                        look = place.id in tally.look, waiting = place.id in tally.waiting,
                                    )
                                },
                                describeCluster = { total, left -> Wording.cluster(noun, total, left) },
                                focusPlace = backTo,
                                onFocused = { backTo = null },
                                onActivate = { id ->
                                    val place = shown.firstOrNull { it.id == id }
                                    if (place != null && place.isOpen && id !in done && id !in tally.waiting) {
                                        selected = null
                                        backTo = id
                                        open(place)
                                    } else selected = id
                                },
                            )
                            shown.firstOrNull { it.id == selected }?.let { place ->
                                val opens = place.isOpen && place.id !in done && place.id !in tally.waiting
                                val subtitle = when {
                                    place.id in tally.look -> Wording.NEEDS_LOOK
                                    place.id in tally.waiting -> Wording.WAITING_TO_SEND
                                    place.id in tally.pending -> Wording.SAVED_NOT_ON_MAP
                                    place.id in done -> "Already checked"
                                    else -> Wording.TAP_TO_ANSWER
                                }
                                PlaceCallout(
                                    name = Wording.placeName(place.task, noun),
                                    subtitle = subtitle,
                                    distance = Places.rowText(origin, place.position),
                                    onOpen = if (opens) ({ selected = null; backTo = place.id; open(place) }) else null,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val name = current?.challenge?.name ?: "Campaign"
    when (sheet) {
        Sheet.WELCOME -> WelcomeDialog(
            name = name,
            description = current?.challenge?.description,
            openCount = open.size,
            questions = remember(current) { current?.let { c -> Campaigns.questions(c.tasks.map { it.task }) }.orEmpty() },
            noun = noun,
            signedIn = state.account != null,
            onClose = { closeWelcome() },
            previewingAs = if (previewing) Wording.account(state.account?.userId, state.account?.displayName) ?: "" else null,
            guest = !previewing && session.canAnswerAsGuest,
        )
        Sheet.SHARE -> ShareDialog(name, CampaignLink.webUrl(campaignId, session.config.campaignLinkHost)) { sheet = null }
        Sheet.PUT_ON_MAP -> PutOnMapSheet(session, noun, onSignIn = signInHere) { sheet = null }
        Sheet.DAY_SUMMARY -> DaySummarySheet(
            session, checkedToday = checkedLog.today(campaignId), done = tally.checked, total = tally.total, noun = noun,
            answers = guestInfo.status?.answersHere(campaignId) ?: ChallengeAnswerCounts.ZERO, onSignIn = signInHere,
            waiting = tally.waiting.size,
        ) { sheet = null }
        null -> Unit
    }
}

private enum class Sheet { WELCOME, SHARE, PUT_ON_MAP, DAY_SUMMARY }

/** Where a preview link stands (design/preview-mode.md §3, §5). */
private enum class PreviewPhase {
    /** Reading the campaign to tell a draft from a published one. */
    OPENING,
    /** Not signed in with OpenStreetMap: drafts need an organizer. */
    SIGN_IN_NEEDED,
    /** Signed in, but the draft read was refused (403 or 404). */
    NO_ACCESS,
    ACTIVE,
    /** A refresh found the campaign published. Preview answers are still never sent. */
    PUBLISHED_NOW,
}

/** A draft's read refused (§6: 404 for anyone without organize rights, like an unknown id). */
private fun refused(error: Exception): Boolean = when ((error as? MapRouletteException)?.kind) {
    ErrorKind.NOT_FOUND, ErrorKind.PERMISSION, ErrorKind.AUTHENTICATION -> true
    else -> false
}

@Composable
private fun PreviewCard(title: String, text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text)
        Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(primary.first) }
        TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(secondary.first) }
    }
}

/** A tapped pin's name card (iOS: the callout). Tapping it opens the place when [onOpen] is set. */
@Composable
private fun PlaceCallout(name: String, subtitle: String, distance: String?, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        // Read out when it appears, so a TalkBack user activating a pin hears the card (A11y1).
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(modifier = Modifier.heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PlaceList(
    open: List<LocatedTask>,
    /** Reported here (not here, moved, status 6): still openable, never offered as nearest. */
    look: List<LocatedTask>,
    /** A guest's answers still on this phone: not counted yet, not opened again. */
    unsent: List<LocatedTask>,
    checked: List<LocatedTask>,
    pending: Set<Long>,
    origin: LatLon?,
    noun: lol.osm.streettally.campaign.Noun,
    onOpen: (LocatedTask) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (open.isNotEmpty()) item { SectionHeader("To check") }
        items(open, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = false, onClick = { onOpen(task) })
            HorizontalDivider()
        }
        if (look.isNotEmpty()) item { SectionHeader(Wording.NEEDS_LOOK) }
        items(look, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = false, look = true, onClick = { onOpen(task) })
            HorizontalDivider()
        }
        if (unsent.isNotEmpty()) item { SectionHeader(Wording.WAITING_TO_SEND) }
        items(unsent, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = false, waiting = true, onClick = null)
            HorizontalDivider()
        }
        if (checked.isNotEmpty()) item { SectionHeader("Already checked") }
        items(checked, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = true, pending = task.id in pending, onClick = null)
            HorizontalDivider()
        }
    }
}

/** A place as TalkBack reads it, in the list and on the map: name, distance, state. */
private fun placeDescription(
    task: LocatedTask, origin: LatLon?, noun: lol.osm.streettally.campaign.Noun,
    done: Boolean, pending: Boolean, look: Boolean = false, waiting: Boolean = false,
): String = listOfNotNull(
    Wording.placeName(task.task, noun), Places.rowText(origin, task.position), placeState(done, pending, look, waiting),
).joinToString(", ")

/** A place's state for TalkBack, as iOS's VoiceOver value. */
private fun placeState(done: Boolean, pending: Boolean, look: Boolean = false, waiting: Boolean = false): String? = when {
    look -> Wording.NEEDS_LOOK
    waiting -> Wording.WAITING_TO_SEND
    pending -> Wording.SAVED_NOT_ON_MAP
    done -> "Already checked"
    else -> null
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaceRow(
    task: LocatedTask,
    origin: LatLon?,
    noun: lol.osm.streettally.campaign.Noun,
    done: Boolean,
    onClick: (() -> Unit)?,
    pending: Boolean = false,
    look: Boolean = false,
    waiting: Boolean = false,
) {
    val name = Wording.placeName(task.task, noun)
    val distance = Places.rowText(origin, task.position)
    val description = placeDescription(task, origin, noun, done, pending, look, waiting)
    val color = if (done || waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) Text("✓ ", color = color)
        Text(name, color = color)
        Spacer(Modifier.weight(1f))
        distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The last fix, as a pair of doubles, so it survives leaving for a place and coming back. */
/** logcat tag for open-time steps (`adb logcat -s StreetTallyTime`). */
internal const val TRACE = "StreetTallyTime"

/** The least time between two shown steps of a loading campaign. */
private const val STEP_MS = 500L

private val OriginSaver = Saver<MutableState<LatLon?>, DoubleArray>(
    save = { state -> state.value?.let { doubleArrayOf(it.lat, it.lon) } },
    restore = { mutableStateOf(LatLon(it[0], it[1])) },
)

/** [Wording.keptTime] with the device's own clock and date formats. */
private fun keptTime(context: android.content.Context, savedAt: Long): String = Wording.keptTime(
    savedAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
    clock = { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) },
    day = {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd")
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(it))
    },
)
