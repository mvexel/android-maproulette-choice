package lol.osm.streettally.location

import org.junit.Assert.assertEquals
import org.junit.Test

/** Indoors GPS rarely gets a fix, so it never gets the whole wait when another provider can help. */
class LocatorTest {
    @Test fun fusedFirstThenTheNetwork() {
        assertEquals(listOf("fused" to 10_000L, "network" to 5_000L), Locator.attempts(listOf("fused", "gps", "network"), 15_000))
    }

    @Test fun fusedWithoutTheNetworkFallsBackToGps() {
        assertEquals(listOf("fused" to 10_000L, "gps" to 5_000L), Locator.attempts(listOf("fused", "gps"), 15_000))
    }

    @Test fun withoutFusedGpsFallsBackToTheNetwork() {
        assertEquals(listOf("gps" to 10_000L, "network" to 5_000L), Locator.attempts(listOf("gps", "network"), 15_000))
    }

    @Test fun aSingleProviderGetsTheWholeWait() {
        assertEquals(listOf("network" to 15_000L), Locator.attempts(listOf("network"), 15_000))
        assertEquals(listOf("gps" to 15_000L), Locator.attempts(listOf("gps"), 15_000))
        assertEquals(emptyList<Pair<String, Long>>(), Locator.attempts(emptyList(), 15_000))
    }
}
