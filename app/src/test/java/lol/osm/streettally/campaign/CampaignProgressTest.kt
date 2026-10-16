package lol.osm.streettally.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.osm.streettally.task.BENCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import org.maproulette.sdk.ChallengeAnswerCounts
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.GuestEmailState
import org.maproulette.sdk.GuestState
import org.maproulette.sdk.GuestStatus
import org.maproulette.sdk.HeldKind
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus
import org.maproulette.sdk.TaskSummary

class CampaignProgressTest {
    private fun place(id: Long, status: Int, lat: Double = 40.76, lon: Double = -111.89) = LocatedTask(
        Task(
            id = TaskId(id), challengeId = ChallengeId(3), name = "t$id", instruction = null, status = TaskStatus(status),
            geometry = Json.parseToJsonElement("""{"features":[]}""").jsonObject, location = null, cooperativeWork = BENCH,
        ),
        LatLon(lat, lon),
    )

    @Test fun heldPlacesAreShownAndCountedAsCheckedButNotOffered() {
        // 1 and 2 open, 3 open but held by someone else's saved answer, 4 fixed, 5 deleted (neither open nor done).
        val tasks = listOf(place(1, 0), place(2, 0), place(3, 0), place(4, 1), place(5, 4))
        for (guest in listOf(false, true)) {
            val tally = CampaignProgress(tasks, held = setOf(3L), guest = guest)
            assertEquals(setOf(3L, 4L), tally.done)
            assertEquals(2, tally.checked)
            assertEquals(4, tally.total)
            assertEquals(listOf(1L, 2L, 3L, 4L), tally.shown.map { it.id })
            assertEquals(listOf(1L, 2L), tally.open.map { it.id })
            // Plainly checked here: "Saved, not on the map yet" is only for the phone that saved it (T-001).
            assertEquals(emptySet<Long>(), tally.pending)
        }
        // A held id that isn't open (already fixed) is not counted twice.
        assertEquals(1, CampaignProgress(tasks, held = setOf(4L)).checked)
    }

    @Test fun reportedPlacesNeedAnotherLookAndAreNotCheckedOrOffered() {
        // 1 open, 2 reported here (not here, moved, or "There's no door"), 3 reported but since fixed.
        val tasks = listOf(place(1, 0), place(2, 0), place(3, 1))
        for (guest in listOf(false, true)) {
            val tally = CampaignProgress(tasks, held = emptySet(), guest = guest, reported = setOf(2L, 3L))
            assertEquals(setOf(2L), tally.look)
            assertEquals(setOf(3L), tally.done)
            assertEquals(1, tally.checked)
            assertEquals(3, tally.total)
            assertEquals(listOf(1L, 2L, 3L), tally.shown.map { it.id })
            assertEquals(listOf(1L), tally.open.map { it.id })
            assertEquals(emptySet<Long>(), tally.pending)
            // Held by the guest's own saved report: still needs another look, not checked.
            val heldReport = CampaignProgress(tasks, held = setOf(2L), guest = guest, reported = setOf(2L, 3L))
            assertEquals(setOf(2L), heldReport.look)
            assertEquals(1, heldReport.checked)
        }
    }

    @Test fun placesSavedHereCountStraightAwayAndAGuestsShowAsSaved() {
        // 1 and 2 open, 3 open and held by this guest's own saved answer, 4 fixed.
        val tasks = listOf(place(1, 0), place(2, 0), place(3, 0), place(4, 1))
        val guest = CampaignProgress(tasks, held = setOf(3L), justDone = setOf(3L), guest = true)
        assertEquals(setOf(3L, 4L), guest.done)
        assertEquals(2, guest.checked)
        assertEquals(4, guest.total)
        assertEquals(listOf(1L, 2L, 3L, 4L), guest.shown.map { it.id })
        assertEquals(listOf(1L, 2L), guest.open.map { it.id })
        assertEquals(setOf(3L), guest.pending)
        // Signed in, a place saved here is simply checked; one done on the server is never pending.
        val account = CampaignProgress(tasks, held = emptySet(), justDone = setOf(2L, 4L))
        assertEquals(emptySet<Long>(), account.pending)
        assertEquals(listOf(1L, 3L), account.open.map { it.id })
        assertEquals(setOf(2L), CampaignProgress(tasks, emptySet(), justDone = setOf(2L, 4L), guest = true).pending)
    }

    @Test fun answersStillOnThePhoneAreShownButNotCountedOrOffered() {
        // 1 and 2 open, 3 open and waiting on this phone, 4 fixed and still waiting (the server closed it).
        val tasks = listOf(place(1, 0), place(2, 0), place(3, 0), place(4, 1))
        val tally = CampaignProgress(tasks, held = emptySet(), justDone = setOf(3L), guest = true, waiting = setOf(3L, 4L))
        assertEquals(setOf(3L), tally.waiting)
        assertEquals(setOf(4L), tally.done)
        assertEquals(1, tally.checked)
        assertEquals(4, tally.total)
        assertEquals(listOf(1L, 2L, 3L, 4L), tally.shown.map { it.id })
        assertEquals(listOf(1L, 2L), tally.open.map { it.id })
        assertEquals(emptySet<Long>(), tally.pending)
        assertEquals(emptySet<Long>(), tally.look)
        // Sent: it counts, as a saved one.
        val sent = CampaignProgress(tasks, held = setOf(3L), justDone = setOf(3L), guest = true)
        assertEquals(2, sent.checked)
        assertEquals(setOf(3L), sent.pending)
    }

    @Test fun flagsAreUsedOnlyWhenEveryRowHasOne() {
        fun row(id: Long, pending: Boolean?, kind: HeldKind? = null) =
            TaskSummary(TaskId(id), ChallengeId(3), "t$id", TaskStatus(0), null, pending, kind)
        // An older backend says nothing about what holds a task: every held place reads as checked.
        assertEquals(Held(setOf(2L)), HeldTasks.fromFlags(listOf(row(1, false), row(2, true))))
        // A report holds 3 (T-002): it needs another look instead.
        assertEquals(
            Held(setOf(2L, 3L), reports = setOf(3L)),
            HeldTasks.fromFlags(listOf(row(1, false), row(2, true, HeldKind.ANSWER), row(3, true, HeldKind.REPORT))),
        )
        assertNull(HeldTasks.fromFlags(listOf(row(1, false), row(2, null))))
        assertNull(HeldTasks.fromFlags(emptyList()))
    }

    @Test fun boundsArePaddedSoOnePlaceMakesABox() {
        val box = HeldTasks.bounds(listOf(LatLon(40.76, -111.89)))!!
        assertEquals(40.7595, box.south, 1e-9)
        assertEquals(-111.8895, box.east, 1e-9)
        assertNull(HeldTasks.bounds(emptyList()))
    }

    private fun guest(pending: Int, byChallenge: Map<ChallengeId, ChallengeAnswerCounts>) = GuestStatus(
        guestId = "g", state = GuestState.ACTIVE, email = GuestEmailState.NONE, pendingCount = pending,
        publishedCount = 0, expiresAt = Instant.EPOCH, byChallenge = byChallenge,
    )

    @Test fun answersHereReadsThisCampaignsCounts() {
        val status = guest(5, mapOf(ChallengeId(3) to ChallengeAnswerCounts(2, 4), ChallengeId(4) to ChallengeAnswerCounts(3, 0)))
        assertEquals(ChallengeAnswerCounts(2, 4), status.answersHere(3))
        assertEquals(ChallengeAnswerCounts.ZERO, status.answersHere(9))
    }

    @Test fun beforePerCampaignCountsThePendingTotalStandsIn() {
        assertEquals(ChallengeAnswerCounts(5, 0), guest(5, emptyMap()).answersHere(3))
    }
}
