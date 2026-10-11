package lol.osm.streettally.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import lol.osm.streettally.auth.AppSession
import lol.osm.streettally.campaign.CampaignPlaces
import lol.osm.streettally.campaign.CampaignPrefs
import lol.osm.streettally.campaign.Campaigns
import lol.osm.streettally.campaign.CheckedLog
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.InMemoryCredentialStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val MAP = "map"

/** Where a tap on the moved map puts the East door: 40 m north of where the map has it. */
private val MOVED_TO = WoodbineBackend.DOORS.single { it.id == 103L }.position.let { LatLon(it.lat + 40 / 111_320.0, it.lon) }

/**
 * The Woodbine pilot journey end to end, as a guest on dev, against [WoodbineBackend] (no network):
 * the welcome card, the map and the list (the door that needs an account included), an answer
 * saved for later, "There's no door" and "It's moved" (both "Needs another look"), and the tally. Compose under Robolectric rather than an emulator, so it runs in CI's unit tests.
 * Maps are placeholders (MapLibre doesn't draw here); a tap on the moved map picks [MOVED_TO].
 * No location: the phone never answers the permission prompt, so places are in id order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp")
class WoodbineJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aGuestChecksTheWoodbineDoors() {
        val backend = WoodbineBackend()
        val session = AppSession(WoodbineBackend.CONFIG, InMemoryCredentialStore(), backend)
        runBlocking { session.restore() }
        val prefs = CampaignPrefs(RuntimeEnvironment.getApplication())
        compose.setContent { StreetTallyTheme(dark = false) { App(session, prefs, CheckedLog()) } }

        // Welcome card, once the campaign has loaded.
        waitFor("Let's go")
        compose.onNodeWithText("What to look for").assertExists()
        compose.onNodeWithText("• How does this door open?").assertExists()
        tap("Let's go")
        waitFor("The tally: 0 of 4 entrances checked")

        // The map first, then the list. The door that needs an account is listed too; it says so when opened.
        compose.onNodeWithTag(MAP).assertExists()
        assertGone(Wording.GUEST_CAMPAIGN_NEEDS_ACCOUNT)
        tap("List")
        waitFor("To check")
        compose.onNodeWithText("Loading dock door").assertExists()

        // An answer, saved for later.
        tap("Main door")
        waitFor("How does this door open?")
        tap("Swings open")
        waitFor("Does it open by itself?")
        tap("Yes, with a button")
        waitFor("Check your answers")
        tapButton(Wording.DONE)
        waitFor("✓ Saved. That's 1 entrance today.")
        tap(BACK)

        // "There's no door": the place needs another look.
        tap("Food hall door")
        waitFor("How does this door open?")
        tap(Wording.SOMETHING_WRONG)
        waitFor("There's no door")
        tap("There's no door")
        waitFor("✓ Saved. That's 2 entrances today.")
        tap(BACK)

        // "It's moved": pick the spot on the map, check the report, send it.
        tap("East door")
        waitFor("How does this door open?")
        tap(Wording.SOMETHING_WRONG)
        waitFor(Wording.MOVED)
        tap(Wording.MOVED)
        waitFor(Wording.SAVE_THIS_SPOT)
        compose.onNodeWithTag(MAP).performClick()
        tapButton(Wording.SAVE_THIS_SPOT)
        waitFor(Wording.CHECK_REPORT)
        tapButton(Wording.DONE)
        waitFor("✓ " + Wording.REPORTED_MOVED)
        tap(BACK)

        // The tally counts the answer; the two reports need another look.
        waitFor("The tally: 1 of 4 entrances checked")
        compose.onNodeWithText(Wording.NEEDS_LOOK).assertExists()
        compose.onNodeWithText("Already checked").assertExists()

        assertEquals(listOf(101L, 102L, 103L), backend.pending.keys.toList())
        assertEquals("""{"answers":{"automatic-opening":"button","door-type":"hinged"}}""", backend.pending[101])
        assertEquals("""{"outcome":"no-door"}""", backend.pending[102])
        assertTrue(backend.pending[103]!!.startsWith("""{"outcome":"moved","location":"""))
        // The door that needs an account was never opened.
        assertFalse(backend.paths.any { it.contains("task/104") })
    }

    /** A campaign without a place a guest can answer says so once, and asks to sign in above Nearest. */
    @Test fun aGuestIsAskedToSignInWhereNothingTakesGuestAnswers() {
        val backend = WoodbineBackend(WoodbineBackend.DOORS.map { it.copy(live = false) })
        val session = AppSession(WoodbineBackend.CONFIG, InMemoryCredentialStore(), backend)
        runBlocking { session.restore() }
        compose.setContent {
            StreetTallyTheme(dark = false) { App(session, CampaignPrefs(RuntimeEnvironment.getApplication()), CheckedLog()) }
        }
        waitFor("Let's go")
        compose.onNodeWithText(Wording.CAMPAIGN_NEEDS_ACCOUNT).assertExists()
        assertGone(Wording.noAccountNeeded(Noun("entrance", "entrances")))
        tap("Let's go")
        waitFor("Sign in to check the rest")
        compose.onNodeWithText(
            "The entrances still open in this campaign need an OpenStreetMap account. Any answers you've saved stay saved.",
        ).assertExists()
        compose.onNodeWithText("Sign in with OpenStreetMap").assertExists()
        compose.onNodeWithText("The tally: 0 of 4 entrances checked").assertExists()
        tap("List")
        compose.onNodeWithText("Main door").assertExists()
    }

    /** The picker badges a campaign whose first place takes no guest answers. */
    @Test fun aCampaignGuestsCantAnswerIsBadged() {
        fun needing(doors: List<Door>): Set<ChallengeId> {
            val session = AppSession(WoodbineBackend.CONFIG, InMemoryCredentialStore(), WoodbineBackend(doors))
            return runBlocking {
                session.restore()
                val campaign = session.client().getChallenge(ChallengeId(WoodbineBackend.CAMPAIGN))
                Campaigns.needingAccount(session.client(), listOf(campaign))
            }
        }
        assertEquals(emptySet<ChallengeId>(), needing(WoodbineBackend.DOORS))
        assertEquals(setOf(ChallengeId(WoodbineBackend.CAMPAIGN)), needing(WoodbineBackend.DOORS.map { it.copy(live = false) }))
    }

    /** Campaign → place, as MainActivity: the campaign keeps its saveable state (the list) while a
     * place is open. Every map is a placeholder. */
    @Composable
    private fun App(session: AppSession, prefs: CampaignPrefs, checked: CheckedLog) {
        val saved = rememberSaveableStateHolder()
        var place by remember { mutableStateOf<Pair<LocatedTask, CampaignPlaces>?>(null) }
        CompositionLocalProvider(
            LocalMapPlaceholder provides { _, modifier, onPick -> Box(modifier.testTag(MAP).clickable { onPick(MOVED_TO) }) },
        ) {
            val open = place
            if (open != null) {
                PlaceScreen(
                    session = session, checked = checked, campaignId = WoodbineBackend.CAMPAIGN,
                    firstTaskId = open.first.id, candidates = open.second.tasks, noun = open.second.noun,
                    origin = null, onSignIn = {}, onBack = { place = null },
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

    private fun assertGone(text: String) =
        assertEquals(0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Scrolls [node] into view when it is in a scrolling column, then taps it. */
    private fun SemanticsNodeInteraction.tapInView() {
        runCatching { performScrollTo() }
        performClick()
    }

    private fun tap(text: String) {
        waitFor(text)
        compose.onNodeWithText(text).tapInView()
    }

    /** A button labeled exactly [text] (other text on screen may say the same). */
    private fun tapButton(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text) and hasClickAction()).tapInView()
    }

    private companion object {
        const val BACK = "Back to all entrances"
    }
}
