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
        val log = CheckedLog(clock)
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
}
