package lol.osm.streettally.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignLink
import lol.osm.streettally.campaign.CampaignPlaces
import lol.osm.streettally.campaign.CampaignProgress
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.HeldTasks
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.errorMessage
import lol.osm.streettally.location.LocateResult
import lol.osm.streettally.location.Locator
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
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val guestInfo by session.guest.collectAsState()
    var places by remember { mutableStateOf<CampaignPlaces?>(null) }
    // Open places a guest's saved answer holds: counted as checked, not shown or offered.
    var held by remember { mutableStateOf(emptySet<Long>()) }
    var status by remember { mutableStateOf("Loading…") }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var origin by remember { mutableStateOf<LatLon?>(null) }
    var locationNote by remember { mutableStateOf<String?>(null) }
    var locationPermitted by remember { mutableStateOf(Locator.permitted(context)) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(if (preview) PreviewPhase.OPENING else null) }
    // The welcome card in preview: shown on each visit, never stored (§4.4).
    var previewWelcomeSeen by remember { mutableStateOf(false) }
    val previewing = phase == PreviewPhase.ACTIVE || phase == PreviewPhase.PUBLISHED_NOW

    fun locate() {
        locationNote = "Finding your location…"
        scope.launch {
            locationNote = when (val result = Locator.locate(context)) {
                is LocateResult.Found -> { origin = result.position; "Nearest first." }
                LocateResult.Denied -> "Location is off, so the list isn't sorted by distance."
                LocateResult.Unavailable -> "Your location isn't available right now, so the list isn't sorted by distance."
                LocateResult.TimedOut -> "No location yet, so the list isn't sorted by distance."
            }
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
    LaunchedEffect(Unit) {
        if (preview) return@LaunchedEffect // Shown once the draft is open; nothing is stored.
        prefs.lastCampaign = campaignId
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
    LaunchedEffect(state.generation, reload) {
        if (!state.restored) return@LaunchedEffect
        if (preview && state.account == null) {
            // Drafts are readable only by their organizers (§3): a guest or signed out can't preview.
            phase = PreviewPhase.SIGN_IN_NEEDED
            return@LaunchedEffect
        }
        loading = true
        failed = false
        if (!preview) launch { session.refreshGuestStatus() }
        try {
            val loaded = Campaigns.places(session.client(), ChallengeId(campaignId))
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
            // Preview hides the tally, so held places aren't looked up.
            val heldNow = if (preview) emptySet() else HeldTasks.load(session.client(), ChallengeId(campaignId), loaded.tasks)
            places = loaded
            held = heldNow
            status = when {
                Campaigns.shown(loaded.tasks).isNotEmpty() -> ""
                preview -> Wording.previewNoPlaces(loaded.noun)
                else -> "Nothing to check in this campaign yet."
            }
            if (welcomeDue && !prefs.hasSeenWelcome(campaignId) && sheet == null) sheet = Sheet.WELCOME
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (preview && !previewing && refused(e)) {
                phase = PreviewPhase.NO_ACCESS
                return@LaunchedEffect
            }
            if (places == null) status = errorMessage(e)
            failed = true
        } finally {
            loading = false
        }
    }

    val current = places
    val noun = current?.noun ?: lol.osm.streettally.campaign.Noun.PLACE
    // Places saved on this phone count as checked straight away; a guest's show as saved, not on the map.
    val justDone = if (preview) emptySet() else checkedLog.done(campaignId).toSet()
    val reported = if (preview) emptySet() else checkedLog.reported(campaignId).toSet()
    val tally = remember(current, held, justDone, reported, state.guest) {
        CampaignProgress(current?.tasks.orEmpty(), held, justDone, guest = state.guest, reported = reported)
    }
    val shown = tally.shown
    val done = tally.done
    val open = Places.sorted(tally.open, origin)
    val checked = Places.sorted(shown.filter { it.id in done }, origin)
    val look = Places.sorted(shown.filter { it.id in tally.look }, origin)
    // Held places are not offered by "Next nearest" either.
    fun open(task: LocatedTask) { current?.let { onPlace(task, it.copy(tasks = it.tasks.filter { t -> t.id !in held }), origin) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.challenge?.name ?: "Campaign", maxLines = 1) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Tally and progress are hidden in preview: nothing changes them (§4.2).
                if (tally.total > 0 && !preview) {
                    Text(
                        Wording.progress(tally.checked, tally.total, noun),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { tally.checked.toFloat() / tally.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val today = checkedLog.today(campaignId)
                    if (today > 0) Text("You've checked $today today", style = MaterialTheme.typography.bodyMedium)
                    val saved = guestInfo.status
                    if (state.guest && saved != null) {
                        GuestSavedSection(saved, noun, onLink = { sheet = Sheet.PUT_ON_MAP }, onDoneForToday = { sheet = Sheet.DAY_SUMMARY })
                    }
                } else if (tally.total == 0 || loading || failed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) CircularProgressIndicator()
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                } else if (tally.total > 0) {
                    Text(Wording.allChecked(noun), style = MaterialTheme.typography.titleMedium)
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !showList, onClick = { showList = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Map") }
                    SegmentedButton(
                        selected = showList, onClick = { showList = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("List") }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (showList) {
                    PullToRefreshBox(
                        isRefreshing = loading && places != null,
                        onRefresh = { reload++ },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlaceList(open, look, checked, tally.pending, origin, locationNote, noun) { open(it) }
                    }
                } else {
                    PlacesMap(
                        styleUrl = session.config.mapStyleUrl,
                        places = shown,
                        done = done,
                        pending = tally.pending,
                        look = tally.look,
                        locationPermitted = locationPermitted,
                        onPlace = { id -> shown.firstOrNull { it.id == id }?.takeIf { it.isOpen && it.id !in done }?.let(::open) },
                        modifier = Modifier.fillMaxSize(),
                    )
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
        Sheet.PUT_ON_MAP -> PutOnMapSheet(session, noun, onSignIn = onSignIn) { sheet = null }
        Sheet.DAY_SUMMARY -> DaySummarySheet(
            session, checkedToday = checkedLog.today(campaignId), done = tally.checked, total = tally.total, noun = noun,
            onSignIn = onSignIn,
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

@Composable
private fun PlaceList(
    open: List<LocatedTask>,
    /** Reported here (not here, moved, status 6): still openable, never offered as nearest. */
    look: List<LocatedTask>,
    checked: List<LocatedTask>,
    pending: Set<Long>,
    origin: LatLon?,
    locationNote: String?,
    noun: lol.osm.streettally.campaign.Noun,
    onOpen: (LocatedTask) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (open.isNotEmpty()) item { SectionHeader("To check") }
        items(open, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = false, onClick = { onOpen(task) })
            HorizontalDivider()
        }
        locationNote?.let {
            item {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (look.isNotEmpty()) item { SectionHeader(Wording.NEEDS_LOOK) }
        items(look, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = false, look = true, onClick = { onOpen(task) })
            HorizontalDivider()
        }
        if (checked.isNotEmpty()) item { SectionHeader("Already checked") }
        items(checked, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = true, pending = task.id in pending, onClick = null)
            HorizontalDivider()
        }
    }
}

/** A place's state for TalkBack, as iOS's VoiceOver value. */
private fun placeState(done: Boolean, pending: Boolean, look: Boolean = false): String? = when {
    look -> Wording.NEEDS_LOOK
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
) {
    val name = Wording.placeName(task.task, noun)
    val distance = Places.rowText(origin, task.position)
    val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(name, distance, placeState(done, pending, look)).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) Text("✓ ", color = color)
        Text(name, color = color)
        Spacer(Modifier.weight(1f))
        distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
