package lol.osm.streettally.ui

import android.content.Context
import android.content.res.Configuration
import java.net.URI
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/** Which map style to load for the system appearance (which-one-spec.md, "Dark map"; iOS #39/#40).
 * OpenFreeMap has one dark style; every OpenFreeMap light style maps to it in dark mode. Any other
 * style URL (a custom MAP_STYLE_URL) is left as configured. The activity keeps running when the
 * appearance changes (configChanges="uiMode"): the places map reloads its style in place, and the
 * small maps are made again. */
internal object MapStyle {
    const val DARK = "https://tiles.openfreemap.org/styles/dark"

    fun url(light: String, dark: Boolean): String = if (dark && host(light) == host(DARK)) DARK else light

    fun isDark(context: Context): Boolean = isDark(context.resources.configuration)

    fun isDark(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The dark style's street names are too dim to read outdoors ("Dark map" 4). Call once the dark
     * style has loaded, before adding the app's own labelled layers. */
    fun brightenLabels(style: Style) {
        style.layers.filterIsInstance<SymbolLayer>().forEach {
            it.setProperties(
                PropertyFactory.textColor(LABEL),
                PropertyFactory.textHaloColor("#000000"),
                PropertyFactory.textHaloWidth(1f),
            )
        }
    }

    const val LABEL = "#B8B8B8"
    /** Lines that are gray on the light map (moved-spot lines), light gray on the dark one. */
    const val LIGHT_GRAY = "#CCCCCC"

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()
}
