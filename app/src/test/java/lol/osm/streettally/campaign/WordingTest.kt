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
        assertEquals(Noun.PLACE, Noun.from(emptyList<Task>()))
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
}
