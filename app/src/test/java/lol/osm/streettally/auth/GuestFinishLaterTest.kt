package lol.osm.streettally.auth

import java.net.URI
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.GuestEmailState
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.Transport

/** Finishing later as a guest (deferred sign-up §1.3–§1.6): status, the email link, deleting the
 * guest's data, and becoming the account that claimed the answers on the web. */
class GuestFinishLaterTest {
    private val config = AppConfig(
        baseUrl = "https://mr-api.osm.lol",
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
        guests = true,
    )

    private fun status(state: String = "active", email: String = "none", pending: Int = 3, published: Int = 0, claimed: Boolean = false) =
        HttpResponse(
            200,
            body = """{"guestId":"guest-1","state":"$state","email":"$email","pending":$pending,"published":$published,""" +
                """"expiresAt":"2026-11-30T18:00:00Z"""" +
                (if (claimed) ""","claimedAs":{"displayName":"rosa_slc","osmId":42}""" else "") + "}",
        )

    /** A guest backend: [me] answers the status read, [email] the link request; the claim grant
     * signs in only when [claimable]. Records "METHOD path". */
    private class Backend(
        var me: HttpResponse,
        var email: HttpResponse = HttpResponse(404),
        val claimable: Boolean = false,
    ) : Transport {
        val calls = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path
            calls += "${request.method} $path"
            val form = request.body.orEmpty()
            return when (path) {
                "/oauth/mobile/guest" -> HttpResponse(201, body = """{"guestId":"guest-1","guestSecret":"secret-secret-secret-1"}""")
                "/oauth/mobile/token" -> when {
                    "guest_claim" in form && claimable -> HttpResponse(
                        200,
                        body = """{"token_type":"Bearer","access_token":"account-access-token-01","refresh_token":"account-refresh-token-1","expires_in":3600,""" +
                            """"scope":"$SCOPES"}""",
                    )
                    "guest_claim" in form -> HttpResponse(400, body = """{"error":"claim_pending"}""")
                    else -> HttpResponse(200, body = """{"token_type":"Bearer","access_token":"guest-access-token-0001","expires_in":900,"scope":"guest"}""")
                }
                "/oauth/mobile/me" -> HttpResponse(200, body = """{"id":900,"osmId":42,"displayName":"rosa_slc","scope":"$SCOPES"}""")
                "/api/v2/mobile-guest/me" -> me
                "/api/v2/mobile-guest/email" -> email
                "/api/v2/mobile-guest" -> HttpResponse(204)
                else -> HttpResponse(404)
            }
        }
    }

    private suspend fun guest(backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore()): AppSession {
        val session = AppSession(config, store, backend)
        session.restore()
        assertTrue(session.ensureGuest())
        return session
    }

    @Test fun statusIsReadForGuestsOnly() = runTest {
        val backend = Backend(status(pending = 3))
        val signedOut = AppSession(config, InMemoryCredentialStore(), backend)
        signedOut.restore()
        signedOut.refreshGuestStatus()
        assertNull(signedOut.guest.value.status)
        assertTrue(backend.calls.none { it.endsWith("mobile-guest/me") })

        val session = guest(backend)
        session.refreshGuestStatus()
        assertEquals(3, session.guest.value.status?.pendingCount)
        // A failed read keeps the last status.
        backend.me = HttpResponse(503)
        session.refreshGuestStatus()
        assertEquals(3, session.guest.value.status?.pendingCount)
    }

    @Test fun theLinkAddressIsKeptForThisLaunchOnly() = runTest {
        val store = InMemoryCredentialStore()
        val backend = Backend(status(), email = status(email = "pending"))
        val session = guest(backend, store)
        session.sendFinishLink(" rosa@example.com ")
        assertEquals("rosa@example.com", session.guest.value.lastLinkEmail)
        assertEquals(GuestEmailState.PENDING, session.guest.value.status?.email)
        assertTrue("PUT /api/v2/mobile-guest/email" in backend.calls)

        val next = AppSession(config, store, Backend(status(email = "pending")))
        next.restore()
        assertTrue(next.state.value.guest)
        assertEquals(GuestEmailState.PENDING, next.guest.value.status?.email)
        assertNull(next.guest.value.lastLinkEmail)
    }

    @Test fun aRefusedLinkThrowsAndChangesNothing() = runTest {
        val backend = Backend(status(), email = HttpResponse(409, body = """{"error":"nothing_saved"}"""))
        val session = guest(backend)
        val error = runCatching { session.sendFinishLink("rosa@example.com") }.exceptionOrNull() as MapRouletteException
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertNull(session.guest.value.lastLinkEmail)
    }

    @Test fun deletingForgetsTheGuest() = runTest {
        val store = InMemoryCredentialStore()
        val backend = Backend(status())
        val session = guest(backend, store)
        session.refreshGuestStatus()
        session.deleteGuestData()
        assertTrue("DELETE /api/v2/mobile-guest" in backend.calls)
        assertFalse(session.state.value.guest)
        assertNull(session.guest.value.status)
        val next = AppSession(config, store, backend)
        next.restore()
        assertFalse(next.state.value.guest)
    }

    @Test fun aClaimedGuestBecomesThatAccountOnLaunch() = runTest {
        val store = InMemoryCredentialStore()
        guest(Backend(status()), store)
        val next = AppSession(config, store, Backend(status(state = "claimed", pending = 0, published = 12, claimed = true), claimable = true))
        next.restore()
        val state = next.state.value
        assertFalse(state.guest)
        assertEquals(900L, state.account?.userId?.toLong())
        assertEquals(
            "You're signed in as rosa_slc. Your 12 answers are on the map. New answers go straight on the map from now on.",
            state.message,
        )
    }

    @Test fun anUnclaimedGuestStaysAGuest() = runTest {
        val store = InMemoryCredentialStore()
        guest(Backend(status()), store)
        val backend = Backend(status(pending = 5))
        val next = AppSession(config, store, backend)
        next.restore()
        assertTrue(next.state.value.guest)
        assertEquals(5, next.guest.value.status?.pendingCount)
        assertNull(next.state.value.message)
    }

    private companion object {
        const val SCOPES = "osm:tagfix tasks:read tasks:write"
    }
}
