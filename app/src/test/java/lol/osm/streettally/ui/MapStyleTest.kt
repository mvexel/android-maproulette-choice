package lol.osm.streettally.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** The fallback when the style can't load: valid JSON, one background layer, the right shade. */
    @Test fun plainStyleIsJustABackground() {
        for ((dark, color) in listOf(false to "#F2EFE9", true to "#1C1C1E")) {
            val style = Json.parseToJsonElement(MapStyle.plain(dark)).jsonObject
            assertEquals("8", style.getValue("version").jsonPrimitive.content)
            val layer = style.getValue("layers").jsonArray.single().jsonObject
            assertEquals("background", layer.getValue("type").jsonPrimitive.content)
            assertEquals(color, layer.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
        }
    }
}
