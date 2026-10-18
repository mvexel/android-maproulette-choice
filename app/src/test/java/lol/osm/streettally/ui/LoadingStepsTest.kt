package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.AppConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.demo.DemoBackend
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.Transport
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** While a campaign's later pages still load, its first places show with "Loading trees… 100 so
 * far" in place of the tally, and the tally replaces it once every page is in (L1 step 2, Tess's AE7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp")
class LoadingStepsTest {
    @get:Rule val compose = createComposeRule()

    /** A demo campaign (the trees: 139 places, two pages), with every page after the first held until [rest]. */
    private class SlowPages(private val demo: Transport, private val campaign: Long = DemoBackend.CAMPAIGN) : Transport {
        val rest = CompletableDeferred<Unit>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val uri = URI(request.url)
            if (uri.path.endsWith("/challenge/$campaign/tasks") && uri.rawQuery.orEmpty().split('&').none { it == "page=0" }) {
                rest.await()
            }
            return demo.execute(request)
        }
    }

    private val config = AppConfig(
        baseUrl = DemoBackend.ORIGIN, oauthClientId = "maproulette-ios-choice", callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false, mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol", guests = true, demo = true,
    )

    private fun waitForText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("No \"$text\". Screen:\n" + compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(), e)
        }
    }

    private fun show(wire: Transport, campaign: Long, welcomeSeen: Boolean = true) {
        val session = AppSession(config, InMemoryCredentialStore(), wire)
        runBlocking { session.restore() }
        val prefs = CampaignPrefs(RuntimeEnvironment.getApplication()).apply { if (welcomeSeen) markWelcomeSeen(campaign) }
        compose.setContent {
            CompositionLocalProvider(LocalMapPlaceholder provides { _, modifier, _ -> Box(modifier) }) {
                StreetTallyTheme(dark = false) {
                    CampaignScreen(
                        session = session, prefs = prefs, checkedLog = CheckedLog(),
                        campaignId = campaign, onBack = {}, onPlace = { _, _, _ -> },
                    )
                }
            }
        }
    }

    private fun assertGone(text: String, why: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().let { assertTrue(why, it.isEmpty()) }

    @Test fun theFirstPageShowsWhileTheRestLoads() {
        val wire = SlowPages(DemoBackend(File("src/demo/assets/${DemoBackend.ASSET}").readText()))
        show(wire, DemoBackend.CAMPAIGN)
        waitForText("100 so far")
        assertGone("The tally:", "tally shown while loading")

        wire.rest.complete(Unit)
        waitForText("The tally:")
        assertGone("so far", "loading line still shown")
    }

    /** A first page with every place checked isn't the whole campaign: no "All trees are checked" until
     * every page is in (Tess, AE9). */
    @Test fun aCheckedFirstPageDoesNotSayAllAreChecked() {
        val trees = File("src/demo/assets/${DemoBackend.ASSET}").readText()
        val demo = DemoBackend(mapOf(DemoBackend.TREES to trees, DemoBackend.LARGE to trees)).apply {
            places = 300
            checked = 150
            check(use(DemoBackend.LARGE))
        }
        val campaign = DemoBackend.IDS.getValue(DemoBackend.LARGE)
        val wire = SlowPages(demo, campaign)
        show(wire, campaign)
        waitForText("100 so far")
        assertGone("are checked. Thank you!", "all checked shown while loading")

        wire.rest.complete(Unit)
        waitForText("The tally:")
        assertGone("are checked. Thank you!", "all checked shown with places left")
    }

    /** The welcome says how many places there are, so it waits for every page (Tess, AE13). */
    @Test fun theWelcomeWaitsForEveryPage() {
        val wire = SlowPages(DemoBackend(File("src/demo/assets/${DemoBackend.ASSET}").readText()))
        show(wire, DemoBackend.CAMPAIGN, welcomeSeen = false)
        waitForText("100 so far")
        assertGone("Let's go", "welcome shown with only the first page")

        wire.rest.complete(Unit)
        waitForText("Let's go")
    }
}
