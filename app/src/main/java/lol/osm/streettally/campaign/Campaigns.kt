package lol.osm.streettally.campaign

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.PageCursor
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.work

/** The published campaigns, as the iOS picker reads them. */
data class CampaignList(val campaigns: List<Challenge>, val hidden: Int, val truncated: Boolean)

/** One campaign's places. */
data class CampaignPlaces(
    val challenge: Challenge,
    val tasks: List<LocatedTask>,
    val noun: Noun,
    val truncated: Boolean,
    /** Other people's reports, for the place screen's banner (parity row 4). */
    val reports: Map<Long, TaskReport> = emptyMap(),
)

object Campaigns {
    /** `challenges.cooperative_type` for multiple-choice challenges. */
    const val CHOICE_TYPE = 3
    /** The tag the admin's "Publish" adds. */
    const val PUBLISHED_TAG = "mobile-survey-v1"
    private const val MAX_CHALLENGE_PAGES = 5
    /** 5,000 places (L1; as iOS #54). A longer campaign shows its first ones and says so. */
    const val MAX_TASK_PAGES = 50
    private const val TASK_PAGE_SIZE = 100

    /** Published for participants (preview-mode §6): enabled and tagged [PUBLISHED_TAG]. Anything
     * else is a draft, so an unknown `enabled` never lets a preview write. */
    fun isPublished(challenge: Challenge): Boolean =
        challenge.enabled == true && challenge.tags?.contains(PUBLISHED_TAG) == true

    /** Unarchived, enabled choice challenges published for mobile. */
    fun published(all: List<Challenge>): List<Challenge> = all.filter {
        it.cooperativeType == CHOICE_TYPE && it.archived != true && it.enabled != false &&
            it.tags?.contains(PUBLISHED_TAG) == true
    }

    suspend fun load(client: MapRouletteClient): CampaignList {
        val all = mutableListOf<Challenge>()
        var cursor: PageCursor? = null
        var pages = 0
        do {
            val page = client.searchChallenges(pageSize = 100, after = cursor)
            all += page.items
            cursor = page.next
            pages += 1
        } while (cursor != null && pages < MAX_CHALLENGE_PAGES)
        val choice = published(all).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return CampaignList(choice, all.size - choice.size, cursor != null)
    }

    /** A campaign's places, all pages read (up to [MAX_TASK_PAGES]). */
    suspend fun places(client: MapRouletteClient, id: ChallengeId): CampaignPlaces =
        placesLoading(client, id).last().places

    /** One step of [placesLoading]: the places so far, and whether every page is in. */
    data class Loading(val places: CampaignPlaces, val complete: Boolean)

    /** A campaign's places as their pages land (L1 step 2): read a few pages at a time and merged by
     * task id (a task can shift between pages while they load), in id order, so the map can draw
     * before all 5,000 are in. The last step is [Loading.complete] and says whether it was cut off. */
    fun placesLoading(client: MapRouletteClient, id: ChallengeId): Flow<Loading> = flow {
        val challenge = client.getChallenge(id)
        val byId = HashMap<Long, LocatedTask>()
        var full = 0
        fun snapshot(truncated: Boolean): CampaignPlaces {
            val tasks = byId.values.sortedBy { it.id }
            return CampaignPlaces(challenge, tasks, Noun.from(tasks.map { it.task }), truncated)
        }
        client.listAllTasks(id, MAX_TASK_PAGES).collect { page ->
            // isOpen parses the choice payload: worked out here, off the main thread, not by the tally.
            page.items.forEach { byId[it.id.value] = LocatedTask.of(it).also { place -> place.isOpen } }
            if (page.items.size == TASK_PAGE_SIZE) full++
            emit(Loading(snapshot(truncated = false), complete = false))
        }
        // Every page full: the campaign has more than this phone shows.
        emit(Loading(snapshot(truncated = full == MAX_TASK_PAGES), complete = true))
    }

    /** Every question asked in the campaign, in first-seen order, for the welcome card. */
    fun questions(tasks: List<Task>): List<ChoiceQuestion> {
        val seen = mutableSetOf<String>()
        return tasks.flatMap { (it.work() as? TaskWork.Choice)?.questions.orEmpty() }.filter { seen.add(it.id) }
    }

    /** Places shown on the map and list: open ones and checked ones. */
    fun shown(tasks: List<LocatedTask>): List<LocatedTask> = tasks.filter { it.isOpen || it.isDone }
}
