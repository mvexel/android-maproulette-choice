package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignPlaces
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.InMemoryCredentialStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tess's guest cases (test/test-plan-sprint2-woodbine.md §G) on dev, against [WoodbineBackend]: no
 * network, no emulator. The copy is the spec, so each case asserts Wording's exact strings. G-6 and
 * G-7 stay manual; G-8 covers a lost response here, and killing the app stays on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp")
class GuestCasesTest {
    @get:Rule val compose = createComposeRule()

    private val noun = Noun("entrance", "entrances")

    /** G-1: a fresh install, no sign-in, a live campaign: every open place is listed, the tally starts at 0. */
    @Test fun g1AGuestSeesEveryOpenPlace() {
        open(WoodbineBackend())
        waitFor(Wording.noAccountNeeded(noun))
        tap("Let's go")
        waitFor(Wording.progress(0, 4, noun))
        compose.onNodeWithText(Wording.choose(noun)).assertExists()
        tap("List")
        waitFor("To check")
        WoodbineBackend.DOORS.forEach { compose.onNodeWithText(it.name).assertExists() }
    }

    /** G-2 on this phone: the saved copy and its note, the place saved but not on the map, the tally +1. */
    @Test fun g2AGuestAnswerIsSavedNotOnTheMap() {
        val backend = WoodbineBackend()
        open(backend)
        tap("Let's go")
        answerMainDoor()
        waitFor("✓ " + Wording.savedForLater(1, noun))
        compose.onNodeWithText(Wording.SAVED_FOR_LATER_NOTE).assertExists()
        backTo()
        waitFor(Wording.progress(1, 4, noun))
        tap("List")
        waitFor("Already checked")
        compose.onNode(hasContentDescription(Wording.SAVED_NOT_ON_MAP, substring = true)).assertExists()
        assertEquals(listOf(101L), backend.pending.keys.toList())
    }

    /** G-2 on the other phone (T-001): a guest's saved answer, flagged by the bounds search with
     * includePending, shows as plainly checked: counted, under "Already checked", never "Saved, not on
     * the map yet", and not offered. */
    @Test fun g2AnotherPhoneShowsTheSavedPlaceAsChecked() {
        val backend = WoodbineBackend()
        backend.pending[101L] = """{"answers":{"automatic-opening":"button","door-type":"hinged"}}"""
        open(backend)
        tap("Let's go")
        waitFor(Wording.progress(1, 4, noun))
        tap("List")
        waitFor("Already checked")
        compose.onNode(hasContentDescription("Main door", substring = true) and hasContentDescription("Already checked", substring = true))
            .assertExists()
        assertGone(Wording.SAVED_NOT_ON_MAP)
        assertEquals(0, compose.onAllNodes(hasContentDescription(Wording.SAVED_NOT_ON_MAP, substring = true)).fetchSemanticsNodes().size)
        // Listed once, as checked: not also under "To check".
        assertEquals(1, compose.onAllNodesWithText("Main door").fetchSemanticsNodes().size)
    }

    /** which-one-spec.md F: the way out records "I can't check this" and the place needs another look. */
    @Test fun iCantTellWhichOneNeedsAnotherLook() {
        val backend = WoodbineBackend()
        open(backend)
        tap("Let's go")
        tap("List")
        tap("Main door")
        waitFor("How does this door open?")
        tap(Wording.cantTell(noun))
        waitFor(Wording.cantTellThanks(noun))
        assertTrue(backend.pending[101L].orEmpty().contains("\"outcome\":\"too-hard\""))
        backTo()
        waitFor(Wording.progress(0, 4, noun))
        tap("List")
        waitFor(Wording.NEEDS_LOOK)
    }

    /** T-002: another guest's saved report (here "There's no door") holds a place: it needs another
     * look on this phone, doesn't count, and isn't offered. */
    @Test fun anotherGuestsSavedReportNeedsAnotherLook() {
        val backend = WoodbineBackend()
        backend.pending[102L] = """{"outcome":"no-door"}"""
        open(backend)
        tap("Let's go")
        waitFor(Wording.progress(0, 4, noun))
        tap("List")
        waitFor(Wording.NEEDS_LOOK)
        compose.onNode(hasContentDescription("Food hall door", substring = true) and hasContentDescription(Wording.NEEDS_LOOK, substring = true))
            .assertExists()
        assertGone("Already checked")
    }

    /** G-3 after G1 (guests everywhere): a campaign without liveMissingQuestions reads like any other to a
     * guest: "No account needed", every place visible, no sign-in block. */
    @Test fun g3ACampaignWithoutTheLiveFlagIsOpenToGuests() {
        open(WoodbineBackend(WoodbineBackend.DOORS.map { it.copy(live = false) }))
        waitFor(Wording.noAccountNeeded(noun))
        tap("Let's go")
        waitFor(Wording.progress(0, 4, noun))
        assertGone("Sign in with OpenStreetMap")
        compose.onNodeWithText(Wording.choose(noun)).assertExists()
        tap("List")
        waitFor("To check")
        WoodbineBackend.DOORS.forEach { compose.onNodeWithText(it.name).assertExists() }
    }

    /** G-4 after G1: a guest answers a place without the flag. Where the server still refuses it
     * (422 unsupported_task), sign-in is offered for this place, with the next open one first. */
    @Test fun g4AnUnsupportedPlaceFallsBackToSignIn() {
        val backend = WoodbineBackend()
        open(backend)
        tap("Let's go")
        tap("List")
        tap("Loading dock door")
        waitFor("How does this door open?")
        tap("Swings open")
        waitFor("Does it open by itself?")
        tap("Yes, with a button")
        waitFor("Check your answers")
        tapButton(Wording.DONE)
        waitFor(Wording.guestSignIn(noun))
        compose.onNodeWithText(Wording.guestSignInDetail(noun)).assertExists()
        compose.onNodeWithText(Wording.next(noun, null), substring = true).assertExists()
        compose.onNodeWithText("Sign in with OpenStreetMap").assertExists()
        assertGone(Wording.lastOne(noun))
        assertEquals(0, compose.onAllNodesWithText("these ${noun.many}", substring = true).fetchSemanticsNodes().size)
        assertEquals(listOf(104L), backend.answerPosts.toList())
        assertEquals(emptyList<Long>(), backend.pending.keys.toList())
    }

    /** G-5: every place checked: the all-checked line, and no sign-in block. */
    @Test fun g5AFullyCheckedCampaignHasNoSignInBlock() {
        open(WoodbineBackend(WoodbineBackend.DOORS.map { it.copy(live = false, status = 1) }))
        tap("Let's go")
        waitFor(Wording.progress(4, 4, noun))
        waitFor(Wording.allChecked(noun))
        assertGone("Sign in with OpenStreetMap")
    }

    /** G-1 / S-3 parity: on the same backend, a guest and a signed-in account see the same places in
     * the same sections, and the same tally (one place held by someone's saved answer). */
    @Test fun g1AGuestAndAnAccountSeeTheSamePlaces() {
        val backend = WoodbineBackend()
        backend.pending[102L] = """{"answers":{"automatic-opening":"no","door-type":"hinged"}}"""
        val guest = session(backend)
        val account = session(backend).also { s -> runBlocking { s.signIn(::approve) } }
        assertEquals("rosa_slc", account.state.value.account?.displayName)
        var current by mutableStateOf(guest)
        val prefs = CampaignPrefs(RuntimeEnvironment.getApplication())
        compose.setContent {
            StreetTallyTheme(dark = false) { key(current) { App(current, prefs, CheckedLog()) } }
        }
        tap("Let's go")
        fun seen(): List<String> {
            waitFor(Wording.progress(1, 4, noun))
            tap("List")
            waitFor("Already checked")
            val rows = compose.onAllNodes(hasClickAction() or hasContentDescription("Already checked", substring = true))
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }
                .filter { d -> WoodbineBackend.DOORS.any { d.startsWith(it.name) } }
            return rows
        }
        val asGuest = seen()
        current = account
        compose.waitForIdle()
        val signedIn = seen()
        assertEquals(asGuest, signedIn)
        assertEquals(listOf("Main door", "East door", "Loading dock door", "Food hall door, Already checked"), asGuest)
    }

    /** Plays the browser for an account sign-in: approves and echoes the state. */
    private fun approve(url: String): String {
        val state = java.net.URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }.first { it[0] == "state" }[1]
        return "lol.osm.maproulette.choice:/oauth2redirect?code=the-code&state=$state"
    }

    /** G-8: the connection drops after the server stored the answer. The answers stay on screen, sending
     * again replaces the stored one, and the place and the tally count once. */
    @Test fun g8ALostResponseSavesTheAnswerOnce() {
        val backend = WoodbineBackend()
        backend.dropNextAnswer = true
        open(backend)
        tap("Let's go")
        answerMainDoor()
        waitFor("Couldn't connect. Check your connection and try again.")
        waitFor("Check your answers")
        tapButton(Wording.DONE)
        waitFor("✓ " + Wording.savedForLater(1, noun))
        backTo()
        waitFor(Wording.progress(1, 4, noun))
        assertEquals(listOf(101L, 101L), backend.answerPosts.toList())
        assertEquals(listOf(101L), backend.pending.keys.toList())
        tap("List")
        waitFor("Already checked")
        assertEquals(1, compose.onAllNodes(hasContentDescription(Wording.SAVED_NOT_ON_MAP, substring = true)).fetchSemanticsNodes().size)
    }

    /** Main door, from the list: both questions, then Done. */
    private fun answerMainDoor() {
        tap("List")
        tap("Main door")
        waitFor("How does this door open?")
        tap("Swings open")
        waitFor("Does it open by itself?")
        tap("Yes, with a button")
        waitFor("Check your answers")
        tapButton(Wording.DONE)
    }

    private fun session(backend: WoodbineBackend) =
        AppSession(WoodbineBackend.CONFIG, InMemoryCredentialStore(), backend).also { runBlocking { it.restore() } }

    /** Opens the campaign as a fresh guest, as [WoodbineJourneyTest]. */
    private fun open(backend: WoodbineBackend) {
        val session = session(backend)
        compose.setContent {
            StreetTallyTheme(dark = false) { App(session, CampaignPrefs(RuntimeEnvironment.getApplication()), CheckedLog()) }
        }
        waitFor("Let's go")
    }

    @Composable
    private fun App(session: AppSession, prefs: CampaignPrefs, checked: CheckedLog) {
        val saved = rememberSaveableStateHolder()
        var place by remember { mutableStateOf<Pair<LocatedTask, CampaignPlaces>?>(null) }
        CompositionLocalProvider(LocalMapPlaceholder provides { _, modifier, _ -> Box(modifier) }) {
            val open = place
            if (open != null) {
                PlaceScreen(
                    session = session, checked = checked, campaignId = WoodbineBackend.CAMPAIGN,
                    firstTaskId = open.first.id, candidates = open.second.tasks, noun = open.second.noun,
                    origin = null, onSignIn = {}, onBack = { place = null }, reports = open.second.reports,
                )
            } else {
                saved.SaveableStateProvider("campaign") {
                    CampaignScreen(
                        session = session, prefs = prefs, checkedLog = checked, campaignId = WoodbineBackend.CAMPAIGN,
                        onBack = {}, onPlace = { task, places, _ -> place = task to places },
                    )
                }
            }
        }
    }

    /** Back to the campaign from a place. */
    private fun backTo() = tap(Wording.backToAll(noun))

    private fun assertGone(text: String) =
        assertEquals(0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    /** Waits for [text]; on timeout, fails with the text and what the screen shows instead. */
    private fun waitFor(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("No “$text” on screen:\n" + compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(), e)
        }
    }

    private fun SemanticsNodeInteraction.tapInView() {
        runCatching { performScrollTo() }
        performClick()
    }

    private fun tap(text: String) {
        waitFor(text)
        compose.onNodeWithText(text).tapInView()
    }

    private fun tapButton(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text) and hasClickAction()).tapInView()
    }
}
