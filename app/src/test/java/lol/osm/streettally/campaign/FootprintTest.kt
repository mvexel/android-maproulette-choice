package lol.osm.streettally.campaign

import kotlin.math.abs
import kotlin.math.cos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

/** Footprints from the map's tiles (design/which-one-sprint.md §3 E), as iOS FootprintTests. */
class FootprintTest {
    private val here = LatLon(40.75, -111.9)

    /** A point [east] and [north] metres from [here]. */
    private fun at(east: Double, north: Double) =
        LatLon(here.lat + north / 111_320, here.lon + east / (111_320 * cos(Math.toRadians(here.lat))))

    /** A square [size] metres across with its south-west corner [east], [north] from [here]. */
    private fun square(east: Double, north: Double, size: Double = 10.0) =
        listOf(at(east, north), at(east + size, north), at(east + size, north + size), at(east, north + size), at(east, north))

    @Test fun outlineHoldingThePointWins() {
        val holding = square(-5.0, -5.0)
        val next = square(6.0, -5.0)
        assertEquals(holding, Footprint.outline(here, listOf(next, holding)))
    }

    /** A centroid outside an L-shaped building still finds it, within 8 m. */
    @Test fun nearestOutlineWithinReach() {
        val near = square(3.0, -5.0)
        val far = square(-30.0, -5.0)
        assertEquals(near, Footprint.outline(here, listOf(far, near)))
        assertNull(Footprint.outline(here, listOf(square(9.0, -5.0))))
    }

    /** The bar crosses the street at right angles, centred on the node, 12 m long. */
    @Test fun barAcrossTheStreet() {
        val eastWest = listOf(at(-50.0, 1.0), at(50.0, 1.0))
        val northSouth = listOf(at(20.0, -50.0), at(20.0, 50.0))
        val bar = Footprint.bar(here, listOf(northSouth, eastWest))
        assertNotNull(bar)
        bar!!
        assertEquals(2, bar.size)
        assertEquals(12.0, Places.meters(bar[0], bar[1]), 0.1)
        // North-south bar across an east-west street.
        assertTrue(abs(bar[0].lon - bar[1].lon) < 1e-7)
        assertTrue(abs((bar[0].lat + bar[1].lat) / 2 - here.lat) < 1e-7)
        assertNull(Footprint.bar(here, listOf(northSouth)))
    }

    @Test fun nearestSegmentBearing() {
        val (distance, bearing) = Footprint.nearest(listOf(at(0.0, -10.0), at(0.0, 10.0)), at(3.0, 0.0))!!
        assertEquals(3.0, distance, 0.01)
        assertEquals(0.0, bearing, 0.01)
        assertNull(Footprint.nearest(listOf(here), here))
    }

    @Test fun containsUnclosedRing() {
        val ring = square(-5.0, -5.0).dropLast(1)
        assertTrue(Footprint.contains(ring, here))
        assertFalse(Footprint.contains(ring, at(6.0, 0.0)))
    }

    private fun task(element: String, match: String) = Task(
        TaskId(1), ChallengeId(3), "t", null, TaskStatus(0),
        Json.parseToJsonElement("{}").jsonObject, null,
        Json.parseToJsonElement(
            """{"meta":{"version":2,"type":3,"choiceVersion":1},"element":"$element","match":$match,""" +
                """"questions":[{"id":"q","prompt":"Q?","expect":{"x":null},"options":[{"id":"a","label":"A","setTags":{"x":"a"}},{"id":"b","label":"B","setTags":{"x":"b"}}]}]}""",
        ).jsonObject,
    )

    @Test fun kindComesFromTheIdentityTags() {
        assertEquals(Footprint.Kind.CROSSING, Footprint.kind(task("node/1", """{"highway":"crossing"}""")))
        assertEquals(Footprint.Kind.BUILDING, Footprint.kind(task("way/1", """{"building":"yes"}""")))
        assertEquals(Footprint.Kind.POINT, Footprint.kind(task("node/1", """{"building":"yes"}""")))
        assertEquals(Footprint.Kind.POINT, Footprint.kind(task("node/1", """{"natural":"tree"}""")))
    }
}
