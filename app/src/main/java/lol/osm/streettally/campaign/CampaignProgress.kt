package lol.osm.streettally.campaign

import kotlinx.coroutines.CancellationException
import org.maproulette.sdk.Bounds
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.PageCursor
import org.maproulette.sdk.TaskFilter
import org.maproulette.sdk.TaskSummary

/** A campaign's progress from its tasks, as in the iOS `CampaignProgress`.
 *
 * Held places (open tasks someone's saved guest answer holds) count as checked in the tally and
 * toward the total (design/live-progress.md §3.2): they are answered, just not on the map yet.
 * They are neither shown nor offered. */
class CampaignProgress(
    tasks: List<LocatedTask>,
    held: Set<Long>,
    justDone: Set<Long> = emptySet(),
    /** Answering as a guest: [justDone] places are saved, not on the map yet. */
    guest: Boolean = false,
    /** Reported here as needing another look (status 6, not-here-moved.md §3.3). */
    reported: Set<Long> = emptySet(),
) {
    /** Checked: done on the server, or saved on this phone since launch ([justDone]). */
    val done: Set<Long> = tasks.filter { it.isDone || it.id in justDone }.map { it.id }.toSet()
    /** The tally's "N": checked places plus held ones. */
    val checked: Int = tasks.count { it.id in done || (it.isOpen && it.id in held) }
    /** The campaign's size: open places (held ones included) and checked ones. */
    val total: Int = tasks.count { it.isOpen || it.id in done }
    /** Open places that are not held, and checked ones; the rest are left off the map and list. */
    val shown: List<LocatedTask> = tasks.filter { (it.isOpen && it.id !in held) || it.id in done }
    /** Needs another look: reported here and still open. Shown, but neither checked nor offered. */
    val look: Set<Long> = tasks.filter { it.isOpen && it.id in reported && it.id !in done }.map { it.id }.toSet()
    /** Still to check: open, not saved or reported here, not held. In task order. */
    val open: List<LocatedTask> = tasks.filter { it.isOpen && it.id !in justDone && it.id !in reported && it.id !in held }

    /** A guest's own saved answers: checked here, but not on the map until they finish signing up. */
    val pending: Set<Long> =
        if (guest) tasks.filter { !it.isDone && it.id in justDone }.map { it.id }.toSet() else emptySet()
}

/** Which open tasks a guest's saved answer holds until it goes on the map or expires. */
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

    /** From an `includePending` search: the rows flagged pending, or null when the backend didn't
     * report the flag on every row (an older backend, or no rows). */
    fun fromFlags(rows: List<TaskSummary>): Set<Long>? =
        if (rows.isNotEmpty() && rows.all { it.pending != null }) rows.filter { it.pending == true }.map { it.id.value }.toSet()
        else null

    /** The bounds search with `includePending` says so per task (backend #16). A backend without it
     * sends no `pending`, so the held set is then what the `excludePending` search leaves out. Best
     * effort: on failure or a short read, none. */
    suspend fun load(client: MapRouletteClient, id: ChallengeId, tasks: List<LocatedTask>): Set<Long> {
        val open = tasks.filter { it.isOpen }
        val bounds = bounds(open.mapNotNull { it.position }) ?: return emptySet()
        val withFlags = search(client, TaskFilter(listOf(id), bounds, choiceOnly = true, includePending = true)) ?: return emptySet()
        fromFlags(withFlags)?.let { return it }
        val unheld = search(client, TaskFilter(listOf(id), bounds, choiceOnly = true, excludePending = true)) ?: return emptySet()
        return open.map { it.id }.toSet() - unheld.map { it.id.value }.toSet()
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
