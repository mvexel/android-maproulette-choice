package lol.osm.streettally.campaign

import java.time.Clock
import java.time.LocalDate

/** Places this person saved, per campaign: "That's 3 stops today" and never offering a just-saved
 * place as next while the list catches up. The per-day counts are kept in [counts], so they survive
 * a restart; which places were saved is kept for the app process only. */
class CheckedLog(
    private val counts: Counts = Counts.inMemory(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** Where the per-day counts live: the app's preferences on a phone, a map in tests. */
    interface Counts {
        fun get(key: String): Int
        fun set(key: String, value: Int)

        companion object {
            fun inMemory(): Counts = object : Counts {
                private val values = mutableMapOf<String, Int>()
                override fun get(key: String) = values[key] ?: 0
                override fun set(key: String, value: Int) {
                    values[key] = value
                }
            }
        }
    }

    private val saved = mutableMapOf<Long, MutableSet<Long>>()
    private val reported = mutableMapOf<Long, MutableSet<Long>>()
    private val reports = mutableMapOf<Long, MutableMap<Long, TaskReport>>()

    /** Counts [taskId] once, however often it's recorded. [look]: a status 6 result ("It's not
     * here", "It's moved", or a campaign outcome such as "There's no door"). It counts for the
     * participant but leaves the place as "Needs another look" (not-here-moved.md §3.3). [report]:
     * "It's not here" or "It's moved", kept for the map's moved-spot marker. */
    fun record(campaignId: Long, taskId: Long, look: Boolean = false, report: TaskReport? = null) {
        if (taskId in done(campaignId) || taskId in reported(campaignId)) return
        (if (look) reported else saved).getOrPut(campaignId) { mutableSetOf() }.add(taskId)
        if (report != null) reports.getOrPut(campaignId) { mutableMapOf() }[taskId] = report
        val key = key(campaignId)
        counts.set(key, counts.get(key) + 1)
    }

    /** A waiting answer the server refused for good: its place is open again. Today's count stays,
     * as for a skipped answer (offline-copy.md). */
    fun forget(campaignId: Long, taskId: Long) {
        saved[campaignId]?.remove(taskId)
        reported[campaignId]?.remove(taskId)
        reports[campaignId]?.remove(taskId)
    }

    /** "Delete my data": every place saved or reported here is open again; today's counts stay. */
    fun forgetAll() {
        saved.clear()
        reported.clear()
        reports.clear()
    }

    fun today(campaignId: Long): Int = counts.get(key(campaignId))

    /** Places checked here: they count toward the campaign's tally. */
    fun done(campaignId: Long): Set<Long> = saved[campaignId].orEmpty()

    /** Places reported here as needing another look: not checked, not offered as next. */
    fun reported(campaignId: Long): Set<Long> = reported[campaignId].orEmpty()

    /** The not-here and moved reports made here, by task id. */
    fun reports(campaignId: Long): Map<Long, TaskReport> = reports[campaignId].orEmpty()

    /** One count per campaign per local calendar day, like iOS's CampaignStore. */
    private fun key(campaignId: Long) = "checked.$campaignId.${LocalDate.now(clock)}"
}
