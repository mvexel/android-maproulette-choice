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
        // Woodbine: the entrance tag's value differs per task (main, yes).
        assertEquals(Noun("entrance", "entrances"), Noun.from(mapOf("entrance" to "main")))
        assertEquals("entrances", Noun.from(mapOf("entrance" to "yes")).many)
        assertEquals(Noun.PLACE, Noun.from(emptyList<Task>()))
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

    @Test fun finishLaterCopy() {
        val utc = java.time.ZoneOffset.UTC
        val date = java.time.Instant.parse("2026-11-30T18:00:00Z")
        val stop = Noun("stop", "stops")
        java.util.Locale.setDefault(java.util.Locale.US)
        assertEquals("Nov\u00A030", Wording.day(date, utc))
        assertEquals("Sent to rosa@example.com. Check your email later. The link works until Nov\u00A030.", Wording.linkSent("rosa@example.com", date, utc))
        assertEquals("Sent to rosa@example.com. Check your email later.", Wording.linkSent("rosa@example.com", null))
        assertEquals("1 saved stop will be removed on Nov\u00A030 unless you finish on a computer.", Wording.savedClosing(1, stop, date, utc))
        assertEquals("14 saved stops will be removed on Nov\u00A030 unless you finish on a computer.", Wording.savedClosing(14, stop, date, utc))
        assertEquals("3 stops saved, not on the map yet. Finish on a computer by Nov\u00A030.", Wording.savedNotOnMap(3, stop, date, utc))
        assertEquals("You checked 14 stops. The tally: 212 of 410.", Wording.daySummary(14, 212, 410, stop))
        assertEquals(
            "They're saved until Nov\u00A030. If we emailed you a link, open it on a computer to put them on the map.",
            Wording.daySummarySaved(date, utc),
        )
        // No unmeasured "about 3 minutes", and no promise that an email was sent (Iris, copy fixes).
        for (text in listOf(Wording.PUT_ON_MAP_INTRO, Wording.daySummarySaved(null), Wording.SIGN_IN_PUTS_ON_MAP)) {
            assert("3 minutes" !in text && "We'll email" !in text) { text }
        }
    }

    @Test fun linkFailures() {
        fun error(kind: org.maproulette.sdk.ErrorKind, status: Int, reason: String? = null) =
            org.maproulette.sdk.MapRouletteException(kind, status, reason = reason)
        val C = org.maproulette.sdk.ErrorKind.CONFLICT
        val stop = Noun("stop", "stops")
        assertEquals("Answer a stop first, then we can send you the link.", Wording.linkFailed(error(C, 409, "nothing_saved"), stop))
        assertEquals(
            "These answers are already linked to an OpenStreetMap account. Sign in with it to see them.",
            Wording.linkFailed(error(C, 409, "guest_claimed")),
        )
        assertEquals("Couldn't send the link right now. Try again later.", Wording.linkFailed(error(C, 409)))
        assertEquals("That doesn't look like an email address.", Wording.linkFailed(IllegalArgumentException("bad")))
        assertEquals("That doesn't look like an email address.", Wording.linkFailed(error(org.maproulette.sdk.ErrorKind.HTTP, 422)))
        assertEquals(
            "We've sent a few links already. Try again tomorrow.",
            Wording.linkFailed(error(org.maproulette.sdk.ErrorKind.RATE_LIMIT, 429, "email_rate_limited")),
        )
        assert(Wording.isInputError(IllegalArgumentException()))
        assert(!Wording.isInputError(error(org.maproulette.sdk.ErrorKind.RATE_LIMIT, 429)))
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
