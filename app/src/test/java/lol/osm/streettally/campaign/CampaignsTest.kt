package lol.osm.streettally.campaign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

class CampaignsTest {
    private fun challenge(id: Long, type: Int?, tags: List<String>?, archived: Boolean? = false) = Challenge(
        id = ChallengeId(id), projectId = ProjectId(1), name = "c$id", instruction = null, description = null,
        tags = tags, enabled = true, archived = archived, requiresLocal = null, cooperativeType = type,
    )

    @Test fun publishedKeepsUnarchivedTaggedChoiceChallenges() {
        val all = listOf(
            challenge(1, 3, listOf("mobile-survey-v1")),
            challenge(2, 3, listOf("other")),
            challenge(3, 1, listOf("mobile-survey-v1")),
            challenge(4, 3, listOf("mobile-survey-v1"), archived = true),
            challenge(5, 3, null),
            challenge(6, 3, listOf("x", "mobile-survey-v1"), archived = null),
        )
        assertEquals(listOf(1L, 6L), Campaigns.published(all).map { it.id.value })
    }

    @Test fun shownDropsTasksThatAreNeitherOpenNorDone() {
        fun task(id: Long, status: Int) = LocatedTask(
            Task(
                TaskId(id), ChallengeId(3), "t", null, TaskStatus(status),
                Json.parseToJsonElement("""{"features":[]}""").jsonObject, null, null,
            ),
            null,
        )
        // No choice payload, so none is open; Fixed (1), Not an issue (2), Already fixed (5) count as done.
        val tasks = listOf(task(1, 0), task(2, 1), task(3, 2), task(4, 3), task(5, 5), task(6, 6))
        assertEquals(listOf(2L, 3L, 5L), Campaigns.shown(tasks).map { it.id })
    }
}
