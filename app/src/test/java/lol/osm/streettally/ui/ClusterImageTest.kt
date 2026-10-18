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
        assertEquals("cluster-1240-940", ClusterImage.id(1240, 940))
        assertEquals(1240 to 940, ClusterImage.parse("cluster-1240-940"))
        assertEquals(7 to 0, ClusterImage.parse("cluster-7-0"))
        // Never more left than there are.
        assertEquals(7 to 7, ClusterImage.parse("cluster-7-12"))
        assertNull(ClusterImage.parse("pin-todo"))
        assertNull(ClusterImage.parse("cluster-x-1"))
        assertNull(ClusterImage.parse("cluster-12"))
    }

    @Test fun darkClustersHaveTheirOwnIds() {
        assertEquals("cluster-dark-1240-940", ClusterImage.id(1240, 940, dark = true))
        assertEquals(1240 to 940, ClusterImage.parse("cluster-dark-1240-940"))
        assertEquals(true, ClusterImage.isDark("cluster-dark-7-0"))
        assertEquals(false, ClusterImage.isDark("cluster-7-0"))
        assertNull(ClusterImage.parse("cluster-dark-12"))
    }

    @Test fun sizedInThreeSteps() {
        assertEquals(36f, ClusterImage.diameter(99), 0f)
        assertEquals(44f, ClusterImage.diameter(100), 0f)
        assertEquals(44f, ClusterImage.diameter(999), 0f)
        assertEquals(52f, ClusterImage.diameter(1_000), 0f)
        val bitmap = ClusterImage.bitmap(5_200, 4_100, density = 2f)
        assertEquals(104, bitmap.width)
        assertEquals(104, bitmap.height)
        assertEquals(72, ClusterImage.bitmap(40, 0, density = 2f).width)
        assertEquals(72, ClusterImage.bitmap(40, 0, density = 2f, dark = true).width)
        assertEquals(13f, ClusterImage.textSize(99), 0f)
        assertEquals(13f, ClusterImage.textSize(999), 0f)
        assertEquals(14f, ClusterImage.textSize(1_000), 0f)
    }
}
