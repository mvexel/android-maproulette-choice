package lol.osm.streettally.auth

import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import lol.osm.streettally.task.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ErrorKind
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
        // Registered once; the next launch only reads the guest's status.
        assertEquals(1, backend.paths.count { it == "/oauth/mobile/guest" })
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
        // Published, so the account section falls back to the sign-in wording.
        assertTrue(session.state.value.guestsRefused)
        assertEquals("guests: refused by backend (404)", diagnostic(session))
    }

    @Test fun aClientWithoutGuestScopeFallsBackToSignIn() = runTest {
        for (status in listOf(401, 403)) {
            val session = AppSession(config(), InMemoryCredentialStore(), Backend(status = status))
            session.restore()
            assertEquals("guests: ready", diagnostic(session))
            assertFalse(session.ensureGuest())
            assertFalse(session.canAnswerAsGuest)
            assertEquals("guests: refused (401/403, client scope?)", diagnostic(session))
        }
    }

    @Test fun theDiagnosticNamesTheBuildFirst() {
        assertEquals("guests: off in this build", guestDiagnostic(false, true, ErrorKind.NOT_FOUND))
        assertEquals("guests: writes off for this backend", guestDiagnostic(true, false, null))
        assertEquals("guests: ready", guestDiagnostic(true, true, null))
    }

    private fun diagnostic(session: AppSession) =
        guestDiagnostic(session.config.guests, session.config.writesAllowed, session.state.value.guestRefusal)

    @Test fun aFailedRegistrationTriesAgainLater() = runTest {
        val backend = Backend(status = 503)
        val session = AppSession(config(), InMemoryCredentialStore(), backend)
        session.restore()
        assertFalse(session.ensureGuest())
        assertTrue(session.canAnswerAsGuest)
    }

    /** The first status read hangs until released; later ones answer at once with one more answer. */
    private class SlowFirstRead : Transport {
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstSent = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val reads = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun execute(request: HttpRequest): HttpResponse = when (URI(request.url).path) {
            "/oauth/mobile/guest" -> HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-secret-secret-1"}""")
            "/oauth/mobile/token" -> HttpResponse(200, body = """{"token_type":"Bearer","access_token":"guest-access-token-0001","expires_in":900,"scope":"guest"}""")
            "/api/v2/mobile-guest/me" -> {
                val pending = reads.getAndIncrement()
                if (pending == 0) {
                    firstSent.complete(Unit)
                    first.await()
                }
                HttpResponse(200, body = """{"guestId":"guest-1","state":"active","email":"none","pending":$pending,"published":0,""" +
                    """"expiresAt":"2099-01-01T00:00:00Z","byChallenge":{"3":{"pending":$pending,"onMap":0}}}""")
            }
            else -> HttpResponse(404)
        }
    }

    @Test fun anOlderStatusReadNeverHidesANewerCount() = runTest {
        val backend = SlowFirstRead()
        val session = AppSession(config(), InMemoryCredentialStore(), backend)
        session.restore()
        assertTrue(session.ensureGuest())
        // A campaign loading reads the status; meanwhile a waiting answer sends and reads it again.
        val loading = backgroundScope.launch { session.refreshGuestStatus() }
        backend.firstSent.await()
        session.refreshGuestStatus()
        assertEquals(1, session.guest.value.status?.pendingCount)
        backend.first.complete(Unit)
        loading.join()
        assertEquals(1, session.guest.value.status?.pendingCount)
    }
}
