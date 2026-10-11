package lol.osm.streettally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import lol.osm.streettally.auth.SessionState
import lol.osm.streettally.campaign.Wording
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.ClaimState
import org.maproulette.sdk.ClaimStatus
import org.maproulette.sdk.ClaimedAccount
import org.maproulette.sdk.GuestClaim
import org.maproulette.sdk.MobileAccount
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The account note through a phone claim (B5, Iris's list in copy-fixes-2026-10-11.md), saved as
 * PNGs next to the report snapshots (`street-tally-report-snapshots` in CI) for design review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class ClaimSnapshotTest {
    @get:Rule val compose = createComposeRule()

    private val account = MobileAccount(900, setOf("tasks:read", "tasks:write", "osm:tagfix"), 2, "rosa_slc")
    private val claim = GuestClaim(9, ClaimState.QUEUED, 14, ClaimedAccount("rosa_slc", 123), isNew = true)
    private fun status(state: ClaimState, published: Int, skipped: Int = 0, failed: Int = 0) =
        ClaimStatus(9, state, 14, published, skipped, 0, failed, emptyList())

    @Test fun started() = shoot("claim-1-started", Wording.claimStarted(claim), calm = true)
    @Test fun progress() = shoot("claim-2-progress", Wording.claimProgress(status(ClaimState.RUNNING, 6)), calm = true)
    @Test fun done() = shoot("claim-3-done", Wording.claimProgress(status(ClaimState.DONE, 14)), calm = true)
    @Test fun someChanged() = shoot("claim-4-some-changed", Wording.claimProgress(status(ClaimState.DONE, 12, skipped = 2)), calm = true)
    @Test fun someFailed() = shoot("claim-5-some-failed", Wording.claimProgress(status(ClaimState.FAILED, 8, failed = 6)), calm = false)
    @Test fun waiting() = shoot("claim-6-waiting", Wording.claimStarted(claim.copy(state = ClaimState.WAITING)), calm = true)
    @Test fun gone() = shoot("claim-7-gone", Wording.claimGone("rosa_slc"), calm = true)
    @Test fun signInAgain() = shoot("claim-8-sign-in-again", Wording.claimSignInAgain("rosa_slc"), calm = false)
    @Test fun later() = shoot("claim-9-later", Wording.claimLater("rosa_slc"), calm = true)

    private fun shoot(name: String, message: String, calm: Boolean) {
        compose.setContent {
            StreetTallyTheme(dark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.testTag(SHOT).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountSection(
                            state = SessionState(restored = true, account = account, message = message, messageCalm = calm),
                            needsReconsent = false, privateSignIn = false, guestsAvailable = true,
                            onSignIn = {}, onSignOut = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        OUT.mkdirs()
        compose.onNodeWithTag(SHOT).captureRoboImage(File(OUT, "$name.png"))
    }

    private companion object {
        const val SHOT = "shot"
        val OUT = File("build/outputs/snapshots")
    }
}
