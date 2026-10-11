package lol.osm.streettally.campaign

import java.time.Clock
import java.time.LocalDate

/** Places this person saved, per campaign: "That's 3 stops today" and never offering a just-saved
 * place as next while the list catches up. Kept for the app process only. */
class CheckedLog(private val clock: Clock = Clock.systemDefaultZone()) {
    private val saved = mutableMapOf<Long, MutableMap<Long, LocalDate>>()

    fun record(campaignId: Long, taskId: Long) {
        saved.getOrPut(campaignId) { mutableMapOf() }[taskId] = LocalDate.now(clock)
    }

    fun today(campaignId: Long): Int {
        val now = LocalDate.now(clock)
        return saved[campaignId]?.values?.count { it == now } ?: 0
    }

    fun done(campaignId: Long): Set<Long> = saved[campaignId]?.keys.orEmpty()
}
