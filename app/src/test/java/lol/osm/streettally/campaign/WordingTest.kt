package lol.osm.streettally.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maproulette.sdk.ChallengeId
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
        assertEquals("Nearest stop · 120 m", Wording.nearest(stop, "120 m"))
        assertEquals("Nearest stop", Wording.nearest(stop, null))
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
}
