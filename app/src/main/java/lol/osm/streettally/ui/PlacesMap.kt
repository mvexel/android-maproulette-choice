package lol.osm.streettally.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.location.Locator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point


/** A campaign's places on a MapLibre map with OpenFreeMap tiles, drawn as the brand pins ([Pin]).
 * Tapping a place calls [onPlace] with its task id; tapping elsewhere calls [onBackground]. */
@Composable
fun PlacesMap(
    styleUrl: String,
    places: List<LocatedTask>,
    done: Set<Long>,
    /** A guest's saved places (a subset of [done]): saved, not on the map yet. */
    pending: Set<Long> = emptySet(),
    /** Reported places: "Needs another look" ([Pin.LOOK]). */
    look: Set<Long> = emptySet(),
    /** Where moved places were reported to be: a small gray dot, joined to the pin by a dashed line. */
    movedTo: Map<Long, LatLon> = emptyMap(),
    locationPermitted: Boolean,
    onPlace: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onBackground: () -> Unit = {},
) {
    LocalMapPlaceholder.current?.let { placeholder ->
        placeholder(null, modifier, {})
        return
    }
    val context = LocalContext.current
    val holder = remember { PlacesMapHolder(context, styleUrl) }
    holder.onPlace = onPlace
    holder.onBackground = onBackground
    LaunchedEffect(locationPermitted) { holder.syncLocation() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    holder.view.onResume()
                    holder.syncLocation()
                }
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onDestroy()
        }
    }
    AndroidView(factory = { holder.view }, modifier = modifier, update = {
        holder.show(places, done, pending, look, movedTo)
        holder.syncLocation()
    })
}

private class PlacesMapHolder(private val context: Context, styleUrl: String) {
    val view: MapView
    var onPlace: (Long) -> Unit = {}
    var onBackground: () -> Unit = {}
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var movedSource: GeoJsonSource? = null
    private var waiting: Shown? = null
    private var framed = false

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                // Under the pins: the dashed line from a moved place to its reported spot, then the dot.
                val moved = GeoJsonSource(MOVED_SOURCE)
                style.addSource(moved)
                style.addLayer(
                    LineLayer(MOVED_LINES, MOVED_SOURCE).withProperties(
                        PropertyFactory.lineColor(MOVED_GRAY),
                        PropertyFactory.lineWidth(1.5f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                    ).apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString"))) },
                )
                style.addLayer(
                    CircleLayer(MOVED_DOTS, MOVED_SOURCE).withProperties(
                        PropertyFactory.circleColor(MOVED_GRAY),
                        PropertyFactory.circleRadius(4f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(1f),
                    ).apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))) },
                )
                movedSource = moved
                val source = GeoJsonSource(SOURCE)
                style.addSource(source)
                val density = view.resources.displayMetrics.density
                Pin.entries.forEach { style.addImage(it.id, it.bitmap(density)) }
                style.addLayer(
                    SymbolLayer(LAYER, SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("pin")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                this.source = source
                syncLocation()
                waiting?.let { show(it.places, it.done, it.pending, it.look, it.movedTo) }
            }
            map.addOnMapClickListener { latLng ->
                val point = map.projection.toScreenLocation(latLng)
                val slop = 24 * view.resources.displayMetrics.density
                val hit = map.queryRenderedFeatures(
                    RectF(point.x - slop, point.y - slop, point.x + slop, point.y + slop), LAYER,
                ).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toLong()
                if (id == null) {
                    onBackground()
                    return@addOnMapClickListener false
                }
                onPlace(id)
                true
            }
        }
    }

    @SuppressLint("MissingPermission") // Rechecked before activating or enabling location.
    fun syncLocation() {
        val map = map ?: return
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val component = map.locationComponent
        val allowed = Locator.permitted(context)
        if (!allowed) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.NORMAL
        }
        if (!component.isLocationComponentEnabled) component.isLocationComponentEnabled = true
    }

    fun show(places: List<LocatedTask>, done: Set<Long>, pending: Set<Long>, look: Set<Long>, movedTo: Map<Long, LatLon>) {
        val source = source
        if (source == null) {
            waiting = Shown(places, done, pending, look, movedTo)
            return
        }
        waiting = null
        val located = places.filter { it.position != null }
        movedSource?.setGeoJson(
            FeatureCollection.fromFeatures(
                located.filter { it.id in look }.flatMap { place ->
                    val spot = movedTo[place.id] ?: return@flatMap emptyList()
                    val from = Point.fromLngLat(place.position!!.lon, place.position.lat)
                    val to = Point.fromLngLat(spot.lon, spot.lat)
                    listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(from, to))), Feature.fromGeometry(to))
                },
            ),
        )
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                located.map { place ->
                    val p = place.position!!
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                        addNumberProperty("id", place.id)
                        val pin = when (place.id) {
                            in look -> Pin.LOOK
                            in pending -> Pin.PENDING
                            in done -> Pin.DONE
                            else -> Pin.TODO
                        }
                        addStringProperty("pin", pin.id)
                    }
                },
            ),
        )
        if (!framed && located.isNotEmpty()) {
            framed = true
            val map = map ?: return
            val padding = (48 * view.resources.displayMetrics.density).toInt()
            if (located.size == 1) {
                val p = located.first().position!!
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 16.0))
            } else {
                val bounds = LatLngBounds.Builder().apply {
                    located.forEach { include(LatLng(it.position!!.lat, it.position.lon)) }
                }.build()
                // Once laid out: bounds fitted into no space land far off (after a recreation, the cached style can beat layout).
                view.doOnLayout { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding)) }
            }
        }
    }

    private companion object {
        const val SOURCE = "places"
        const val LAYER = "places-pins"
        const val MOVED_SOURCE = "moved"
        const val MOVED_LINES = "moved-lines"
        const val MOVED_DOTS = "moved-dots"
        const val MOVED_GRAY = "#8A8F98"
    }
}

private data class Shown(
    val places: List<LocatedTask>, val done: Set<Long>, val pending: Set<Long>, val look: Set<Long>, val movedTo: Map<Long, LatLon>,
)
