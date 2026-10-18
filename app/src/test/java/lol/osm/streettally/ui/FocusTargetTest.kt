package lol.osm.streettally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where TalkBack's focus lands on the map (iOS #63), without iOS's two AX-FOCUS gaps (Tess, via Ada). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusTargetTest {
    private val here = LatLng(40.70, -111.90)
    private val nearby = LatLng(40.701, -111.90)
    private val far = LatLng(40.80, -111.90)

    @Test fun backLandsOnTheOpenedPlace() {
        val nodes = listOf(FocusNode(1, far, true), FocusNode(7, here, true))
        assertEquals(1, focusTarget(nodes, place = 7, placeAt = here, near = null, settled = false))
    }

    @Test fun aGonePlaceHandsTheFocusToTheNearestNotTheFirst() {
        val nodes = listOf(FocusNode(1, far, true), FocusNode(null, nearby, true), FocusNode(2, far, true))
        assertNull("waits while the map may still draw it", focusTarget(nodes, place = 7, placeAt = here, near = null, settled = false))
        assertEquals(1, focusTarget(nodes, place = 7, placeAt = here, near = null, settled = true))
    }

    @Test fun aNodeCutOffByTheEdgeDoesNotTakeTheFocus() {
        val nodes = listOf(FocusNode(7, here, false), FocusNode(2, nearby, true), FocusNode(3, far, true))
        assertEquals(1, focusTarget(nodes, place = 7, placeAt = here, near = null, settled = true))
        assertEquals(1, focusTarget(nodes, place = null, placeAt = null, near = here, settled = true))
    }

    @Test fun nothingWhollyOnScreenTakesNothing() {
        val nodes = listOf(FocusNode(7, here, false))
        assertNull(focusTarget(nodes, place = 7, placeAt = here, near = null, settled = true))
    }
}
