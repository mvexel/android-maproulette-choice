package lol.osm.streettally.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.customview.widget.ExploreByTouchHelper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Places
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
 * Tapping a place calls [onPlace] with its task id; tapping elsewhere calls [onBackground].
 * TalkBack reaches each pin on screen, nearest [origin] first, read as [describe] says; a double tap
 * calls [onActivate] (A11y1). */
@Composable
fun PlacesMap(
    styleUrl: String,
    places: List<LocatedTask>,
    done: Set<Long>,
    /** A guest's saved places (a subset of [done]): saved, not on the map yet. */
    pending: Set<Long> = emptySet(),
    /** Reported places: "Needs another look" ([Pin.LOOK]). */
    look: Set<Long> = emptySet(),
    /** A guest's answers still on this phone: "Waiting to send" ([Pin.WAITING]). */
    unsent: Set<Long> = emptySet(),
    /** Where moved places were reported to be: a small gray dot, joined to the pin by a dashed line. */
    movedTo: Map<Long, LatLon> = emptyMap(),
    locationPermitted: Boolean,
    onPlace: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onBackground: () -> Unit = {},
    origin: LatLon? = null,
    describe: (LocatedTask) -> String = { it.id.toString() },
    onActivate: (Long) -> Unit = onPlace,
) {
    LocalMapPlaceholder.current?.let { placeholder ->
        placeholder(null, modifier, {})
        return
    }
    val context = LocalContext.current
    val holder = remember { PlacesMapHolder(context, styleUrl) }
    holder.onPlace = onPlace
    holder.onBackground = onBackground
    holder.onActivate = onActivate
    holder.describe = describe
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
        holder.show(Shown(places, done, pending, look, unsent, movedTo, origin))
        holder.syncLocation()
    })
}

private class PlacesMapHolder(private val context: Context, styleUrl: String) {
    val view: MapView
    var onPlace: (Long) -> Unit = {}
    var onBackground: () -> Unit = {}
    var onActivate: (Long) -> Unit = {}
    var describe: (LocatedTask) -> String = { it.id.toString() }
    /** The pins TalkBack can reach, nearest first; their index is the node id. */
    private var pins: List<LocatedTask> = emptyList()
    private val access: PinAccess
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var movedSource: GeoJsonSource? = null
    private var waiting: Shown? = null
    /** What the map last drew: a recomposition with the same places redraws nothing (5,000 pins, AE6). */
    private var drawn: Shown? = null
    private var framed = false

    init {
        MapLibre.getInstance(context)
        val dark = MapStyle.isDark(context)
        // MapLibre draws the pins as pixels: hover events go to [access], which gives TalkBack a node per pin.
        view = object : MapView(context) {
            override fun dispatchHoverEvent(event: MotionEvent): Boolean =
                access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
        }
        access = PinAccess()
        ViewCompat.setAccessibilityDelegate(view, access)
        view.onCreate(null)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(MapStyle.url(styleUrl, dark))) { style ->
                if (dark) MapStyle.brightenLabels(style)
                // Under the pins: the dashed line from a moved place to its reported spot, then the dot.
                val moved = GeoJsonSource(MOVED_SOURCE)
                style.addSource(moved)
                style.addLayer(
                    LineLayer(MOVED_LINES, MOVED_SOURCE).withProperties(
                        PropertyFactory.lineColor(if (dark) MapStyle.LIGHT_GRAY else MOVED_GRAY),
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
                Pin.entries.forEach { style.addImage(it.id, it.bitmap(density, dark)) }
                style.addLayer(
                    SymbolLayer(LAYER, SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("pin")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                this.source = source
                syncLocation()
                waiting?.let { show(it) }
            }
            // Nodes follow the pins once the map stops moving; panning itself announces nothing.
            map.addOnCameraIdleListener { access.invalidateRoot() }
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

    fun show(shown: Shown) {
        val source = source
        if (source == null) {
            waiting = shown
            return
        }
        waiting = null
        val last = drawn
        if (shown == last) return
        drawn = shown
        val (places, done, pending, look, unsent, movedTo, origin) = shown
        val located = places.filter { it.position != null }
        pins = Places.sorted(located, origin)
        access.invalidateRoot()
        // Walking only reorders TalkBack's pins; the map itself is unchanged.
        if (last != null && last.copy(origin = origin) == shown) return
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
                            in unsent -> Pin.WAITING
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

    /** Where pin [index] is on screen, or null when it's off the map's visible area. */
    private fun spot(index: Int): Rect? {
        val map = map ?: return null
        val p = pins.getOrNull(index)?.position ?: return null
        val point = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
        if (point.x < 0 || point.y < 0 || point.x > view.width || point.y > view.height) return null
        val half = (24 * view.resources.displayMetrics.density).toInt()
        return Rect(point.x.toInt() - half, point.y.toInt() - half, point.x.toInt() + half, point.y.toInt() + half)
    }

    private inner class PinAccess : ExploreByTouchHelper(view) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            pins.indices.mapNotNull { i -> spot(i)?.takeIf { it.contains(x.toInt(), y.toInt()) }?.let { i to it } }
                .minByOrNull { (_, r) -> (r.exactCenterX() - x).let { dx -> dx * dx } + (r.exactCenterY() - y).let { dy -> dy * dy } }
                ?.first ?: INVALID_ID

        // The nearest on-screen pins only: thousands of nodes swamp TalkBack and the system (AE6).
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            pins.indices.asSequence().filter { spot(it) != null }.take(MAX_PIN_NODES).toCollection(ids)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val pin = pins.getOrNull(id)
            // A node must have bounds even if the pin moved off screen since the last refresh.
            @Suppress("DEPRECATION")
            node.setBoundsInParent(spot(id) ?: Rect(0, 0, 1, 1))
            node.contentDescription = pin?.let(describe) ?: ""
            // Clickable, or TalkBack's double-tap falls back to a tap on the map under the pin.
            node.isClickable = true
            // A stable id for PinAccessTest; TalkBack doesn't read it.
            node.viewIdResourceName = "${view.context.packageName}:id/pin"
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val pin = pins.getOrNull(id) ?: return false
            onActivate(pin.id)
            return true
        }
    }

    private companion object {
        const val SOURCE = "places"
        const val LAYER = "places-pins"
        const val MOVED_SOURCE = "moved"
        const val MOVED_LINES = "moved-lines"
        const val MOVED_DOTS = "moved-dots"
        const val MOVED_GRAY = "#8A8F98"
        /** TalkBack nodes for at most this many pins on screen, nearest first: enough for a whole
         * Woodbine survey (139 trees, which PinAccessTest walks), not thousands. */
        const val MAX_PIN_NODES = 200
    }
}

private data class Shown(
    val places: List<LocatedTask>, val done: Set<Long>, val pending: Set<Long>, val look: Set<Long>, val unsent: Set<Long>,
    val movedTo: Map<Long, LatLon>, val origin: LatLon?,
)
