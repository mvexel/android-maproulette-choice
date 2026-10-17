package lol.osm.streettally.campaign

import java.net.URI
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.ProjectId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

class CampaignsTest {
    private fun challenge(id: Long, type: Int?, tags: List<String>?, archived: Boolean? = false, enabled: Boolean? = true) = Challenge(
        id = ChallengeId(id), projectId = ProjectId(1), name = "c$id", instruction = null, description = null,
        tags = tags, enabled = enabled, archived = archived, requiresLocal = null, cooperativeType = type,
    )

    @Test fun publishedKeepsUnarchivedTaggedChoiceChallenges() {
        val all = listOf(
            challenge(1, 3, listOf("mobile-survey-v1")),
            challenge(2, 3, listOf("other")),
            challenge(3, 1, listOf("mobile-survey-v1")),
            challenge(4, 3, listOf("mobile-survey-v1"), archived = true),
            challenge(5, 3, null),
            challenge(6, 3, listOf("x", "mobile-survey-v1"), archived = null),
            // A draft is hidden; an unknown `enabled` is listed, as on iOS.
            challenge(7, 3, listOf("mobile-survey-v1"), enabled = false),
            challenge(8, 3, listOf("mobile-survey-v1"), enabled = null),
        )
        assertEquals(listOf(1L, 6L, 8L), Campaigns.published(all).map { it.id.value })
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

    /** Challenge 42 with [count] tasks, 100 a page; the last page repeats task 1000 (it shifted mid-load). */
    private fun backend(count: Int) = org.maproulette.sdk.Transport { request: HttpRequest ->
        val url = URI(request.url)
        val page = url.query.orEmpty().split("&").firstOrNull { it.startsWith("page=") }?.substringAfter("=")?.toInt() ?: 0
        when {
            url.path.endsWith("/challenge/42") -> HttpResponse(200, body = """{"id":42,"parent":1,"name":"Benches","cooperativeType":3}""")
            url.path.endsWith("/challenge/42/tasks") -> {
                val ids = (page * 100 until minOf(count, page * 100 + 100)).map { 1000L + it } + (if (page == count / 100 && count % 100 != 0) listOf(1000L) else emptyList())
                HttpResponse(200, body = ids.joinToString(",", "[", "]") {
                    """{"id":$it,"parent":42,"name":"Bench","status":0,"geometries":{"type":"FeatureCollection","features":[]}}"""
                })
            }
            else -> HttpResponse(404)
        }
    }

    @Test fun placesArriveByPageMergedByIdAndInOrder() = runTest {
        val steps = Campaigns.placesLoading(MapRouletteClient(transport = backend(250)), ChallengeId(42)).toList()
        assertTrue(steps.size >= 2)
        assertTrue(steps.dropLast(1).none { it.complete })
        val last = steps.last()
        assertTrue(last.complete)
        assertEquals((1000L until 1250L).toList(), last.places.tasks.map { it.id })
        assertFalse(last.places.truncated)
        assertEquals("Benches", last.places.challenge.name)
    }

    @Test fun fiftyFullPagesSayTheCampaignHasMore() = runTest {
        val cut = Campaigns.places(MapRouletteClient(transport = backend(5_200)), ChallengeId(42))
        assertEquals(5_000, cut.tasks.size)
        assertTrue(cut.truncated)
        assertFalse(Campaigns.places(MapRouletteClient(transport = backend(4_950)), ChallengeId(42)).truncated)
    }
}
