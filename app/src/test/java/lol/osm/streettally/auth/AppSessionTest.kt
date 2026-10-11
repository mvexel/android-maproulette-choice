package lol.osm.streettally.auth

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import lol.osm.streettally.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.HttpRequest
import org.maproulette.sdk.HttpResponse
import org.maproulette.sdk.InMemoryCredentialStore
import org.maproulette.sdk.MobileAccount
import org.maproulette.sdk.SignInFailure
import org.maproulette.sdk.Transport

class AppSessionTest {
    private val writeScopes = "osm:tagfix tasks:read tasks:write"

    private fun config(baseUrl: String = "https://mr-api.osm.lol", live: Boolean = false) = AppConfig(
        baseUrl = baseUrl,
        oauthClientId = "maproulette-ios-choice",
        callbackScheme = "lol.osm.maproulette.choice",
        allowLiveWrites = live,
        mapStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        campaignLinkHost = "streettally.osm.lol",
    )

    /** The backend's mobile OAuth routes with canned answers; records request paths. */
    private class Backend(val scope: String, val displayName: String? = "rosa_slc") : Transport {
        val paths = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path
            paths += path
            return when (path) {
                "/oauth/mobile/token" -> HttpResponse(
                    200,
                    body = """{"token_type":"Bearer","access_token":"access-1","refresh_token":"refresh-1",""" +
                        """"expires_in":3600,"scope":"$scope"}""",
                )
                "/oauth/mobile/me" -> HttpResponse(
                    200,
                    body = """{"id":900,"osmId":12345,"scope":"$scope"""" +
                        (displayName?.let { ""","displayName":"$it"""" } ?: "") + "}",
                )
                "/oauth/mobile/revoke" -> HttpResponse(200)
                else -> HttpResponse(404)
            }
        }
    }

    /** Plays the browser: approves and echoes the state. */
    private fun approve(url: String): String {
        val query = URI(url).rawQuery.split('&').associate {
            val kv = it.split('=', limit = 2)
            kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }
        assertEquals("lol.osm.maproulette.choice:/oauth2redirect", query["redirect_uri"])
        assertEquals("maproulette-ios-choice", query["client_id"])
        return "lol.osm.maproulette.choice:/oauth2redirect?code=the-code&state=${query["state"]}"
    }

    @Test fun restoreWithNothingSavedIsSignedOut() = runTest {
        val session = AppSession(config(), InMemoryCredentialStore(), Backend(writeScopes))
        assertFalse(session.state.value.restored)
        session.restore()
        val state = session.state.value
        assertTrue(state.restored)
        assertNull(state.account)
        assertNull(state.message)
    }

    @Test fun signInAsksForWriteScopesOnDevAndKeepsTheAccount() = runTest {
        val store = InMemoryCredentialStore()
        val backend = Backend(writeScopes)
        val session = AppSession(config(), store, backend)
        session.restore()
        val before = session.state.value.generation
        var opened: String? = null
        session.signIn { url -> opened = url; approve(url) }
        assertTrue(opened!!.startsWith("https://mr-api.osm.lol/oauth/mobile/authorize?"))
        assertTrue(opened!!.contains("scope=osm%3Atagfix+tasks%3Aread+tasks%3Awrite") || opened!!.contains("scope=osm%3Atagfix%20tasks%3Aread%20tasks%3Awrite"))
        val state = session.state.value
        assertEquals("rosa_slc", state.account?.displayName)
        assertFalse(state.busy)
        assertNull(state.message)
        assertNotEquals(before, state.generation)
        assertFalse(session.needsReconsent)

        // A new process restores the same account from the store.
        val again = AppSession(config(), store, backend)
        again.restore()
        assertEquals(900L, again.state.value.account?.userId)
    }

    @Test fun readOnlyStageAsksForReadOnly() = runTest {
        val session = AppSession(config("https://mr-stage.osm.lol"), InMemoryCredentialStore(), Backend("tasks:read"))
        assertEquals(setOf("tasks:read"), session.requestedScopes)
        val live = AppSession(config("https://mr-stage.osm.lol", live = true), InMemoryCredentialStore(), Backend(writeScopes))
        assertEquals(setOf("tasks:read", "tasks:write", "osm:tagfix"), live.requestedScopes)
    }

    // As iOS (Iris, parity row 15): a sign-in that didn't finish says so.
    @Test fun closingTheBrowserSaysSignInDidntFinish() = runTest {
        val session = AppSession(config(), InMemoryCredentialStore(), Backend(writeScopes))
        session.restore()
        session.signIn { throw IllegalStateException("browser closed") }
        val state = session.state.value
        assertNull(state.account)
        assertEquals("Sign-in didn't finish. Please try again.", state.message)
        assertFalse(state.busy)
    }

    @Test fun signOutRevokesAndChangesGeneration() = runTest {
        val backend = Backend(writeScopes)
        val session = AppSession(config(), InMemoryCredentialStore(), backend)
        session.restore()
        session.signIn(::approve)
        val signedIn = session.state.value.generation
        session.signOut()
        assertNull(session.state.value.account)
        assertNotEquals(signedIn, session.state.value.generation)
        assertTrue("/oauth/mobile/revoke" in backend.paths)
    }

    @Test fun readOnlyGrantNeedsReconsentWhenWritesAreOn() {
        val readOnly = MobileAccount(900, setOf("tasks:read"), 1)
        val full = MobileAccount(900, setOf("tasks:read", "tasks:write", "osm:tagfix"), 1)
        assertTrue(AppSession.needsReconsent(writesAllowed = true, account = readOnly))
        assertFalse(AppSession.needsReconsent(writesAllowed = true, account = full))
        assertFalse(AppSession.needsReconsent(writesAllowed = false, account = readOnly))
        assertFalse(AppSession.needsReconsent(writesAllowed = true, account = null))
    }

    @Test fun failureMessages() {
        assertEquals("Sign-in didn't finish. Please try again.", AppSession.failureMessage(SignInFailure.Canceled))
        assertNull(AppSession.failureMessage(SignInFailure.Superseded))
        assertEquals(AppSession.TRY_AGAIN, AppSession.failureMessage(SignInFailure.TokenExchange))
        assertEquals(AppSession.TRY_AGAIN, AppSession.failureMessage(SignInFailure.ProviderError("server_error")))
        assertEquals("Sign-in was canceled on OpenStreetMap.", AppSession.failureMessage(SignInFailure.Denied))
    }
}
