package lol.osm.streettally.auth

import java.net.URI
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import lol.osm.streettally.task.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.Transport

/** Deferred sign-up on the phone: answering as a guest before signing in, as the iOS app does. */
class GuestSessionTest {
    private fun config(baseUrl: String = "https://mr-api.osm.lol", guests: Boolean = true) = AppConfig(
        baseUrl = baseUrl,
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
        guests = guests,
    )

    /** Answers guest registration with [status]; records request paths. */
    private class Backend(val status: Int = 201) : Transport {
        val paths = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path
            paths += path
            return if (path == "/oauth/mobile/guest" && status == 201) {
                HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-secret-secret-1"}""")
            } else HttpResponse(status)
        }
    }

    @Test fun aGuestRegistersOnceAndIsRestoredOnTheNextLaunch() = runTest {
        val store = InMemoryCredentialStore()
        val backend = Backend()
        val session = AppSession(config(), store, backend)
        session.restore()
        assertTrue(session.canAnswerAsGuest)
        assertNull(session.writer())
        assertTrue(session.ensureGuest())
        assertTrue(session.ensureGuest())
        assertEquals(listOf("/oauth/mobile/guest"), backend.paths)
        val state = session.state.value
        assertTrue(state.guest)
        assertNull(state.account)
        assertEquals(Writer.GUEST, session.writer())

        val next = AppSession(config(), store, backend)
        next.restore()
        assertTrue(next.state.value.guest)
        assertEquals(Writer.GUEST, next.writer())
        assertEquals(1, backend.paths.size)
    }

    @Test fun guestsOffMeansSignInFirst() = runTest {
        val backend = Backend()
        for (session in listOf(
            AppSession(config(guests = false), InMemoryCredentialStore(), backend),
            // A build that can't save answers there (stage without live writes) has no guests either.
            AppSession(config(baseUrl = "https://mr-stage.osm.lol"), InMemoryCredentialStore(), backend),
        )) {
            session.restore()
            assertFalse(session.canAnswerAsGuest)
            assertFalse(session.ensureGuest())
            assertNull(session.writer())
        }
        assertEquals(emptyList<String>(), backend.paths)
    }

    @Test fun aBackendWithoutGuestsFallsBackToSignIn() = runTest {
        val backend = Backend(status = 404)
        val session = AppSession(config(), InMemoryCredentialStore(), backend)
        session.restore()
        assertFalse(session.ensureGuest())
        assertFalse(session.canAnswerAsGuest)
        assertFalse(session.ensureGuest())
        assertEquals(listOf("/oauth/mobile/guest"), backend.paths)
        assertFalse(session.state.value.guest)
    }

    @Test fun aFailedRegistrationTriesAgainLater() = runTest {
        val backend = Backend(status = 503)
        val session = AppSession(config(), InMemoryCredentialStore(), backend)
        session.restore()
        assertFalse(session.ensureGuest())
        assertTrue(session.canAnswerAsGuest)
    }
}
