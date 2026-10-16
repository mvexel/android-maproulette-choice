package lol.osm.streettally.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.AppConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.Transport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pulling the campaign list down reloads it: a campaign published meanwhile (in the admin) shows up
 * without restarting the app (Martijn's report after publishing on dev). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickerRefreshTest {
    @get:Rule val compose = createComposeRule()

    /** Serves `challenges/extendedFind`: nothing published until [published] is set. */
    private class Backend : Transport {
        @Volatile var published = false
        @Volatile var searches = 0
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (!URI(request.url).path.endsWith("challenges/extendedFind")) return HttpResponse(404)
            searches += 1
            val tags = if (published) """["mobile-survey-v1"]""" else "[]"
            return HttpResponse(
                200,
                body = """[{"id":3,"parent":1,"name":"Woodbine entrance doors","description":"Doors near the food hall.",""" +
                    """"enabled":true,"isArchived":false,"cooperativeType":3,"tags":$tags}]""",
            )
        }
    }

    private val config = AppConfig(
        baseUrl = "https://mr-api.osm.lol",
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
    )

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun pullingDownShowsANewlyPublishedCampaign() {
        val backend = Backend()
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        runBlocking { session.restore() }
        compose.setContent {
            PickerScreen(
                session = session, appScope = CoroutineScope(Dispatchers.Unconfined),
                outbox = null, signIn = { error("no sign-in in this test") }, onCampaign = {},
            )
        }
        waitForText(Wording.NO_CAMPAIGNS.take(20))
        assertEquals(1, backend.searches)

        backend.published = true
        compose.onNodeWithTag(CAMPAIGN_LIST_TAG).performTouchInput { swipeDown() }
        waitForText("Woodbine entrance doors")
        assertEquals(2, backend.searches)
    }
}
