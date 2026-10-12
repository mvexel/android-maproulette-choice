package lol.osm.streettally.campaign

import lol.osm.streettally.task.ChoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.MovedLocation

class TaskReportTest {
    private val door = Noun("entrance", "entrances")
    private val place = LatLon(43.6842, -79.3178)

    @Test fun reportsComeOnlyFromNotHereAndMoved() {
        assertEquals(TaskReport.NotHere, TaskReport.of(ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE)))
        assertEquals(
            TaskReport.Moved(LatLon(43.6846, -79.3178)),
            TaskReport.of(ChoiceAction.Moved(MovedLocation(43.6846, -79.3178, MovedLocation.Source.TAP))),
        )
        assertNull(TaskReport.of(ChoiceAction.Answers(emptyMap())))
        assertNull(TaskReport.of(ChoiceAction.Skip))
    }

    @Test fun bannerSaysWhatSomeoneReported() {
        assertEquals("Someone reported this entrance isn't here.", TaskReport.banner(TaskReport.NotHere, place, door))
        val north40 = LatLon(place.lat + 40 / 111_320.0, place.lon)
        assertEquals("Someone reported this entrance moved, about 40 m.", TaskReport.banner(TaskReport.Moved(north40), place, door))
        assertNull(TaskReport.banner(TaskReport.Moved(north40), null, door))
        assertNull(TaskReport.banner(null, place, door))
    }

    @Test fun theLogKeepsReportsMadeHere() {
        val log = CheckedLog()
        log.record(3, 10, look = true, report = TaskReport.NotHere)
        log.record(3, 11)
        log.record(3, 10, look = true, report = TaskReport.Moved(place))
        assertEquals(mapOf(10L to TaskReport.NotHere), log.reports(3))
        assertEquals(emptyMap<Long, TaskReport>(), log.reports(4))
    }
}
