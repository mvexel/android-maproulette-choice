package lol.osm.streettally.campaign

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import lol.osm.streettally.demo.DemoBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteException

/** The last campaign kept on the phone for a dead spot (BACKLOG O2). */
class CampaignCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val backend = DemoBackend(File("src/demo/assets/${DemoBackend.ASSET}").readText())
    private val config = AppConfig(
        baseUrl = DemoBackend.ORIGIN, oauthClientId = "maproulette-ios-choice", callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false, mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol", guests = true, demo = true,
    )

    private class Memory(var value: String? = null) : CampaignCache.Store {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private suspend fun places() = Campaigns.places(MapRouletteClient(config.environment(), backend), ChallengeId(DemoBackend.CAMPAIGN))

    @Test fun theCopySurvivesARestart() = runTest {
        val places = places()
        val held = Held(setOf(1092L), setOf(1093L), setOf(1001L, 1092L, 1093L))
        val store = Memory()
        CampaignCache(store) { 1_000L }.save(places.copy(reports = mapOf(1001L to TaskReport.NotHere)), held)

        val kept = CampaignCache(store).get(DemoBackend.CAMPAIGN)
        assertNotNull(kept)
        kept!!
        assertEquals(1_000L, kept.savedAt)
        assertEquals(places.challenge, kept.places.challenge)
        assertEquals(places.tasks, kept.places.tasks)
        assertEquals(places.noun, kept.places.noun)
        assertEquals(places.truncated, kept.places.truncated)
        assertEquals(held, kept.held)
        // Other people's reports are for the moment, not for a copy that may be days old.
        assertTrue(kept.places.reports.isEmpty())
    }

    @Test fun aPlaceOpensFromTheCopy() = runTest {
        val cache = CampaignCache(Memory())
        cache.save(places(), Held())
        val (task, challenge) = cache.task(1092L)!!
        assertEquals(1092L, task.id.value)
        assertEquals(DemoBackend.CAMPAIGN, challenge.id.value)
        assertNull(cache.task(99_999L))
    }

    @Test fun thePickerListsTheKeptCampaign() = runTest {
        val cache = CampaignCache(Memory())
        assertNull(cache.keptCampaign())
        cache.save(places(), Held())
        assertEquals(DemoBackend.CAMPAIGN, cache.keptCampaign()?.id?.value)
    }

    @Test fun onlyOneCampaignIsKept() = runTest {
        val store = Memory()
        val cache = CampaignCache(store)
        cache.save(places(), Held())
        cache.keepOnly(DemoBackend.CAMPAIGN)
        assertNotNull(cache.get(DemoBackend.CAMPAIGN))
        assertNull(cache.get(DemoBackend.CAMPAIGN + 1))

        cache.keepOnly(DemoBackend.CAMPAIGN + 1)
        assertNull(cache.get(DemoBackend.CAMPAIGN))
        assertNull(store.value)
    }

    @Test fun aBrokenCopyIsIgnored() {
        assertNull(CampaignCache(Memory("{not json")).get(DemoBackend.CAMPAIGN))
    }

    @Test fun theFileIsReplacedWhole() = runTest {
        val file = File(folder.root, "campaign-cache.json")
        val cache = CampaignCache(CampaignCache.fileStore(file))
        cache.save(places(), Held())
        assertTrue(file.exists())
        assertFalse(File(folder.root, "campaign-cache.json.new").exists())
        assertNotNull(CampaignCache(CampaignCache.fileStore(file)).get(DemoBackend.CAMPAIGN))
        cache.clear()
        assertFalse(file.exists())
    }

    @Test fun onlyANoConnectionErrorShowsTheCopy() {
        assertTrue(CampaignCache.isOffline(IOException()))
        assertTrue(CampaignCache.isOffline(MapRouletteException(ErrorKind.NETWORK)))
        assertFalse(CampaignCache.isOffline(MapRouletteException(ErrorKind.NOT_FOUND, 404)))
        assertFalse(CampaignCache.isOffline(IllegalStateException()))
    }
}
