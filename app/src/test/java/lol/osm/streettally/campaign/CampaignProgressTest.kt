package lol.osm.streettally.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.osm.streettally.task.BENCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maproulette.sdk.ChallengeId
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

    @Test fun heldPlacesCountAsCheckedButAreNeitherShownNorOffered() {
        // 1 and 2 open, 3 open but held, 4 fixed, 5 deleted (neither open nor done).
        val tasks = listOf(place(1, 0), place(2, 0), place(3, 0), place(4, 1), place(5, 4))
        val tally = CampaignProgress(tasks, held = setOf(3L))
        assertEquals(setOf(4L), tally.done)
        assertEquals(2, tally.checked)
        assertEquals(4, tally.total)
        assertEquals(listOf(1L, 2L, 4L), tally.shown.map { it.id })
        assertEquals(listOf(1L, 2L), tally.open.map { it.id })
        // A held id that isn't open (already fixed) is not counted twice.
        assertEquals(1, CampaignProgress(tasks, held = setOf(4L)).checked)
    }

    @Test fun flagsAreUsedOnlyWhenEveryRowHasOne() {
        fun row(id: Long, pending: Boolean?) = TaskSummary(TaskId(id), ChallengeId(3), "t$id", TaskStatus(0), null, pending)
        assertEquals(setOf(2L), HeldTasks.fromFlags(listOf(row(1, false), row(2, true))))
        assertNull(HeldTasks.fromFlags(listOf(row(1, false), row(2, null))))
        assertNull(HeldTasks.fromFlags(emptyList()))
    }

    @Test fun boundsArePaddedSoOnePlaceMakesABox() {
        val box = HeldTasks.bounds(listOf(LatLon(40.76, -111.89)))!!
        assertEquals(40.7595, box.south, 1e-9)
        assertEquals(-111.8895, box.east, 1e-9)
        assertNull(HeldTasks.bounds(emptyList()))
    }
}
