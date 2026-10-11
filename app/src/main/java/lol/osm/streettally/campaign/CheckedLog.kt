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

    /** Counts [taskId] once, however often it's recorded. */
    fun record(campaignId: Long, taskId: Long) {
        if (!saved.getOrPut(campaignId) { mutableSetOf() }.add(taskId)) return
        val key = key(campaignId)
        counts.set(key, counts.get(key) + 1)
    }

    fun today(campaignId: Long): Int = counts.get(key(campaignId))

    fun done(campaignId: Long): Set<Long> = saved[campaignId].orEmpty()

    /** One count per campaign per local calendar day, like iOS's CampaignStore. */
    private fun key(campaignId: Long) = "checked.$campaignId.${LocalDate.now(clock)}"
}
