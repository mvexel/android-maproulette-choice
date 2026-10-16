package lol.osm.streettally.campaign

import kotlinx.coroutines.CancellationException
import org.maproulette.sdk.Bounds
import org.maproulette.sdk.ChallengeAnswerCounts
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.GuestStatus
import org.maproulette.sdk.HeldKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.PageCursor
import org.maproulette.sdk.TaskFilter
import org.maproulette.sdk.TaskSummary

/** A campaign's progress from its tasks, as in the iOS `CampaignProgress`.
 *
 * Held places (open tasks someone's saved guest answer holds) are checked (design/live-progress.md
 * §3.2): shown and counted as checked, never offered. Only the phone that saved one shows it as
 * saved, not on the map yet ([pending]); everywhere else it is plainly checked (T-001). */
class CampaignProgress(
    all: List<LocatedTask>,
    held: Set<Long>,
    justDone: Set<Long> = emptySet(),
    /** Answering as a guest: [justDone] places are saved, not on the map yet. */
    guest: Boolean = false,
    /** Reported here as needing another look (status 6, not-here-moved.md §3.3). */
    reported: Set<Long> = emptySet(),
    /** A guest's answers still on this phone, not sent yet (offline-copy.md). */
    waiting: Set<Long> = emptySet(),
    /** Every place the bounds search offers or holds ([Held.offered]); null when unknown. */
    offered: Set<Long>? = null,
) {
    /** Open by its own status but gone from the bounds search (stale, or its element deleted): left
     * out of the tally and the map like a place nobody can answer (total = offered + checked). */
    val gone: Set<Long> = if (offered == null) emptySet() else all.filter {
        it.isOpen && it.position != null && it.id !in offered && it.id !in justDone && it.id !in waiting
    }.map { it.id }.toSet()
    private val tasks = all.filter { it.id !in gone }

    /** Answered here but still on this phone: shown, but not counted until the server has it, and
     * not offered. A place the server has since closed is plainly done. */
    val waiting: Set<Long> = tasks.filter { !it.isDone && it.id in waiting }.map { it.id }.toSet()
    /** Checked: done on the server, saved on this phone since launch ([justDone]), or held (unless
     * reported here: a report needs another look, even once a guest's saved report holds it). Never
     * a [waiting] one. */
    val done: Set<Long> = tasks.filter {
        it.id !in this.waiting && (it.isDone || it.id in justDone || (it.isOpen && it.id in held && it.id !in reported))
    }.map { it.id }.toSet()
    /** The tally's "N". */
    val checked: Int = done.size
    /** The campaign's size: open places (held ones included) and checked ones. */
    val total: Int = tasks.count { it.isOpen || it.id in done || it.id in this.waiting }
    /** Open places, checked ones and waiting ones; the rest are left off the map and list. */
    val shown: List<LocatedTask> = tasks.filter { it.isOpen || it.id in done || it.id in this.waiting }
    /** Needs another look: reported here and still open. Shown, but neither checked nor offered. */
    val look: Set<Long> = tasks.filter { it.isOpen && it.id in reported && it.id !in done && it.id !in this.waiting }.map { it.id }.toSet()
    /** Still to check: open, not saved, waiting or reported here, not held. In task order. */
    val open: List<LocatedTask> = tasks.filter {
        it.isOpen && it.id !in justDone && it.id !in reported && it.id !in held && it.id !in this.waiting
    }

    /** A guest's own saved answers: checked here, but not on the map until they finish signing up. */
    val pending: Set<Long> =
        if (guest) tasks.filter { !it.isDone && it.id in justDone && it.id !in this.waiting }.map { it.id }.toSet() else emptySet()
}

/** Open tasks a guest's saved submission holds: [ids] all of them, [reports] those held by a report
 * (no door, moved, any status-6 outcome), which need another look rather than count as checked
 * (T-002). [reports] is empty from a backend that doesn't say what holds a task. */
data class Held(
    val ids: Set<Long> = emptySet(),
    val reports: Set<Long> = emptySet(),
    /** Every row of the `includePending` search, held or not: the places still offered or held. Null
     * when unknown (an older backend, a failed or short read). */
    val offered: Set<Long>? = null,
)

/** Which open tasks a guest's saved answer or report holds until it goes on the map or expires. */
object HeldTasks {
    private const val MAX_PAGES = 10
    private const val PAD = 0.0005

    /** The box around [points], padded so a single place still makes a valid box. */
    fun bounds(points: List<LatLon>): Bounds? {
        if (points.isEmpty()) return null
        return runCatching {
            Bounds(
                west = maxOf(points.minOf { it.lon } - PAD, -180.0),
                south = maxOf(points.minOf { it.lat } - PAD, -90.0),
                east = minOf(points.maxOf { it.lon } + PAD, 180.0),
                north = minOf(points.maxOf { it.lat } + PAD, 90.0),
            )
        }.getOrNull()
    }

    /** From an `includePending` search: the rows flagged pending, and which of them a report holds;
     * null when the backend didn't report the flag on every row (an older backend, or no rows). */
    fun fromFlags(rows: List<TaskSummary>): Held? {
        if (rows.isEmpty() || rows.any { it.pending == null }) return null
        val held = rows.filter { it.pending == true }
        return Held(
            held.map { it.id.value }.toSet(),
            held.filter { it.heldKind == HeldKind.REPORT }.map { it.id.value }.toSet(),
            rows.map { it.id.value }.toSet(),
        )
    }

    /** The bounds search with `includePending` says so per task (backend #16). A backend without it
     * sends no `pending`, so the held set is then what the `excludePending` search leaves out. Best
     * effort: on failure or a short read, none. */
    suspend fun load(client: MapRouletteClient, id: ChallengeId, tasks: List<LocatedTask>): Held {
        val open = tasks.filter { it.isOpen }
        val bounds = bounds(open.mapNotNull { it.position }) ?: return Held()
        val withFlags = search(client, TaskFilter(listOf(id), bounds, choiceOnly = true, includePending = true)) ?: return Held()
        fromFlags(withFlags)?.let { return it }
        val unheld = search(client, TaskFilter(listOf(id), bounds, choiceOnly = true, excludePending = true)) ?: return Held()
        return Held(open.map { it.id }.toSet() - unheld.map { it.id.value }.toSet())
    }

    /** Every page of a bounds search, or null on failure or a short read. */
    private suspend fun search(client: MapRouletteClient, filter: TaskFilter): List<TaskSummary>? {
        val rows = mutableListOf<TaskSummary>()
        var cursor: PageCursor? = null
        var pages = 0
        try {
            do {
                val page = client.findTasksInBounds(filter, pageSize = 100, after = cursor)
                rows += page.items
                cursor = page.next
                pages += 1
            } while (cursor != null && pages < MAX_PAGES)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return if (cursor == null) rows else null
    }
}

/** A guest's answers in [campaignId]: saved and still waiting, and on the map. A backend from before
 * per-campaign counts sends none at all, so the overall pending count stands in, with none on the map. */
fun GuestStatus.answersHere(campaignId: Long): ChallengeAnswerCounts =
    if (byChallenge.isEmpty()) ChallengeAnswerCounts(pendingCount, 0) else answersIn(ChallengeId(campaignId))
