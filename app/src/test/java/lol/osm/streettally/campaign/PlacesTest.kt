package lol.osm.streettally.campaign

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskStatus

class PlacesTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun task(id: Long, location: String? = null, geometry: String = """{"features":[]}""") = Task(
        id = TaskId(id), challengeId = ChallengeId(3), name = "t$id", instruction = null,
        status = TaskStatus(0), geometry = json(geometry), location = location?.let(::json), cooperativeWork = null,
    )

    private fun at(id: Long, lat: Double, lon: Double) = LocatedTask(task(id), LatLon(lat, lon))

    @Test fun pointReadsGeoJsonAndRejectsBadValues() {
        assertEquals(LatLon(40.74, -111.87), Places.point(json("""{"type":"Point","coordinates":[-111.87,40.74]}""")))
        assertNull(Places.point(json("""{"type":"LineString","coordinates":[-111.87,40.74]}""")))
        assertNull(Places.point(json("""{"type":"Point","coordinates":[-111.87]}""")))
        assertNull(Places.point(json("""{"type":"Point","coordinates":["-111.87",40.74]}""")))
        assertNull(Places.point(json("""{"type":"Point","coordinates":[10,95]}""")))
    }

    @Test fun positionPrefersLocationThenFirstPointFeature() {
        val geometry = """{"features":[
            {"geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}},
            {"geometry":{"type":"Point","coordinates":[2,3]}}]}"""
        assertEquals(LatLon(3.0, 2.0), Places.position(task(1, geometry = geometry)))
        assertEquals(LatLon(5.0, 4.0), Places.position(task(1, """{"type":"Point","coordinates":[4,5]}""", geometry)))
        assertNull(Places.position(task(1)))
    }

    @Test fun sortedIsNearestFirstWithUnlocatedLastAndIdTies() {
        val far = at(1, 40.80, -111.87)
        val near = at(2, 40.741, -111.87)
        val tieA = at(3, 40.75, -111.87)
        val tieB = at(4, 40.75, -111.87)
        val nowhere = LocatedTask(task(9), null)
        val origin = LatLon(40.74, -111.87)
        assertEquals(listOf(2L, 3L, 4L, 1L, 9L), Places.sorted(listOf(tieB, nowhere, far, tieA, near), origin).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 9L), Places.sorted(listOf(tieB, nowhere, far, tieA, near), null).map { it.id })
    }

    @Test fun metersAndBearing() {
        val a = LatLon(0.0, 0.0)
        assertEquals(111_195.0, Places.meters(a, LatLon(1.0, 0.0)), 5.0)
        assertEquals(0.0, Places.bearing(a, LatLon(1.0, 0.0)), 0.001)
        assertEquals(90.0, Places.bearing(a, LatLon(0.0, 1.0)), 0.001)
        assertEquals(270.0, Places.bearing(a, LatLon(0.0, -1.0)), 0.001)
    }

    @Test fun compassHasEightPoints() {
        assertEquals("north", Places.compass(0.0))
        assertEquals("north", Places.compass(350.0))
        assertEquals("northeast", Places.compass(30.0))
        assertEquals("south", Places.compass(-180.0))
        assertEquals("northwest", Places.compass(337.0))
    }

    @Test fun distanceUsesLocalRoadUnits() {
        assertEquals("130 m", Places.distance(127.0, Locale.GERMANY))
        assertEquals("10 m", Places.distance(2.0, Locale.GERMANY))
        assertEquals("1.5 km", Places.distance(1520.0, Locale.GERMANY))
        assertEquals("400 ft", Places.distance(122.0, Locale.US))
        assertEquals("1.0 mi", Places.distance(1609.0, Locale.US))
        assertEquals("130 m", Places.distance(127.0, Locale.UK))
    }

    @Test fun rowTextNeedsBothPoints() {
        val a = LatLon(0.0, 0.0)
        assertEquals("110 m · north", Places.rowText(a, LatLon(0.001, 0.0), Locale.GERMANY))
        assertNull(Places.rowText(null, a))
        assertNull(Places.distanceText(a, null))
    }

    @Test fun nearestSkipsVisitedAndClosedPlaces() {
        val origin = LatLon(40.74, -111.87)
        val a = at(1, 40.741, -111.87)
        val b = at(2, 40.75, -111.87)
        // No choice payload: none is open, so nothing is offered.
        assertNull(Places.nearest(origin, listOf(a, b), emptySet()))
    }

    @Test fun directionTexts() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.002)
        assertEquals("220 m east", Places.directionText(a, b, Locale.GERMANY))
        assertEquals("220 m east of you", Places.fromYouText(a, b, Locale.GERMANY))
        assertNull(Places.fromYouText(null, b))
    }
}
