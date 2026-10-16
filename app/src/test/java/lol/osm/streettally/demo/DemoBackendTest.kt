package lol.osm.streettally.demo

import java.io.File
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.Footprint
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.answersHere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChallengeAnswerCounts
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.HttpMethod
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.MapRouletteClient

/** The demo build's in-process "Woodbine trees" (the demo flavor's bundled survey file). */
class DemoBackendTest {
    private val backend = DemoBackend(File("src/demo/assets/${DemoBackend.ASSET}").readText())
    private val config = AppConfig(
        baseUrl = DemoBackend.ORIGIN, oauthClientId = "maproulette-ios-choice", callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false, mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol", guests = true, demo = true,
    )

    @Test fun servesEveryTreeAsAChoiceTask() = runTest {
        val places = Campaigns.places(MapRouletteClient(config.environment(), backend), ChallengeId(DemoBackend.CAMPAIGN))
        assertEquals("Woodbine trees", places.challenge.name)
        assertEquals(139, places.tasks.size)
        assertEquals(Noun("tree", "trees"), places.noun)
        assertEquals((1001L..1139L).toList(), places.tasks.map { it.id })
        assertTrue(places.tasks.single { it.id == 1092L }.isOpen)
        assertTrue(places.tasks.single { it.id == 1090L }.isDone)
    }

    /** As the app loads it: a guest first, so the SDK's checks on the guest token apply (Tess). */
    @Test fun aGuestSessionLoadsTheCampaign() = runTest {
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        session.restore()
        assertTrue(session.ensureGuest())
        assertEquals(139, Campaigns.places(session.client(), ChallengeId(DemoBackend.CAMPAIGN)).tasks.size)
    }

    @Test fun crossingsAndBuildingsServeTheirOwnFiles() = runTest {
        val all = DemoBackend(DemoBackend.ASSETS.mapValues { (_, asset) -> File("src/demo/assets/$asset").readText() })
        val client = MapRouletteClient(config.environment(), all)
        assertTrue(all.use("crossings"))
        assertEquals(4L, all.campaignId)
        val crossings = Campaigns.places(client, ChallengeId(all.campaignId))
        assertEquals(81, crossings.tasks.size)
        assertEquals(Noun("crossing", "crossings"), crossings.noun)
        assertEquals(Footprint.Kind.CROSSING, Footprint.kind(crossings.tasks.first().task))
        assertTrue(crossings.tasks.none { it.isDone })
        assertTrue(all.use("buildings"))
        assertEquals(5L, all.campaignId)
        val buildings = Campaigns.places(client, ChallengeId(all.campaignId))
        assertEquals(93, buildings.tasks.size)
        assertEquals(Noun("building", "buildings"), buildings.noun)
        assertEquals("way/35962668", buildings.tasks.first().task.name)
        assertEquals(Footprint.Kind.BUILDING, Footprint.kind(buildings.tasks.first().task))
        assertFalse(all.use("benches"))
    }

    /** Each demo campaign has its own id, so "checked today" isn't shared (Tess, #40); after a
     * relaunch the app asks for the last one by id. */
    @Test fun aCampaignIsServedByItsOwnId() = runTest {
        val all = DemoBackend(DemoBackend.ASSETS.mapValues { (_, asset) -> File("src/demo/assets/$asset").readText() })
        val client = MapRouletteClient(config.environment(), all)
        assertEquals(DemoBackend.CAMPAIGN, all.campaignId)
        assertEquals(81, Campaigns.places(client, ChallengeId(4)).tasks.size)
        assertEquals(4L, all.campaignId)
    }

    @Test fun theGuestStatusCountsThisCampaignsSavedAnswers() = runTest {
        backend.execute(HttpRequest("${DemoBackend.ORIGIN}/api/v2/task/1092/choice/pending", emptyMap(), HttpMethod.POST, """{"answers":{}}"""))
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        session.restore()
        assertTrue(session.ensureGuest())
        session.refreshGuestStatus()
        assertEquals(ChallengeAnswerCounts(1, 0), session.guest.value.status?.answersHere(DemoBackend.CAMPAIGN))
    }

    @Test fun theOnMapFlagPutsAnswersOnTheMap() = runTest {
        backend.onMap = 1
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        session.restore()
        assertTrue(session.ensureGuest())
        session.refreshGuestStatus()
        assertEquals(ChallengeAnswerCounts(0, 1), session.guest.value.status?.answersHere(DemoBackend.CAMPAIGN))
    }

    @Test fun offlineFailsOnlyTheSave() = runTest {
        backend.offline = true
        val save = HttpRequest("${DemoBackend.ORIGIN}/api/v2/task/1092/choice/pending", emptyMap(), HttpMethod.POST, """{"answers":{}}""")
        assertTrue(runCatching { backend.execute(save) }.exceptionOrNull() is java.io.IOException)
        assertEquals(139, Campaigns.places(MapRouletteClient(config.environment(), backend), ChallengeId(DemoBackend.CAMPAIGN)).tasks.size)
        backend.offline = false
        assertEquals(200, backend.execute(save).status)
        assertEquals(1, backend.pending.size)
    }

    @Test fun deleteMyDataClearsTheSavedAnswers() = runTest {
        backend.execute(HttpRequest("${DemoBackend.ORIGIN}/api/v2/task/1092/choice/pending", emptyMap(), HttpMethod.POST, """{"answers":{}}"""))
        val response = backend.execute(HttpRequest("${DemoBackend.ORIGIN}/api/v2/mobile-guest", emptyMap(), HttpMethod.DELETE, null))
        assertEquals(204, response.status)
        assertTrue(backend.pending.isEmpty())
    }

    @Test fun answersStayInMemory() = runTest {
        val response = backend.execute(
            HttpRequest("${DemoBackend.ORIGIN}/api/v2/task/1092/choice/pending", emptyMap(), HttpMethod.POST, """{"answers":{}}"""),
        )
        assertEquals(200, response.status)
        assertEquals(setOf(1092L), backend.pending.keys)
    }

    @Test fun theDemoTakesAnswersOnlyFromItsOwnOrigin() {
        assertTrue(config.writesAllowed)
        assertFalse(runCatching { config.copy(baseUrl = "https://mr-prod.osm.lol") }.isSuccess)
    }
}
