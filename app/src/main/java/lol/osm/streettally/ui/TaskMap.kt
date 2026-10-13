package lol.osm.streettally.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lol.osm.streettally.R
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.WhichOne
import lol.osm.streettally.campaign.Wording.RECENTER
import lol.osm.streettally.location.Locator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.CompassListener
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** What the task map has sensed, read by the direction line once a second. Written on the main
 * thread by the map; null until known. */
internal class Sensed {
    @Volatile var you: LatLon? = null
    @Volatile var accuracyM: Float? = null
    /** Degrees from north; null without a (reliable) compass reading. */
    @Volatile var heading: Double? = null
}

/** The close-up task map (design/which-one-spec.md A and B): pan and pinch, north up; the target
 * as the brand pin over the campaign's other places as small dots; you as the blue dot with a
 * heading cone once the compass reads. Opens on you and the target ([WhichOne.frame]); the
 * recenter button brings that frame back after the person moves the map. [onNeighboursInFrame]
 * gets how many neighbours the first frame shows. */
@Composable
fun TaskMap(
    styleUrl: String,
    target: LatLon,
    neighbours: List<WhichOne.Neighbour>,
    label: String,
    sensed: Sensed,
    onNeighboursInFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocalMapPlaceholder.current?.let { placeholder ->
        placeholder(null, modifier.clearAndSetSemantics { contentDescription = label }) {}
        return
    }
    val context = LocalContext.current
    var moved by remember { mutableStateOf(false) }
    val holder = remember { TaskMapHolder(context, styleUrl, target, sensed) }
    holder.onMoved = { moved = it }
    holder.onNeighboursInFrame = onNeighboursInFrame
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.start()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // Replays ON_START and ON_RESUME when already resumed.
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onPause()
            holder.stop()
            holder.view.onDestroy()
        }
    }
    Box(modifier = modifier) {
        // One element for TalkBack: the map's pins aren't usable one by one.
        AndroidView(
            factory = { holder.view },
            modifier = Modifier.matchParentSize().clearAndSetSemantics { contentDescription = label },
            update = { holder.showNeighbours(neighbours) },
        )
        if (moved) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp).clip(CircleShape),
            ) {
                IconButton(onClick = { holder.recenter() }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = RECENTER)
                }
            }
        }
    }
}

private class TaskMapHolder(
    private val context: Context,
    styleUrl: String,
    private val target: LatLon,
    private val sensed: Sensed,
) {
    val view: MapView
    var onMoved: (Boolean) -> Unit = {}
    var onNeighboursInFrame: (Int) -> Unit = {}
    private var map: MapLibreMap? = null
    private var neighbourSource: GeoJsonSource? = null
    private var waiting: List<WhichOne.Neighbour>? = null
    private var shown: List<WhichOne.Neighbour>? = null
    /** The person moved the map: their view stays until they tap recenter. */
    private var movedByUser = false
    /** The first frame has seen a fix (or settled for the target alone). */
    private var framedWithYou = false
    private var started = false
    private var unreliable = false

    private val request = LocationEngineRequest.Builder(UPDATE_MS)
        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
        .setFastestInterval(UPDATE_MS)
        .build()

    private val fixes = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
            val location = result?.lastLocation ?: return
            sensed.you = LatLon(location.latitude, location.longitude)
            sensed.accuracyM = location.accuracy.takeIf { location.hasAccuracy() }
            if (!framedWithYou && !movedByUser) {
                framedWithYou = true
                frame()
            }
        }

        override fun onFailure(exception: Exception) = Unit
    }

    private val compass = object : CompassListener {
        override fun onCompassChanged(userHeading: Float) {
            if (unreliable) return
            sensed.heading = userHeading.toDouble()
            // The cone only once the compass reads (spec A).
            map?.locationComponent?.takeIf { it.isLocationComponentActivated && it.renderMode != RenderMode.COMPASS }
                ?.renderMode = RenderMode.COMPASS
        }

        override fun onCompassAccuracyChange(compassStatus: Int) {
            // The spec trusts a heading within ±45°. Android reports a status, not degrees:
            // medium and high count as known, low and unreliable as unknown.
            unreliable = compassStatus <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            if (unreliable) {
                sensed.heading = null
                map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.renderMode = RenderMode.NORMAL
            }
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), WhichOne.TARGET_ZOOM))
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && !movedByUser) {
                    movedByUser = true
                    onMoved(true)
                }
            }
            map.addOnCameraIdleListener { if (!movedByUser) countInFrame() }
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                val neighbours = GeoJsonSource(NEIGHBOURS)
                style.addSource(neighbours)
                // Needs another look: gray with an amber ring; checked: small green; open: gray, white ring.
                style.addLayer(dots(OPEN_LAYER, WhichOne.Kind.OPEN, 5f, GRAY, "#FFFFFF", 1.5f))
                style.addLayer(dots(LOOK_LAYER, WhichOne.Kind.LOOK, 5f, GRAY, AMBER, 2f))
                style.addLayer(dots(DONE_LAYER, WhichOne.Kind.DONE, 4f, GREEN, GREEN, 0f))
                val density = view.resources.displayMetrics.density
                style.addImage(Pin.TODO.id, Pin.TODO.bitmap(density))
                style.addSource(GeoJsonSource(TARGET, Feature.fromGeometry(Point.fromLngLat(target.lon, target.lat))))
                style.addLayer(
                    SymbolLayer(TARGET, TARGET).withProperties(
                        PropertyFactory.iconImage(Pin.TODO.id),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                neighbourSource = neighbours
                waiting?.let { showNeighbours(it) }
                showLocation(style)
            }
        }
    }

    private fun dots(id: String, kind: WhichOne.Kind, radius: Float, fill: String, ring: String, ringWidth: Float) =
        CircleLayer(id, NEIGHBOURS).withProperties(
            PropertyFactory.circleRadius(radius),
            PropertyFactory.circleColor(fill),
            PropertyFactory.circleStrokeColor(ring),
            PropertyFactory.circleStrokeWidth(ringWidth),
        ).apply { setFilter(Expression.eq(Expression.get("kind"), Expression.literal(kind.name))) }

    fun showNeighbours(neighbours: List<WhichOne.Neighbour>) {
        val source = neighbourSource
        if (source == null) {
            waiting = neighbours
            return
        }
        waiting = null
        if (neighbours == shown) return
        shown = neighbours
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                neighbours.map { n ->
                    Feature.fromGeometry(Point.fromLngLat(n.position.lon, n.position.lat)).apply { addStringProperty("kind", n.kind.name) }
                },
            ),
        )
        if (!movedByUser) countInFrame()
    }

    private fun countInFrame() {
        val map = map ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        onNeighboursInFrame(shown.orEmpty().count { bounds.contains(LatLng(it.position.lat, it.position.lon)) })
    }

    /** You and the target with 40 dp padding at zoom 17 to 20, or the target alone at 19. */
    private fun frame() {
        val map = map ?: return
        val points = WhichOne.frame(sensed.you, target)
        if (points.size == 1) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), WhichOne.TARGET_ZOOM))
            return
        }
        val padding = (40 * view.resources.displayMetrics.density).toInt()
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        val zoom = map.cameraPosition.zoom.coerceIn(WhichOne.MIN_ZOOM, WhichOne.MAX_ZOOM)
        if (zoom != map.cameraPosition.zoom) map.moveCamera(CameraUpdateFactory.zoomTo(zoom))
    }

    fun recenter() {
        movedByUser = false
        onMoved(false)
        frame()
    }

    @SuppressLint("MissingPermission") // Checked by Locator.permitted.
    private fun showLocation(style: Style) {
        val map = map ?: return
        if (!Locator.permitted(context)) return
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .locationEngineRequest(request)
                    .locationComponentOptions(
                        LocationComponentOptions.builder(context).bearingDrawable(R.drawable.heading_cone).build(),
                    )
                    .build(),
            )
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.NORMAL
        }
        component.isLocationComponentEnabled = true
        if (started) listen()
    }

    fun start() {
        view.onStart()
        started = true
        listen()
    }

    /** Fixes and compass only while the screen shows, for the battery. */
    fun stop() {
        if (started) {
            started = false
            val component = map?.locationComponent?.takeIf { it.isLocationComponentActivated }
            component?.locationEngine?.removeLocationUpdates(fixes)
            component?.compassEngine?.removeCompassListener(compass)
        }
        view.onStop()
    }

    @SuppressLint("MissingPermission") // Only after the component was activated with permission.
    private fun listen() {
        val component = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        component.locationEngine?.removeLocationUpdates(fixes)
        component.locationEngine?.requestLocationUpdates(request, fixes, Looper.getMainLooper())
        component.compassEngine?.removeCompassListener(compass)
        component.compassEngine?.addCompassListener(compass)
    }

    private companion object {
        const val UPDATE_MS = 1_000L
        const val NEIGHBOURS = "which-neighbours"
        const val OPEN_LAYER = "which-open"
        const val LOOK_LAYER = "which-look"
        const val DONE_LAYER = "which-done"
        const val TARGET = "which-target"
        const val GRAY = "#9CA3AF"
        const val AMBER = "#F5A524"
        const val GREEN = "#1F7A4D"
    }
}
