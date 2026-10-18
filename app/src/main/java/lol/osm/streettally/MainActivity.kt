package lol.osm.streettally

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import lol.osm.streettally.auth.BrowserSignIn
import lol.osm.streettally.campaign.CampaignLink
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.TaskReport
import lol.osm.streettally.demo.DemoBackend
import lol.osm.streettally.ui.CampaignScreen
import lol.osm.streettally.ui.PickerScreen
import lol.osm.streettally.ui.PlaceScreen
import lol.osm.streettally.ui.StreetTallyTheme

/** The place being answered and the campaign context it was opened from. */
private class OpenPlace(
    val taskId: Long, val candidates: List<LocatedTask>, val noun: Noun, val origin: LatLon?, val reports: Map<Long, TaskReport>,
)

class MainActivity : ComponentActivity() {
    /** A campaign a link asked to open, and whether it asked for preview, until the UI takes it. */
    private var linked by mutableStateOf<Pair<Long, Boolean>?>(null)
    /** The demo build's place to open once its campaign loads (the `task` extra). */
    private var demoTask by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            linked = campaignFrom(intent)
            demoFrom(intent)
        }
        val app = application as StreetTallyApp
        val authenticate: suspend (String) -> String = { url -> BrowserSignIn.authenticate(this, url) }
        setContent {
            StreetTallyTheme {
                // Picker → campaign → place. The campaign screen keeps its saveable state (map or
                // list) while a place is open and reloads its places when it comes back.
                val saved = rememberSaveableStateHolder()
                // Reopen the last campaign once per launch; Back leads to the campaign list.
                var campaignId by rememberSaveable { mutableStateOf(if (savedInstanceState == null) app.prefs.lastCampaign else null) }
                var place by remember { mutableStateOf<OpenPlace?>(null) }
                // The open place's task, kept across recreation (dark mode, font size): the campaign
                // screen reopens it once its places load (Tess, #36 emulator pass).
                var placeId by rememberSaveable { mutableStateOf<Long?>(null) }
                fun closePlace() {
                    // Answers in progress are kept only while the place is open.
                    placeId?.let { saved.removeState("place-$it") }
                    place = null
                    placeId = null
                }
                // A preview link (design/preview-mode.md) opens its campaign in preview. [visit] is new
                // for each open, so opening the link again reloads the campaign and starts afresh.
                var previewing by rememberSaveable { mutableStateOf(false) }
                var visit by remember { mutableIntStateOf(0) }
                val previewSaved = remember(visit) { mutableSetOf<Long>() }
                // A one-time note on the campaign screen (a preview link to a published campaign).
                var campaignNotice by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(linked) {
                    linked?.let { (linkedId, preview) ->
                        campaignId = linkedId
                        previewing = preview
                        campaignNotice = null
                        visit += 1
                        closePlace()
                        linked = null
                    }
                }
                fun exitPreview() {
                    closePlace()
                    campaignNotice = null
                    previewing = false
                    campaignId = null
                }
                val id = campaignId
                val open = place
                when {
                    id == null -> PickerScreen(
                        session = app.session,
                        appScope = app.scope,
                        outbox = app.outbox,
                        cache = app.cache,
                        signIn = { app.signIn(authenticate) },
                        onDeletedMyData = { app.checked.forgetAll() },
                        onCampaign = {
                            previewing = false
                            campaignNotice = null
                            campaignId = it
                        },
                    )
                    open != null -> {
                        BackHandler { closePlace() }
                        saved.SaveableStateProvider("place-${open.taskId}") { PlaceScreen(
                            session = app.session,
                            checked = app.checked,
                            outbox = app.outbox,
                            cache = app.cache,
                            campaignId = id,
                            firstTaskId = open.taskId,
                            candidates = open.candidates,
                            noun = open.noun,
                            origin = open.origin,
                            onSignIn = { app.scope.launch { app.signIn(authenticate) } },
                            onBack = { closePlace() },
                            previewSaved = if (previewing) previewSaved else null,
                            onExitPreview = { exitPreview() },
                            reports = open.reports,
                        ) }
                    }
                    else -> saved.SaveableStateProvider("campaign-$id-$previewing-$visit") {
                        BackHandler { exitPreview() }
                        CampaignScreen(
                            session = app.session,
                            prefs = app.prefs,
                            checkedLog = app.checked,
                            outbox = app.outbox,
                            cache = app.cache,
                            networkBack = app.networkReturns.collectAsState().value,
                            onReachable = { app.sendQueued() },
                            campaignId = id,
                            onBack = { exitPreview() },
                            onPlace = { task, places, origin ->
                                place = OpenPlace(task.id, places.tasks, places.noun, origin, places.reports)
                                placeId = task.id
                            },
                            preview = previewing,
                            onSignIn = { app.scope.launch { app.signIn(authenticate) } },
                            onOpenForReal = { alreadyPublished ->
                                previewing = false
                                visit += 1
                                campaignNotice = if (alreadyPublished) Wording.PREVIEW_ALREADY_PUBLISHED else null
                            },
                            notice = campaignNotice,
                            openTask = demoTask ?: placeId,
                            onOpenTask = { demoTask = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        campaignFrom(intent)?.let { linked = it }
        demoFrom(intent)
    }

    /** Dark mode arrives here instead of recreating the activity (configChanges="uiMode"); Compose and
     * the maps follow on their own, the system bars and the window behind them need telling. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
        val theme = resources.newTheme().apply { applyStyle(R.style.Theme_StreetTally, true) }
        val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        window.setBackgroundDrawable(attrs.getDrawable(0))
        attrs.recycle()
    }

    override fun onPause() {
        super.onPause()
        BrowserSignIn.onHostPaused()
    }

    override fun onResume() {
        super.onResume()
        BrowserSignIn.onHostResumed()
        (application as StreetTallyApp).sendQueued()
    }

    /** Demo build only: `adb shell am start -n <app>/lol.osm.streettally.MainActivity --es demo crossings
     * --es task 1012` opens that demo campaign (trees, the default; crossings; buildings) at that place
     * (a task id, 1001 onwards, or the feature's OSM id). `--ei onmap 1` has a local mapper already put
     * that many of the guest's answers on the map. `--ez offline true` is a dead spot: every request
     * fails as if there were no connection, until `--ez offline false`, which also sends what's waiting
     * and refreshes a campaign shown from the phone's copy. `--es demo large --ei places 5200` serves
     * the trees survey copied to that many places (`--ei checked 2000` has the first 2,000 checked), and `--ei latency 300` holds every answer back that
     * many milliseconds (open-time checks; L1). `--ez hang true` leaves every request unanswered until
     * `--ez hang false`, and `--ez cold true` has the phone's copy read from its file again, as after
     * a restart. */
    private fun demoFrom(intent: Intent?) {
        if (!BuildConfig.DEMO || intent == null) return
        if (intent.hasExtra(DEMO_ON_MAP)) (application as StreetTallyApp).demo?.onMap = intent.getIntExtra(DEMO_ON_MAP, 0).coerceAtLeast(0)
        if (intent.hasExtra(DEMO_OFFLINE)) {
            val app = application as StreetTallyApp
            val offline = intent.getBooleanExtra(DEMO_OFFLINE, false)
            app.demo?.offline = offline
            if (!offline) app.networkBack()
        }
        if (intent.hasExtra(DEMO_PLACES)) (application as StreetTallyApp).demo?.places = intent.getIntExtra(DEMO_PLACES, DemoBackend.LARGE_PLACES).coerceAtLeast(1)
        if (intent.hasExtra(DEMO_CHECKED)) (application as StreetTallyApp).demo?.checked = intent.getIntExtra(DEMO_CHECKED, 0).coerceAtLeast(0)
        if (intent.hasExtra(DEMO_HANG)) (application as StreetTallyApp).demo?.hang = intent.getBooleanExtra(DEMO_HANG, false)
        if (intent.getBooleanExtra(DEMO_COLD, false)) (application as StreetTallyApp).cache.forget()
        if (intent.hasExtra(DEMO_LATENCY)) (application as StreetTallyApp).demo?.latencyMs = intent.getIntExtra(DEMO_LATENCY, 0).coerceAtLeast(0).toLong()
        val switched = intent.getStringExtra(DEMO_CAMPAIGN)?.let { (application as StreetTallyApp).demo?.use(it) } == true
        // A task id, or the feature's OSM element: node/<id>, way/<id> or the bare id.
        val task = intent.getStringExtra(DEMO_TASK)?.substringAfterLast('/')?.toLongOrNull()
            ?: intent.getLongExtra(DEMO_TASK, -1L).takeIf { it > 0 }
        if (!switched && task == null) return
        linked = ((application as StreetTallyApp).demo?.campaignId ?: DemoBackend.CAMPAIGN) to false
        demoTask = task
    }

    private fun campaignFrom(intent: Intent?): Pair<Long, Boolean>? {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return null
        return CampaignLink.challenge(link)?.let { it to CampaignLink.isPreview(link) }
    }
}

private const val DEMO_TASK = "task"
private const val DEMO_CAMPAIGN = "demo"
private const val DEMO_ON_MAP = "onmap"
private const val DEMO_OFFLINE = "offline"
private const val DEMO_PLACES = "places"
private const val DEMO_CHECKED = "checked"
private const val DEMO_LATENCY = "latency"
private const val DEMO_HANG = "hang"
private const val DEMO_COLD = "cold"
