package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.printToString
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.AppConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.demo.DemoBackend
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.InMemoryCredentialStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The demo build's campaign screen, as `--es demo trees --es task <id>` opens it (Tess, emulator):
 * the places load from [DemoBackend] and the asked-for place opens, by task id or by node. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp")
class DemoCampaignTest {
    @get:Rule val compose = createComposeRule()

    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val backend = DemoBackend(
        DemoBackend.ASSETS.mapValues { (_, asset) -> File("src/demo/assets/$asset").readText() },
        log = { requests += it },
    )
    private val config = AppConfig(
        baseUrl = DemoBackend.ORIGIN, oauthClientId = "maproulette-ios-choice", callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false, mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol", guests = true, demo = true,
    )

    private fun opened(task: Long): Long? {
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        runBlocking { session.restore() }
        var place: Long? = null
        compose.setContent {
            // MapLibre has no native library here: the maps are placeholders, as in the journey tests.
            CompositionLocalProvider(LocalMapPlaceholder provides { _, modifier, _ -> Box(modifier) }) {
                StreetTallyTheme(dark = false) {
                    CampaignScreen(
                        session = session, prefs = CampaignPrefs(RuntimeEnvironment.getApplication()), checkedLog = CheckedLog(),
                        campaignId = DemoBackend.CAMPAIGN, onBack = {}, onPlace = { t, _, _ -> place = t.id },
                        openTask = task,
                    )
                }
            }
        }
        try {
            compose.waitUntil(10_000) { place != null }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "No place opened. Requests:\n" + requests.joinToString("\n") + "\nScreen:\n" +
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(),
                e,
            )
        }
        return place
    }

    @Test fun opensATreeByTaskId() = assertEquals(1093L, opened(1093))

    /** Task 1093 is node/13900294463. */
    @Test fun opensATreeByNode() = assertEquals(1093L, opened(13900294463))
}
