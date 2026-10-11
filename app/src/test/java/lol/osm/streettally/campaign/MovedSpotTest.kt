package lol.osm.streettally.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedSpotTest {
    @Test
    fun distanceRulesMatchTheSpec() {
        assertEquals(MovedSpot.Check.TOO_CLOSE, MovedSpot.check(4.9))
        assertEquals(MovedSpot.Check.OK, MovedSpot.check(5.0))
        assertEquals(MovedSpot.Check.OK, MovedSpot.check(150.0))
        assertEquals(MovedSpot.Check.FAR, MovedSpot.check(150.1))
        assertEquals(MovedSpot.Check.FAR, MovedSpot.check(500.0))
        assertEquals(MovedSpot.Check.TOO_FAR, MovedSpot.check(500.1))
        assertFalse(MovedSpot.canSave(3.0))
        assertTrue(MovedSpot.canSave(30.0))
        assertTrue(MovedSpot.canSave(200.0))
        assertFalse(MovedSpot.canSave(600.0))
    }

    @Test
    fun metersRoundToFiveThenTen() {
        assertEquals(30, MovedSpot.roundedMeters(31.0))
        assertEquals(35, MovedSpot.roundedMeters(33.0))
        assertEquals(100, MovedSpot.roundedMeters(97.6))
        assertEquals(120, MovedSpot.roundedMeters(123.0))
        assertEquals("About 30 m from where the map has it.", Wording.movedDistance(31.0))
        assertEquals("The stop has moved, about 120 m from where the map has it.", Wording.reportMoved(123.0, Noun("stop", "stops")))
    }
}
