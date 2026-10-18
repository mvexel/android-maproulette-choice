package lol.osm.streettally.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.IneligibleReason
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

class WordingTest {
    private fun task(properties: String) = Task(
        TaskId(7), ChallengeId(3), "t", null, TaskStatus(0),
        Json.parseToJsonElement("""{"features":[{"properties":$properties}]}""").jsonObject, null, null,
    )

    @Test fun nounFromIdentityTag() {
        assertEquals("stop", Noun.from(mapOf("highway" to "bus_stop")).one)
        assertEquals("benches", Noun.from(mapOf("amenity" to "bench")).many)
        assertEquals(Noun.PLACE, Noun.from(mapOf("shop" to "bakery")))
        assertEquals(Noun("tree", "trees"), Noun.from(mapOf("natural" to "tree")))
        assertEquals(Noun("crossing", "crossings"), Noun.from(mapOf("highway" to "crossing")))
        assertEquals(Noun("building", "buildings"), Noun.from(mapOf("building" to "retail")))
        assertEquals("a building", Noun.from(mapOf("building" to "yes")).aOne)
        // Woodbine: the entrance tag's value differs per task (main, yes).
        assertEquals(Noun("entrance", "entrances"), Noun.from(mapOf("entrance" to "main")))
        assertEquals("entrances", Noun.from(mapOf("entrance" to "yes")).many)
        assertEquals(Noun.PLACE, Noun.from(emptyList<Task>()))
    }

    @Test fun turnOnLocation() {
        assertEquals("Turn on location to see which way the tree is.", Wording.turnOnLocation(Noun("tree", "trees")))
    }

    @Test fun nounArticle() {
        assertEquals("an entrance", Noun("entrance", "entrances").aOne)
        assertEquals("a stop", Noun("stop", "stops").aOne)
        assertEquals("Choose an entrance", Wording.choose(Noun("entrance", "entrances")))
    }

    @Test fun nounCounts() {
        val stop = Noun("stop", "stops")
        assertEquals("1 stop", stop.count(1))
        assertEquals("0 stops", stop.count(0))
        assertEquals("Stop", stop.capitalized)
        assertEquals("The tally: 2 of 7 stops checked", Wording.progress(2, 7, stop))
        assertEquals("Loading stops… 5,000 so far", Wording.loadingSoFar(stop, 5000, java.util.Locale.US))
        assertEquals("Loading stops… 5.000 so far", Wording.loadingSoFar(stop, 5000, java.util.Locale.forLanguageTag("nl-NL")))
        assertEquals("Loading stops, 600 so far", Wording.loadingSoFarSpoken(stop, 600, java.util.Locale.US))
        assertEquals("Nearest stop · 120 m", Wording.nearest(stop, "120 m"))
        assertEquals("Nearest stop", Wording.nearest(stop, null))
    }

    @Test fun clusterReadsWhatIsLeft() {
        val bench = Noun("bench", "benches")
        assertEquals("940 benches left to check here", Wording.cluster(bench, 1240, 940, java.util.Locale.US))
        assertEquals("1 bench left to check here", Wording.cluster(bench, 30, 1, java.util.Locale.US))
        assertEquals("120 benches here, all checked", Wording.cluster(bench, 120, 0, java.util.Locale.US))
        assertEquals("1.240 benches left to check here", Wording.cluster(bench, 2000, 1240, java.util.Locale.forLanguageTag("nl-NL")))
    }

    @Test fun placeNamePrefersNameThenRef() {
        val stop = Noun("stop", "stops")
        assertEquals("900 E / 1300 S", Wording.placeName(task("""{"name":" 900 E / 1300 S ","ref":"12"}"""), stop))
        assertEquals("Stop 12", Wording.placeName(task("""{"name":"","ref":"12"}"""), stop))
        assertEquals("Stop", Wording.placeName(task("{}"), stop))
    }

    /** design/handback-copy.md §1: counted now, on the map once a local mapper has checked it. Never
     * a deadline, a computer or an email. */
    @Test fun handbackCopy() {
        val stop = Noun("stop", "stops")
        assertEquals("3 stops saved and counted.", Wording.savedNotOnMap(3, stop))
        assertEquals("\u2713 3 on the map", Wording.onTheMap(3))
        assertEquals("3 on the map", Wording.onTheMapSpoken(3))
        assertEquals("You checked 14 stops. The tally: 212 of 410.", Wording.daySummary(14, 212, 410, stop))
        assertEquals("The tally: 212 of 410.", Wording.daySummary(0, 212, 410, stop))
        assertEquals("Nice work today.", Wording.daySummaryTitle(14))
        assertEquals("Your answers", Wording.daySummaryTitle(0))
        assertEquals("Local mappers will check them and add them to the map.", Wording.DAY_SUMMARY_SAVED)
        assertEquals("No account needed. Local mappers check your answers and add them to the map.", Wording.NO_ACCOUNT_NEEDED)
        assertEquals(
            "Your answers are counted for this campaign right away. Local mappers then check them and add them to OpenStreetMap, the free map anyone can use, under their own names.",
            Wording.GUEST_ANSWERS_ARE_PUBLIC,
        )
        for (text in listOf(Wording.savedNotOnMap(1, stop), Wording.DAY_SUMMARY_SAVED, Wording.NO_ACCOUNT_NEEDED, Wording.GUEST_ANSWERS_ARE_PUBLIC)) {
            assert("computer" !in text && "email" !in text && "removed" !in text) { text }
        }
    }

    /** Stale places by why (Iris, parity with iOS #48). */
    @Test fun noLongerNeededSaysWhy() {
        val door = Noun("entrance", "entrances")
        assertEquals("This entrance is no longer on the map. Thanks anyway!", Wording.noLongerNeeded(IneligibleReason.ELEMENT_GONE, door))
        assertEquals(
            "This entrance changed on the map since the campaign started. Thanks anyway!",
            Wording.noLongerNeeded(IneligibleReason.MATCH_FAILED, door),
        )
        for (reason in listOf(IneligibleReason.KEY_CHANGED, IneligibleReason.UNKNOWN, null)) {
            assertEquals("Someone already checked this entrance. Thanks anyway!", Wording.noLongerNeeded(reason, door))
        }
    }

    @Test fun keptCopySaysHowOld() {
        val zone = java.time.ZoneId.of("America/Toronto")
        val now = java.time.ZonedDateTime.of(2026, 10, 16, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val at = { h: Int, d: Int -> java.time.ZonedDateTime.of(2026, 10, d, h, 30, 0, 0, zone).toInstant().toEpochMilli() }
        fun time(savedAt: Long) = Wording.keptTime(savedAt, now, zone, clock = { "clock" }, day = { "day" })
        assertEquals("1 min ago", time(now - 30_000))
        assertEquals("12 min ago", time(now - 12 * 60_000))
        assertEquals("clock", time(at(9, 16)))
        assertEquals("yesterday", time(at(23, 15)))
        assertEquals("day", time(at(9, 14)))
        assertEquals("No signal. Showing the list from 12 min ago. Some may be done already.", Wording.keptCopy("12 min ago"))
        assertEquals(
            "No signal, and this campaign isn't on your phone yet. Try again when you have signal.", Wording.NOT_KEPT_NO_SIGNAL,
        )
    }

    /** design/offline-copy.md: never "offline", "queue", "sync" or "upload". */
    @Test fun offlineCopy() {
        val stop = Noun("stop", "stops")
        assertEquals("Kept on this phone for now. It sends by itself when you have signal.", Wording.SAVED_ON_PHONE)
        assertEquals("Waiting to send", Wording.WAITING_TO_SEND)
        assertEquals("1 waiting for signal", Wording.waitingForSignal(1))
        assertEquals("3 waiting for signal", Wording.waitingForSignal(3))
        assertEquals(
            "3 stops are still on this phone. They send by themselves when you have signal, so keep Street Tally installed until then.",
            Wording.stillOnPhone(3, stop),
        )
        assertEquals(
            "1 stop is still on this phone. It sends by itself when you have signal, so keep Street Tally installed until then.",
            Wording.stillOnPhone(1, stop),
        )
        assertEquals("It also removes answers still waiting on this phone.", Wording.DELETE_MY_DATA_WAITING)
        assertEquals(
            "${Wording.DELETE_MY_DATA_EXPLAINED} It also removes answers still waiting on this phone. Deleting the app doesn't remove them. Use this button instead.",
            Wording.deleteMyDataExplained(waiting = true),
        )
        assertEquals("${Wording.DELETE_MY_DATA_EXPLAINED} Deleting the app doesn't remove them. Use this button instead.", Wording.deleteMyDataExplained(false))
        for (text in listOf(Wording.SAVED_ON_PHONE, Wording.WAITING_TO_SEND, Wording.waitingForSignal(2), Wording.stillOnPhone(2, stop), Wording.DELETE_MY_DATA_WAITING)) {
            for (word in listOf("offline", "queue", "sync", "upload", "counted")) assert(word !in text.lowercase()) { text }
        }
    }

    @Test fun claimedMessages() {
        assertEquals(
            "You're signed in with OpenStreetMap. New answers go straight on the map from now on.",
            Wording.claimedMessage(null),
        )
    }

    @Test fun withoutALocationNothingIsNearest() {
        val stop = Noun("stop", "stops")
        assertEquals("Choose a stop", Wording.choose(stop))
        assertEquals("Location is off. Choose a stop on the map or in the list.", Wording.noLocation(stop, permitted = false))
        assertEquals("Your location isn't available yet. Choose a stop on the map or in the list.", Wording.noLocation(stop, permitted = true))
    }

    @Test fun notSentMatchesRow17() {
        assertEquals("1 waiting answer wasn't needed. The place changed on the map before it was sent.", Wording.notSent(1))
        assertEquals("3 waiting answers weren't needed. The places changed on the map before they were sent.", Wording.notSent(3))
    }

    @Test fun aLiveSaveTheChangedPlaceRefusedMatchesIos() {
        assertEquals("This bench changed on the map while you were answering. Thanks anyway!", Wording.changedWhileAnswering(Noun("bench", "benches")))
    }

    @Test fun aPlaceGoneWhenOpenedHasNoThanks() {
        assertEquals("This bench is no longer on the map.", Wording.goneAtOpen(Noun("bench", "benches")))
    }

    @Test fun aLongCampaignSaysItShowsTheFirstOnes() {
        assertEquals("Showing 5,000 benches. This campaign has more.", Wording.truncatedPlaces(5_000, Noun("bench", "benches"), java.util.Locale.US))
        assertEquals("Showing 5.000 benches. This campaign has more.", Wording.truncatedPlaces(5_000, Noun("bench", "benches"), java.util.Locale.GERMANY))
        // Tally and welcome-card counts are grouped too (Tess's AE6: "4 of 5000 benches").
        assertEquals("5,000 benches", Noun("bench", "benches").count(5_000, java.util.Locale.US))
        assertEquals("5.000 benches", Noun("bench", "benches").count(5_000, java.util.Locale.GERMANY))
        assertEquals("1 bench", Noun("bench", "benches").count(1, java.util.Locale.US))
        assertEquals(50, Campaigns.MAX_TASK_PAGES)
    }
}
