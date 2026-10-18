package lol.osm.streettally.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Wording
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome's line on where answers go: in preview it says nothing is saved, never "saved to
 * OpenStreetMap", which the preview banner contradicts (Tess's AW1, preview-mode.md §4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WelcomeDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun show(previewingAs: String? = null, guest: Boolean = false) = compose.setContent {
        WelcomeDialog(
            name = "Trees", description = null, openCount = 3, questions = emptyList(), noun = Noun("tree", "trees"),
            signedIn = previewingAs != null, onClose = {}, previewingAs = previewingAs, guest = guest,
        )
    }

    private fun assertGone(text: String) =
        assertTrue("\"$text\" shown", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())

    @Test fun previewSaysNothingIsSaved() {
        show(previewingAs = "mvexel")
        compose.onNodeWithText(Wording.PREVIEW_NOTHING_SAVED).assertExists()
        compose.onNodeWithText(Wording.previewingAs("mvexel")).assertExists()
        assertGone("saved to OpenStreetMap")
    }

    @Test fun signedInSaysAnswersArePublic() {
        show()
        compose.onNodeWithText(Wording.ANSWERS_ARE_PUBLIC).assertExists()
        assertGone(Wording.PREVIEW_NOTHING_SAVED)
    }
}
