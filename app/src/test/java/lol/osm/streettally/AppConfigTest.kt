package lol.osm.streettally

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    private fun config(baseUrl: String, live: Boolean = false, loopback: Boolean = false) = AppConfig(
        baseUrl = baseUrl,
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = live,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
        allowLoopback = loopback,
    )

    @Test fun devTakesWrites() {
        assertTrue(config("https://mr-api.osm.lol").writesAllowed)
        assertTrue(config("https://mr-dev.osm.lol").writesAllowed)
        assertTrue(config("https://mr-api.osm.lol").environment().allowsWrites)
    }

    @Test fun stageAndProdTakeWritesOnlyWithLiveWrites() {
        for (origin in listOf("https://mr-stage.osm.lol", "https://mr-prod.osm.lol")) {
            assertFalse(config(origin).writesAllowed)
            assertFalse(config(origin).environment().allowsWrites)
            assertTrue(config(origin, live = true).writesAllowed)
            assertTrue(config(origin, live = true).environment().allowsWrites)
        }
    }

    @Test fun otherHostsAreReadOnly() {
        assertFalse(config("https://maproulette.org", live = true).writesAllowed)
        assertFalse(config("https://example.com", live = true).environment().allowsWrites)
    }

    @Test fun environmentUsesTheApiPath() {
        assertEquals("https://mr-stage.osm.lol/api/v2/", config("https://mr-stage.osm.lol").environment().serviceUrl)
    }

    @Test fun redirectUriMatchesIos() {
        assertEquals("lol.osm.maproulette.choice:/oauth2redirect", config("https://mr-api.osm.lol").redirectUri)
    }

    @Test fun baseUrlMustBeAnHttpsOrigin() {
        assertNull(AppConfig.origin("http://mr-api.osm.lol"))
        assertNull(AppConfig.origin("https://mr-api.osm.lol/api"))
        assertNull(AppConfig.origin("https://user@mr-api.osm.lol"))
        assertThrows(IllegalArgumentException::class.java) { config("https://mr-api.osm.lol/") }
    }

    @Test fun loopbackIsOnlyForBuildsThatAllowIt() {
        assertThrows(IllegalArgumentException::class.java) { config("http://127.0.0.1:18080") }
        val proxy = config("http://127.0.0.1:18080", loopback = true)
        assertTrue(proxy.writesAllowed)
        assertTrue(proxy.environment().allowsWrites)
        assertEquals("http://localhost:18080", AppConfig.origin("http://LOCALHOST:18080", loopback = true))
        // Plain http elsewhere stays refused, and https origins are unchanged.
        assertNull(AppConfig.origin("http://10.0.2.2:18080", loopback = true))
        assertNull(AppConfig.origin("http://mr-api.osm.lol", loopback = true))
        assertFalse(config("https://mr-stage.osm.lol", loopback = true).writesAllowed)
    }
}
