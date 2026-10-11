package lol.osm.streettally

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
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
import lol.osm.streettally.ui.CampaignScreen
import lol.osm.streettally.ui.PickerScreen
import lol.osm.streettally.ui.PlaceScreen
import lol.osm.streettally.ui.StreetTallyTheme

/** The place being answered and the campaign context it was opened from. */
private class OpenPlace(val taskId: Long, val candidates: List<LocatedTask>, val noun: Noun, val origin: LatLon?)

class MainActivity : ComponentActivity() {
    /** A campaign a link asked to open, and whether it asked for preview, until the UI takes it. */
    private var linked by mutableStateOf<Pair<Long, Boolean>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) linked = campaignFrom(intent)
        val app = application as StreetTallyApp
        val privateSignIn = BrowserSignIn.privateTabsSupported(this)
        val authenticate: suspend (String) -> String = { url -> BrowserSignIn.authenticate(this, url) }
        setContent {
            StreetTallyTheme {
                // Picker → campaign → place. The campaign screen keeps its saveable state (map or
                // list) while a place is open and reloads its places when it comes back.
                val saved = rememberSaveableStateHolder()
                // Reopen the last campaign once per launch; Back leads to the campaign list.
                var campaignId by rememberSaveable { mutableStateOf(if (savedInstanceState == null) app.prefs.lastCampaign else null) }
                var place by remember { mutableStateOf<OpenPlace?>(null) }
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
                        place = null
                        linked = null
                    }
                }
                fun exitPreview() {
                    place = null
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
                        privateSignIn = privateSignIn,
                        authenticate = authenticate,
                        onCampaign = {
                            previewing = false
                            campaignNotice = null
                            campaignId = it
                        },
                    )
                    open != null -> {
                        BackHandler { place = null }
                        PlaceScreen(
                            session = app.session,
                            checked = app.checked,
                            campaignId = id,
                            firstTaskId = open.taskId,
                            candidates = open.candidates,
                            noun = open.noun,
                            origin = open.origin,
                            onSignIn = { app.scope.launch { app.session.signIn(authenticate) } },
                            onBack = { place = null },
                            previewSaved = if (previewing) previewSaved else null,
                            onExitPreview = { exitPreview() },
                            firstSavedForLater = { app.prefs.takePutOnMapCard() },
                        )
                    }
                    else -> saved.SaveableStateProvider("campaign-$id-$previewing-$visit") {
                        BackHandler { exitPreview() }
                        CampaignScreen(
                            session = app.session,
                            prefs = app.prefs,
                            checkedLog = app.checked,
                            campaignId = id,
                            onBack = { exitPreview() },
                            onPlace = { task, places, origin -> place = OpenPlace(task.id, places.tasks, places.noun, origin) },
                            preview = previewing,
                            onSignIn = { app.scope.launch { app.session.signIn(authenticate) } },
                            onOpenForReal = { alreadyPublished ->
                                previewing = false
                                visit += 1
                                campaignNotice = if (alreadyPublished) Wording.PREVIEW_ALREADY_PUBLISHED else null
                            },
                            notice = campaignNotice,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        campaignFrom(intent)?.let { linked = it }
    }

    override fun onPause() {
        super.onPause()
        BrowserSignIn.onHostPaused()
    }

    override fun onResume() {
        super.onResume()
        BrowserSignIn.onHostResumed()
    }

    private fun campaignFrom(intent: Intent?): Pair<Long, Boolean>? {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return null
        return CampaignLink.challenge(link)?.let { it to CampaignLink.isPreview(link) }
    }
}
