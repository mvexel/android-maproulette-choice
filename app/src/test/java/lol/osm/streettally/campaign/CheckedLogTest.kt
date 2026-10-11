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
}
