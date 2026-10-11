package lol.osm.streettally.auth

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.Transport

/** "I already use OpenStreetMap" after answering as a guest (B5, UX §1.3): signing in on the phone
 * claims the saved answers and follows them onto the map. */
class GuestClaimTest {
    private val config = AppConfig(
        baseUrl = "https://mr-api.osm.lol",
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = false,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
        guests = true,
    )

    private fun claim(status: Int = 202, state: String = "queued", pending: Int = 3) = HttpResponse(
        status, body = """{"claimId":9,"state":"$state","pending":$pending,"user":{"displayName":"rosa_slc","osmId":123}}""",
    )

    private fun progress(state: String, published: Int, skipped: Int = 0, failed: Int = 0) = HttpResponse(
        200,
        body = """{"claimId":9,"state":"$state","total":3,"published":$published,"skipped":$skipped,"superseded":0,""" +
            """"failed":$failed,"items":[]}""",
    )

    /** Guest registration, browser sign-in, then [claims] for `POST mobile-claim` and [statuses] for
     * `GET mobile-claim/9`, in turn. Records "METHOD path". */
    private class Backend(claims: List<HttpResponse> = emptyList(), statuses: List<HttpResponse> = emptyList()) : Transport {
        val claims = ArrayDeque(claims)
        val statuses = ArrayDeque(statuses)
        val calls = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path
            calls += "${request.method} $path"
            val form = request.body.orEmpty()
            return when (path) {
                "/oauth/mobile/guest" -> HttpResponse(201, body = """{"guestId":"6f1c2a9e-0000-4000-8000-000000000001","guestSecret":"${"s".repeat(43)}"}""")
                "/oauth/mobile/token" -> if ("grant_type=urn" in form) {
                    HttpResponse(200, body = """{"token_type":"Bearer","access_token":"guest-access-token-0001","expires_in":900,"scope":"guest"}""")
                } else HttpResponse(
                    200,
                    body = """{"token_type":"Bearer","access_token":"account-access-token-01","refresh_token":"account-refresh-token-1",""" +
                        """"expires_in":3600,"scope":"$SCOPES"}""",
                )
                "/oauth/mobile/me" -> HttpResponse(200, body = """{"id":900,"osmId":123,"displayName":"rosa_slc","scope":"$SCOPES"}""")
                "/api/v2/mobile-guest/me" -> HttpResponse(
                    200, body = """{"guestId":"g","state":"active","email":"none","pending":3,"published":0,"expiresAt":"2026-11-30T18:00:00Z"}""",
                )
                "/api/v2/mobile-claim" -> claims.removeFirstOrNull() ?: HttpResponse(500)
                "/api/v2/mobile-claim/9" -> statuses.removeFirstOrNull() ?: HttpResponse(500)
                else -> HttpResponse(404)
            }
        }
        val claimPosts get() = calls.count { it == "POST /api/v2/mobile-claim" }
    }

    private fun approve(url: String): String {
        val query = URI(url).rawQuery.split('&').associate {
            val kv = it.split('=', limit = 2)
            kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }
        return "lol.osm.maproulette.choice:/oauth2redirect?code=the-code&state=${query["state"]}"
    }

    private suspend fun guest(backend: Backend, store: InMemoryCredentialStore = InMemoryCredentialStore()): AppSession {
        val session = AppSession(config, store, backend)
        session.restore()
        assertTrue(session.ensureGuest())
        return session
    }

    @Test fun signingInClaimsTheSavedAnswersAndFollowsThemOntoTheMap() = runTest {
        val backend = Backend(listOf(claim()), listOf(progress("running", 1), progress("done", 3)))
        val session = guest(backend)
        session.signIn(::approve)
        val state = session.state.value
        assertEquals("rosa_slc", state.account?.displayName)
        assertFalse(state.guest)
        assertNull(session.guest.value.status)
        assertEquals(1, backend.claimPosts)
        assertEquals(2, backend.calls.count { it == "GET /api/v2/mobile-claim/9" })
        assertEquals("✓ 3 answers are on the map.", state.message)
        assertTrue("good news isn't shown as an error", state.messageCalm)
    }

    @Test fun theFirstNoteSaysTheAnswersAreGoingOnTheMap() {
        val claim = org.maproulette.sdk.GuestClaim(9, org.maproulette.sdk.ClaimState.QUEUED, 12, org.maproulette.sdk.ClaimedAccount("rosa_slc", 123), true)
        assertEquals("You're signed in as rosa_slc. Putting your 12 saved answers on the map…", Wording.claimStarted(claim))
        assertEquals("You're signed in as rosa_slc. Putting your saved answer on the map…", Wording.claimStarted(claim.copy(pendingCount = 1)))
        assertEquals(
            "You're signed in as rosa_slc. Your 12 saved answers are now linked to your account. They'll go on the map later.",
            Wording.claimStarted(claim.copy(state = org.maproulette.sdk.ClaimState.WAITING)),
        )
        assertEquals("You're signed in as rosa_slc. New answers go straight on the map from now on.", Wording.claimStarted(claim.copy(pendingCount = 0)))
    }

    @Test fun partialAndFailedResults() {
        fun status(state: org.maproulette.sdk.ClaimState, published: Int, skipped: Int, failed: Int) =
            org.maproulette.sdk.ClaimStatus(9, state, 14, published, skipped, 0, failed, emptyList())
        assertEquals(
            "✓ 12 answers are on the map. 2 weren't added, because someone changed those places on the map after you checked.",
            Wording.claimProgress(status(org.maproulette.sdk.ClaimState.DONE, 12, 2, 0)),
        )
        assertEquals(
            "8 of 14 are on the map. The rest are still saved.",
            Wording.claimProgress(status(org.maproulette.sdk.ClaimState.FAILED, 8, 0, 6)),
        )
        assertEquals(
            "✓ 1 answer is on the map. 1 wasn't added, because someone changed that place on the map after you checked.",
            Wording.claimProgress(status(org.maproulette.sdk.ClaimState.DONE, 1, 1, 0)),
        )
        assertEquals("Putting your answers on the map… 6 of 14", Wording.claimProgress(status(org.maproulette.sdk.ClaimState.RUNNING, 6, 0, 0)))
    }

    @Test fun aClaimWhileWritesAreOffWaitsWithoutPolling() = runTest {
        val backend = Backend(listOf(claim(state = "waiting")))
        val session = guest(backend)
        session.signIn(::approve)
        assertTrue(session.state.value.message!!.endsWith("They'll go on the map later."))
        assertTrue(backend.calls.none { it.startsWith("GET /api/v2/mobile-claim/") })
    }

    @Test fun aGuestThatCanNoLongerBeClaimedIsDroppedWithANote() = runTest {
        val backend = Backend(listOf(HttpResponse(404, body = """{"error":"not_found"}""")))
        val session = guest(backend)
        session.signIn(::approve)
        assertFalse(session.state.value.guest)
        assertEquals(
            "You're signed in as rosa_slc. We couldn't find your saved answers. They may have expired, or they're linked to a different OpenStreetMap account.",
            session.state.value.message,
        )
        assertNotNullAccount(session)
    }

    @Test fun aFailedClaimIsTriedAgainOnTheNextLaunch() = runTest {
        val store = InMemoryCredentialStore()
        val backend = Backend(listOf(HttpResponse(503)), listOf(progress("done", 3)))
        val session = guest(backend, store)
        session.signIn(::approve)
        assertEquals("You're signed in as rosa_slc. Your answers are still saved. We'll try again next time you open the app.", session.state.value.message)
        assertEquals(1, backend.claimPosts)

        // Next launch: signed in, guest still kept; StreetTallyApp claims again.
        val next = AppSession(config, store, Backend(listOf(claim()), listOf(progress("done", 3))))
        next.restore()
        next.claimKeptGuest()
        assertEquals("✓ 3 answers are on the map.", next.state.value.message)
        assertFalse(next.state.value.guest)
    }

    @Test fun osmReauthAsksToSignInAgain() = runTest {
        val backend = Backend(listOf(HttpResponse(401, body = """{"error":"osm_reauth_required"}""")))
        val session = guest(backend)
        session.signIn(::approve)
        assertEquals("Sign in again so we can put your saved answers on the map.", session.state.value.message)
        assertFalse(session.state.value.messageCalm)
    }

    @Test fun signingInWithoutAGuestClaimsNothing() = runTest {
        val backend = Backend()
        val session = AppSession(config, InMemoryCredentialStore(), backend)
        session.restore()
        session.signIn(::approve)
        assertEquals(0, backend.claimPosts)
        assertNull(session.state.value.message)
    }

    private fun assertNotNullAccount(session: AppSession) = assertTrue(session.state.value.account != null)

    private companion object {
        const val SCOPES = "osm:tagfix tasks:read tasks:write"
    }
}
