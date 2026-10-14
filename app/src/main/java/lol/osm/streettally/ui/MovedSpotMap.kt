package lol.osm.streettally.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.hypot
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.MovedSpot
import lol.osm.streettally.location.Locator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Brand colors (brand/colors.json): the navy of the pins' ring, and a neutral gray for where the
 * map has the place. */
private const val NAVY = "#14213D"
private const val GRAY = "#6B7280"

/** Draws in place of every map when set. Robolectric tests set it: MapLibre's view doesn't render
 * there. Gets the picked spot (or null), the map's modifier, and what a tap on the map picks. */
/** Where other people's reports come from (parity row 4). Nothing until the backend can list them;
 * tests provide one. Read only where the build's serverReports flag is on. */
internal val LocalReportSource = staticCompositionLocalOf { lol.osm.streettally.campaign.ReportSource.NONE }

internal val LocalMapPlaceholder =
    staticCompositionLocalOf<(@Composable (LatLon?, Modifier, (LatLon) -> Unit) -> Unit)?> { null }

/** "It's moved" (design/not-here-moved.md §3.2): the place's mapped position as a gray hollow ring
 * labeled [onTheMapLabel], the picked spot as a navy pin, and a dashed line between them. Tapping the
 * map calls [onPick]. With [interactive] false the map only shows the two points (the review step). */
@Composable
fun MovedSpotMap(
    styleUrl: String,
    mapped: LatLon,
    picked: LatLon?,
    onTheMapLabel: String,
    interactive: Boolean,
    onPick: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocalMapPlaceholder.current?.let { placeholder ->
        placeholder(picked, modifier, onPick)
        return
    }
    val context = LocalContext.current
    val holder = remember { MovedSpotHolder(context, styleUrl, mapped, onTheMapLabel, interactive) }
    holder.onPick = onPick
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // Replays ON_START and ON_RESUME when already resumed.
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onPause()
            holder.view.onStop()
            holder.view.onDestroy()
        }
    }
    // TalkBack can't tap a spot on a map: once placed, the pin moves in 5 m steps (as iOS).
    val accessible = if (interactive && picked != null) {
        Modifier.semantics {
            contentDescription = "New spot"
            stateDescription = MovedSpot.describe(mapped, picked)
            customActions = listOf(
                "Move north" to MovedSpot.nudge(picked, northM = MovedSpot.NUDGE_M),
                "Move south" to MovedSpot.nudge(picked, northM = -MovedSpot.NUDGE_M),
                "Move east" to MovedSpot.nudge(picked, eastM = MovedSpot.NUDGE_M),
                "Move west" to MovedSpot.nudge(picked, eastM = -MovedSpot.NUDGE_M),
            ).map { (label, spot) -> CustomAccessibilityAction(label) { onPick(spot); true } }
        }
    } else Modifier
    AndroidView(factory = { holder.view }, modifier = modifier.then(accessible), update = { holder.showPick(picked) })
}

@SuppressLint("ClickableViewAccessibility") // The pin drag; TalkBack moves it with custom actions.
private class MovedSpotHolder(
    private val context: Context,
    styleUrl: String,
    private val mapped: LatLon,
    private val label: String,
    private val interactive: Boolean,
) {
    val view: MapView
    var onPick: (LatLon) -> Unit = {}
    private var map: MapLibreMap? = null
    private var pinSource: GeoJsonSource? = null
    private var lineSource: GeoJsonSource? = null
    private var pending: LatLon? = null
    private var shown: LatLon? = null

    init {
        MapLibre.getInstance(context)
        val dark = MapStyle.isDark(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            if (!interactive) map.uiSettings.setAllGesturesEnabled(false)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mapped.lat, mapped.lon), 18.0))
            map.setStyle(Style.Builder().fromUri(MapStyle.url(styleUrl, dark))) { style ->
                if (dark) MapStyle.brightenLabels(style)
                val mappedSource = GeoJsonSource(MAPPED, Feature.fromGeometry(point(mapped)).apply { addStringProperty("label", label) })
                val line = GeoJsonSource(LINE)
                val pin = GeoJsonSource(PIN)
                style.addSource(mappedSource)
                style.addSource(line)
                style.addSource(pin)
                style.addLayer(
                    LineLayer(LINE, LINE).withProperties(
                        // Light gray on the dark map ("Dark map" 1).
                        PropertyFactory.lineColor(if (dark) MapStyle.LIGHT_GRAY else NAVY),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                    ),
                )
                style.addLayer(
                    CircleLayer(MAPPED, MAPPED).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleOpacity(0f),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(GRAY),
                    ),
                )
                style.addLayer(
                    SymbolLayer(MAPPED_LABEL, MAPPED).withProperties(
                        PropertyFactory.textField("{label}"),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor(if (dark) MapStyle.LABEL else GRAY),
                        PropertyFactory.textHaloColor(if (dark) "#000000" else "#FFFFFF"),
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
                        PropertyFactory.textAllowOverlap(true),
                    ),
                )
                if (dark) {
                    // The moved spot on the dark map ("Dark map" 1): a white disc with a navy centre and
                    // a 2 dp dark halo, since navy vanishes there.
                    style.addLayer(
                        CircleLayer(PIN, PIN).withProperties(
                            PropertyFactory.circleRadius(11f),
                            PropertyFactory.circleColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#000000"),
                        ),
                    )
                    style.addLayer(
                        CircleLayer(PIN_GLYPH, PIN).withProperties(
                            PropertyFactory.circleRadius(5f),
                            PropertyFactory.circleColor(NAVY),
                        ),
                    )
                } else {
                    style.addLayer(
                        CircleLayer(PIN, PIN).withProperties(
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleColor(NAVY),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )
                }
                pinSource = pin
                lineSource = line
                if (interactive) showLocation(style)
                showPick(pending)
            }
            if (interactive) {
                map.addOnMapClickListener { latLng ->
                    onPick(LatLon(latLng.latitude, latLng.longitude))
                    true
                }
                view.setOnTouchListener { _, event -> drag(event) }
            }
        }
    }

    private var dragging = false

    /** Dragging the navy pin moves the spot; a touch that doesn't start on it pans the map. */
    private fun drag(event: MotionEvent): Boolean {
        val map = map ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val spot = shown ?: return false
                val pin = map.projection.toScreenLocation(LatLng(spot.lat, spot.lon))
                dragging = hypot(event.x - pin.x, event.y - pin.y) <= DRAG_SLOP_DP * view.resources.displayMetrics.density
                return dragging
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val at = map.projection.fromScreenLocation(PointF(event.x, event.y))
                onPick(LatLon(at.latitude, at.longitude))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission") // Checked by Locator.permitted.
    private fun showLocation(style: Style) {
        val map = map ?: return
        if (!Locator.permitted(context)) return
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build(),
            )
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.NORMAL
        }
        component.isLocationComponentEnabled = true
    }

    fun showPick(picked: LatLon?) {
        val pin = pinSource
        val line = lineSource
        if (pin == null || line == null) {
            pending = picked
            return
        }
        if (picked == shown && pending == null) return
        pending = null
        shown = picked
        if (picked == null) {
            pin.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            line.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        pin.setGeoJson(Feature.fromGeometry(point(picked)))
        line.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(listOf(point(mapped), point(picked)))))
        if (!interactive) {
            val padding = (40 * view.resources.displayMetrics.density).toInt()
            val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
                .include(LatLng(mapped.lat, mapped.lon)).include(LatLng(picked.lat, picked.lon)).build()
            // Once laid out, as the task map.
            view.doOnLayout { map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding)) }
        }
    }

    private companion object {
        const val MAPPED = "moved-mapped"
        const val MAPPED_LABEL = "moved-mapped-label"
        const val LINE = "moved-line"
        const val PIN = "moved-pin"
        const val PIN_GLYPH = "moved-pin-glyph"
        const val DRAG_SLOP_DP = 32
    }
}

private fun point(p: LatLon): Point = Point.fromLngLat(p.lon, p.lat)
