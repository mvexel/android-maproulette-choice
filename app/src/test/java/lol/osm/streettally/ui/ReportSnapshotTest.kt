package lol.osm.streettally.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.util.Locale
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.Noun
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.campaign.Wording
import lol.osm.streettally.task.ChoiceAction
import lol.osm.streettally.task.outcomes
import lol.osm.streettally.task.task
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.MovedLocation
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the PNGs go; CI uploads this folder as the `street-tally-report-snapshots` artifact. */
private val OUT = File("build/outputs/snapshots")

private val NOUN = Noun("bench", "benches")
private val MAPPED = LatLon(40.76080, -111.89100)

/** A spot [meters] north of the mapped position. */
private fun north(meters: Double) = LatLon(MAPPED.lat + meters / 111_320.0, MAPPED.lon)

/**
 * The not-here and moved screens (design/not-here-moved.md), the same set as iOS #17, saved as
 * PNGs for design review with Roborazzi (its library only; the record flag is set in
 * app/build.gradle.kts). The map is a placeholder: MapLibre's view doesn't draw under Robolectric.
 * The problems list is drawn on a dialog-shaped card rather than in a real dialog window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp-xxhdpi")
class ReportSnapshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        // So "I'm standing at it" is enabled, as on a phone that allowed location.
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Test
    fun problemsSheet() = shoot("1-problems-sheet", header = false) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Wording.SOMETHING_WRONG, style = MaterialTheme.typography.headlineSmall)
                ProblemsList(
                    NOUN, canSkip = true, outcomes = outcomes(task()), notHere = true, canMove = true,
                    onChoose = {}, onNotHere = {}, onMoved = {},
                )
            }
        }
    }

    @Test
    fun movedMapTap() = shoot("2-moved-map-tap") {
        MovedPicker(MAPPED, north(35.0), fromHere = false, NOUN, styleUrl = "", onPick = { _, _ -> }, onSave = {}, onCancel = {})
    }

    @Test
    fun movedMapStandingAtIt() = shoot("3-moved-map-standing-at-it") {
        MovedPicker(MAPPED, north(12.0), fromHere = true, NOUN, styleUrl = "", onPick = { _, _ -> }, onSave = {}, onCancel = {})
    }

    @Test
    fun reviewNotHere() = shoot("4-review-not-here") {
        ReportReview(
            ChoiceAction.Outcome(ChoiceOutcome.NOT_HERE), MAPPED, NOUN, styleUrl = "", previewing = false,
            onDone = {}, onBack = {},
        )
    }

    @Test
    fun reviewMoved() = shoot("5-review-moved") {
        val spot = north(35.0)
        ReportReview(
            ChoiceAction.Moved(MovedLocation(spot.lat, spot.lon, MovedLocation.Source.TAP)), MAPPED, NOUN,
            styleUrl = "", previewing = false, onDone = {}, onBack = {},
        )
    }

    @Test
    fun saved() = shoot("6-saved") {
        ReportSaved(moved = true)
        NextButtons(NOUN, Wording.next(NOUN, Places.directionText(MAPPED, north(40.0))), prominent = true, onNext = {}, onBack = {})
    }

    @Test
    fun tooFar() = shoot("7-too-far") {
        MovedPicker(MAPPED, north(650.0), fromHere = false, NOUN, styleUrl = "", onPick = { _, _ -> }, onSave = {}, onCancel = {})
    }

    /** Draws [content] the way the place screen lays it out (under the place's header) and saves it. */
    private fun shoot(name: String, header: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
        compose.setContent {
            StreetTallyTheme(dark = false) {
                CompositionLocalProvider(LocalMapPlaceholder provides { picked, modifier -> MapPlaceholder(picked, modifier) }) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.testTag(SHOT).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (header) Header(task(), NOUN, "30 m north of you")
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Roborazzi draws the views itself; captureToImage's PixelCopy never returns under Robolectric.
        OUT.mkdirs()
        compose.onNodeWithTag(SHOT).captureRoboImage(File(OUT, "$name.png"))
    }

    private companion object {
        const val SHOT = "shot"
    }
}

@Composable
private fun MapPlaceholder(picked: LatLon?, modifier: Modifier) {
    Box(
        modifier.background(Color(0xFFE5E7EB), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF9CA3AF), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val pin = picked?.let { "Pin ${Places.distance(Places.meters(MAPPED, it), Locale.US)} from the mapped spot" } ?: "No pin yet"
        Text(
            "Map (MapLibre isn't drawn in snapshots)\n$pin",
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium, color = Color(0xFF374151),
            modifier = Modifier.padding(16.dp),
        )
    }
}
