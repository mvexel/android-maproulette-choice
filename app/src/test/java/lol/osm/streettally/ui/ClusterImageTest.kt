package lol.osm.streettally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cluster images MapLibre asks for by id on a big campaign's map (L1-CLUSTER). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClusterImageTest {
    @Test fun idsRoundTrip() {
        assertEquals("cluster-1240-3", ClusterImage.id(1240, 3))
        assertEquals(1240 to 3, ClusterImage.parse("cluster-1240-3"))
        // The tenths come from a rounded expression: clamped, never a broken ring.
        assertEquals(7 to 10, ClusterImage.parse("cluster-7-12"))
        assertNull(ClusterImage.parse("pin-todo"))
        assertNull(ClusterImage.parse("cluster-x-1"))
        assertNull(ClusterImage.parse("cluster-12"))
    }

    @Test fun sizedInThreeSteps() {
        assertEquals(36f, ClusterImage.diameter(99), 0f)
        assertEquals(44f, ClusterImage.diameter(100), 0f)
        assertEquals(44f, ClusterImage.diameter(999), 0f)
        assertEquals(52f, ClusterImage.diameter(1_000), 0f)
        val bitmap = ClusterImage.bitmap(5_200, 4, density = 2f, dark = false)
        assertEquals(104, bitmap.width)
        assertEquals(104, bitmap.height)
    }
}
