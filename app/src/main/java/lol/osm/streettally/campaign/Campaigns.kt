package lol.osm.streettally.campaign

import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.PageCursor
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.work
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import lol.osm.streettally.task.takesGuestAnswers

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
    private const val MAX_TASK_PAGES = 10

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

    suspend fun places(client: MapRouletteClient, id: ChallengeId): CampaignPlaces {
        val challenge = client.getChallenge(id)
        val all = mutableListOf<Task>()
        var cursor: PageCursor? = null
        var pages = 0
        do {
            val page = client.listTasks(id, pageSize = 100, after = cursor)
            all += page.items
            cursor = page.next
            pages += 1
        } while (cursor != null && pages < MAX_TASK_PAGES)
        return CampaignPlaces(challenge, all.map(LocatedTask::of), Noun.from(all), cursor != null)
    }

    /**
     * Campaigns a guest can't answer (no `liveMissingQuestions`), from each one's first task: the
     * survey builder sets the flag per campaign (guest-vs-signed-in-review.md). A failed read leaves
     * the campaign out; its places still say so when opened.
     */
    suspend fun needingAccount(client: MapRouletteClient, campaigns: List<Challenge>): Set<ChallengeId> = coroutineScope {
        campaigns.map { c ->
            async {
                val first = try {
                    client.listTasks(c.id, pageSize = 1).items.firstOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                c.id.takeIf { first != null && !first.takesGuestAnswers() }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    /** Every question asked in the campaign, in first-seen order, for the welcome card. */
    fun questions(tasks: List<Task>): List<ChoiceQuestion> {
        val seen = mutableSetOf<String>()
        return tasks.flatMap { (it.work() as? TaskWork.Choice)?.questions.orEmpty() }.filter { seen.add(it.id) }
    }

    /** Places shown on the map and list: open ones and checked ones. */
    fun shown(tasks: List<LocatedTask>): List<LocatedTask> = tasks.filter { it.isOpen || it.isDone }
}
