package lol.osm.streettally.campaign

import java.util.Locale
import lol.osm.streettally.campaign.WhichOne.Kind
import lol.osm.streettally.campaign.WhichOne.Side
import lol.osm.streettally.campaign.WhichOne.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

/** design/which-one-spec.md, A to C: first frame, neighbours and the direction line. */
class WhichOneTest {
    private val tree = Noun("tree", "trees")
    private val target = LatLon(40.7500, -111.9000)

    /** The point [meters] from [target] in direction [bearing] (flat-earth, fine at these distances). */
    private fun from(bearing: Double, meters: Double): LatLon {
        val rad = Math.toRadians(bearing)
        val dLat = meters * Math.cos(rad) / 111_320.0
        val dLon = meters * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(target.lat)))
        return LatLon(target.lat + dLat, target.lon + dLon)
    }

    @Test fun unitsFollowTheOtherDistanceLines() {
        assertEquals(Units.IMPERIAL, WhichOne.units(Locale.US))
        assertEquals(Units.METRIC, WhichOne.units(Locale.GERMANY))
        assertEquals(Units.METRIC, WhichOne.units(Locale.CANADA))
    }

    @Test fun feetRoundToFiveUnderAHundredThenTen() {
        assertEquals("25 ft", WhichOne.distance(7.6, Units.IMPERIAL)) // 24.9 ft
        assertEquals("95 ft", WhichOne.distance(29.0, Units.IMPERIAL)) // 95.1 ft
        assertEquals("330 ft", WhichOne.distance(100.0, Units.IMPERIAL)) // 328 ft
        assertEquals("0.7 mi", WhichOne.distance(1_100.0, Units.IMPERIAL))
    }

    @Test fun metersRoundToOneThenFiveThenTen() {
        assertEquals("8 m", WhichOne.distance(8.4, Units.METRIC))
        assertEquals("25 m", WhichOne.distance(23.0, Units.METRIC))
        assertEquals("340 m", WhichOne.distance(337.0, Units.METRIC))
        assertEquals("1.2 km", WhichOne.distance(1_234.0, Units.METRIC))
    }

    @Test fun noLineWithoutALocation() {
        assertNull(WhichOne.line(null, null, 0.0, target, tree, Units.METRIC))
    }

    @Test fun aRoughFixSaysSo() {
        assertEquals(WhichOne.ROUGH, WhichOne.line(from(0.0, 20.0), 30f, 0.0, target, tree, Units.METRIC)?.text)
    }

    @Test fun closeOrInsideTheAccuracyIsAtIt() {
        val atIt = "You're at it. It's the tree at the big pin."
        assertEquals(atIt, WhichOne.line(from(0.0, 4.0), 3f, 0.0, target, tree, Units.METRIC)?.text)
        assertEquals(atIt, WhichOne.line(from(0.0, 9.0), 12f, 0.0, target, tree, Units.METRIC)?.text)
    }

    @Test fun withAHeadingTheLineSaysWhichSide() {
        // Standing south of the target: it's north of you.
        val you = from(180.0, 8.0)
        fun at(heading: Double) = WhichOne.line(you, 4f, heading, target, tree, Units.METRIC)?.text
        assertEquals("8 m ahead", at(0.0))
        assertEquals("8 m ahead, slightly left", at(40.0))
        assertEquals("8 m ahead, slightly right", at(-40.0))
        assertEquals("8 m to your left", at(90.0))
        assertEquals("8 m to your right", at(270.0))
        assertEquals("8 m behind you, to the left", at(140.0))
        assertEquals("8 m behind you, to the right", at(220.0))
        assertEquals("8 m behind you", at(180.0))
    }

    @Test fun withoutAHeadingTheLineUsesTheCompass() {
        val you = from(225.0, 8.0) // southwest of the target
        assertEquals("25 ft northeast of you", WhichOne.line(you, 4f, null, target, tree, Units.IMPERIAL)?.text)
    }

    @Test fun aSideHoldsUntilTheAngleIsWellPastTheBoundary() {
        assertEquals(Side.AHEAD, WhichOne.side(30.0, Side.AHEAD))
        assertEquals(Side.SLIGHTLY_RIGHT, WhichOne.side(36.0, Side.AHEAD))
        assertEquals(Side.SLIGHTLY_RIGHT, WhichOne.side(20.0, Side.SLIGHTLY_RIGHT))
        assertEquals(Side.AHEAD, WhichOne.side(14.0, Side.SLIGHTLY_RIGHT))
        assertEquals(Side.BEHIND, WhichOne.side(-170.0, Side.BEHIND))
    }

    @Test fun theFirstFrameDropsYouWhenFarAway() {
        val near = from(90.0, 40.0)
        assertEquals(listOf(near, target), WhichOne.frame(near, target))
        assertEquals(listOf(target), WhichOne.frame(from(90.0, 200.0), target))
        assertEquals(listOf(target), WhichOne.frame(null, target))
    }

    @Test fun neighboursAreDrawnByState() {
        fun task(id: Long, status: Int) = LocatedTask(
            Task(TaskId(id), ChallengeId(3), "t", null, TaskStatus(status), Json.parseToJsonElement("{}").jsonObject, null, null),
            from(id * 10.0, 9.0),
        )
        val tasks = listOf(task(1, 0), task(2, 0), task(3, 1), task(4, 6), task(5, 0), task(6, 4), task(7, 0), task(8, 3))
        val drawn = WhichOne.neighbours(targetId = 1, tasks = tasks, done = setOf(5L), look = setOf(7L))
            .associate { it.id to it.kind }
        assertEquals(
            mapOf(2L to Kind.OPEN, 3L to Kind.DONE, 4L to Kind.LOOK, 5L to Kind.DONE, 7L to Kind.LOOK, 8L to Kind.OPEN),
            drawn,
        )
    }
}
