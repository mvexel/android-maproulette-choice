package lol.osm.streettally.ui

import lol.osm.streettally.auth.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maproulette.sdk.MobileAccount

class AccountTextTest {
    @Test fun labelsMatchIos() {
        assertEquals("Sign in with OpenStreetMap", signInLabel(signedIn = false, needsReconsent = false))
        assertEquals("Sign in again to save answers", signInLabel(signedIn = true, needsReconsent = true))
        assertEquals("Sign out", signInLabel(signedIn = true, needsReconsent = false))
        assertEquals("I already use OpenStreetMap", signInLabel(signedIn = false, needsReconsent = false, guestsAvailable = true))
        assertEquals("Sign out", signInLabel(signedIn = true, needsReconsent = false, guestsAvailable = true))
    }

    @Test fun accountLine() {
        assertEquals("Checking your sign-in…", accountLine(SessionState()))
        val signedOut = SessionState(restored = true)
        assertEquals("To save answers you'll sign in with a free OpenStreetMap account.", accountLine(signedOut))
        assertEquals(
            "No account needed to start. Answer a few places first, and we'll help you put them on the map afterwards.",
            accountLine(signedOut, guestsAvailable = true),
        )
        val named = signedOut.copy(account = MobileAccount(1, setOf("tasks:read"), 1, "rosa_slc"))
        assertEquals("Signed in as rosa_slc.", accountLine(named))
        assertEquals("Signed in.", accountLine(named.copy(account = named.account!!.copy(displayName = null))))
    }
}
