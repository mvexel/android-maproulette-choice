package lol.osm.streettally.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** which-one-spec.md, "Dark map": OpenFreeMap styles switch to its dark style; others stay. */
class MapStyleTest {
    private val positron = "https://tiles.openfreemap.org/styles/positron"
    private val liberty = "https://tiles.openfreemap.org/styles/liberty"

    @Test fun darkModeUsesOpenFreeMapDark() {
        assertEquals(MapStyle.DARK, MapStyle.url(positron, dark = true))
        assertEquals(MapStyle.DARK, MapStyle.url(liberty, dark = true))
        assertEquals(positron, MapStyle.url(positron, dark = false))
    }

    @Test fun customStyleIsLeftAlone() {
        val custom = "https://example.org/style.json"
        assertEquals(custom, MapStyle.url(custom, dark = true))
    }
}
