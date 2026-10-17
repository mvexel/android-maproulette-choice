package lol.osm.streettally.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.AppConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignCache
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.Held
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.demo.DemoBackend
import lol.osm.streettally.task.SLOW_MS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.Transport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A campaign list that hangs shows the kept campaign after SLOW_MS, and the server's list still
 * replaces it if it arrives (as iOS #55, Tess's AB9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickerSlowTest {
    @get:Rule val compose = createComposeRule()

    /** `challenges/extendedFind` waits for [answer]. */
    private class Hanging : Transport {
        val answer = CompletableDeferred<Unit>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (!URI(request.url).path.endsWith("challenges/extendedFind")) return HttpResponse(404)
            answer.await()
            return HttpResponse(
                200,
                body = """[{"id":3,"parent":1,"name":"Woodbine entrance doors","description":"Doors near the food hall.",""" +
                    """"enabled":true,"isArchived":false,"cooperativeType":3,"tags":["mobile-survey-v1"]}]""",
            )
        }
    }

    private class Memory(var value: String? = null) : CampaignCache.Store {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private val config = AppConfig(
        baseUrl = "https://mr-api.osm.lol", oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice", allowLiveWrites = false,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron", campaignLinkHost = "streettally.osm.lol",
    )

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun aHangingListShowsTheKeptCampaignThenTheServers() {
        val demo = config.copy(baseUrl = DemoBackend.ORIGIN, demo = true)
        val backend = DemoBackend(File("src/demo/assets/${DemoBackend.ASSET}").readText())
        val cache = CampaignCache(Memory())
        runBlocking {
            cache.save(Campaigns.places(MapRouletteClient(demo.environment(), backend), ChallengeId(DemoBackend.CAMPAIGN)), Held())
        }
        val kept = cache.keptCampaign()!!.name

        val wire = Hanging()
        val session = AppSession(config, InMemoryCredentialStore(), wire)
        runBlocking { session.restore() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PickerScreen(
                session = session, appScope = CoroutineScope(Dispatchers.Unconfined), outbox = null, cache = cache,
                signIn = { error("no sign-in in this test") }, onCampaign = {},
            )
        }
        compose.mainClock.advanceTimeBy(SLOW_MS - 1_000)
        compose.onNodeWithText(Wording.PICKER_KEPT).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.advanceTimeByFrame()
        waitForText(Wording.PICKER_KEPT)
        compose.onNodeWithText(kept).assertExists()

        wire.answer.complete(Unit)
        compose.mainClock.autoAdvance = true
        waitForText("Woodbine entrance doors")
        compose.onNodeWithText(Wording.PICKER_KEPT).assertDoesNotExist()
    }

    @Test fun theNoSignalRetryBacksOffToAMinute() {
        org.junit.Assert.assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), (0..5).map(::retryDelayMs))
    }
}
