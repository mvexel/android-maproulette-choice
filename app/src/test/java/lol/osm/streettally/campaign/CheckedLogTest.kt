package lol.osm.streettally.campaign

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckedLogTest {
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Test fun countsTodayPerCampaignAndRemembersDone() {
        val clock = MovableClock(Instant.parse("2026-10-11T10:00:00Z"))
        val log = CheckedLog(clock = clock)
        log.record(3, 10)
        log.record(3, 11)
        log.record(3, 11)
        log.record(4, 12)
        assertEquals(2, log.today(3))
        assertEquals(setOf(10L, 11L), log.done(3))
        clock.now = Instant.parse("2026-10-12T10:00:00Z")
        assertEquals(0, log.today(3))
        assertEquals(setOf(10L, 11L), log.done(3))
        assertEquals(0, log.today(99))
    }

    @Test fun aReportCountsTodayButIsNotDone() {
        val log = CheckedLog()
        log.record(3, 10, look = true)
        log.record(3, 10)
        log.record(3, 11)
        assertEquals(2, log.today(3))
        assertEquals(setOf(10L), log.reported(3))
        assertEquals(setOf(11L), log.done(3))
    }

    @Test fun todaysCountOutlivesTheProcessButDoneDoesNot() {
        val clock = MovableClock(Instant.parse("2026-10-11T10:00:00Z"))
        val counts = CheckedLog.Counts.inMemory()
        CheckedLog(counts, clock).apply {
            record(3, 10)
            record(3, 11)
        }
        val restarted = CheckedLog(counts, clock)
        assertEquals(2, restarted.today(3))
        assertEquals(emptySet<Long>(), restarted.done(3))
        clock.now = Instant.parse("2026-10-12T10:00:00Z")
        assertEquals(0, restarted.today(3))
    }

    @Test fun aRefusedWaitingAnswerOpensThePlaceAgainButKeepsTodaysCount() {
        val log = CheckedLog()
        log.record(3, 1)
        log.record(3, 2, look = true, report = TaskReport.NotHere)
        log.forget(3, 1)
        log.forget(3, 2)
        assertEquals(emptySet<Long>(), log.done(3))
        assertEquals(emptySet<Long>(), log.reported(3))
        assertEquals(emptyMap<Long, TaskReport>(), log.reports(3))
        assertEquals(2, log.today(3))
    }

    @Test fun deletingMyDataOpensEveryPlaceButKeepsTodaysCount() {
        val log = CheckedLog()
        log.record(3, 1)
        log.record(4, 2, look = true, report = TaskReport.NotHere)
        log.forgetAll()
        assertEquals(emptySet<Long>(), log.done(3))
        assertEquals(emptySet<Long>(), log.reported(4))
        assertEquals(emptyMap<Long, TaskReport>(), log.reports(4))
        assertEquals(1, log.today(3))
        assertEquals(1, log.today(4))
    }
}
