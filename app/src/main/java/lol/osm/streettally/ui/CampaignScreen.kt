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
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.errorMessage
import lol.osm.streettally.location.LocateResult
import lol.osm.streettally.location.Locator
import org.maproulette.sdk.ChallengeId

/** One campaign: the tally, the nearest place still to check, and all its places on a map or in
 * a list sorted by distance. Checked places stay visible, greyed out. Mirrors the iOS screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignScreen(
    session: AppSession,
    prefs: CampaignPrefs,
    checked: CheckedLog,
    campaignId: Long,
    onBack: () -> Unit,
    /** The tapped place, the campaign's places, and where the person is (if known). */
    onPlace: (LocatedTask, CampaignPlaces, LatLon?) -> Unit,
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    var places by remember { mutableStateOf<CampaignPlaces?>(null) }
    var status by remember { mutableStateOf("Loading…") }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var origin by remember { mutableStateOf<LatLon?>(null) }
    var locationNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locate() }

    fun askLocation() {
        if (Locator.permitted(context)) locate() else permission.launch(Locator.PERMISSIONS)
    }
    // First visit: the welcome card explains location before the system asks.
    var sheet by remember { mutableStateOf(if (prefs.hasSeenWelcome(campaignId)) null else Sheet.WELCOME) }
    LaunchedEffect(Unit) {
        prefs.lastCampaign = campaignId
        if (sheet == null) askLocation()
    }
    fun closeWelcome() {
        val first = !prefs.hasSeenWelcome(campaignId)
        prefs.markWelcomeSeen(campaignId)
        sheet = null
        if (first || origin == null) askLocation()
    }
    LaunchedEffect(state.generation, reload) {
        if (!state.restored) return@LaunchedEffect
        loading = true
        failed = false
        try {
            val loaded = Campaigns.places(session.client(), ChallengeId(campaignId))
            places = loaded
            status = if (Campaigns.shown(loaded.tasks).isEmpty()) "Nothing to check in this campaign yet." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (places == null) status = errorMessage(e)
            failed = true
        } finally {
            loading = false
        }
    }

    val current = places
    val noun = current?.noun ?: lol.osm.streettally.campaign.Noun.PLACE
    val shown = current?.let { Campaigns.shown(it.tasks) }.orEmpty()
    val done = shown.filter { it.isDone }.map { it.id }.toSet()
    val open = Places.sorted(shown.filter { it.isOpen }, origin)
    val checked = Places.sorted(shown.filter { it.isDone }, origin)
    fun open(task: LocatedTask) { current?.let { onPlace(task, it, origin) } }

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
                    IconButton(onClick = { sheet = Sheet.WELCOME }) {
                        Icon(Icons.Filled.Info, contentDescription = "About this campaign")
                    }
                    IconButton(onClick = { sheet = Sheet.SHARE }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share campaign")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shown.isNotEmpty()) {
                    Text(
                        Wording.progress(done.size, shown.size, noun),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { done.size.toFloat() / shown.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val today = checked.today(campaignId)
                    if (today > 0) Text("You've checked $today today", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) CircularProgressIndicator()
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (failed) TextButton(onClick = { reload++ }) { Text("Retry") }
                    }
                }
                val nearest = open.firstOrNull()
                if (nearest != null) {
                    Button(onClick = { open(nearest) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(Wording.nearest(noun, Places.distanceText(origin, nearest.position)))
                    }
                } else if (shown.isNotEmpty()) {
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
                    PlaceList(open, checked, origin, locationNote, noun) { open(it) }
                } else {
                    PlacesMap(
                        styleUrl = session.config.mapStyleUrl,
                        places = shown,
                        done = done,
                        onPlace = { id -> shown.firstOrNull { it.id == id }?.takeIf { it.isOpen }?.let(::open) },
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
        )
        Sheet.SHARE -> ShareDialog(name, CampaignLink.webUrl(campaignId, session.config.campaignLinkHost)) { sheet = null }
        null -> Unit
    }
}

private enum class Sheet { WELCOME, SHARE }

@Composable
private fun PlaceList(
    open: List<LocatedTask>,
    checked: List<LocatedTask>,
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
        if (checked.isNotEmpty()) item { SectionHeader("Already checked") }
        items(checked, key = { it.id }) { task ->
            PlaceRow(task, origin, noun, done = true, onClick = null)
            HorizontalDivider()
        }
    }
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
                contentDescription = listOfNotNull(name, distance, if (done) "Already checked" else null).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) Text("✓ ", color = color)
        Text(name, color = color)
        Spacer(Modifier.weight(1f))
        distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
